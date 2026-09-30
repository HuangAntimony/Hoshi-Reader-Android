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
    val readingSettings = readingSettingsRepository.settings
        .map<SasayakiSettings, SasayakiSettings?> { it }
        .catch { mutableError.value = TtsFailure.Storage; emit(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun readingSettings(value: SasayakiSettings) {
        viewModelScope.launch {
            try { readingSettingsRepository.update { current -> current.withTtsReadingSettings(value) } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableError.value = TtsFailure.Storage }
        }
    }

    val preferences = repository.preferences.catch { mutableError.value = TtsFailure.Storage; emit(TtsPreferences()) }.stateIn(viewModelScope, SharingStarted.Eagerly, TtsPreferences())
    private val mutableCatalog = MutableStateFlow<TtsCatalog?>(null)
    val catalog = mutableCatalog.asStateFlow()
    private val mutableEngines = MutableStateFlow<List<TtsEngineInfo>>(emptyList())
    val engines = mutableEngines.asStateFlow()
    val error = mutableError.asStateFlow()
    private val catalogBusy = MutableStateFlow(false)
    private val previewBusy = MutableStateFlow(false)
    val busy = combine(catalogBusy, previewBusy) { catalog, preview -> catalog || preview }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private var catalogGeneration = 0L
    private var previewGeneration = 0L
    private var refreshJob: Job? = null
    private var previewJob: Job? = null
    private var previewPlayer: ExoPlayer? = null
    private var previewFile: java.io.File? = null

    fun refresh() {
        val generation = ++catalogGeneration
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            catalogBusy.value = true
            mutableError.value = null
            mutableCatalog.value = null
            try {
                val settings = repository.preferences.first().settings
                mutableEngines.value = engine.installedEngines()
                mutableCatalog.value = engine.catalog(settings)
                if (mutableCatalog.value?.voices.isNullOrEmpty()) mutableError.value = TtsFailure.MissingJapanese
            } catch (error: TimeoutCancellationException) { mutableError.value = TtsFailure.Timeout }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableCatalog.value = null; mutableError.value = (error as? TtsException)?.failure ?: TtsFailure.EngineUnavailable }
            finally { if (generation == catalogGeneration) catalogBusy.value = false }
        }
    }
    fun settings(value: TtsSettings) {
        stopPreview()
        viewModelScope.launch { try {
            val previous = repository.preferences.first().settings
            repository.settings(value)
            if (previous.engine != value.engine || previous.online != value.online || catalog.value == null) refresh()
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
        refresh()
        null
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) { (error as? TtsException)?.failure ?: TtsFailure.Storage }

    /** Tests the draft by synthesizing a sample without saving it or changing the active voice. */
    suspend fun testConfiguration(source: TtsOnlineSource, region: String, key: String, sample: String): TtsFailure? = try {
        val config = configuration(source, region, key)
        val current = repository.preferences.first()
        val draft = current.copy(settings = current.settings.copy(engine = source.id, voice = "", rate = 1f, pitch = 0f,
            online = current.settings.online + (source.id to config), parameters = emptyMap()))
        audio.export(TtsSentence(0, 0, 0, sample), null, draft).delete()
        null
    } catch (error: TimeoutCancellationException) { TtsFailure.Timeout }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) { (error as? TtsException)?.failure ?: TtsFailure.Synthesis }
    suspend fun save(word: TtsWord): TtsWordError? = try {
        repository.save(word.copy(surface = word.surface.trim(), reading = word.reading.trim()))
    } catch (error: CancellationException) { throw error } catch (_: Exception) { TtsWordError.Storage }
    fun delete(id: String) { viewModelScope.launch { try { repository.delete(id) } catch (error: CancellationException) { throw error } catch (_: Exception) { mutableError.value = TtsFailure.Storage } } }
    fun preview(sentence: TtsSentence, bookId: String?, word: TtsWord? = null) {
        stopPreview()
        playback.pause()
        val generation = previewGeneration
        previewJob = viewModelScope.launch {
            previewBusy.value = true
            try {
                val current = repository.preferences.first()
                val config = if (word == null) current else current.copy(words = current.words.filterNot { it.id == word.id || it.surface == word.surface && it.bookId == word.bookId } + word)
                val file = audio.export(sentence, bookId, config)
                previewFile = file
                previewPlayer = ExoPlayer.Builder(context).build().apply {
                    setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED) stopPreview() }
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) { mutableError.value = TtsFailure.AudioUnavailable; stopPreview() }
                    })
                    setMediaItem(MediaItem.fromUri(file.toURI().toString())); prepare(); play()
                }
            } catch (error: TimeoutCancellationException) { mutableError.value = TtsFailure.Timeout }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableError.value = (error as? TtsException)?.failure ?: TtsFailure.Synthesis }
            finally { if (generation == previewGeneration) previewBusy.value = false }
        }
    }
    fun stopPreview() {
        previewGeneration++
        previewBusy.value = false
        previewJob?.cancel(); previewJob = null
        previewPlayer?.release(); previewPlayer = null
        previewFile?.delete(); previewFile = null
    }
    suspend fun sourceSentence(book: moe.antimony.hoshi.epub.EpubBook, chapter: Int, selection: moe.antimony.hoshi.features.reader.ReaderSelectionData): TtsSentence =
        TtsText.selection(books.chapter(book, chapter), selection.sentence, selection.normalizedOffset, selection.sentenceOffset)

    suspend fun sentenceAudio(sentence: TtsSentence, bookId: String?): java.io.File = audio.export(sentence, bookId, repository.preferences.first())
    fun dismissError() { mutableError.value = null; playback.clearError() }
    override fun onCleared() { stopPreview() }
}
