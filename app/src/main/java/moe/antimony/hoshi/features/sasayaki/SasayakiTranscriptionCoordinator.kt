package moe.antimony.hoshi.features.sasayaki

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.di.ApplicationScope
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.epub.BookWorkRegistry
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.features.sasayaki.transcription.SasayakiTranscriptionBackend
import moe.antimony.hoshi.ui.UiText

internal enum class SasayakiTranscriptionStage { Idle, Preparing, AwaitingDownload, Downloading, Transcribing, Pausing, Aligning }

internal data class SasayakiTranscriptionState(
    val root: File? = null,
    val stage: SasayakiTranscriptionStage = SasayakiTranscriptionStage.Idle,
    val through: Double = 0.0,
    val duration: Double = 0.0,
    val download: Double = 0.0,
    val downloadBytes: Long = 0,
    val remainingSeconds: Double? = null,
    val hasTranscript: Boolean = false,
    val error: UiText? = null,
    val match: SasayakiMatchData? = null,
    val revision: Long = 0,
    val completionRevision: Long = 0,
) {
    val running get() = stage != SasayakiTranscriptionStage.Idle
}

internal fun interface SasayakiTranscriptionClock { fun nowNanos(): Long }

/** One process-wide transcription. Removing the Reader route pauses inference and finishes saving/alignment. */
@Singleton
internal class SasayakiTranscriptionCoordinator @Inject constructor(
    private val backend: SasayakiTranscriptionBackend,
    private val repository: SasayakiTranscriptionRepository,
    private val workRegistry: BookWorkRegistry,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val clock: SasayakiTranscriptionClock = SasayakiTranscriptionClock(System::nanoTime),
) {
    private val gate = Mutex()
    @Volatile private var task: Job? = null
    @Volatile private var downloadApproval: CompletableDeferred<Unit>? = null
    private val mutableState = MutableStateFlow(SasayakiTranscriptionState())
    val state = mutableState.asStateFlow()

    suspend fun start(root: File, source: String, preset: SasayakiTranscriptionPreset = SasayakiTranscriptionPreset.Balanced): Boolean = gate.withLock {
        if (task?.isCompleted == false) return false
        val deleted = AtomicBoolean(false)
        val entered = AtomicBoolean(false)
        val job = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
            entered.set(true)
            run(root, source, deleted, preset)
        }
        val registration = try {
            workRegistry.register(root) {
                deleted.set(true)
                job.cancelAndJoin()
            }
        } catch (error: Exception) {
            job.cancel()
            mutableState.value = SasayakiTranscriptionState(root = root, error = failure(), revision = state.value.revision, completionRevision = state.value.completionRevision)
            return false
        }
        task = job
        mutableState.value = SasayakiTranscriptionState(
            root = root, stage = SasayakiTranscriptionStage.Preparing, revision = state.value.revision,
            completionRevision = state.value.completionRevision,
        )
        job.invokeOnCompletion {
            registration.close()
            // A lazy job cancelled before dispatch never enters run's finally block.
            if (!entered.get() && task === job) {
                mutableState.update { it.copy(stage = SasayakiTranscriptionStage.Idle) }
            }
        }
        job.start()
        true
    }

    fun pause(root: File) {
        if (mutableState.value.root != root || !mutableState.value.running) return
        // Repeated pause while finalizing must not interrupt saving the checkpoint.
        if (mutableState.value.stage == SasayakiTranscriptionStage.Aligning) return
        mutableState.update { it.copy(stage = SasayakiTranscriptionStage.Pausing) }
        task?.cancel()
    }

    fun confirmDownload(root: File) {
        if (state.value.root == root && state.value.stage == SasayakiTranscriptionStage.AwaitingDownload) {
            downloadApproval?.complete(Unit)
        }
    }

    suspend fun clear(root: File): Boolean = gate.withLock {
        if (task?.isCompleted == false) return false
        repository.clear(root)
        if (mutableState.value.root == root) {
            mutableState.update { it.copy(through = 0.0, duration = 0.0, hasTranscript = false, error = null) }
        }
        true
    }

    private suspend fun run(root: File, source: String, deleted: AtomicBoolean, preset: SasayakiTranscriptionPreset) = coroutineScope {
        var duration = 0.0
        var through = 0.0
        var checkpointNeeded = false
        var hasSavedTranscript = false
        val tokens = ArrayList<SasayakiToken>()
        var result: SasayakiMatchData? = null
        var error: UiText? = null
        var completed = false
        var alignment: SasayakiTranscriptionAlignment? = null
        var alignedCount = -1
        val requests = Channel<List<SasayakiToken>>(Channel.CONFLATED)
        suspend fun align(snapshot: List<SasayakiToken>, complete: Boolean) {
            val active = alignment ?: repository.openAlignment(root).also { alignment = it }
            val matched = active.align(snapshot, complete)
            if (deleted.get()) return
            alignedCount = snapshot.size
            result = matched
            mutableState.update {
                it.copy(match = matched, error = null, revision = it.revision + if (it.match == matched) 0 else 1)
            }
        }
        val matching = launch {
            var lastMatchAt: Long? = null
            for (pending in requests) {
                lastMatchAt?.let { previous ->
                    val waitMillis = ((15_000_000_000L - (clock.nowNanos() - previous)) / 1_000_000L).coerceAtLeast(0)
                    delay(waitMillis)
                }
                val snapshot = requests.tryReceive().getOrNull() ?: pending
                lastMatchAt = clock.nowNanos()
                try {
                    align(snapshot, complete = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A match failure must not discard successful recognition or stop inference.
                    alignment = null
                    mutableState.update { it.copy(error = failure()) }
                }
            }
        }
        fun checkpoint() = SasayakiTranscript(through, duration, tokens.toList(), source)
        try {
            duration = backend.duration(source)
            require(duration.isFinite() && duration > 0)
            val saved = repository.load(root)?.takeIf {
                kotlin.math.abs(it.duration - duration) < 0.01 && (it.source == null || it.source == source)
            }
            if (saved != null) {
                through = saved.through
                tokens.addAll(saved.tokens)
            }
            hasSavedTranscript = saved != null
            val startPosition = through
            var firstBatchAt: Long? = null
            var lastPersist = clock.nowNanos()

            mutableState.update { it.copy(
                stage = SasayakiTranscriptionStage.Preparing, through = through, duration = duration,
                hasTranscript = saved != null,
            ) }
            if (saved?.isComplete != true) {
                backend.transcribe(source, through, parallelism = preset.parallelism,
                    previousTokens = tokens.takeLastWhile { it.end >= through - 1.0 }, onDownloadRequired = { bytes ->
                    val approval = CompletableDeferred<Unit>()
                    downloadApproval = approval
                    try {
                        currentCoroutineContext().ensureActive()
                        mutableState.update { it.copy(stage = SasayakiTranscriptionStage.AwaitingDownload, downloadBytes = bytes) }
                        approval.await()
                    } finally {
                        downloadApproval = null
                    }
                }, onDownload = { fraction ->
                    currentCoroutineContext().ensureActive()
                    mutableState.update { it.copy(
                        stage = if (fraction >= 1.0) SasayakiTranscriptionStage.Preparing else SasayakiTranscriptionStage.Downloading,
                        download = fraction.coerceIn(0.0, 1.0),
                    ) }
                }, onBatch = { batch ->
                    currentCoroutineContext().ensureActive()
                    require(batch.through.isFinite() && batch.through >= through && batch.through <= duration + 1.5)
                    val previous = tokens.lastOrNull()?.start ?: 0.0
                    require(batch.tokens.withIndex().all { (index, token) ->
                        token.text.isNotEmpty() && token.start.isFinite() && token.end.isFinite() && token.start >= 0 && token.end > token.start &&
                            token.end <= duration + 1.5 && token.start >= (if (index == 0) previous else batch.tokens[index - 1].start)
                    })
                    tokens.addAll(batch.tokens)
                    through = batch.through.coerceAtMost(duration)
                    checkpointNeeded = true
                    val now = clock.nowNanos()
                    if (firstBatchAt == null) firstBatchAt = now
                    val elapsed = (now - requireNotNull(firstBatchAt)) / 1e9
                    val processed = through - startPosition
                    val remaining = if (elapsed > 3 && processed > 30) (duration - through) * elapsed / processed else null
                    mutableState.update { it.copy(
                        stage = SasayakiTranscriptionStage.Transcribing, through = through, duration = duration,
                        remainingSeconds = remaining, hasTranscript = true,
                    ) }
                    if (batch.tokens.isNotEmpty()) requests.trySend(tokens.toList())
                    if (now - lastPersist >= 15_000_000_000L) {
                        repository.save(root, checkpoint())
                        lastPersist = now
                    }
                })
                currentCoroutineContext().ensureActive()
                through = duration
            } else {
                // Preserve legacy iOS completion when adding Android's source identity.
                through = duration
            }
            checkpointNeeded = true
            completed = true
        } catch (_: CancellationException) {
            // Pause keeps only complete backend batches. Resume re-decodes the unfinished segment.
        } catch (_: Exception) {
            error = failure()
        } finally {
            requests.close()
            withContext(NonCancellable + ioDispatcher) {
                matching.cancelAndJoin()
                if (checkpointNeeded && !deleted.get()) {
                    try {
                        repository.save(root, checkpoint())
                        if (tokens.isNotEmpty() && !deleted.get() && (completed || alignedCount != tokens.size)) {
                            mutableState.update { it.copy(stage = SasayakiTranscriptionStage.Aligning) }
                            align(tokens, complete = completed)
                        }
                    } catch (_: Exception) { error = failure() }
                }
                mutableState.update { it.copy(
                    stage = SasayakiTranscriptionStage.Idle,
                    through = through, duration = duration, remainingSeconds = null,
                    hasTranscript = (checkpointNeeded || hasSavedTranscript) && !deleted.get(), error = error ?: it.error,
                    match = result ?: it.match, completionRevision = it.completionRevision + 1,
                ) }
            }
        }
    }

    private fun failure() = UiText.Resource(R.string.sasayaki_transcription_failed)
}
