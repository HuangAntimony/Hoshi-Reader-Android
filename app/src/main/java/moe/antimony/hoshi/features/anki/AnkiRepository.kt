package moe.antimony.hoshi.features.anki

import android.content.Context
import android.util.Log
import androidx.core.content.FileProvider
import de.manhhao.hoshi.HoshiDicts
import moe.antimony.hoshi.features.audio.LocalAudioFile
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import moe.antimony.hoshi.features.audio.LocalAudioResolver
import java.io.File
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class AnkiRepository(
    private val context: Context,
    private val backend: AnkiBackend,
    private val settingsRepository: AnkiSettingsRepository,
    private val localAudioRepository: LocalAudioRepository = LocalAudioRepository.fromContext(context),
) {
    val settings: Flow<AnkiSettings> = settingsRepository.settings

    suspend fun updateSettings(transform: (AnkiSettings) -> AnkiSettings) {
        settingsRepository.update(transform)
    }

    suspend fun fetchConfiguration(): AnkiFetchResult = withContext(Dispatchers.IO) {
        val fetched = runCatching {
            if (!backend.isAvailable()) return@withContext AnkiFetchResult.Error("AnkiDroid is not available.")
            backend.fetchDecks() to backend.fetchNoteTypes()
        }.getOrElse { error ->
            return@withContext AnkiFetchResult.Error(
                error.message ?: "Hoshi could not access AnkiDroid. Grant AnkiDroid database access and try again.",
            )
        }
        val (decks, noteTypes) = fetched
        if (decks.isEmpty() || noteTypes.isEmpty()) {
            return@withContext AnkiFetchResult.Error("No AnkiDroid decks or note types were found.")
        }
        settingsRepository.update { current ->
            val selectedDeck = selectDeckAfterFetch(decks, current)
            val selectedNoteType = selectNoteTypeAfterFetch(noteTypes, current)
            current.copy(
                selectedDeckId = selectedDeck.id,
                selectedDeckName = selectedDeck.name,
                selectedNoteTypeId = selectedNoteType.id,
                selectedNoteTypeName = selectedNoteType.name,
                availableDecks = decks,
                availableNoteTypes = noteTypes,
                fieldMappings = fieldMappingsAfterFetch(selectedNoteType, current),
            )
        }
        AnkiFetchResult.Success(decks, noteTypes)
    }

    suspend fun mineEntry(
        rawPayload: String,
        context: AnkiMiningContext,
        decks: List<AnkiDeck>,
        noteTypes: List<AnkiNoteType>,
    ): Boolean = withContext(Dispatchers.IO) {
        val settings = settings.first()
        val availableDecks = decks.ifEmpty { backend.fetchDecks() }
        val availableNoteTypes = noteTypes.ifEmpty { backend.fetchNoteTypes() }
        val deck = availableDecks.firstOrNull { it.id == settings.selectedDeckId }
            ?: settings.selectedDeckName?.let { name -> availableDecks.firstOrNull { it.name == name } }
            ?: return@withContext false
        val noteType = availableNoteTypes.firstOrNull { it.id == settings.selectedNoteTypeId }
            ?: settings.selectedNoteTypeName?.let { name -> availableNoteTypes.firstOrNull { it.name == name } }
            ?: return@withContext false
        val fieldMappings = settings.fieldMappings
        val payload = runCatching { AnkiMiningPayload.fromJson(rawPayload) }.getOrNull()
            ?: return@withContext false
        val mediaContext = AnkiMiningContext(
            sentence = context.sentence,
            documentTitle = context.documentTitle,
            coverPath = context.coverPath?.let { addMediaFile(it, "hoshi_cover_${File(it).name}", mimeTypeForPath(it)) },
            sasayakiAudioPath = context.sasayakiAudioPath?.let { addMediaFile(it, File(it).name, mimeTypeForPath(it)) },
            sentenceOffset = context.sentenceOffset,
        )
        val mediaPayload = payload.copy(
            audio = payload.audio.takeIf { it.isNotBlank() }?.let(::addRemoteAudio).orEmpty(),
        )
        val dictionaryMediaTags = payload.dictionaryMedia.associate { media ->
            media.filename to addDictionaryMedia(media).orEmpty()
        }.filterValues { it.isNotBlank() }
        val fields = fieldMappings.mapValues { (_, template) ->
            dictionaryMediaTags.entries.fold(
                AnkiHandlebarRenderer.render(template, mediaPayload, mediaContext),
            ) { value, (filename, tag) -> value.replace(filename, tag) }
                .let(::normalizeAnkiDictionaryHtml)
        }.filterValues { it.isNotBlank() }

        val added = backend.addNote(
            deck = deck,
            noteType = noteType,
            fieldsByName = fields,
            tags = settings.tags.split(Regex("\\s+")).filter { it.isNotBlank() }.toSet(),
            allowDupes = settings.allowDupes,
            duplicateScope = settings.duplicateScope,
            checkDuplicatesAcrossAllModels = settings.checkDuplicatesAcrossAllModels,
        )
        added
    }

    suspend fun isDuplicate(
        expression: String,
        decks: List<AnkiDeck>,
        noteTypes: List<AnkiNoteType>,
    ): Boolean = withContext(Dispatchers.IO) {
        val settings = settings.first()
        val availableNoteTypes = noteTypes.ifEmpty { backend.fetchNoteTypes() }
        val availableDecks = decks.ifEmpty { backend.fetchDecks() }
        val deck = availableDecks.firstOrNull { it.id == settings.selectedDeckId }
            ?: settings.selectedDeckName?.let { name -> availableDecks.firstOrNull { it.name == name } }
            ?: return@withContext false
        val noteTypeId = settings.selectedNoteTypeId
            ?: settings.selectedNoteTypeName?.let { name -> availableNoteTypes.firstOrNull { it.name == name }?.id }
            ?: return@withContext false
        val noteType = availableNoteTypes.firstOrNull { it.id == noteTypeId } ?: return@withContext false
        backend.isDuplicate(
            deck = deck,
            noteType = noteType,
            key = expression,
            duplicateScope = settings.duplicateScope,
            checkDuplicatesAcrossAllModels = settings.checkDuplicatesAcrossAllModels,
        )
    }

    private fun addRemoteAudio(url: String): String? =
        runCatching {
            val data = readAnkiAudioBytes(
                url = url,
                readLocalAudio = localAudioRepository::loadAudio,
                readRemoteAudio = { remoteUrl -> URL(remoteUrl).openStream().use { it.readBytes() } },
            )
                ?: return null
            val file = mediaCacheFile("hoshi_audio_${data.contentHashCode()}.mp3")
            file.writeBytes(data)
            addMediaFile(file.absolutePath, file.name, "audio/mpeg")
        }.getOrNull()

    private fun addDictionaryMedia(media: DictionaryMedia): String? =
        runCatching {
            val data = HoshiDicts.getMediaFile(HoshiDicts.lookupObject, media.dictionary, media.path)
                ?: return null
            val file = mediaCacheFile("hoshi_dict_${data.contentHashCode()}.${media.path.substringAfterLast('.', "bin")}")
            file.writeBytes(data)
            addMediaFile(file.absolutePath, file.name, mimeTypeForPath(media.path))?.let(::ankiInlineMediaReference)
        }.onFailure { Log.w(TAG, "Failed to add dictionary media ${media.path}", it) }
            .getOrNull()

    private fun addMediaFile(path: String, preferredName: String, mimeType: String): String? {
        val file = File(path).takeIf { it.isFile } ?: return null
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.grantUriPermission("com.ichi2.anki", uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            backend.addMediaFromUri(uri.toString(), preferredName, mimeType)
        }
            .onFailure { Log.w(TAG, "Failed to add Anki media $preferredName", it) }
            .getOrNull()
    }

    private fun mediaCacheFile(name: String): File {
        val dir = File(context.cacheDir, "anki-media").also { it.mkdirs() }
        return File(dir, name)
    }
}

internal fun readAnkiAudioBytes(
    url: String,
    readLocalAudio: (LocalAudioFile) -> ByteArray?,
    readRemoteAudio: (String) -> ByteArray?,
): ByteArray? {
    val localFile = LocalAudioResolver.parseAudioUrl(url)
    return if (localFile != null) {
        readLocalAudio(localFile)
    } else {
        readRemoteAudio(url)
    }
}

private const val TAG = "AnkiRepository"

internal fun selectDeckAfterFetch(
    decks: List<AnkiDeck>,
    current: AnkiSettings,
): AnkiDeck =
    decks.firstOrNull { it.id == current.selectedDeckId }
        ?: current.selectedDeckName?.let { name -> decks.firstOrNull { it.name == name } }
        ?: decks.firstOrNull { !it.name.equals("Default", ignoreCase = true) }
        ?: decks.first()

internal fun selectNoteTypeAfterFetch(
    noteTypes: List<AnkiNoteType>,
    current: AnkiSettings,
): AnkiNoteType =
    noteTypes.firstOrNull { it.id == current.selectedNoteTypeId }
        ?: current.selectedNoteTypeName?.let { name -> noteTypes.firstOrNull { it.name == name } }
        ?: noteTypes.firstOrNull { LapisPreset.matches(it) }
        ?: noteTypes.first()

internal fun fieldMappingsAfterFetch(
    selectedNoteType: AnkiNoteType,
    current: AnkiSettings,
): Map<String, String> =
    if (LapisPreset.matches(selectedNoteType) && !currentSelectionMatchesLapis(current)) {
        LapisPreset.applyDefaults(selectedNoteType, emptyMap())
    } else {
        current.fieldMappings
    }

private fun currentSelectionMatchesLapis(current: AnkiSettings): Boolean =
    current.availableNoteTypes.firstOrNull {
        it.id == current.selectedNoteTypeId || it.name == current.selectedNoteTypeName
    }
        ?.let(LapisPreset::matches)
        ?: current.selectedNoteTypeName?.contains("lapis", ignoreCase = true)
        ?: false

sealed interface AnkiFetchResult {
    data class Success(
        val decks: List<AnkiDeck>,
        val noteTypes: List<AnkiNoteType>,
    ) : AnkiFetchResult

    data class Error(val message: String) : AnkiFetchResult
}

fun mimeTypeForPath(path: String): String =
    when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "heic" -> "image/heic"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

internal fun ankiInlineMediaReference(addMediaResult: String): String {
    val imageSrc = Regex("""<img\s+[^>]*src=["']([^"']+)["'][^>]*>""")
        .find(addMediaResult)
        ?.groupValues
        ?.getOrNull(1)
    if (!imageSrc.isNullOrBlank()) return imageSrc
    val soundFile = Regex("""\[sound:([^\]]+)]""")
        .find(addMediaResult)
        ?.groupValues
        ?.getOrNull(1)
    return soundFile ?: addMediaResult
}

internal fun normalizeAnkiDictionaryHtml(value: String): String {
    if (!value.contains("data-sc-img") || !value.contains("gloss-image")) return value
    return value + AnkiGaijiImageStyle
}

private const val AnkiGaijiImageStyle =
    """<style>.yomitan-glossary [data-sc-img][data-sc-class="gaiji"]{display:inline!important;white-space:nowrap!important;vertical-align:baseline!important}.yomitan-glossary [data-sc-img][data-sc-class="gaiji"] .gloss-image-link{display:inline-block!important;vertical-align:text-bottom!important;max-width:1.2em!important}.yomitan-glossary [data-sc-img][data-sc-class="gaiji"] .gloss-image-container{display:inline-block!important;width:1em!important;height:1em!important;max-width:1em!important;max-height:1em!important;vertical-align:text-bottom!important;font-size:1em!important}.yomitan-glossary [data-sc-img][data-sc-class="gaiji"] .gloss-image-sizer{display:none!important}.yomitan-glossary [data-sc-img][data-sc-class="gaiji"] .gloss-image{position:static!important;width:1em!important;height:1em!important;vertical-align:text-bottom!important}</style>"""
