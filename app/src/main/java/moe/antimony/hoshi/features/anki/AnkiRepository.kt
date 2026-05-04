package moe.antimony.hoshi.features.anki

import android.content.Context
import androidx.core.content.FileProvider
import de.manhhao.hoshi.HoshiDicts
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
        val selectedDeck = decks.firstOrNull { !it.name.equals("Default", ignoreCase = true) } ?: decks.first()
        val selectedNoteType = noteTypes.firstOrNull { LapisPreset.matches(it) } ?: noteTypes.first()
        settingsRepository.update { current ->
            current.copy(
                selectedDeckId = selectedDeck.id,
                selectedDeckName = selectedDeck.name,
                selectedNoteTypeId = selectedNoteType.id,
                selectedNoteTypeName = selectedNoteType.name,
                fieldMappings = LapisPreset.applyDefaults(selectedNoteType, current.fieldMappings),
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
        val fieldMappings = LapisPreset.applyDefaults(noteType, settings.fieldMappings)
        if (fieldMappings != settings.fieldMappings) {
            settingsRepository.update { it.copy(fieldMappings = fieldMappings) }
        }
        val payload = runCatching { AnkiMiningPayload.fromJson(rawPayload) }.getOrNull()
            ?: return@withContext false
        val mediaContext = AnkiMiningContext(
            sentence = context.sentence,
            documentTitle = context.documentTitle,
            coverPath = context.coverPath?.let { addMediaFile(it, "hoshi_cover_${File(it).name}", mimeTypeForPath(it)) },
            sasayakiAudioPath = context.sasayakiAudioPath?.let { addMediaFile(it, "hoshi_sasayaki_${File(it).name}", "audio/mp4") },
        )
        val mediaPayload = payload.copy(
            audio = payload.audio.takeIf { it.isNotBlank() }?.let(::addRemoteAudio).orEmpty(),
            popupSelectionText = context.popupSelectionText ?: payload.popupSelectionText,
        )
        val dictionaryMediaTags = payload.dictionaryMedia.associate { media ->
            media.filename to addDictionaryMedia(media).orEmpty()
        }.filterValues { it.isNotBlank() }
        val fields = fieldMappings.mapValues { (_, template) ->
            dictionaryMediaTags.entries.fold(
                AnkiHandlebarRenderer.render(template, mediaPayload, mediaContext),
            ) { value, (filename, tag) -> value.replace(filename, tag) }
        }.filterValues { it.isNotBlank() }

        val added = backend.addNote(
            deck = deck,
            noteType = noteType,
            fieldsByName = fields,
            tags = settings.tags.split(Regex("\\s+")).filter { it.isNotBlank() }.toSet(),
            allowDupes = settings.allowDupes,
        )
        added
    }

    suspend fun isDuplicate(
        expression: String,
        noteTypes: List<AnkiNoteType>,
    ): Boolean = withContext(Dispatchers.IO) {
        val settings = settings.first()
        val availableNoteTypes = noteTypes.ifEmpty { backend.fetchNoteTypes() }
        val noteTypeId = settings.selectedNoteTypeId
            ?: settings.selectedNoteTypeName?.let { name -> availableNoteTypes.firstOrNull { it.name == name }?.id }
            ?: return@withContext false
        backend.isDuplicate(noteTypeId, expression)
    }

    private fun addRemoteAudio(url: String): String? =
        runCatching {
            val data = URL(url).openStream().use { it.readBytes() }
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
            addMediaFile(file.absolutePath, file.name, mimeTypeForPath(media.path))
        }.getOrNull()

    private fun addMediaFile(path: String, preferredName: String, mimeType: String): String? {
        val file = File(path).takeIf { it.isFile } ?: return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.grantUriPermission("com.ichi2.anki", uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return backend.addMediaFromUri(uri.toString(), preferredName, mimeType)
    }

    private fun mediaCacheFile(name: String): File {
        val dir = File(context.cacheDir, "anki-media").also { it.mkdirs() }
        return File(dir, name)
    }
}

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
