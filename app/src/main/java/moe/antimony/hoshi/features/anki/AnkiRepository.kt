package moe.antimony.hoshi.features.anki

import android.content.Context
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import moe.antimony.hoshi.R
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.features.audio.LocalAudioFile
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import moe.antimony.hoshi.features.audio.LocalAudioResolver
import moe.antimony.hoshi.ui.UiText
import java.io.File
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Singleton
internal class AnkiRepository(
    private val context: Context,
    private val backend: AnkiBackend,
    private val settingsRepository: AnkiSettingsRepository,
    private val localAudioRepository: LocalAudioRepository,
    private val ankiConnectBackendFactory: (String) -> AnkiBackend,
    private val loadDictionaryMedia: (DictionaryMedia) -> ByteArray?,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        backend: AnkiBackend,
        settingsRepository: AnkiSettingsRepository,
        localAudioRepository: LocalAudioRepository,
        dictionaryRepository: DictionaryRepository,
    ) : this(
        context = context,
        backend = backend,
        settingsRepository = settingsRepository,
        localAudioRepository = localAudioRepository,
        ankiConnectBackendFactory = { endpoint: String -> AnkiConnectBackend(endpoint) },
        loadDictionaryMedia = { media -> dictionaryRepository.dictionaryMedia(media.dictionary, media.path) },
    )

    internal constructor(
        context: Context,
        backend: AnkiBackend,
        settingsRepository: AnkiSettingsRepository,
        localAudioRepository: LocalAudioRepository,
        ankiConnectBackendFactory: (String) -> AnkiBackend = { endpoint: String -> AnkiConnectBackend(endpoint) },
    ) : this(
        context = context,
        backend = backend,
        settingsRepository = settingsRepository,
        localAudioRepository = localAudioRepository,
        ankiConnectBackendFactory = ankiConnectBackendFactory,
        loadDictionaryMedia = { null },
    )

    val settings: Flow<AnkiSettings> = settingsRepository.settings

    suspend fun updateSettings(transform: (AnkiSettings) -> AnkiSettings) {
        settingsRepository.update(transform)
    }

    fun isAnkiDroidAvailable(): Boolean = backend.isAvailable()

    suspend fun fetchConfiguration(): AnkiFetchResult = withContext(Dispatchers.IO) {
        val currentSettings = settings.first()
        val activeBackend = activeBackendOrError(currentSettings).getOrElse { error ->
            return@withContext AnkiFetchResult.Error(
                error.message?.let(UiText::Literal) ?: UiText.Resource(R.string.anki_fetch_configure_failed),
            )
        }
        val fetched = runCatching {
            if (!activeBackend.isAvailable()) {
                return@withContext AnkiFetchResult.Error(
                    message = if (currentSettings.backendKind == AnkiBackendKind.AnkiDroid) {
                        UiText.Resource(AnkiFetchFailure.ApiUnavailable.userMessageRes)
                    } else {
                        UiText.Resource(R.string.anki_fetch_connect_ankiconnect_failed)
                    },
                    failure = if (currentSettings.backendKind == AnkiBackendKind.AnkiDroid) {
                        AnkiFetchFailure.ApiUnavailable
                    } else {
                        null
                    },
                )
            }
            activeBackend.fetchDecks() to activeBackend.fetchNoteTypes()
        }.getOrElse { error ->
            if (error is AnkiFetchException) {
                logAnkiFetchFailure("Unable to fetch Anki configuration: ${error.failure}", error)
                return@withContext AnkiFetchResult.Error(
                    message = if (error.message != error.failure.userMessage) {
                        UiText.Literal(error.message ?: error.failure.userMessage)
                    } else {
                        UiText.Resource(error.failure.userMessageRes)
                    },
                    failure = error.failure,
                )
            }
            logAnkiFetchFailure("Unable to fetch Anki configuration.", error)
            return@withContext AnkiFetchResult.Error(
                if (currentSettings.backendKind == AnkiBackendKind.AnkiDroid) {
                    UiText.Resource(AnkiFetchFailure.ProviderFailure.userMessageRes)
                } else {
                    error.message?.let(UiText::Literal)
                        ?: UiText.Resource(R.string.anki_fetch_ankiconnect_provider_failure)
                },
            )
        }
        val (decks, noteTypes) = fetched
        if (decks.isEmpty()) {
            return@withContext AnkiFetchResult.Error(
                if (currentSettings.backendKind == AnkiBackendKind.AnkiDroid) {
                    UiText.Resource(R.string.anki_fetch_no_ankidroid_decks)
                } else {
                    UiText.Resource(R.string.anki_fetch_no_ankiconnect_decks)
                },
            )
        }
        if (noteTypes.isEmpty()) {
            return@withContext AnkiFetchResult.Error(
                if (currentSettings.backendKind == AnkiBackendKind.AnkiDroid) {
                    UiText.Resource(R.string.anki_fetch_no_ankidroid_note_types)
                } else {
                    UiText.Resource(R.string.anki_fetch_no_ankiconnect_note_types)
                },
            )
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

    suspend fun pingAnkiConnect(): AnkiConnectConnectionResult = withContext(Dispatchers.IO) {
        val currentSettings = settings.first()
        val endpoint = runCatching {
            AnkiConnectUrlValidator.requireValidEndpoint(currentSettings.ankiConnectUrl).toString()
        }.getOrElse { error ->
            return@withContext AnkiConnectConnectionResult.Error(
                error.message?.let(UiText::Literal) ?: UiText.Resource(R.string.anki_connect_invalid_url),
            )
        }
        if (ankiConnectBackendFactory(endpoint).isAvailable()) {
            AnkiConnectConnectionResult.Connected
        } else {
            AnkiConnectConnectionResult.Error(UiText.Resource(R.string.anki_fetch_connect_ankiconnect_failed))
        }
    }

    suspend fun mineEntry(
        rawPayload: String,
        context: AnkiMiningContext,
        decks: List<AnkiDeck>,
        noteTypes: List<AnkiNoteType>,
    ): Boolean = withContext(Dispatchers.IO) {
        val settings = settings.first()
        val activeBackend = activeBackendOrError(settings).getOrElse { return@withContext false }
        val availableDecks = decks.ifEmpty { activeBackend.fetchDecks() }
        val availableNoteTypes = noteTypes.ifEmpty { activeBackend.fetchNoteTypes() }
        val deck = availableDecks.firstOrNull { it.id == settings.selectedDeckId }
            ?: settings.selectedDeckName?.let { name -> availableDecks.firstOrNull { it.name == name } }
            ?: return@withContext false
        val noteType = availableNoteTypes.firstOrNull { it.id == settings.selectedNoteTypeId }
            ?: settings.selectedNoteTypeName?.let { name -> availableNoteTypes.firstOrNull { it.name == name } }
            ?: return@withContext false
        val fieldMappings = settings.fieldMappings.activeAnkiFieldMappings(noteType)
        val payload = runCatching { AnkiMiningPayload.fromJson(rawPayload) }.getOrNull()
            ?: return@withContext false
        val needsCover = fieldMappings.referencesAnkiHandlebar("{book-cover}")
        val needsSasayakiAudio = fieldMappings.referencesAnkiHandlebar("{sasayaki-audio}")
        val needsAudio = fieldMappings.referencesAnkiHandlebar("{audio}")
        val mediaContext = AnkiMiningContext(
            sentence = context.sentence,
            documentTitle = context.documentTitle,
            coverPath = context.coverPath?.takeIf { needsCover }?.let {
                addMediaFile(it, "hoshi_cover_${File(it).name}", mimeTypeForPath(it), activeBackend, settings.backendKind)
            },
            sasayakiAudioPath = context.sasayakiAudioPath?.takeIf { needsSasayakiAudio }?.let {
                addMediaFile(it, File(it).name, mimeTypeForPath(it), activeBackend, settings.backendKind)
            },
            sentenceOffset = context.sentenceOffset,
        )
        val mediaPayload = payload.copy(
            audio = payload.audio.takeIf { needsAudio && it.isNotBlank() }
                ?.let { addRemoteAudio(it, activeBackend, settings.backendKind) }
                .orEmpty(),
        )
        val dictionaryMediaTags = payload.dictionaryMedia.associate { media ->
            media.filename to addDictionaryMedia(media, activeBackend, settings.backendKind).orEmpty()
        }.filterValues { it.isNotBlank() }
        val fields = fieldMappings.mapValues { (_, template) ->
            dictionaryMediaTags.entries.fold(
                AnkiHandlebarRenderer.render(template, mediaPayload, mediaContext),
            ) { value, (filename, tag) -> value.replace(filename, tag) }
                .let(::normalizeAnkiDictionaryHtml)
        }.filterValues { it.isNotBlank() }

        val added = activeBackend.addNote(
            deck = deck,
            noteType = noteType,
            fieldsByName = fields,
            tags = settings.tags.split(Regex("\\s+")).filter { it.isNotBlank() }.toSet(),
            allowDupes = settings.allowDupes,
            duplicateScope = settings.duplicateScope,
            checkDuplicatesAcrossAllModels = settings.checkDuplicatesAcrossAllModels,
        )
        if (added) {
            when (settings.backendKind) {
                AnkiBackendKind.AnkiConnect -> if (settings.ankiConnectForceSync) activeBackend.sync()
                AnkiBackendKind.AnkiDroid -> if (settings.ankiDroidForceSync) activeBackend.sync()
            }
        }
        added
    }

    suspend fun isDuplicate(
        expression: String,
        decks: List<AnkiDeck>,
        noteTypes: List<AnkiNoteType>,
    ): Boolean = withContext(Dispatchers.IO) {
        val settings = settings.first()
        val activeBackend = activeBackendOrError(settings).getOrElse { return@withContext false }
        val availableNoteTypes = noteTypes.ifEmpty { activeBackend.fetchNoteTypes() }
        val availableDecks = decks.ifEmpty { activeBackend.fetchDecks() }
        val deck = availableDecks.firstOrNull { it.id == settings.selectedDeckId }
            ?: settings.selectedDeckName?.let { name -> availableDecks.firstOrNull { it.name == name } }
            ?: return@withContext false
        val noteTypeId = settings.selectedNoteTypeId
            ?: settings.selectedNoteTypeName?.let { name -> availableNoteTypes.firstOrNull { it.name == name }?.id }
            ?: return@withContext false
        val noteType = availableNoteTypes.firstOrNull { it.id == noteTypeId } ?: return@withContext false
        activeBackend.isDuplicate(
            deck = deck,
            noteType = noteType,
            key = expression,
            duplicateScope = settings.duplicateScope,
            checkDuplicatesAcrossAllModels = settings.checkDuplicatesAcrossAllModels,
        )
    }

    private fun addRemoteAudio(url: String, activeBackend: AnkiBackend, backendKind: AnkiBackendKind): String? =
        runCatching {
            val data = readAnkiAudioBytes(
                url = url,
                readLocalAudio = localAudioRepository::loadAudio,
                readRemoteAudio = { remoteUrl -> URL(remoteUrl).openStream().use { it.readBytes() } },
            )
                ?: return null
            val media = ankiAudioMediaFile(url, data)
            val file = mediaCacheFile(media.preferredName)
            file.writeBytes(data)
            addMediaFile(file.absolutePath, file.name, media.mimeType, activeBackend, backendKind)
        }.getOrNull()

    private fun addDictionaryMedia(media: DictionaryMedia, activeBackend: AnkiBackend, backendKind: AnkiBackendKind): String? =
        runCatching {
            val data = loadDictionaryMedia(media) ?: return null
            val file = mediaCacheFile("hoshi_dict_${data.contentHashCode()}.${media.path.substringAfterLast('.', "bin")}")
            file.writeBytes(data)
            addMediaFile(file.absolutePath, file.name, mimeTypeForPath(media.path), activeBackend, backendKind)
                ?.let(::ankiInlineMediaReference)
        }.onFailure { Log.w(TAG, "Failed to add dictionary media ${media.path}", it) }
            .getOrNull()

    private fun addMediaFile(
        path: String,
        preferredName: String,
        mimeType: String,
        activeBackend: AnkiBackend,
        backendKind: AnkiBackendKind,
    ): String? {
        val file = File(path).takeIf { it.isFile } ?: return null
        return runCatching {
            if (backendKind == AnkiBackendKind.AnkiConnect) {
                return@runCatching activeBackend.addMediaFromBytes(file.readBytes(), preferredName, mimeType)
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.grantUriPermission("com.ichi2.anki", uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activeBackend.addMediaFromUri(uri.toString(), preferredName, mimeType)
        }
            .onFailure { Log.w(TAG, "Failed to add Anki media $preferredName", it) }
            .getOrNull()
    }

    private fun mediaCacheFile(name: String): File {
        val dir = File(context.cacheDir, "anki-media").also { it.mkdirs() }
        return File(dir, name)
    }

    private fun activeBackendOrError(settings: AnkiSettings): Result<AnkiBackend> =
        when (settings.backendKind) {
            AnkiBackendKind.AnkiDroid -> Result.success(backend)
            AnkiBackendKind.AnkiConnect -> runCatching {
                val endpoint = AnkiConnectUrlValidator.requireValidEndpoint(settings.ankiConnectUrl).toString()
                ankiConnectBackendFactory(endpoint)
            }
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

internal data class AnkiAudioMediaFile(
    val preferredName: String,
    val mimeType: String,
)

internal fun ankiAudioMediaFile(url: String, data: ByteArray): AnkiAudioMediaFile {
    val extension = ankiAudioExtension(url)
    val preferredName = "hoshi_audio_${data.contentHashCode()}.$extension"
    return AnkiAudioMediaFile(
        preferredName = preferredName,
        mimeType = mimeTypeForPath(preferredName),
    )
}

private fun ankiAudioExtension(url: String): String {
    LocalAudioResolver.parseAudioUrl(url)?.file?.let { localFile ->
        LocalAudioResolver.audioExtension(localFile)
            .takeIf(::isSupportedAnkiAudioExtension)
            ?.let { return it }
    }
    return runCatching { URL(url).path }
        .getOrDefault(url.substringBefore('?'))
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .takeIf(::isSupportedAnkiAudioExtension)
        ?: "mp3"
}

private fun isSupportedAnkiAudioExtension(extension: String): Boolean =
    extension in setOf("mp3", "opus", "ogg", "aac", "m4a", "wav")

private const val TAG = "AnkiRepository"

private fun logAnkiFetchFailure(message: String, error: Throwable) {
    runCatching { Log.w(TAG, message, error) }
}

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
        ?: noteTypes.firstOrNull { AnkiFieldTemplates.matches(it) }
        ?: noteTypes.first()

internal fun fieldMappingsAfterFetch(
    selectedNoteType: AnkiNoteType,
    current: AnkiSettings,
): Map<String, String> =
    AnkiFieldTemplates.applyDefaultsIfUnmapped(selectedNoteType, current.fieldMappings)

sealed interface AnkiFetchResult {
    data class Success(
        val decks: List<AnkiDeck>,
        val noteTypes: List<AnkiNoteType>,
    ) : AnkiFetchResult

    data class Error(
        val message: UiText,
        val failure: AnkiFetchFailure? = null,
    ) : AnkiFetchResult
}

sealed interface AnkiConnectConnectionResult {
    data object Connected : AnkiConnectConnectionResult
    data class Error(val message: UiText) : AnkiConnectConnectionResult
}

fun mimeTypeForPath(path: String): String =
    when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "opus" -> "audio/ogg"
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
