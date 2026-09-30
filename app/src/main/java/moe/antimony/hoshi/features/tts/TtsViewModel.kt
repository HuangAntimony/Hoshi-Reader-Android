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
    private val engine: AndroidTtsEngine,
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
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private var refreshJob: Job? = null
    private var previewJob: Job? = null
    private var previewPlayer: ExoPlayer? = null
    private var previewFile: java.io.File? = null

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            mutableBusy.value = true
            mutableError.value = null
            try {
                val settings = repository.preferences.first().settings
                mutableEngines.value = engine.installedEngines()
                mutableCatalog.value = engine.catalog(settings)
                if (mutableCatalog.value?.voices.isNullOrEmpty()) mutableError.value = TtsFailure.MissingJapanese
            } catch (error: TimeoutCancellationException) { mutableError.value = TtsFailure.Timeout }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableCatalog.value = null; mutableError.value = (error as? TtsException)?.failure ?: TtsFailure.EngineUnavailable }
            finally { mutableBusy.value = false }
        }
    }
    fun settings(value: TtsSettings) {
        stopPreview()
        viewModelScope.launch { try { repository.settings(value); refresh() } catch (error: CancellationException) { throw error } catch (_: Exception) { mutableError.value = TtsFailure.Storage } }
    }
    suspend fun save(word: TtsWord): TtsWordError? = try {
        repository.save(word.copy(surface = word.surface.trim(), reading = word.reading.trim()))
    } catch (error: CancellationException) { throw error } catch (_: Exception) { TtsWordError.Storage }
    fun delete(id: String) { viewModelScope.launch { try { repository.delete(id) } catch (error: CancellationException) { throw error } catch (_: Exception) { mutableError.value = TtsFailure.Storage } } }
    fun preview(sentence: TtsSentence, bookId: String?, word: TtsWord? = null) {
        stopPreview()
        playback.pause()
        previewJob = viewModelScope.launch {
            mutableBusy.value = true
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
            finally { mutableBusy.value = false }
        }
    }
    fun stopPreview() {
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
