package moe.antimony.hoshi.features.tts

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
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
    private var outputPrepared = false
    private var queueEpoch = 0L
    private var nextQueueToken = 0L
    private val queuedPositions = linkedMapOf<Long, TtsBookPosition>()
    private var preferences = TtsPreferences()
    fun retryPreferences() { repository.retryPreferences() }
    private var associated = false
    private var stopAfterSentence = false
    var imageHoldMillis: Long = 1_000
        set(value) {
            if (field == value) return
            field = value
            invalidateUpcoming()
            if (state.value.playing) prefetchAhead()
        }
    var presentSentence: (suspend (TtsSentence, Boolean) -> Unit)? = null
    private var presentationJob: Job? = null
    private var heldImageIndex: Int? = null
    private var naturalAdvance = false
    private var sentenceFinished = false
    private var pendingImageHold = false

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
        output.onTransition = { enteredQueuedSentence(it) }
        scope.launch(Dispatchers.Main.immediate) {
            repository.preferencesReloads.flatMapLatest {
                repository.preferences.catch { preferences = TtsPreferences(); stop(); fail(it.toTtsError(TtsFailure.Storage)) }
            }.collect { next ->
                val prefetchChanged = next.settings.prefetchSentences != preferences.settings.prefetchSentences
                val settingsChanged = next.settings.synthesisSettings() != preferences.settings.synthesisSettings()
                val wordsChanged = next.words != preferences.words
                val pauseChanged = next.settings.sentencePauseMs != preferences.settings.sentencePauseMs
                preferences = next
                if (!next.settings.enabled) { stop(); return@collect }
                output.setPlaybackRate(next.settings.playbackRate)
                if (settingsChanged || wordsChanged || prefetchChanged || pauseChanged) invalidateUpcoming()
                val restartPending = wordsChanged && state.value.playing && state.value.preparing && !settingsChanged
                if (settingsChanged || restartPending || wordsChanged && !state.value.playing) {
                    pause()
                    preparedIndex = null
                    clearOutput()
                    if (restartPending) play()
                }
                if ((prefetchChanged || wordsChanged || pauseChanged) && !settingsChanged && state.value.playing) prefetchAhead()
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
        if (!preferences.settings.enabled || associated || book == null) return
        pause()
        if (!natural) { pendingImageHold = false; invalidateUpcoming(syncCurrent = false) }
        preparedIndex = null
        sentenceFinished = false
        if (!natural) clearOutput()
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
    fun detachPlayer() {
        (output as? AndroidTtsPlaybackOutput)?.detach()
        pause(); invalidateUpcoming(syncCurrent = false); preparedIndex = null; outputPrepared = false
    }

    fun startAt(chapter: Int, offset: Int, singleSentence: Boolean = false) {
        val source = book ?: return
        requestPosition({ books.at(source, chapter, offset) }, startPlayback = true, singleSentence = singleSentence)
    }
    fun toggle() { if (state.value.playing) pause() else play() }
    fun previous() = move(-1)
    fun next() = move(1)
    fun repeat() = seek(state.value.index, startPlayback = true)
    fun restartCurrentSentence() {
        if (!preferences.settings.enabled || associated || position == null) return
        pause()
        // Reconcile an actual playlist transition before clearing its queue tokens.
        invalidateUpcoming()
        seek(state.value.index, startPlayback = true)
    }
    private fun move(direction: Int, natural: Boolean = false) {
        val current = position ?: return
        val source = book ?: return
        val index = current.index + direction
        if (index in current.sentences.indices) seek(index, natural = natural)
        else requestPosition({ books.adjacent(source, current, direction) ?: current }, state.value.playing, natural = natural)
    }
    private fun advance() {
        // An ended playlist cannot still contain useful lookahead. This also rejects late
        // completion work before resolving a new position or waiting at a deliberate barrier.
        queuedPositions.clear()
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
        if (!preferences.settings.enabled || index !in state.value.sentences.indices || associated) return
        pause()
        if (!natural) { pendingImageHold = false; invalidateUpcoming(syncCurrent = false) }
        sentenceFinished = false
        if (!natural) clearOutput()
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
        if (!preferences.settings.enabled || associated || book == null || state.value.playing) return
        if (position == null && pendingPosition == null) {
            val source = requireNotNull(book)
            pendingPosition = { books.at(source, 0, 0) }
        }
        if (sentenceFinished) {
            sentenceFinished = false
            preparedIndex = null
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
                var previousPosition = position
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
                        if (naturalAdvance && outputPrepared) output.append(files, current.title, sentence.text, ++nextQueueToken)
                        else { output.prepare(files, current.title, sentence.text); outputPrepared = true }
                        preparedIndex = current.index
                    }
                    val holdImages = naturalAdvance && heldImageIndex != current.index && imageHoldMillis > 0 &&
                        (pendingImageHold || (previousPosition?.let { books.hasImagesBetween(requireNotNull(book), it, requireNotNull(position)) } ?: sentence.imageBefore))
                    // Initial/direct playback and real image holds retain their ordered presentation.
                    // Ordinary natural transitions update the Reader without gating the audio.
                    pendingImageHold = holdImages
                    if (!naturalAdvance || holdImages || files?.isEmpty() == true) presentForPlayback(sentence, holdImages)
                    if (generation != token || associated) return@launch
                    pendingImageHold = false
                    heldImageIndex = current.index
                    if (files?.isEmpty() == true) {
                        // A deleted sentence has no player completion event. Traverse it here,
                        // retaining image presentation and cancellation, without a sentence pause.
                        if (stopAfterSentence || !current.hasNext) {
                            preparedIndex = null
                            clearOutput()
                            pause()
                            return@launch
                        }
                        preparedIndex = null
                        naturalAdvance = true
                        val next = books.adjacent(requireNotNull(book), requireNotNull(position), 1)
                            ?: throw TtsException(TtsFailure.EmptyText)
                        if (generation != token || associated) return@launch
                        previousPosition = position
                        installPosition(next)
                        current = state.value
                        yield()
                        continue
                    }
                    mutableState.value = state.value.copy(preparing = false)
                    output.play()
                    prefetchAhead()
                    if (naturalAdvance && !holdImages) presentForPlayback(sentence, false)
                    return@launch
                }
            } catch (error: TimeoutCancellationException) {
                if (generation == token) fail(error.toTtsError(TtsFailure.Timeout))
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (generation == token) fail(error.toTtsError(TtsFailure.Synthesis)) }
        }
    }
    private suspend fun presentForPlayback(sentence: TtsSentence, holdImages: Boolean) = coroutineScope {
        // Reader navigation can abandon an image/restore without cancelling speech.
        // Pausing playback still cancels this child through its parent.
        val presentation = launch(start = CoroutineStart.LAZY) {
            val presenter = presentSentence
            if (presenter != null) presenter(sentence, holdImages)
            else if (sentence.imageBefore && holdImages) delay(imageHoldMillis)
        }
        presentationJob = presentation
        try {
            presentation.join()
        } finally {
            if (presentationJob === presentation) presentationJob = null
        }
    }
    fun cancelReaderPresentation() { presentationJob?.cancel() }

    private fun invalidateUpcoming(syncCurrent: Boolean = true) {
        queueEpoch++
        prefetch?.cancel(); prefetch = null
        // Media3 may already have advanced while its batched onEvents is still pending.
        // Keep the retained current audio and controller position in sync before dropping tokens.
        val currentToken = output.discardUpcoming()
        if (syncCurrent && currentToken != null) enteredQueuedSentence(currentToken, refill = false)
        queuedPositions.clear()
    }

    private fun clearOutput() {
        outputPrepared = false
        queuedPositions.clear()
        output.clear()
    }

    private fun enteredQueuedSentence(token: Long, refill: Boolean = true) {
        val next = queuedPositions[token] ?: return
        while (queuedPositions.isNotEmpty()) {
            val first = queuedPositions.keys.first()
            queuedPositions.remove(first)
            if (first == token) break
        }
        if (state.value.playing) generation++
        job?.cancel()
        installPosition(next)
        preparedIndex = next.index
        heldImageIndex = next.index
        naturalAdvance = true
        pendingImageHold = false
        sentenceFinished = false
        mutableState.value = state.value.copy(preparing = false)
        if (state.value.playing) {
            val expectedGeneration = generation
            job = scope.launch(Dispatchers.Main.immediate) {
                try { presentForPlayback(next.sentence, false) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { if (generation == expectedGeneration) fail(error.toTtsError(TtsFailure.AudioUnavailable)) }
            }
            if (refill) prefetchAhead()
        }
    }

    private fun prefetchAhead() {
        if (prefetch?.isActive == true) return
        prefetch = scope.launch(Dispatchers.Main.immediate) {
            val epoch = queueEpoch
            try {
                val source = book ?: return@launch
                var cursor = position ?: return@launch
                val queued = mutableListOf<Pair<Int, Int>>()
                fun key(value: TtsBookPosition) = value.sentence.chapter to value.index
                fun after(a: Pair<Int, Int>, b: Pair<Int, Int>) =
                    a.first > b.first || a.first == b.first && a.second > b.second
                data class Pending(val previous: TtsBookPosition, val next: TtsBookPosition, val audio: Deferred<List<File>?>)
                data class Discovered(val result: Result<TtsBookPosition?>)
                val pending = ArrayDeque<Pending>()
                val workers = Semaphore(ttsSynthesisConcurrency)
                var discovery: Deferred<Discovered>? = null
                var atEnd = false
                while (true) {
                    val current = position ?: break
                    queued.removeAll { !after(it, key(current)) }
                    if (after(key(current), key(cursor))) {
                        discovery?.cancel()
                        discovery = null
                        cursor = current
                    }
                    val depth = preferences.settings.prefetchSentences.coerceIn(ttsPrefetchRange)
                    while (!atEnd && queued.size + pending.size < depth) {
                        val previous = cursor
                        // Farther chapter reads must not delay publishing ready nearby audio.
                        val reading = discovery ?: async {
                            val result = try { Result.success(books.adjacent(source, previous, 1)) }
                            catch (error: CancellationException) { throw error }
                            catch (error: Exception) { Result.failure(error) }
                            Discovered(result)
                        }.also { discovery = it }
                        val discovered = select<Discovered?> {
                            pending.firstOrNull()?.audio?.onAwait { null }
                            reading.onAwait { it }
                        } ?: break
                        discovery = null
                        val next = discovered.result.getOrThrow()
                        if (next == null) { atEnd = true; break }
                        cursor = next
                        val config = preferences
                        val bookId = state.value.bookId
                        pending += Pending(previous, next, async {
                            try { workers.withPermit { audio.audio(next.sentence, bookId, config) } }
                            catch (error: CancellationException) { throw error }
                            catch (error: Exception) {
                                ErrorDiagnosticEvents.buffer.record(UiText.Resource(R.string.tts_failed), error)
                                null // Cache later results, but never skip this unit in the playlist.
                            }
                        })
                    }
                    if (pending.isEmpty()) break
                    val item = pending.removeFirst()
                    val files = item.audio.await()
                    val hasImageBarrier = imageHoldMillis > 0L && books.hasImagesBetween(source, item.previous, item.next)
                    if (epoch != queueEpoch) return@launch
                    // Check live state after suspension, and publish completed requests in source order.
                    val tail = queuedPositions.values.lastOrNull() ?: position
                    val canQueue = !stopAfterSentence && preferences.settings.sentencePauseMs == 0L &&
                        outputPrepared && preparedIndex != null && tail?.let { key(it) == key(item.previous) } == true &&
                        !files.isNullOrEmpty() && !hasImageBarrier
                    if (canQueue && queuedPositions.values.none { key(it) == key(item.next) }) {
                        val token = ++nextQueueToken
                        queuedPositions[token] = item.next
                        output.append(requireNotNull(files), state.value.title, item.next.sentence.text, token)
                    }
                    queued += key(item.next)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { ErrorDiagnosticEvents.buffer.record(UiText.Resource(R.string.tts_failed), error) }
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
        invalidateUpcoming(syncCurrent = false)
        preparedIndex = null
        heldImageIndex = null
        naturalAdvance = false
        pendingImageHold = false
        clearOutput()
        output.disconnect()
        mutableState.value = TtsPlaybackState()
        scope.launch(Dispatchers.Main.immediate) { audio.clearPlaybackCache() }
    }
    fun clearError() { mutableState.value = state.value.copy(error = null) }
}
