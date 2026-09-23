package moe.antimony.hoshi.features.sasayaki

import androidx.lifecycle.ViewModelStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookWorkRegistry
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiMatchSource
import moe.antimony.hoshi.features.sasayaki.transcription.SasayakiTranscriptionBackend
import moe.antimony.hoshi.features.sasayaki.transcription.SasayakiTranscriptionBatch
import moe.antimony.hoshi.ui.UiText
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SasayakiTranscriptionViewModelTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun matchSourceSelectsDefaultEvenWithoutSavedTranscriptAndAfterReaderReopen() = runTest {
        val root = temporary.newFolder()
        val repository = MemoryRepository()
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        val model = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        model.bind(root, "audio") {}; runCurrent()
        // Match loading may finish after the initial Reader binding.
        model.bind(root, "audio", SasayakiMatchSource.Transcription) {}; runCurrent()
        assertEquals(SasayakiMatchMode.Transcription, model.uiState.value.mode)
        assertFalse(model.uiState.value.hasTranscript)
        model.selectMode(SasayakiMatchMode.Subtitles)
        model.bind(root, "audio", SasayakiMatchSource.Transcription) {}
        assertEquals(SasayakiMatchMode.Subtitles, model.uiState.value.mode)

        val reopened = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        reopened.bind(root, "audio", SasayakiMatchSource.Transcription) {}; runCurrent()
        assertEquals(SasayakiMatchMode.Transcription, reopened.uiState.value.mode)
        reopened.bind(temporary.newFolder(), "audio", SasayakiMatchSource.Subtitles) {}; runCurrent()
        assertEquals(SasayakiMatchMode.Subtitles, reopened.uiState.value.mode)
    }

    @Test fun existingTranscriptDoesNotOverrideSubtitleMatchOrManualTabChoice() = runTest {
        val root = temporary.newFolder()
        val repository = MemoryRepository(SasayakiTranscript(20.0, 100.0, emptyList(), "audio"))
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        val model = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        model.bind(root, "audio", SasayakiMatchSource.Subtitles) {}; runCurrent()
        assertTrue(model.uiState.value.hasTranscript)
        assertEquals(SasayakiMatchMode.Subtitles, model.uiState.value.mode)
        model.selectMode(SasayakiMatchMode.Transcription)
        model.bind(root, "different-audio", SasayakiMatchSource.Subtitles) {}; runCurrent()
        assertEquals(SasayakiMatchMode.Transcription, model.uiState.value.mode)
    }

    @Test fun manualSelectionWinsWhenMatchSourceArrivesLater() = runTest {
        val root = temporary.newFolder()
        val repository = MemoryRepository()
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        val model = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        model.bind(root, "audio") {}; runCurrent()
        model.selectMode(SasayakiMatchMode.Subtitles)
        model.bind(root, "audio", SasayakiMatchSource.Transcription) {}; runCurrent()
        assertEquals(SasayakiMatchMode.Subtitles, model.uiState.value.mode)
    }

    @Test fun publishesDuringTranscriptionWithoutLockingPauseOrReloadingControls() = runTest {
        val repository = MemoryRepository()
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        val model = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        val matches = mutableListOf<SasayakiMatchData>()
        model.bind(temporary.newFolder(), "audio", onMatchUpdated = matches::add)
        runCurrent(); model.start(); runCurrent()
        assertEquals(listOf(repository.match), matches)
        assertTrue(model.uiState.value.canPause)
        assertFalse(model.uiState.value.isLoading)
        model.pause(); runCurrent()
        assertEquals(listOf(repository.match), matches)
    }

    @Test fun idleCoordinatorDoesNotOverwriteLaterSubtitleMatchOnReaderReopen() = runTest {
        val root = temporary.newFolder()
        val repository = MemoryRepository()
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        coordinator.start(root, "audio"); runCurrent()
        coordinator.pause(root); runCurrent()
        val imported = SasayakiMatchData(listOf(SasayakiMatch("srt", 20.0, 25.0, "新字幕", 0, 100, 3)), 0)
        var readerMatch = imported
        val reopened = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        reopened.bind(root, "audio") { readerMatch = it }; runCurrent()
        assertEquals(imported, readerMatch)
    }

    @Test fun newReaderBindingReceivesAlreadyPublishedActiveMatch() = runTest {
        val root = temporary.newFolder()
        val repository = MemoryRepository()
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        coordinator.start(root, "audio"); runCurrent()
        val matches = mutableListOf<SasayakiMatchData>()
        val model = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        model.bind(root, "audio", onMatchUpdated = matches::add); runCurrent()
        assertEquals(listOf(repository.match), matches)
        coordinator.pause(root); runCurrent()
    }

    @Test fun fastSilentCompletionRefreshesControlsEvenWhenRunningStateWasConflated() = runTest {
        val repository = MemoryRepository()
        val backend = object : SasayakiTranscriptionBackend {
            override suspend fun duration(source: String) = 100.0
            override suspend fun transcribe(source: String, from: Double, onDownloadRequired: suspend (Long) -> Unit, onDownload: suspend (Double) -> Unit, onBatch: suspend (SasayakiTranscriptionBatch) -> Unit, parallelism: Int) = Unit
        }
        val coordinator = SasayakiTranscriptionCoordinator(backend, repository, BookWorkRegistry(), backgroundScope, kotlinx.coroutines.Dispatchers.Unconfined)
        val model = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        val root = temporary.newFolder()
        model.bind(root, "audio") {}; runCurrent()
        coordinator.start(root, "audio")
        assertFalse(coordinator.state.value.running)
        runCurrent()
        assertTrue(model.uiState.value.transcriptComplete)
        assertEquals(R.string.sasayaki_transcription_realign, model.uiState.value.actionLabelRes)
    }

    @Test fun partialTranscriptResumesOnlyForItsSelectedSource() = runTest {
        val root = temporary.newFolder()
        val repository = MemoryRepository(SasayakiTranscript(20.0, 100.0, emptyList(), "audio"))
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        val model = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        model.bind(root, "audio") {}
        runCurrent()
        assertTrue(model.uiState.value.canStart)
        assertEquals(R.string.sasayaki_transcription_resume, model.uiState.value.actionLabelRes)
        assertEquals(20.0, model.uiState.value.through, 0.0)
        model.bind(root, "different-audio") {}
        runCurrent()
        assertEquals(R.string.sasayaki_transcription_start, model.uiState.value.actionLabelRes)
        model.bind(root, null) {}
        assertFalse(model.uiState.value.canStart)
    }

    @Test fun savedCheckpointNearAudioEndStillOffersResume() = runTest {
        val repository = MemoryRepository(SasayakiTranscript(99.0, 100.0, emptyList(), "audio"))
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        val model = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        model.bind(temporary.newFolder(), "audio") {}
        runCurrent()
        assertEquals(R.string.sasayaki_transcription_resume, model.uiState.value.actionLabelRes)
    }

    @Test fun preparedShortAudioWithNoProgressDoesNotOfferRealignment() = runTest {
        val repository = MemoryRepository(SasayakiTranscript(0.0, 1.0, emptyList(), "audio"))
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        val model = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        model.bind(temporary.newFolder(), "audio") {}
        runCurrent()
        assertEquals(R.string.sasayaki_transcription_start, model.uiState.value.actionLabelRes)
    }

    @Test fun completedLegacyTranscriptOffersRealignment() = runTest {
        val repository = MemoryRepository(SasayakiTranscript(100.0, 100.0, emptyList()))
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        val model = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        model.bind(temporary.newFolder(), "audio") {}
        runCurrent()
        assertEquals(R.string.sasayaki_transcription_realign, model.uiState.value.actionLabelRes)
    }

    @Test fun pauseFinishesAlignmentAndDeliversMatchAfterSheetStopsObserving() = runTest {
        val root = temporary.newFolder()
        val repository = MemoryRepository().apply { finishAlignment = CompletableDeferred() }
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        val model = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        val matches = mutableListOf<SasayakiMatchData>()
        model.bind(root, "audio", onMatchUpdated = matches::add)
        runCurrent()
        model.selectMode(SasayakiMatchMode.Transcription)
        model.start()
        runCurrent()
        assertTrue(model.uiState.value.running)
        assertFalse(model.uiState.value.canStart)
        model.selectMode(SasayakiMatchMode.Subtitles)
        assertEquals(SasayakiMatchMode.Transcription, model.uiState.value.mode)
        model.pause()
        runCurrent()
        assertTrue(model.uiState.value.running)
        assertTrue(matches.isEmpty())
        repository.finishAlignment!!.complete(Unit)
        runCurrent()
        assertFalse(model.uiState.value.running)
        assertEquals(listOf(repository.match), matches)
        assertEquals(R.string.sasayaki_transcription_resume, model.uiState.value.actionLabelRes)
        assertEquals(10.0, model.uiState.value.through, 0.0)
    }

    @Test fun latestReaderCallbackReceivesCompletionOnlyOnce() = runTest {
        val root = temporary.newFolder()
        val repository = MemoryRepository().apply { finishAlignment = CompletableDeferred() }
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        val model = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        val oldMatches = mutableListOf<SasayakiMatchData>()
        val newMatches = mutableListOf<SasayakiMatchData>()
        model.bind(root, "audio", onMatchUpdated = oldMatches::add)
        runCurrent()
        model.start()
        runCurrent()
        model.bind(root, "audio", onMatchUpdated = newMatches::add)
        model.pause()
        runCurrent()
        repository.finishAlignment!!.complete(Unit)
        runCurrent()
        model.bind(root, "audio", onMatchUpdated = newMatches::add)
        runCurrent()
        assertTrue(oldMatches.isEmpty())
        assertEquals(listOf(repository.match), newMatches)
    }

    @Test fun differentBookTaskDisablesStartingWithoutLeakingItsProgress() = runTest {
        val repository = MemoryRepository()
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        val otherRoot = temporary.newFolder()
        coordinator.start(otherRoot, "other-audio")
        runCurrent()
        val model = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        model.bind(temporary.newFolder(), "audio") {}
        runCurrent()
        assertTrue(model.uiState.value.busyElsewhere)
        assertFalse(model.uiState.value.canStart)
        assertFalse(model.uiState.value.running)
        assertEquals(0.0, model.uiState.value.through, 0.0)
        model.start()
        runCurrent()
        assertEquals(otherRoot, coordinator.state.value.root)
        assertEquals(10.0, coordinator.state.value.through, 0.0)
    }

    @Test fun clearingTranscriptRequiresConfirmationAndKeepsExistingMatch() = runTest {
        val root = temporary.newFolder()
        val repository = MemoryRepository(SasayakiTranscript(20.0, 100.0, emptyList()))
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        val model = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        val matches = mutableListOf<SasayakiMatchData>()
        model.bind(root, "audio", onMatchUpdated = matches::add)
        runCurrent()
        model.requestClear()
        assertTrue(model.uiState.value.showClearConfirmation)
        assertNotNull(repository.saved)
        model.confirmClear()
        runCurrent()
        assertNull(repository.saved)
        assertFalse(model.uiState.value.hasTranscript)
        assertFalse(model.uiState.value.showClearConfirmation)
        assertTrue(matches.isEmpty())
    }

    @Test fun leavingImmediatelyAfterStartDoesNotLeaveControlsLocked() = runTest {
        val repository = MemoryRepository()
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        val model = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        model.bind(temporary.newFolder(), "audio") {}
        runCurrent()
        model.start()
        model.pause()
        runCurrent()
        assertTrue(model.uiState.value.canStart)
        assertFalse(model.uiState.value.running)
    }

    @Test fun loadFailureUsesLocalizedErrorAndReleasesLoadingState() = runTest {
        val repository = MemoryRepository().apply { failLoad = true }
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        val model = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        model.bind(temporary.newFolder(), "audio") {}
        runCurrent()
        assertFalse(model.uiState.value.isLoading)
        assertEquals(UiText.Resource(R.string.sasayaki_transcription_failed), model.uiState.value.error)
    }

    @Test fun downloadConfirmationGatesWorkAndCancellationKeepsSavedTranscriptUntouched() = runTest {
        val saved = SasayakiTranscript(20.0, 100.0, listOf(SasayakiToken("前", 1.0, 2.0)), "audio")
        val repository = MemoryRepository(saved)
        val backend = Backend(needsDownload = true)
        val coordinator = SasayakiTranscriptionCoordinator(backend, repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        val model = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        val matches = mutableListOf<SasayakiMatchData>()
        model.bind(temporary.newFolder(), "audio", onMatchUpdated = matches::add)
        runCurrent()
        model.start(); runCurrent()
        assertEquals(SasayakiTranscriptionStage.AwaitingDownload, model.uiState.value.stage)
        assertEquals(161_016_054L, model.uiState.value.downloadBytes)
        assertEquals(0, backend.downloads)
        model.pause(); runCurrent()
        model.confirmDownload(); runCurrent()
        assertEquals(0, backend.downloads)
        assertSame(saved, repository.saved)
        assertTrue(matches.isEmpty())
        assertTrue(model.uiState.value.canStart)
        model.start(); runCurrent()
        model.confirmDownload(); runCurrent()
        assertEquals(1, backend.downloads)
        assertEquals(SasayakiTranscriptionStage.Transcribing, model.uiState.value.stage)
        model.pause(); runCurrent()
    }

    @Test fun cancellingFirstDownloadLeavesNoEmptyTranscriptAndCanRetry() = runTest {
        val repository = MemoryRepository()
        val backend = Backend(needsDownload = true)
        val coordinator = SasayakiTranscriptionCoordinator(backend, repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        val model = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        model.bind(temporary.newFolder(), "audio") {}
        runCurrent()
        model.start(); runCurrent()
        model.pause(); runCurrent()
        assertNull(repository.saved)
        assertFalse(model.uiState.value.hasTranscript)
        assertTrue(model.uiState.value.canStart)
        model.start(); runCurrent()
        assertEquals(SasayakiTranscriptionStage.AwaitingDownload, model.uiState.value.stage)
        model.pause(); runCurrent()
    }

    @Test fun resumeKeepsSavedProgressVisibleWhileAudioIsPreparing() = runTest {
        val repository = MemoryRepository(SasayakiTranscript(20.0, 100.0, emptyList(), "audio"))
        val duration = CompletableDeferred<Unit>()
        val coordinator = SasayakiTranscriptionCoordinator(Backend(prepare = duration), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        val model = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        model.bind(temporary.newFolder(), "audio") {}
        runCurrent()
        model.start(); runCurrent()
        assertEquals(SasayakiTranscriptionStage.Preparing, model.uiState.value.stage)
        assertEquals(20.0, model.uiState.value.through, 0.0)
        assertEquals(100.0, model.uiState.value.duration, 0.0)
        model.pause(); runCurrent()
    }

    @Test fun removingReaderRoutePausesAndSavesCompletedBatches() = runTest {
        val root = temporary.newFolder()
        val repository = MemoryRepository()
        val coordinator = SasayakiTranscriptionCoordinator(Backend(), repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        val model = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        val store = ViewModelStore().apply { put("transcription", model) }
        val matches = mutableListOf<SasayakiMatchData>()
        model.bind(root, "audio", onMatchUpdated = matches::add)
        runCurrent()
        model.start(); runCurrent()
        assertTrue(coordinator.state.value.running)
        val deliveredBeforeClosing = matches.toList()
        store.clear(); runCurrent()
        assertFalse(coordinator.state.value.running)
        assertEquals(10.0, repository.saved!!.through, 0.0)
        assertNotNull(coordinator.state.value.match)
        assertEquals("A closed Reader must not receive UI callbacks", deliveredBeforeClosing, matches)
        val reopened = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        reopened.bind(root, "audio") {}
        runCurrent()
        assertTrue(reopened.uiState.value.canStart)
        assertEquals(R.string.sasayaki_transcription_resume, reopened.uiState.value.actionLabelRes)
    }

    @Test fun removingReaderRouteCancelsUnconfirmedDownloadWithoutWritingTranscript() = runTest {
        val repository = MemoryRepository()
        val backend = Backend(needsDownload = true)
        val coordinator = SasayakiTranscriptionCoordinator(backend, repository, BookWorkRegistry(), backgroundScope, StandardTestDispatcher(testScheduler))
        val model = SasayakiTranscriptionViewModel(coordinator, repository, backgroundScope)
        val store = ViewModelStore().apply { put("transcription", model) }
        model.bind(temporary.newFolder(), "audio") {}
        runCurrent()
        model.start(); runCurrent()
        assertEquals(SasayakiTranscriptionStage.AwaitingDownload, coordinator.state.value.stage)
        store.clear(); runCurrent()
        assertFalse(coordinator.state.value.running)
        assertNull(repository.saved)
        assertEquals(0, backend.downloads)
    }

    private class Backend(private val needsDownload: Boolean = false, private val prepare: CompletableDeferred<Unit>? = null) : SasayakiTranscriptionBackend {
        var downloads = 0
        override suspend fun duration(source: String): Double {
            prepare?.await()
            return 100.0
        }
        override suspend fun transcribe(source: String, from: Double, onDownloadRequired: suspend (Long) -> Unit, onDownload: suspend (Double) -> Unit, onBatch: suspend (SasayakiTranscriptionBatch) -> Unit, parallelism: Int) {
            if (needsDownload) {
                onDownloadRequired(161_016_054)
                downloads++
            }
            onDownload(0.5)
            onBatch(SasayakiTranscriptionBatch(listOf(SasayakiToken("本文", from + 2, from + 3)), from + 10))
            awaitCancellation()
        }
    }

    private class MemoryRepository(var saved: SasayakiTranscript? = null) : SasayakiTranscriptionRepository {
        var failLoad = false
        var finishAlignment: CompletableDeferred<Unit>? = null
        val match = SasayakiMatchData(listOf(SasayakiMatch("1", 2.0, 3.0, "本文", 0, 0, 2)), 0)
        override suspend fun load(root: File): SasayakiTranscript? {
            if (failLoad) error("Private file path must never reach the UI")
            return saved
        }
        override suspend fun save(root: File, transcript: SasayakiTranscript) { saved = transcript }
        override suspend fun clear(root: File) { saved = null }
        override suspend fun openAlignment(root: File) = SasayakiTranscriptionAlignment { tokens, complete ->
            finishAlignment?.await()
            match
        }
    }
}
