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
    val previousChapter: Int? = null,
    val nextChapter: Int? = null,
    val started: Boolean = false,
    val playing: Boolean = false,
    val preparing: Boolean = false,
    val error: TtsError? = null,
) {
    val sentence: TtsSentence? get() = sentences.getOrNull(index)
    val canStart: Boolean get() = bookId != null
    val hasPrevious: Boolean get() = index > 0 || previousChapter != null
    val hasNext: Boolean get() = index < sentences.lastIndex || nextChapter != null
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
    private var book: EpubBook? = null
    private var position: TtsBookPosition? = null
    private var pendingPosition: (suspend () -> TtsBookPosition?)? = null
    private var job: Job? = null
    private var prefetch: Job? = null
    private var preparedIndex: Int? = null
    private var preferences = TtsPreferences()
    private var associated = false
    private var stopAfterSentence = false
    var imageHoldMillis: Long = 1_000
    var presentSentence: (suspend (TtsSentence, Boolean) -> Unit)? = null
    private var heldImageIndex: Int? = null
    private var naturalAdvance = false
    private var sentenceFinished = false

    init {
        output.onEnded = {
            // Completion can arrive after lookup has paused us. Remember it without resuming.
            if (preparedIndex == state.value.index) {
                sentenceFinished = true
                if (state.value.playing) advance()
            }
        }
        output.onPaused = { if (state.value.playing && !state.value.preparing) pause() }
        output.onError = { fail(it.toTtsError(TtsFailure.AudioUnavailable)) }
        scope.launch(Dispatchers.Main.immediate) {
            repository.preferences.catch { fail(it.toTtsError(TtsFailure.Storage)) }.collect { next ->
                val prefetchChanged = next.settings.prefetchSentences != preferences.settings.prefetchSentences
                val settingsChanged = next.settings.synthesisSettings() != preferences.settings.synthesisSettings()
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
        this.book = book
        mutableState.value = TtsPlaybackState(bookId = bookId, title = book.title)
    }

    private fun installPosition(next: TtsBookPosition) {
        if (position?.sentence?.chapter != next.sentence.chapter) {
            preparedIndex = null
            heldImageIndex = null
        }
        position = next
        mutableState.value = state.value.copy(sentences = next.sentences, index = next.index,
            previousChapter = next.previousChapter, nextChapter = next.nextChapter)
    }

    private fun requestPosition(
        resolve: suspend () -> TtsBookPosition?, startPlayback: Boolean,
        singleSentence: Boolean = false, natural: Boolean = false,
    ) {
        if (associated || book == null) return
        pause()
        if (!natural) { prefetch?.cancel(); prefetch = null }
        preparedIndex = null
        sentenceFinished = false
        output.clear()
        stopAfterSentence = singleSentence
        naturalAdvance = natural
        pendingPosition = resolve
        if (startPlayback) { play(); return }
        val token = generation
        mutableState.value = state.value.copy(preparing = true, error = null)
        job = scope.launch(Dispatchers.Main.immediate) {
            try {
                val next = resolve() ?: throw TtsException(TtsFailure.EmptyText)
                if (token != generation) return@launch
                pendingPosition = null
                installPosition(next)
                mutableState.value = state.value.copy(preparing = false)
                if (state.value.started) presentSentence?.invoke(next.sentence, false)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (token == generation) fail(error.toTtsError(TtsFailure.Storage)) }
        }
    }

    fun prepareAt(chapter: Int, offset: Int) {
        if (state.value.started) return
        val source = book ?: return
        requestPosition({ books.at(source, chapter, offset) }, startPlayback = false)
    }

    fun attachPlayer(next: ExoPlayer) { (output as? AndroidTtsPlaybackOutput)?.attach(next) }
    fun detachPlayer() { (output as? AndroidTtsPlaybackOutput)?.detach(); pause(); preparedIndex = null }

    fun startAt(chapter: Int, offset: Int, singleSentence: Boolean = false) {
        val source = book ?: return
        requestPosition({ books.at(source, chapter, offset) }, startPlayback = true, singleSentence = singleSentence)
    }
    fun toggle() { if (state.value.playing) pause() else play() }
    fun previous() = move(-1)
    fun next() = move(1)
    fun repeat() = seek(state.value.index, startPlayback = true)
    private fun move(direction: Int, natural: Boolean = false) {
        val current = position ?: return
        val source = book ?: return
        val index = current.index + direction
        if (index in current.sentences.indices) seek(index, natural = natural)
        else requestPosition({ books.adjacent(source, current, direction) ?: current }, state.value.playing, natural = natural)
    }
    private fun advance() {
        if (stopAfterSentence || !state.value.hasNext) {
            sentenceFinished = false
            pause(); preparedIndex = null; return
        }
        val waitMillis = preferences.settings.sentencePauseMs
        if (waitMillis == 0L) { move(1, natural = true); return }
        val token = generation
        sentenceFinished = true
        mutableState.value = state.value.copy(preparing = true)
        job = scope.launch(Dispatchers.Main.immediate) {
            delay(waitMillis)
            if (token == generation && state.value.playing) move(1, natural = true)
        }
    }
    private fun seek(index: Int, singleSentence: Boolean = false, natural: Boolean = false, startPlayback: Boolean = state.value.playing) {
        if (index !in state.value.sentences.indices || associated) return
        pause()
        if (!natural) { prefetch?.cancel(); prefetch = null }
        sentenceFinished = false
        output.clear()
        stopAfterSentence = singleSentence
        naturalAdvance = natural
        preparedIndex = null
        pendingPosition = null
        position = position?.copy(index = index)
        mutableState.value = state.value.copy(index = index)
        if (startPlayback) play()
        else if (state.value.started) {
            val sentence = requireNotNull(state.value.sentence)
            val token = generation
            job = scope.launch(Dispatchers.Main.immediate) {
                try { presentSentence?.invoke(sentence, false) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { if (generation == token) fail(error.toTtsError(TtsFailure.AudioUnavailable)) }
            }
        }
    }
    fun play() {
        if (associated || book == null || state.value.playing) return
        if (position == null && pendingPosition == null) {
            val source = requireNotNull(book)
            pendingPosition = { books.at(source, 0, 0) }
        }
        if (sentenceFinished) {
            sentenceFinished = false
            preparedIndex = null
            output.clear()
            if (stopAfterSentence || !state.value.hasNext) return
            val source = requireNotNull(book)
            val current = requireNotNull(position)
            pendingPosition = { books.adjacent(source, current, 1) }
            naturalAdvance = true
        }
        val snapshot = state.value
        val config = preferences
        job?.cancel()
        val token = ++generation
        mutableState.value = snapshot.copy(started = true, playing = true, preparing = true, error = null)
        job = scope.launch(Dispatchers.Main.immediate) {
            try {
                pendingPosition?.let { resolve ->
                    val next = resolve() ?: throw TtsException(TtsFailure.EmptyText)
                    if (generation != token || associated) return@launch
                    pendingPosition = null
                    installPosition(next)
                }
                withTimeout(15_000) { output.connect() }
                var current = state.value
                while (generation == token && !associated) {
                    val sentence = requireNotNull(current.sentence)
                    val files = if (preparedIndex != current.index) audio.audio(sentence, current.bookId, config) else null
                    if (generation != token || associated) return@launch
                    if (files?.isNotEmpty() == true) {
                        output.prepare(files, current.title, sentence.text)
                        preparedIndex = current.index
                    }
                    val holdImages = naturalAdvance && heldImageIndex != current.index
                    presentSentence?.invoke(sentence, holdImages)
                    if (sentence.imageBefore && holdImages && presentSentence == null) {
                        delay(imageHoldMillis)
                    }
                    if (generation != token || associated) return@launch
                    heldImageIndex = current.index
                    if (files?.isEmpty() == true) {
                        // A deleted sentence has no player completion event. Traverse it here,
                        // retaining image presentation and cancellation, without a sentence pause.
                        if (stopAfterSentence || !current.hasNext) {
                            preparedIndex = null
                            output.clear()
                            pause()
                            return@launch
                        }
                        preparedIndex = null
                        naturalAdvance = true
                        val next = books.adjacent(requireNotNull(book), requireNotNull(position), 1)
                            ?: throw TtsException(TtsFailure.EmptyText)
                        if (generation != token || associated) return@launch
                        installPosition(next)
                        current = state.value
                        yield()
                        continue
                    }
                    mutableState.value = state.value.copy(preparing = false)
                    output.play()
                    prefetchAhead()
                    return@launch
                }
            } catch (error: TimeoutCancellationException) {
                if (generation == token) fail(error.toTtsError(TtsFailure.Timeout))
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (generation == token) fail(error.toTtsError(TtsFailure.Synthesis)) }
        }
    }
    private fun prefetchAhead() {
        if (prefetch?.isActive == true) return
        prefetch = scope.launch(Dispatchers.Main.immediate) {
            try {
                val source = book ?: return@launch
                var cursor = position ?: return@launch
                val queued = mutableListOf<Pair<Int, Int>>()
                fun key(value: TtsBookPosition) = value.sentence.chapter to value.index
                fun after(a: Pair<Int, Int>, b: Pair<Int, Int>) =
                    a.first > b.first || a.first == b.first && a.second > b.second
                while (true) {
                    val current = position ?: break
                    queued.removeAll { !after(it, key(current)) }
                    if (after(key(current), key(cursor))) cursor = current
                    if (queued.size >= preferences.settings.prefetchSentences.coerceIn(ttsPrefetchRange)) break
                    cursor = books.adjacent(source, cursor, 1) ?: break
                    audio.audio(cursor.sentence, state.value.bookId, preferences)
                    queued += key(cursor)
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
    private fun fail(failure: TtsError) { pause(); mutableState.value = state.value.copy(error = failure) }
    fun stop() {
        sentenceFinished = false
        stopAfterSentence = false
        pause()
        book = null
        position = null
        pendingPosition = null
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
