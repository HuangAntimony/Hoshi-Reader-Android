package moe.antimony.hoshi.features.tts

import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.features.sasayaki.SasayakiSettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class TtsPreviewState { Idle, Preparing, Playing }

@OptIn(UnstableApi::class)
@HiltViewModel
class TtsViewModel @Inject constructor(
    private val repository: TtsRepository,
    private val readingSettingsRepository: SasayakiSettingsRepository,
    private val engine: TtsEngineRouter,
    private val cipher: TtsCredentialCipher,
    private val audio: TtsAudioRepository,
    private val books: TtsBookRepository,
    val playback: TtsPlaybackController,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val mutableError = MutableStateFlow<TtsFailure?>(null)
    private val mutableReadingError = MutableStateFlow<TtsFailure?>(null)
    val readingError = mutableReadingError.asStateFlow()
    val readingSettings = readingSettingsRepository.settings
        .map<SasayakiSettings, SasayakiSettings?> { it }
        .catch { mutableReadingError.value = TtsFailure.Storage; emit(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun readingSettings(value: SasayakiSettings) {
        viewModelScope.launch {
            try { readingSettingsRepository.update { current -> current.withTtsReadingSettings(value) }; mutableReadingError.value = null }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableReadingError.value = TtsFailure.Storage }
        }
    }

    fun useRubyReadings(enabled: Boolean) {
        stopPreview()
        viewModelScope.launch {
            try { repository.useRubyReadings(enabled); mutableReadingError.value = null }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableReadingError.value = TtsFailure.Storage }
        }
    }

    val preferences = repository.preferences.catch { mutableError.value = TtsFailure.Storage; emit(TtsPreferences()) }.stateIn(viewModelScope, SharingStarted.Eagerly, TtsPreferences())
    private val mutableCatalog = MutableStateFlow<TtsCatalog?>(null)
    val catalog = mutableCatalog.asStateFlow()
    private val mutableEngines = MutableStateFlow<List<TtsEngineInfo>>(emptyList())
    val engines = mutableEngines.asStateFlow()
    val error = mutableError.asStateFlow()
    private val catalogBusy = MutableStateFlow(false)
    val catalogLoading = catalogBusy.asStateFlow()
    private val mutablePreviewState = MutableStateFlow(TtsPreviewState.Idle)
    val previewState = mutablePreviewState.asStateFlow()
    val busy = combine(catalogBusy, mutablePreviewState, preferences) { catalog, preview, current ->
        (catalog && current.settings.customSource()?.protocol != TtsCustomProtocol.OpenAi) || preview == TtsPreviewState.Preparing
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private var catalogGeneration = 0L
    private var previewGeneration = 0L
    private var refreshJob: Job? = null
    private var previewJob: Job? = null
    private var previewPlayer: ExoPlayer? = null
    private var previewFile: java.io.File? = null

    fun refresh(force: Boolean = false) {
        val generation = ++catalogGeneration
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            var fallback: TtsCatalog? = null
            try {
                val settings = repository.preferences.first().settings
                val optional = settings.customSource()?.takeIf { it.protocol == TtsCustomProtocol.OpenAi }
                fallback = optional?.let { TtsCatalog(emptyList(), it.id, it.voiceChoices(emptyList())) }
                val cached = engine.cachedCatalog(settings)
                fallback = cached ?: fallback
                mutableError.value = null
                mutableCatalog.value = cached ?: fallback
                catalogBusy.value = force || cached == null
                mutableEngines.value = engine.installedEngines()
                mutableCatalog.value = if (!force && cached != null) cached else engine.catalog(settings, force)
                if (mutableCatalog.value?.voices.isNullOrEmpty() && settings.customSource()?.protocol != TtsCustomProtocol.OpenAi) mutableError.value = TtsFailure.MissingJapanese
            } catch (error: TimeoutCancellationException) { mutableError.value = TtsFailure.Timeout }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableCatalog.value = fallback; mutableError.value = (error as? TtsException)?.failure ?: TtsFailure.EngineUnavailable }
            finally { if (generation == catalogGeneration) catalogBusy.value = false }
        }
    }
    fun settings(proposed: TtsSettings) {
        val value = proposed.copy(customSources = proposed.customSources.map { if (it.id == proposed.engine) it.copy(voice = proposed.voice, rate = proposed.rate) else it })
        viewModelScope.launch { try {
            val previous = repository.preferences.first().settings
            val playbackOnly = previous.synthesisSettings() == value.synthesisSettings()
            if (!playbackOnly) { stopPreview(); dismissError() }
            repository.settings(value)
            previewPlayer?.setPlaybackSpeed(normalizeTtsPlaybackRate(value.playbackRate))
            if (!playbackOnly && (previous.engine != value.engine || previous.online[previous.engine] != value.online[value.engine] ||
                previous.customSource()?.let { it.copy(name = "", rate = 1f, voice = "", format = TtsAudioFormat.Wav, preset = "") } !=
                value.customSource()?.let { it.copy(name = "", rate = 1f, voice = "", format = TtsAudioFormat.Wav, preset = "") } || catalog.value == null)) refresh()
        } catch (error: CancellationException) { throw error } catch (_: Exception) { mutableError.value = TtsFailure.Storage } }
    }
    private suspend fun configuration(source: TtsOnlineSource, region: String, key: String): TtsOnlineConfig {
        val current = repository.preferences.first().settings.online[source.id] ?: TtsOnlineConfig()
        val cleanedRegion = region.trim().lowercase(java.util.Locale.ROOT)
        if (source == TtsOnlineSource.Azure) OnlineTtsProtocol.azureBase(cleanedRegion)
        val encrypted = if (key.isNotBlank()) {
            val value = key.trim()
            if (value.any { it <= ' ' || it >= '\u007f' }) throw TtsException(TtsFailure.Configuration)
            cipher.encrypt(value, source.id)
        } else current.encryptedKey
        if (source.needsKey && encrypted.isBlank()) throw TtsException(TtsFailure.Configuration)
        return TtsOnlineConfig(cleanedRegion, encrypted)
    }

    suspend fun configure(source: TtsOnlineSource, region: String, key: String): TtsFailure? = try {
        val config = configuration(source, region, key)
        stopPreview()
        repository.configure(source, config)
        refresh(force = true)
        null
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) { (error as? TtsException)?.failure ?: TtsFailure.Storage }

    /** Tests the draft by synthesizing a sample without saving it or changing the active voice. */
    suspend fun testConfiguration(source: TtsOnlineSource, region: String, key: String, sample: String): TtsFailure? = try {
        val config = configuration(source, region, key)
        val current = repository.preferences.first()
        val draft = current.copy(settings = current.settings.copy(engine = source.id, voice = "", rate = 1f, pitch = 0f,
            online = current.settings.online + (source.id to config), parameters = emptyMap(), routingBySource = emptyMap()))
        audio.export(TtsSentence(0, 0, 0, sample), null, draft).delete()
        null
    } catch (error: TimeoutCancellationException) { TtsFailure.Timeout }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) { (error as? TtsException)?.failure ?: TtsFailure.Synthesis }
    private suspend fun customDraft(source: TtsCustomSource, key: String, removeKey: Boolean): TtsCustomSource {
        val encrypted = if (removeKey) "" else if (key.isBlank()) source.encryptedKey else {
            val value = key.trim()
            if (value.any { it <= ' ' || it >= '\u007f' }) throw TtsException(TtsFailure.Configuration)
            cipher.encrypt(value, source.id)
        }
        return source.copy(encryptedKey = encrypted).validate()
    }
    suspend fun saveSource(source: TtsCustomSource, key: String, removeKey: Boolean): TtsFailure? = try {
        val draft = customDraft(source, key, removeKey)
        val current = repository.preferences.first().settings
        val created = current.customSources.none { it.id == draft.id }
        val previous = current.customSource()
        val changed = previous?.copy(name = "", voiceIds = emptyList()) != draft.copy(name = "", voiceIds = emptyList())
        if (current.engine == draft.id && changed) stopPreview()
        repository.saveSource(draft)
        if (created) stopPreview()
        if (created || current.engine == draft.id && (changed || previous?.voiceIds != draft.voiceIds)) refresh()
        null
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) { (error as? TtsException)?.failure ?: TtsFailure.Storage }
    suspend fun testSource(source: TtsCustomSource, key: String, removeKey: Boolean, sample: String): TtsFailure? = try {
        val draft = customDraft(source, key, removeKey)
        val current = repository.preferences.first()
        val settings = current.settings.copy(customSources = current.settings.customSources.filterNot { it.id == draft.id } + draft).selectSource(draft.id).copy(routingBySource = emptyMap())
        audio.export(TtsSentence(0, 0, 0, sample), null, current.copy(settings = settings)).delete()
        null
    } catch (error: TimeoutCancellationException) { TtsFailure.Timeout }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) { (error as? TtsException)?.failure ?: TtsFailure.Synthesis }
    suspend fun deleteSource(id: String): TtsFailure? = try {
        val current = repository.preferences.first().settings
        if (current.engine == id) { stopPreview(); playback.pause() }
        repository.deleteSource(id)
        if (current.engine == id) refresh()
        null
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { TtsFailure.Storage }
    suspend fun sourceModels(source: TtsCustomSource, key: String, removeKey: Boolean): List<String> {
        val encrypted = if (removeKey) "" else if (key.isBlank()) source.encryptedKey else cipher.encrypt(key.trim(), source.id)
        return engine.models(source.copy(encryptedKey = encrypted))
    }
    suspend fun save(word: TtsWord): TtsWordError? = try {
        repository.save(word.copy(surface = word.surface.trim()))
    } catch (error: CancellationException) { throw error } catch (_: Exception) { TtsWordError.Storage }
    fun delete(id: String) { viewModelScope.launch { try { repository.delete(id) } catch (error: CancellationException) { throw error } catch (_: Exception) { mutableError.value = TtsFailure.Storage } } }
    fun preview(sentence: TtsSentence, bookId: String?, word: TtsWord? = null) {
        stopPreview()
        playback.pause()
        mutableError.value = null
        val generation = previewGeneration
        previewJob = viewModelScope.launch {
            mutablePreviewState.value = TtsPreviewState.Preparing
            try {
                val current = repository.preferences.first()
                val config = if (word == null) current else current.copy(words = current.words.filterNot { it.id == word.id || it.surface == word.surface && it.bookId == word.bookId } + word)
                val file = audio.export(sentence, bookId, config)
                previewFile = file
                val playbackRate = repository.preferences.first().settings.playbackRate
                previewPlayer = ExoPlayer.Builder(context).build().apply {
                    setPlaybackSpeed(playbackRate)
                    setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
                    addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (isPlaying && generation == previewGeneration) mutablePreviewState.value = TtsPreviewState.Playing
                        }
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED && generation == previewGeneration) stopPreview()
                        }
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            if (generation == previewGeneration) { mutableError.value = TtsFailure.AudioUnavailable; stopPreview() }
                        }
                    })
                    setMediaItem(MediaItem.fromUri(file.toURI().toString())); prepare(); play()
                }
            } catch (error: TimeoutCancellationException) { if (generation == previewGeneration) mutableError.value = TtsFailure.Timeout }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (generation == previewGeneration) mutableError.value = (error as? TtsException)?.failure ?: TtsFailure.Synthesis }
            finally { if (generation == previewGeneration && previewPlayer == null) mutablePreviewState.value = TtsPreviewState.Idle }
        }
    }
    fun stopPreview() {
        previewGeneration++
        mutablePreviewState.value = TtsPreviewState.Idle
        previewJob?.cancel(); previewJob = null
        previewPlayer?.release(); previewPlayer = null
        previewFile?.delete(); previewFile = null
    }
    suspend fun sourceSentence(book: moe.antimony.hoshi.epub.EpubBook, chapter: Int, selection: moe.antimony.hoshi.features.reader.ReaderSelectionData): TtsSentence =
        books.selection(book, chapter, selection.sentence, selection.normalizedOffset, selection.sentenceOffset)

    suspend fun sentenceAudio(sentence: TtsSentence, bookId: String?): java.io.File = audio.export(sentence, bookId, repository.preferences.first())
    fun dismissError() { mutableError.value = null; playback.clearError() }
    override fun onCleared() { stopPreview() }
}
