package moe.antimony.hoshi.features.reader

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.*
import moe.antimony.hoshi.features.sasayaki.*
import moe.antimony.hoshi.features.tts.*
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.*
import org.junit.*
import org.junit.runner.RunWith

/** Real controls and Media3 playback, with generated audio and scratch/memory stores only. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderSentenceRestartTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.cacheDir, "sentence-restart-${System.nanoTime()}").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var release: () -> Unit = {}
    private val settings = mutableStateOf(SasayakiSettings())
    private val appearance = mutableStateOf(ReaderSettings(bottomSafeAreaDp = 40, showPercentage = false))
    private val chrome = ReaderChromeState("Fixture", 123, 1000)
    private val replayLabel get() = context.getString(R.string.reader_restart_sentence)

    @After fun dispose() {
        compose.runOnUiThread { release(); player?.release() }
        runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        root.deleteRecursively()
    }

    @Test fun sharedSwitchControlsLeftmostButtonAndProgressSpacing() {
        val audiobookPanel = mutableStateOf(true)
        var lastAction: ReaderSasayakiBottomSkipButtonAction? = null
        var restartCount = 0
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        SharedReaderPlaybackSettings(
                            settings.value,
                            { update -> settings.value = if (audiobookPanel.value) update(settings.value)
                                else settings.value.withTtsReadingSettings(update) },
                            {}, includeAudiobookControls = audiobookPanel.value,
                        )
                    }
                    val actions = readerSasayakiBottomSkipButtonActions(
                        appearance.value.verticalWriting, settings.value.reverseVerticalReaderSkipButtons,
                    )
                    PlaybackBar(false, { restartCount++ }, {}, { lastAction = actions.left }, { lastAction = actions.right })
                }
            }
        }
        compose.onNodeWithContentDescription(replayLabel).assertDoesNotExist()
        val reverseText = compose.onNodeWithText(context.getString(R.string.sasayaki_reverse_vertical_skip_buttons))
        val replayText = compose.onNodeWithText(context.getString(R.string.reader_show_restart_sentence_button))
        assertTrue(reverseText.fetchSemanticsNode().boundsInRoot.bottom <= replayText.fetchSemanticsNode().boundsInRoot.top)
        // The shared panel places the playback, reverse and replay switches first.
        compose.onAllNodes(isToggleable())[2].assertIsOff().performTouchInput { click() }.assertIsOn()
        compose.onNodeWithContentDescription(replayLabel).assertIsDisplayed().performTouchInput { click() }
        assertEquals(1, restartCount)
        assertControlOrderAndCapture("sasayaki-controls")
        compose.runOnIdle { audiobookPanel.value = false }
        compose.onAllNodes(isToggleable())[2].assertIsOn()
        compose.onAllNodes(isToggleable())[1].performTouchInput { click() }
        compose.onNodeWithContentDescription(context.getString(R.string.sasayaki_rewind)).performTouchInput { click() }
        assertEquals(ReaderSasayakiBottomSkipButtonAction.Forward, lastAction)
        compose.onNodeWithContentDescription(replayLabel).performTouchInput { click() }
        assertEquals(2, restartCount)
        assertControlOrderAndCapture("tts-vertical-controls")
        compose.runOnIdle { appearance.value = appearance.value.copy(verticalWriting = false, bottomSafeAreaDp = 18) }
        compose.onNodeWithContentDescription(context.getString(R.string.sasayaki_rewind)).performTouchInput { click() }
        assertEquals(ReaderSasayakiBottomSkipButtonAction.Backward, lastAction)
        assertControlOrderAndCapture("tts-horizontal-controls")
        compose.onAllNodes(isToggleable())[2].performTouchInput { click() }
        compose.onNodeWithContentDescription(replayLabel).assertDoesNotExist()
        compose.runOnIdle { audiobookPanel.value = true }
        compose.onAllNodes(isToggleable())[2].assertIsOff()
    }

    @Test fun sasayakiButtonRestartsPausedCueAndContinuesIntoNextCue() {
        val wave = File(root, "Sasayaki/fixture.wav").apply { parentFile!!.mkdirs() }
        writeWave(wave, 12)
        lateinit var controller: SasayakiPlaybackController
        compose.runOnUiThread {
            val decoder = ExoPlayer.Builder(context).build().also { player = it }
            controller = SasayakiPlaybackController(
                context, root, object : SasayakiPlaybackRepository {
                    override suspend fun load(): SasayakiPlaybackData? = null
                    override suspend fun save(playback: SasayakiPlaybackData) = Unit
                }, "Fixture", null,
                SasayakiMatchData(listOf(
                    SasayakiMatch("first", 1.0, 3.0, "first", 0, 0, 5),
                    SasayakiMatch("second", 5.0, 7.0, "second", 0, 5, 6),
                ), 0),
                SasayakiPlaybackData(2.5, audioFileName = wave.name), scope, { 0 }, { _, _, _ -> }, {},
                ServiceOwnedSasayakiPlaybackPreparer { ExoPlayerSasayakiPlayerHandle(decoder) }, Dispatchers.IO,
            )
            controller.readerSkipButtonAction = SasayakiReaderSkipButtonAction.Seconds30
            settings.value = settings.value.copy(showReaderRestartSentenceButton = true)
            release = controller::release
        }
        compose.setContent { HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
            PlaybackBar(controller.isPlaying, controller::restartCurrentCue, controller::togglePlayback)
        } }
        compose.onNodeWithContentDescription(replayLabel).performTouchInput { click() }
        awaitPlayback { player!!.isPlaying && player!!.currentPosition in 1000..2000 }
        compose.onNodeWithContentDescription(context.getString(R.string.sasayaki_pause)).performTouchInput { click() }
        compose.runOnIdle { assertFalse(controller.isPlaying) }
        compose.onNodeWithContentDescription(replayLabel).performTouchInput { click() }
        awaitPlayback { player!!.isPlaying && player!!.currentPosition in 1000..2000 }
        compose.onNodeWithContentDescription(replayLabel).performTouchInput { click() }
        awaitPlayback { player!!.isPlaying && player!!.currentPosition in 1000..2000 }
        awaitPlayback { player!!.isPlaying && player!!.currentPosition >= 5200 }
        compose.runOnIdle { assertTrue(controller.isPlaying) }
        capture("sasayaki-playing")
    }

    @Test fun ttsButtonRestartsPausedSentenceAndContinuesIntoNextSentence() {
        val wave = File(root, "sentence.wav")
        writeWave(wave, 3)
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        val repository = TtsRepository(store)
        runBlocking { repository.settings { it.copy(enabled = true) } }
        lateinit var controller: TtsPlaybackController
        compose.runOnUiThread {
            val decoder = ExoPlayer.Builder(context).build().also { player = it }
            val androidOutput = AndroidTtsPlaybackOutput(context).also { it.attach(decoder) }
            val output = object : TtsPlaybackOutput by androidOutput {
                override suspend fun connect() = Unit
            }
            val audio = object : TtsAudioSource {
                override suspend fun audio(sentence: TtsSentence, bookId: String?, preferences: TtsPreferences) = listOf(wave)
                override suspend fun clearPlaybackCache() = Unit
            }
            controller = TtsPlaybackController(TtsBookRepository(Dispatchers.IO), repository, audio, output, scope)
            controller.load("fixture", EpubBook("Fixture", chapters = listOf(
                EpubChapter("one", "one.xhtml", "application/xhtml+xml", "<p>一。二。三。</p>"),
            )), false)
            controller.prepareAt(0, 0)
            settings.value = settings.value.copy(showReaderRestartSentenceButton = true)
            release = controller::stop
        }
        awaitPlayback { controller.state.value.sentence != null }
        compose.setContent { HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
            val state by controller.state.collectAsState()
            PlaybackBar(state.playing, controller::restartCurrentSentence, controller::toggle)
        } }
        compose.onNodeWithContentDescription(replayLabel).performTouchInput { click() }
        awaitPlayback { player!!.isPlaying && player!!.currentPosition >= 800 }
        compose.onNodeWithContentDescription(context.getString(R.string.sasayaki_pause)).performTouchInput { click() }
        compose.runOnIdle { assertFalse(controller.state.value.playing) }
        compose.onNodeWithContentDescription(replayLabel).performTouchInput { click() }
        awaitPlayback { player!!.isPlaying && player!!.currentPosition < 800 }
        compose.onNodeWithContentDescription(replayLabel).performTouchInput { click() }
        awaitPlayback { player!!.isPlaying && player!!.currentPosition < 800 }
        awaitPlayback { controller.state.value.index == 1 && player!!.isPlaying }
        capture("tts-playing")
    }

    @Composable
    private fun PlaybackBar(playing: Boolean, restart: () -> Unit, toggle: () -> Unit,
        previous: () -> Unit = {}, next: () -> Unit = {}) {
        ReaderBottomSafeProgress(
            chrome, appearance.value, ReaderProgressDisplay.characters(),
            readerChromeColors(appearance.value, false), readerBottomChromeMetrics(appearance.value.bottomSafeAreaDp),
            focusMode = false,
            sasayakiPlaybackControls = readerSasayakiBottomPlaybackControls(settings.value, true,
                readerBottomChromeMetrics(appearance.value.bottomSafeAreaDp)),
            sasayakiPlaying = playing, onTapSafeArea = {}, onSasayakiSkipBackward = previous,
            onSasayakiTogglePlayback = toggle, onSasayakiSkipForward = next, onRestartSentence = restart,
        )
    }

    private fun assertControlOrderAndCapture(name: String) {
        capture(name)
        val nodes = listOf(R.string.reader_restart_sentence, R.string.sasayaki_rewind,
            R.string.sasayaki_play, R.string.sasayaki_fast_forward).map {
            compose.onNodeWithContentDescription(context.getString(it)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        nodes.zipWithNext().forEach { (left, right) -> assertTrue(left.right <= right.left) }
        val progress = compose.onNodeWithText("123 / 1000").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        // Each button rounds to physical pixels independently; the combined padding rounds once.
        // Four buttons plus the leading padding can differ from that total by at most two pixels.
        assertTrue("Playback bounds: $nodes; progress: $progress", nodes.last().right <= progress.left + 2f)
    }

    private fun capture(name: String) {
        val directory = File(context.cacheDir, "sentence-restart-evidence").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun awaitPlayback(predicate: () -> Boolean) {
        compose.waitUntil(10_000) { compose.runOnIdle(predicate) }
    }

    private fun writeWave(file: File, seconds: Int) {
        val pcmBytes = 16000 * seconds * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray()).putInt(16)
        header.putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(pcmBytes)
        file.outputStream().use { it.write(header.array()); it.write(ByteArray(pcmBytes)) }
    }
}
