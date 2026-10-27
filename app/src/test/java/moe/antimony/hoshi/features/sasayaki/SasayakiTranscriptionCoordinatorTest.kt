package moe.antimony.hoshi.features.sasayaki

import java.io.File
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookWorkRegistry
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.features.sasayaki.transcription.SasayakiTranscriptionBackend
import moe.antimony.hoshi.features.sasayaki.transcription.SasayakiTranscriptionBatch
import moe.antimony.hoshi.features.sasayaki.transcription.SasayakiDownloadFailure
import moe.antimony.hoshi.features.sasayaki.transcription.SasayakiModelFile
import moe.antimony.hoshi.features.diagnostics.NonfatalDiagnosticsLogger
import moe.antimony.hoshi.features.diagnostics.NonfatalDiagnosticsStore
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.resolve
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SasayakiTranscriptionCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun downloadFailureContextReachesScreenStateAndPersistedDiagnosticsWithOriginalCause() = runTest {
        ErrorDiagnosticEvents.buffer.drain()
        val spec = SasayakiModelFile("nakuru.pack", "https://example.org/nakuru.pack", 100, "pinned-hash")
        val original = IOException("unexpected end of stream", IOException("peer closed connection"))
        val failure = SasayakiDownloadFailure(spec, 42, 3, original)
        val backend = object : SasayakiTranscriptionBackend {
            override suspend fun duration(source: String) = 100.0
            override suspend fun transcribe(source: String, from: Double, onDownloadRequired: suspend (Long) -> Unit, onDownload: suspend (Double) -> Unit, onBatch: suspend (SasayakiTranscriptionBatch) -> Unit, parallelism: Int) {
                throw failure.asSasayakiFailure(SasayakiFailureKind.ModelResources)
            }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = SasayakiTranscriptionCoordinator(backend, MemoryRepository(), BookWorkRegistry(), backgroundScope, dispatcher)
        coordinator.start(temporary.newFolder(), "audio")
        runCurrent()

        val operation = UiText.Joined(listOf(UiText.Resource(R.string.sasayaki_transcription_error_resources),
            UiText.Resource(R.string.sasayaki_transcription_download_details, spec.name, spec.url, 42L, 100L, 3)))
        assertFalse(coordinator.state.value.running)
        assertEquals(UiText.Joined(listOf(operation, UiText.Literal("unexpected end of stream\npeer closed connection"))),
            coordinator.state.value.error)

        val directory = temporary.newFolder()
        val logger = NonfatalDiagnosticsLogger(NonfatalDiagnosticsStore(directory, dispatcher), backgroundScope,
            ErrorDiagnosticEvents.buffer) { it.resolve({ _, args -> args.joinToString("|") }, { _, _, _ -> "" }) }
        logger.snapshot()
        val saved = NonfatalDiagnosticsStore(directory, dispatcher).snapshot().single()
        assertTrue(saved.operation, saved.operation.contains("nakuru.pack|https://example.org/nakuru.pack|42|100|3"))
        assertEquals("unexpected end of stream\npeer closed connection", saved.message)
        assertTrue(saved.stackTrace.contains("Caused by: java.io.IOException: peer closed connection"))
    }

    @Test fun nakuruPublishesMatchesBeforePausing() = runTest {
        val root = temporary.newFolder()
        val repository = MemoryRepository()
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        coordinator.start(root, "audio")
        runCurrent()
        assertEquals(SasayakiTranscriptionStage.Transcribing, coordinator.state.value.stage)
        assertNotNull(coordinator.state.value.match)
        assertEquals(1, repository.alignments)
        assertTrue(repository.savedMatches.isEmpty())
        coordinator.pause(root)
        runCurrent()
        assertEquals(listOf(coordinator.state.value.match), repository.savedMatches)
    }

    @Test fun preparingModelsDoesNotShowTranscriptionOrCompletedDownload() = runTest {
        val download = CompletableDeferred<Unit>()
        val loadModels = CompletableDeferred<Unit>()
        val backend = object : SasayakiTranscriptionBackend {
            override suspend fun duration(source: String) = 100.0
            override suspend fun transcribe(source: String, from: Double, onDownloadRequired: suspend (Long) -> Unit, onDownload: suspend (Double) -> Unit, onBatch: suspend (SasayakiTranscriptionBatch) -> Unit, parallelism: Int) {
                download.await()
                onDownload(1.0)
                loadModels.await()
                onBatch(SasayakiTranscriptionBatch(emptyList(), 10.0))
                awaitCancellation()
            }
        }
        val coordinator = SasayakiTranscriptionCoordinator(backend, MemoryRepository(), BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        val root = temporary.newFolder()
        coordinator.start(root, "audio"); runCurrent()
        assertEquals(SasayakiTranscriptionStage.Preparing, coordinator.state.value.stage)
        download.complete(Unit); runCurrent()
        assertEquals(SasayakiTranscriptionStage.Preparing, coordinator.state.value.stage)
        loadModels.complete(Unit); runCurrent()
        assertEquals(SasayakiTranscriptionStage.Transcribing, coordinator.state.value.stage)
        coordinator.pause(root); runCurrent()
    }

    @Test fun pauseSavesCompletedBatchesAndAlignsBeforeAllowingAnotherRun() = runTest {
        val repository = MemoryRepository()
        val backend = Backend()
        val root = temporary.newFolder()
        val coordinator = SasayakiTranscriptionCoordinator(backend, repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        assertTrue(coordinator.start(root, "audio"))
        runCurrent()
        assertEquals(10.0, coordinator.state.value.through, 0.0)
        assertEquals(2, backend.parallelism)
        assertFalse(coordinator.start(root, "audio"))
        coordinator.pause(root)
        runCurrent()
        assertFalse(coordinator.state.value.running)
        assertEquals(10.0, repository.saved!!.through, 0.0)
        assertEquals(listOf(SasayakiToken("本文", 2.0, 3.0)), repository.saved!!.tokens)
        assertNotNull(coordinator.state.value.match)
        assertEquals(1, repository.alignments)
    }

    @Test fun resumeUsesSavedPositionWhileDifferentSourceRestarts() = runTest {
        val root = temporary.newFolder()
        val repository = MemoryRepository(SasayakiTranscript(20.0, 100.0, listOf(SasayakiToken("前", 1.0, 2.0)), "audio"))
        val backend = Backend()
        val coordinator = SasayakiTranscriptionCoordinator(backend, repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        coordinator.start(root, "audio"); runCurrent()
        assertEquals(20.0, backend.from, 0.0)
        coordinator.pause(root); runCurrent()
        assertEquals(listOf("前", "本文"), repository.saved!!.tokens.map { it.text })
        val revision = coordinator.state.value.revision
        coordinator.start(root, "replacement"); runCurrent()
        assertEquals(0.0, backend.from, 0.0)
        coordinator.pause(root); runCurrent()
        assertEquals(listOf("本文"), repository.saved!!.tokens.map { it.text })
        assertTrue(coordinator.state.value.revision > revision)
    }

    @Test fun resumeCanChangePresetWithoutRestartingOrDiscardingCommittedText() = runTest {
        val root = temporary.newFolder()
        val repository = MemoryRepository()
        val backend = Backend()
        val coordinator = SasayakiTranscriptionCoordinator(backend, repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        coordinator.start(root, "audio", SasayakiTranscriptionPreset.Fast); runCurrent()
        assertEquals(3, backend.parallelism)
        coordinator.pause(root); runCurrent()
        val committed = repository.saved!!
        coordinator.start(root, "audio", SasayakiTranscriptionPreset.Light); runCurrent()
        assertEquals(1, backend.parallelism)
        assertEquals(committed.through, backend.from, 0.0)
        coordinator.pause(root); runCurrent()
        assertEquals(committed.tokens, repository.saved!!.tokens.take(committed.tokens.size))
        assertEquals(20.0, repository.saved!!.through, 0.0)
    }

    @Test fun completeTranscriptRealignsWithoutLoadingModel() = runTest {
        val root = temporary.newFolder()
        val repository = MemoryRepository(SasayakiTranscript(99.0, 100.0, listOf(SasayakiToken("本文", 1.0, 2.0))))
        val backend = Backend()
        val coordinator = SasayakiTranscriptionCoordinator(backend, repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        coordinator.start(root, "audio"); runCurrent()
        assertEquals(0, backend.transcriptions)
        assertEquals(1, repository.alignments)
        assertFalse(coordinator.state.value.running)
        assertTrue(repository.saved!!.isComplete)
        assertEquals(listOf(coordinator.state.value.match), repository.savedMatches)
    }

    @Test fun safeCheckpointNearAudioEndStillResumesRemainingSpeech() = runTest {
        val root = temporary.newFolder()
        val repository = MemoryRepository(SasayakiTranscript(99.0, 100.0, emptyList(), "audio"))
        val backend = object : SasayakiTranscriptionBackend {
            var from = -1.0
            override suspend fun duration(source: String) = 100.0
            override suspend fun transcribe(source: String, from: Double, onDownloadRequired: suspend (Long) -> Unit, onDownload: suspend (Double) -> Unit, onBatch: suspend (SasayakiTranscriptionBatch) -> Unit, parallelism: Int) {
                this.from = from
                onBatch(SasayakiTranscriptionBatch(listOf(SasayakiToken("末尾", 99.1, 99.9)), 100.0))
            }
        }
        val coordinator = SasayakiTranscriptionCoordinator(backend, repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        coordinator.start(root, "audio"); runCurrent()
        assertEquals(99.0, backend.from, 0.0)
        assertEquals("末尾", repository.saved!!.tokens.single().text)
        assertTrue(repository.saved!!.isComplete)
        assertFalse(SasayakiTranscript(0.0, 1.0, emptyList(), "short").isComplete)
        assertTrue(SasayakiTranscript(99.0, 100.0, emptyList()).isComplete)
    }

    @Test fun deletingBookJoinsTaskAndDiscardsFinalWrites() = runTest {
        val root = temporary.newFolder()
        val registry = BookWorkRegistry()
        val repository = MemoryRepository()
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, registry, backgroundScope, StandardTestDispatcher(testScheduler))
        assertTrue(coordinator.start(root, "audio")); runCurrent()
        assertTrue(coordinator.state.value.running)
        val alignmentsBeforeDeletion = repository.alignments
        registry.delete(root) { root.deleteRecursively() }
        assertFalse(coordinator.state.value.running)
        assertNull(repository.saved)
        assertTrue(repository.savedMatches.isEmpty())
        assertEquals(alignmentsBeforeDeletion, repository.alignments)
        assertFalse(root.exists())
    }

    @Test fun immediatePauseReleasesSlotWithoutStartingBackend() = runTest {
        val root = temporary.newFolder()
        val backend = Backend()
        val repository = MemoryRepository()
        val coordinator = SasayakiTranscriptionCoordinator(backend, repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        assertTrue(coordinator.start(root, "audio"))
        coordinator.pause(root); runCurrent()
        assertFalse(coordinator.state.value.running)
        assertEquals(0, backend.transcriptions)
        assertTrue(repository.savedMatches.isEmpty())
    }

    @Test fun decodingFailureRetainsCompletedProgressAndShowsResourceError() = runTest {
        val root = temporary.newFolder()
        val repository = MemoryRepository()
        val coordinator = SasayakiTranscriptionCoordinator(Backend(fail = true), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        coordinator.start(root, "audio"); runCurrent()
        assertEquals(10.0, repository.saved!!.through, 0.0)
        assertNotNull(coordinator.state.value.error)
        assertEquals(1, repository.alignments)
        assertFalse(coordinator.state.value.running)
        assertEquals(listOf(coordinator.state.value.match), repository.savedMatches)
    }

    @Test fun audioDurationFailureUsesAudioSpecificMessage() = runTest {
        val backend = object : SasayakiTranscriptionBackend {
            override suspend fun duration(source: String): Double = throw IOException("decoder detail")
            override suspend fun transcribe(source: String, from: Double, onDownloadRequired: suspend (Long) -> Unit, onDownload: suspend (Double) -> Unit, onBatch: suspend (SasayakiTranscriptionBatch) -> Unit, parallelism: Int) = Unit
        }
        val coordinator = SasayakiTranscriptionCoordinator(backend, MemoryRepository(), BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))

        coordinator.start(temporary.newFolder(), "audio"); runCurrent()

        assertEquals(UiText.Joined(listOf(UiText.Resource(R.string.sasayaki_transcription_error_audio), UiText.Literal("decoder detail"))), coordinator.state.value.error)
    }

    @Test fun modelResourceFailureUsesResourceSpecificMessage() = runTest {
        val backend = object : SasayakiTranscriptionBackend {
            override suspend fun duration(source: String) = 100.0
            override suspend fun transcribe(source: String, from: Double, onDownloadRequired: suspend (Long) -> Unit, onDownload: suspend (Double) -> Unit, onBatch: suspend (SasayakiTranscriptionBatch) -> Unit, parallelism: Int) {
                throw SasayakiOperationFailure(SasayakiFailureKind.ModelResources, IOException("network detail"))
            }
        }
        val coordinator = SasayakiTranscriptionCoordinator(backend, MemoryRepository(), BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))

        coordinator.start(temporary.newFolder(), "audio"); runCurrent()

        assertEquals(UiText.Joined(listOf(UiText.Resource(R.string.sasayaki_transcription_error_resources), UiText.Literal("network detail"))), coordinator.state.value.error)
    }

    @Test fun checkpointWriteFailureUsesStorageSpecificMessage() = runTest {
        val repository = MemoryRepository(failSave = true)
        val root = temporary.newFolder()
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        coordinator.start(root, "audio"); runCurrent()
        coordinator.pause(root); runCurrent()

        assertEquals(UiText.Joined(listOf(UiText.Resource(R.string.sasayaki_transcription_error_storage), UiText.Literal("save detail"))), coordinator.state.value.error)
        assertEquals(listOf(coordinator.state.value.match), repository.savedMatches)
    }

    @Test fun clearRetainsMatchAndCannotRaceWithActiveTranscription() = runTest {
        val root = temporary.newFolder()
        val repository = MemoryRepository()
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        coordinator.start(root, "audio"); runCurrent()
        assertFalse(coordinator.clear(root))
        coordinator.pause(root); runCurrent()
        val match = coordinator.state.value.match
        assertTrue(coordinator.clear(root))
        assertNull(repository.saved)
        assertEquals(match, coordinator.state.value.match)
    }

    @Test fun checkpointsUseWallTimeAndCanResumeWithoutWaitingForPause() = runTest {
        val root = temporary.newFolder()
        val repository = MemoryRepository()
        val backend = object : SasayakiTranscriptionBackend {
            override suspend fun duration(source: String) = 100.0
            override suspend fun transcribe(source: String, from: Double, onDownloadRequired: suspend (Long) -> Unit, onDownload: suspend (Double) -> Unit, onBatch: suspend (SasayakiTranscriptionBatch) -> Unit, parallelism: Int) {
                onBatch(SasayakiTranscriptionBatch(listOf(SasayakiToken("一", 1.0, 2.0)), 10.0))
                delay(15_000)
                onBatch(SasayakiTranscriptionBatch(listOf(SasayakiToken("二", 20.0, 21.0)), 40.0))
                awaitCancellation()
            }
        }
        val coordinator = SasayakiTranscriptionCoordinator(
            backend, repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler),
            SasayakiTranscriptionClock { testScheduler.currentTime * 1_000_000 },
        )
        coordinator.start(root, "audio"); runCurrent()
        assertNull(repository.saved)
        advanceTimeBy(15_000); runCurrent()
        assertTrue(coordinator.state.value.running)
        assertEquals(40.0, repository.saved!!.through, 0.0)
        assertEquals(listOf("一", "二"), repository.saved!!.tokens.map { it.text })
        assertNotNull(coordinator.state.value.remainingSeconds)
        coordinator.pause(root); runCurrent()
    }

    @Test fun publishesMatchesWhileTranscriptionIsStillRunning() = runTest {
        val repository = MemoryRepository()
        val root = temporary.newFolder()
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        coordinator.start(root, "audio"); runCurrent()
        assertEquals(SasayakiTranscriptionStage.Transcribing, coordinator.state.value.stage)
        assertNotNull(coordinator.state.value.match)
        assertTrue(coordinator.state.value.revision > 0)
        coordinator.pause(root); runCurrent()
    }

    @Test fun slowMatchingCoalescesLatestTokensWithoutBlockingRecognition() = runTest {
        val batches = Channel<SasayakiTranscriptionBatch>(Channel.UNLIMITED)
        val finish = CompletableDeferred<Unit>()
        val repository = MemoryRepository(beforeAlign = { count -> if (count == 1) finish.await() })
        val backend = object : SasayakiTranscriptionBackend {
            override suspend fun duration(source: String) = 100.0
            override suspend fun transcribe(source: String, from: Double, onDownloadRequired: suspend (Long) -> Unit, onDownload: suspend (Double) -> Unit, onBatch: suspend (SasayakiTranscriptionBatch) -> Unit, parallelism: Int) {
                for (batch in batches) onBatch(batch)
            }
        }
        val root = temporary.newFolder()
        val coordinator = SasayakiTranscriptionCoordinator(backend, repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler), SasayakiTranscriptionClock { testScheduler.currentTime * 1_000_000 })
        coordinator.start(root, "audio"); runCurrent()
        batches.send(SasayakiTranscriptionBatch(listOf(SasayakiToken("一", 1.0, 2.0)), 10.0)); runCurrent()
        batches.send(SasayakiTranscriptionBatch(listOf(SasayakiToken("二", 11.0, 12.0)), 20.0)); runCurrent()
        batches.send(SasayakiTranscriptionBatch(listOf(SasayakiToken("三", 21.0, 22.0)), 30.0)); runCurrent()
        assertEquals(30.0, coordinator.state.value.through, 0.0)
        assertEquals(listOf(1), repository.alignmentSizes)
        finish.complete(Unit); runCurrent()
        advanceTimeBy(14_999); runCurrent()
        assertEquals(listOf(1), repository.alignmentSizes)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(1, 3), repository.alignmentSizes)
        assertEquals(SasayakiTranscriptionStage.Transcribing, coordinator.state.value.stage)
        batches.close(); runCurrent()
        assertFalse(coordinator.state.value.running)
        assertEquals(listOf(1, 3, 3), repository.alignmentSizes)
        assertTrue(repository.saved!!.isComplete)
    }

    @Test fun deletionCancelsPendingMatchBeforeRemovingBook() = runTest {
        val repository = MemoryRepository(beforeAlign = { awaitCancellation() })
        val registry = BookWorkRegistry()
        val root = temporary.newFolder()
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, registry, backgroundScope, StandardTestDispatcher(testScheduler))
        coordinator.start(root, "audio"); runCurrent()
        registry.delete(root) { root.deleteRecursively() }
        assertFalse(root.exists())
        assertFalse(coordinator.state.value.running)
        assertNull(coordinator.state.value.match)
        assertNull(repository.saved)
    }

    @Test fun matchFailureKeepsRecognitionAndRetriesLatestTokensOnPause() = runTest {
        moe.antimony.hoshi.ui.ErrorDiagnosticEvents.buffer.drain()
        val original = IOException("match failed", IllegalStateException("alignment detail"))
        val repository = MemoryRepository(beforeAlign = { count -> if (count == 1) throw original })
        val root = temporary.newFolder()
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        coordinator.start(root, "audio"); runCurrent()
        assertEquals(SasayakiTranscriptionStage.Transcribing, coordinator.state.value.stage)
        assertEquals(UiText.Joined(listOf(UiText.Resource(R.string.sasayaki_transcription_error_match), UiText.Literal("match failed\nalignment detail"))), coordinator.state.value.error)
        val diagnostic = moe.antimony.hoshi.ui.ErrorDiagnosticEvents.buffer.drain().single()
        assertSame(original, diagnostic.error)
        coordinator.pause(root); runCurrent()
        assertNotNull(coordinator.state.value.match)
        assertNull(coordinator.state.value.error)
        assertEquals(10.0, repository.saved!!.through, 0.0)
    }

    @Test fun finalAlignmentFailureStillSavesTheLastSuccessfulLiveMatch() = runTest {
        val root = temporary.newFolder()
        val backend = BatchBackend()
        val failure = IOException("final alignment failed")
        val repository = MemoryRepository(beforeAlign = { count -> if (count == 2) throw failure })
        val coordinator = SasayakiTranscriptionCoordinator(backend, repository, BookWorkRegistry(), backgroundScope,
            StandardTestDispatcher(testScheduler), SasayakiTranscriptionClock { testScheduler.currentTime * 1_000_000 })
        coordinator.start(root, "audio")
        backend.batches.send(SasayakiTranscriptionBatch(listOf(SasayakiToken("一", 1.0, 2.0)), 10.0)); runCurrent()
        val live = coordinator.state.value.match!!
        backend.batches.send(SasayakiTranscriptionBatch(listOf(SasayakiToken("二", 11.0, 12.0)), 20.0)); runCurrent()
        coordinator.pause(root); runCurrent()
        assertEquals(listOf(1, 2), repository.alignmentSizes)
        assertEquals(listOf(live), repository.savedMatches)
        assertEquals(20.0, repository.saved!!.through, 0.0)
        assertFalse(coordinator.state.value.running)
        assertEquals(UiText.Joined(listOf(UiText.Resource(R.string.sasayaki_transcription_error_match),
            UiText.Literal("final alignment failed"))), coordinator.state.value.error)
    }

    @Test fun failedMatchingNeverSavesAnEmptyReplacement() = runTest {
        val repository = MemoryRepository(beforeAlign = { throw IOException("no successful match") })
        val root = temporary.newFolder()
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        coordinator.start(root, "audio"); runCurrent()
        coordinator.pause(root); runCurrent()
        assertNotNull(repository.saved)
        assertNull(coordinator.state.value.match)
        assertTrue(repository.savedMatches.isEmpty())
    }

    @Test fun finalMatchSaveFailureReportsOriginalErrorAndRetainsCheckpoint() = runTest {
        ErrorDiagnosticEvents.buffer.drain()
        val failure = IOException("match write failed", IOException("disk full"))
        val repository = MemoryRepository(beforeMatchSave = { throw failure })
        val root = temporary.newFolder()
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        coordinator.start(root, "audio"); runCurrent()
        coordinator.pause(root); runCurrent()
        assertFalse(coordinator.state.value.running)
        assertEquals(10.0, repository.saved!!.through, 0.0)
        assertTrue(repository.savedMatches.isEmpty())
        assertEquals(UiText.Joined(listOf(UiText.Resource(R.string.sasayaki_transcription_error_storage),
            UiText.Literal("match write failed\ndisk full"))), coordinator.state.value.error)
        assertSame(failure, ErrorDiagnosticEvents.buffer.drain().single().error)
    }

    @Test fun pausingAgainDuringFinalMatchSaveKeepsTheTaskSlotUntilPersistenceFinishes() = runTest {
        val finishSave = CompletableDeferred<Unit>()
        val repository = MemoryRepository(beforeMatchSave = { finishSave.await() })
        val root = temporary.newFolder()
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        coordinator.start(root, "audio"); runCurrent()
        coordinator.pause(root); runCurrent()
        assertEquals(SasayakiTranscriptionStage.Aligning, coordinator.state.value.stage)
        coordinator.pause(root); runCurrent()
        assertFalse(coordinator.start(root, "audio"))
        assertTrue(repository.savedMatches.isEmpty())
        finishSave.complete(Unit); runCurrent()
        assertFalse(coordinator.state.value.running)
        assertEquals(listOf(coordinator.state.value.match), repository.savedMatches)
    }

    private class BatchBackend : SasayakiTranscriptionBackend {
        val batches = Channel<SasayakiTranscriptionBatch>(Channel.UNLIMITED)
        override suspend fun duration(source: String) = 100.0
        override suspend fun transcribe(source: String, from: Double, onDownloadRequired: suspend (Long) -> Unit,
            onDownload: suspend (Double) -> Unit, onBatch: suspend (SasayakiTranscriptionBatch) -> Unit, parallelism: Int,
        ) { for (batch in batches) onBatch(batch) }
    }

    private class Backend(private val fail: Boolean = false) : SasayakiTranscriptionBackend {
        var from = -1.0
        var transcriptions = 0
        var parallelism = 0
        override suspend fun duration(source: String) = 100.0
        override suspend fun transcribe(source: String, from: Double, onDownloadRequired: suspend (Long) -> Unit, onDownload: suspend (Double) -> Unit, onBatch: suspend (SasayakiTranscriptionBatch) -> Unit, parallelism: Int) {
            this.from = from; this.parallelism = parallelism; transcriptions++
            onBatch(SasayakiTranscriptionBatch(listOf(SasayakiToken("本文", from + 2, from + 3)), from + 10))
            if (fail) error("Decoder details must not be shown to users")
            awaitCancellation()
        }
    }

    private class MemoryRepository(var saved: SasayakiTranscript? = null, val beforeAlign: suspend (Int) -> Unit = {},
        private val failSave: Boolean = false, private val beforeMatchSave: suspend () -> Unit = {},
    ) : SasayakiTranscriptionRepository {
        var alignments = 0
        val alignmentSizes = mutableListOf<Int>()
        override suspend fun load(root: File) = saved
        override suspend fun save(root: File, transcript: SasayakiTranscript) {
            if (failSave) throw IOException("save detail")
            saved = transcript
        }
        val savedMatches = mutableListOf<SasayakiMatchData>()
        override suspend fun saveMatch(root: File, match: SasayakiMatchData) { beforeMatchSave(); savedMatches += match }
        override suspend fun clear(root: File) { saved = null }
        override suspend fun openAlignment(root: File) = SasayakiTranscriptionAlignment { tokens ->
            alignments++
            alignmentSizes += tokens.size
            beforeAlign(alignments)
            SasayakiMatchData(listOf(SasayakiMatch("1", 2.0, 3.0, "本文", 0, 0, 2)), 0)
        }
    }
}
