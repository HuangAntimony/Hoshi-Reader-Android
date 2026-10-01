package moe.antimony.hoshi.features.tts

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import moe.antimony.hoshi.di.ApplicationScope
import moe.antimony.hoshi.epub.EpubBook

data class TtsPlaybackState(
    val bookId: String? = null,
    val title: String = "",
    val sentences: List<TtsSentence> = emptyList(),
    val index: Int = 0,
    val started: Boolean = false,
    val playing: Boolean = false,
    val preparing: Boolean = false,
    val error: TtsFailure? = null,
) {
    val sentence: TtsSentence? get() = sentences.getOrNull(index)
}

@OptIn(UnstableApi::class)
@Singleton
class TtsPlaybackController internal constructor(
    private val books: TtsBookRepository,
    private val repository: TtsRepository,
    private val audio: TtsAudioSource,
    private val output: TtsPlaybackOutput,
    private val scope: CoroutineScope,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        books: TtsBookRepository,
        repository: TtsRepository,
        audio: TtsAudioRepository,
        @ApplicationScope scope: CoroutineScope,
    ) : this(books, repository, audio, AndroidTtsPlaybackOutput(context), scope)
    private val mutableState = MutableStateFlow(TtsPlaybackState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var loadGeneration = 0L
    private var job: Job? = null
    private var prefetch: Job? = null
    private var loadJob: Job? = null
    private var preparedIndex: Int? = null
    private var preferences = TtsPreferences()
    private var associated = false
    private var stopAfterSentence = false
    var imageHoldMillis: Long = 1_000
    var presentSentence: (suspend (TtsSentence, Boolean) -> Unit)? = null
    private var heldImageIndex: Int? = null
    private var naturalAdvance = false
    private var waitingForNextSentence = false

    init {
        output.onEnded = { if (state.value.playing) advance() }
        output.onPaused = { if (state.value.playing && !state.value.preparing) pause() }
        output.onError = { fail(TtsFailure.AudioUnavailable) }
        scope.launch(Dispatchers.Main.immediate) {
            repository.preferences.catch { fail(TtsFailure.Storage) }.collect { next ->
                val prefetchChanged = next.settings.prefetchSentences != preferences.settings.prefetchSentences
                val settingsChanged = next.settings.copy(prefetchSentences = preferences.settings.prefetchSentences,
                    playbackRate = preferences.settings.playbackRate) != preferences.settings
                val wordsChanged = next.words != preferences.words
                preferences = next
                output.setPlaybackRate(next.settings.playbackRate)
                if (settingsChanged || wordsChanged || prefetchChanged) { prefetch?.cancel(); prefetch = null }
                val restartPending = wordsChanged && state.value.playing && state.value.preparing && !settingsChanged
                if (settingsChanged || restartPending || wordsChanged && !state.value.playing) {
                    pause()
                    preparedIndex = null
                    output.clear()
                    if (restartPending) play()
                }
                if (prefetchChanged && !settingsChanged && !wordsChanged && state.value.playing) prefetchAhead()
            }
        }
    }

    fun load(bookId: String, book: EpubBook, hasAudiobook: Boolean) {
        associated = hasAudiobook
        if (hasAudiobook) { stop(); return }
        if (state.value.bookId == bookId) return
        stop()
        associated = false
        mutableState.value = TtsPlaybackState(bookId = bookId, title = book.title, preparing = true)
        val token = loadGeneration
        loadJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                val sentences = books.sentences(book)
                if (token == loadGeneration) mutableState.value = state.value.copy(sentences = sentences, preparing = false, error = TtsFailure.EmptyText.takeIf { sentences.isEmpty() })
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (token == loadGeneration) fail(TtsFailure.EmptyText) }
        }
    }

    fun attachPlayer(next: ExoPlayer) { (output as? AndroidTtsPlaybackOutput)?.attach(next) }
    fun detachPlayer() { (output as? AndroidTtsPlaybackOutput)?.detach(); pause(); preparedIndex = null }

    fun startAt(chapter: Int, offset: Int, singleSentence: Boolean = false) {
        val values = state.value.sentences
        val selected = values.indexOfFirst { it.chapter > chapter || it.chapter == chapter && it.start + it.length > offset }
        if (selected >= 0) seek(selected, singleSentence, startPlayback = true)
        else if (values.isNotEmpty()) seek(values.lastIndex, singleSentence, startPlayback = true)
    }
    fun toggle() { if (state.value.playing) pause() else play() }
    fun previous() = seek((state.value.index - 1).coerceAtLeast(0))
    fun next() = seek((state.value.index + 1).coerceAtMost(state.value.sentences.lastIndex))
    fun repeat() = seek(state.value.index, startPlayback = true)
    private fun advance() {
        if (stopAfterSentence || state.value.index >= state.value.sentences.lastIndex) { pause(); preparedIndex = null; return }
        val waitMillis = preferences.settings.sentencePauseMs
        if (waitMillis == 0L) { seek(state.value.index + 1, natural = true); return }
        val token = generation
        waitingForNextSentence = true
        mutableState.value = state.value.copy(preparing = true)
        job = scope.launch(Dispatchers.Main.immediate) {
            delay(waitMillis)
            if (token == generation && state.value.playing) seek(state.value.index + 1, natural = true)
        }
    }
    private fun seek(index: Int, singleSentence: Boolean = false, natural: Boolean = false, startPlayback: Boolean = state.value.playing) {
        if (index !in state.value.sentences.indices || associated) return
        pause()
        waitingForNextSentence = false
        output.clear()
        stopAfterSentence = singleSentence
        naturalAdvance = natural
        preparedIndex = null
        mutableState.value = state.value.copy(index = index)
        if (startPlayback) play()
        else if (state.value.started) {
            val sentence = requireNotNull(state.value.sentence)
            val token = generation
            job = scope.launch(Dispatchers.Main.immediate) {
                try { presentSentence?.invoke(sentence, false) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { if (generation == token) fail(TtsFailure.AudioUnavailable) }
            }
        }
    }
    fun play() {
        if (associated || state.value.sentence == null || state.value.playing) return
        if (waitingForNextSentence) {
            waitingForNextSentence = false
            preparedIndex = null
            output.clear()
            mutableState.value = state.value.copy(index = (state.value.index + 1).coerceAtMost(state.value.sentences.lastIndex))
            naturalAdvance = true
        }
        val snapshot = state.value
        val config = preferences
        val token = ++generation
        mutableState.value = snapshot.copy(started = true, playing = true, preparing = true, error = null)
        job = scope.launch(Dispatchers.Main.immediate) {
            try {
                withTimeout(15_000) { output.connect() }
                if (preparedIndex != snapshot.index) {
                    val files = audio.audio(requireNotNull(snapshot.sentence), snapshot.bookId, config)
                    if (generation != token || associated) return@launch
                    output.prepare(files, snapshot.title, snapshot.sentence?.text.orEmpty())
                    preparedIndex = snapshot.index
                }
                val holdImages = naturalAdvance && heldImageIndex != snapshot.index
                presentSentence?.invoke(requireNotNull(snapshot.sentence), holdImages)
                if (snapshot.sentence?.imageBefore == true && holdImages && presentSentence == null) {
                    heldImageIndex = snapshot.index
                    delay(imageHoldMillis)
                }
                heldImageIndex = snapshot.index
                if (generation != token || associated) return@launch
                mutableState.value = state.value.copy(preparing = false)
                output.play()
                prefetchAhead()
            } catch (error: TimeoutCancellationException) {
                if (generation == token) fail(TtsFailure.Timeout)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (generation == token) fail((error as? TtsException)?.failure ?: TtsFailure.Synthesis) }
        }
    }
    private fun prefetchAhead() {
        if (prefetch?.isActive == true) return
        prefetch = scope.launch(Dispatchers.Main.immediate) {
            try {
                var index = state.value.index + 1
                while (true) {
                    val current = state.value
                    index = maxOf(index, current.index + 1)
                    if (index > current.index + preferences.settings.prefetchSentences.coerceIn(ttsPrefetchRange)) break
                    val sentence = current.sentences.getOrNull(index) ?: break
                    audio.audio(sentence, current.bookId, preferences)
                    index++
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Retry visibly when this sentence is requested. */ }
        }
    }
    fun pauseForLookup(): () -> Unit {
        val wasPlaying = state.value.playing
        pause()
        val token = generation
        return { if (wasPlaying && generation == token) play() }
    }
    fun pause() {
        generation++
        job?.cancel(); job = null
        mutableState.value = state.value.copy(playing = false, preparing = false)
        output.pause()
    }
    private fun fail(failure: TtsFailure) { pause(); mutableState.value = state.value.copy(error = failure) }
    fun stop() {
        waitingForNextSentence = false
        stopAfterSentence = false
        loadGeneration++
        pause()
        loadJob?.cancel(); loadJob = null
        prefetch?.cancel(); prefetch = null
        preparedIndex = null
        heldImageIndex = null
        naturalAdvance = false
        output.clear()
        output.disconnect()
        mutableState.value = TtsPlaybackState()
        scope.launch(Dispatchers.Main.immediate) { audio.clearPlaybackCache() }
    }
    fun clearError() { mutableState.value = state.value.copy(error = null) }
}
