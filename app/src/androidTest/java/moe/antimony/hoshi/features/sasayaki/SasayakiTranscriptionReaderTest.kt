package moe.antimony.hoshi.features.sasayaki

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookWorkRegistry
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.features.sasayaki.transcription.SasayakiTranscriptionBackend
import moe.antimony.hoshi.features.sasayaki.transcription.SasayakiTranscriptionBatch
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SasayakiTranscriptionReaderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sourceBecomesReadyAndTaskContinuesAcrossClosingAndReopeningControls() = withReader { reader ->
        compose.onNodeWithText(reader.text(R.string.sasayaki_transcription_start)).assertIsNotEnabled()
        compose.onNodeWithText(reader.text(R.string.action_open)).assertDoesNotExist()
        compose.runOnIdle { reader.playback.value = SasayakiPlaybackData(0.0, audioUri = "content://test/audiobook") }
        compose.waitUntil { reader.model.uiState.value.canStart }
        compose.onNodeWithText(reader.text(R.string.sasayaki_transcription_start)).assertIsEnabled().performClick()
        compose.waitUntil { reader.backend.runs == 1 }
        compose.runOnIdle { reader.visible.value = false }
        reader.batch(10.0)
        compose.waitUntil { reader.model.uiState.value.through == 10.0 && reader.matches.size == 1 }
        assertTrue("Matches arrive before pausing, with the controls sheet closed", reader.model.uiState.value.canPause)
        compose.runOnIdle { reader.visible.value = true }
        compose.onAllNodes(hasText("0:00:10 / 0:01:40", substring = true)).assertCountEquals(1)
        compose.onNodeWithText(reader.text(R.string.sasayaki_transcription_pause)).assertIsEnabled().performClick()
        compose.waitUntil { reader.model.uiState.value.canStart }
        compose.onNodeWithText(reader.text(R.string.sasayaki_transcription_resume)).assertIsEnabled().performClick()
        compose.waitUntil { reader.backend.runs == 2 }
        assertEquals(10.0, reader.backend.resumedFrom, 0.0)
        compose.runOnIdle { reader.visible.value = false }
        reader.batch(100.0)
        reader.backend.batches.close()
        compose.waitUntil { reader.matches.size == 2 }
        compose.runOnIdle { reader.visible.value = true }
        compose.onNodeWithText(reader.text(R.string.sasayaki_transcription_realign)).assertIsEnabled()
        assertFalse(reader.model.uiState.value.controlsLocked)
    }

    @Test fun backgroundingDoesNotPauseAndReturningShowsCurrentProgress() = withReader { reader ->
        compose.runOnIdle { reader.playback.value = SasayakiPlaybackData(0.0, audioUri = "content://test/audiobook") }
        compose.waitUntil { reader.model.uiState.value.canStart }
        compose.onNodeWithText(reader.text(R.string.sasayaki_transcription_start)).performClick()
        reader.batch(10.0)
        compose.waitUntil { reader.model.uiState.value.through == 10.0 }
        compose.runOnIdle { reader.lifecycle.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { assertTrue(reader.model.uiState.value.running) }
        reader.batch(30.0)
        compose.waitUntil { reader.model.uiState.value.through == 30.0 }
        compose.runOnIdle { reader.lifecycle.registry.currentState = Lifecycle.State.RESUMED }
        compose.onAllNodes(hasText("0:00:30 / 0:01:40", substring = true)).assertCountEquals(1)
        compose.onNodeWithText(reader.text(R.string.sasayaki_transcription_pause)).assertIsEnabled().performClick()
        compose.waitUntil { reader.model.uiState.value.canStart }
        assertEquals(30.0, reader.repository.saved!!.through, 0.0)
        compose.onNodeWithText(reader.text(R.string.sasayaki_transcription_resume)).assertIsEnabled()
        compose.runOnIdle { reader.playback.value = null }
        compose.waitUntil { !reader.model.uiState.value.hasSource }
        compose.onNodeWithText(reader.text(R.string.sasayaki_transcription_start)).assertIsNotEnabled()
    }

    @Test fun modelDownloadDialogAppearsOnDemandAndCancelOrBackgroundingDoesNotDownload() = withReader { reader ->
        compose.runOnIdle {
            reader.backend.needsDownload = true
            reader.playback.value = SasayakiPlaybackData(0.0, audioUri = "content://test/audiobook")
        }
        compose.waitUntil { reader.model.uiState.value.canStart }
        compose.onNodeWithText("MiB", substring = true).assertDoesNotExist()
        compose.onNodeWithText(reader.text(R.string.sasayaki_transcription_start)).performClick()
        compose.onNodeWithText(reader.text(R.string.sasayaki_transcription_download_title)).assertExists()
        compose.onNodeWithText("154 MiB", substring = true).assertExists()
        assertEquals(0, reader.backend.downloads)
        compose.onNode(hasText(reader.text(R.string.action_cancel)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil { reader.model.uiState.value.canStart }
        assertNull(reader.repository.saved)
        compose.onNodeWithText(reader.text(R.string.sasayaki_transcription_start)).performClick()
        compose.onNodeWithText(reader.text(R.string.sasayaki_transcription_download_title)).assertExists()
        compose.runOnIdle { reader.lifecycle.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle {
            assertEquals(SasayakiTranscriptionStage.AwaitingDownload, reader.coordinator.state.value.stage)
            assertEquals(0, reader.backend.downloads)
        }
        compose.runOnIdle { reader.lifecycle.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithText(reader.text(R.string.sasayaki_transcription_download_title)).assertExists()
        compose.onNode(hasText(reader.text(R.string.action_cancel)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil { reader.model.uiState.value.canStart }
        assertEquals(0, reader.backend.downloads)
        assertNull(reader.repository.saved)
        compose.onNodeWithText(reader.text(R.string.sasayaki_transcription_start)).performClick()
        compose.onNodeWithText(reader.text(R.string.sasayaki_transcription_download_action)).performClick()
        compose.waitUntil { reader.backend.downloads == 1 }
        compose.onNodeWithText("MiB", substring = true).assertDoesNotExist()
        reader.batch(10.0)
        compose.waitUntil { reader.model.uiState.value.through == 10.0 }
        compose.onNodeWithText(reader.text(R.string.sasayaki_transcription_pause)).performClick()
        compose.waitUntil { reader.model.uiState.value.canStart }
        compose.onAllNodes(hasText("0:00:10 / 0:01:40", substring = true)).assertCountEquals(1)
        compose.onNodeWithText(reader.text(R.string.sasayaki_transcription_resume)).performClick()
        compose.waitUntil { reader.backend.runs == 4 }
        compose.onNodeWithText(reader.text(R.string.sasayaki_transcription_download_title)).assertDoesNotExist()
        assertEquals(1, reader.backend.downloads)
    }

    private fun withReader(test: (Reader) -> Unit) {
        val reader = Reader()
        try {
            compose.runOnIdle { reader.lifecycle.registry.currentState = Lifecycle.State.RESUMED }
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides reader.lifecycle) {
                    val state = rememberSasayakiTranscriptionState(
                        reader.root, reader.audioRepository, reader.playback.value, reader.model, reader.matches::add,
                    )
                    HoshiReaderTheme {
                        if (reader.visible.value) {
                            SasayakiTranscriptionSection(
                                state = state, enabled = true,
                                onStart = reader.model::start, onPause = reader.model::pause,
                                onConfirmDownload = reader.model::confirmDownload,
                                onRequestClear = reader.model::requestClear, onDismissClear = reader.model::dismissClear,
                                onConfirmClear = reader.model::confirmClear,
                            )
                        }
                    }
                }
            }
            test(reader)
        } finally {
            compose.runOnIdle { reader.model.pause() }
            compose.waitUntil { !reader.model.uiState.value.running }
            reader.scope.cancel()
            reader.root.deleteRecursively()
        }
    }

    private class Reader {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "transcription-reader-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = MemoryRepository()
        val backend = Backend()
        val coordinator = SasayakiTranscriptionCoordinator(backend, repository, BookWorkRegistry(), scope, Dispatchers.Main.immediate)
        val model = SasayakiTranscriptionViewModel(coordinator, repository, scope)
        val audioRepository = SasayakiAudioRepository(root)
        val playback = mutableStateOf<SasayakiPlaybackData?>(null)
        val visible = mutableStateOf(true)
        val matches = mutableListOf<SasayakiMatchData>()
        val lifecycle = ReaderLifecycle()
        fun text(id: Int) = context.getString(id)
        fun batch(through: Double) {
            backend.batches.trySend(SasayakiTranscriptionBatch(listOf(SasayakiToken("本文", through - 2, through - 1)), through)).getOrThrow()
        }
    }

    private class ReaderLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle get() = registry
    }

    private class Backend : SasayakiTranscriptionBackend {
        val batches = Channel<SasayakiTranscriptionBatch>(Channel.UNLIMITED)
        @Volatile var runs = 0
        @Volatile var resumedFrom = 0.0
        @Volatile var needsDownload = false
        @Volatile var downloads = 0
        override suspend fun duration(source: String) = 100.0
        override suspend fun transcribe(source: String, from: Double, onDownloadRequired: suspend (Long) -> Unit, onDownload: suspend (Double) -> Unit, onBatch: suspend (SasayakiTranscriptionBatch) -> Unit) {
            resumedFrom = from
            runs++
            if (needsDownload) {
                onDownloadRequired(161_016_054)
                downloads++
                onDownload(0.5)
                needsDownload = false
                onDownload(1.0)
            }
            for (batch in batches) onBatch(batch)
        }
    }

    private class MemoryRepository : SasayakiTranscriptionRepository {
        var saved: SasayakiTranscript? = null
        override suspend fun load(root: File) = saved
        override suspend fun save(root: File, transcript: SasayakiTranscript) { saved = transcript }
        override suspend fun clear(root: File) { saved = null }
        override suspend fun openAlignment(root: File) = SasayakiTranscriptionAlignment { _, _ -> SasayakiMatchData(emptyList(), 0) }
    }
}
