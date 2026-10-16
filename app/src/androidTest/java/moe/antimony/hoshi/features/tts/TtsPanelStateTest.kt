package moe.antimony.hoshi.features.tts

import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.ReaderHardwareKeyAction
import moe.antimony.hoshi.features.reader.ReaderNavigationDirection
import moe.antimony.hoshi.features.reader.readerHardwareKeyEventForKeyEvent
import moe.antimony.hoshi.features.sasayaki.SasayakiSettingsRepository
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.runner.RunWith

/** Actual ViewModel/tab flow, using memory stores and a transport that cannot contact services. */
@RunWith(AndroidJUnit4::class)
class TtsPanelStateTest {
    @get:Rule val compose = createComposeRule()
    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        var failWrites = false
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (failWrites) throw IOException("Fixture write failure")
            return transform(data.value).also { data.value = it }
        }
    }
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val reading = MemoryStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val models = ViewModelStore()
    private var playCount = 0
    private val panelVisible = androidx.compose.runtime.mutableStateOf(true)
    private var onlineRequests = 0
    @Volatile private var onlineVoice = "ja-JP-NanamiNeural"
    @Volatile private var onlineFailure = false
    @Volatile private var refreshGate: CountDownLatch? = null
    private val discoveryStarted = AtomicBoolean(false)
    private lateinit var model: TtsViewModel
    private lateinit var speech: MemoryStore
    private val configMessage get() = context.getString(R.string.tts_configuration_error)

    private fun panel(system: Boolean = false, reader: Boolean = false, online: Boolean = false, discovery: CountDownLatch? = null) {
        val source = if (discovery != null) "custom:manual" else if (system) androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("ttsEngine").orEmpty() else if (online) TtsOnlineSource.Edge.id else TtsOnlineSource.GoogleCloud.id
        val profiles = if (discovery == null) "" else """, "voice":"sample", "customSources":[{"id":"custom:manual","name":"Local","baseUrl":"http://127.0.0.1:8088/v1","model":"irodori-tts","voice":"sample","voiceIds":["neutral","bright"]}]"""
        speech = MemoryStore(preferencesOf(stringPreferencesKey("preferences") to """{"settings":{"engine":"$source"$profiles}}"""))
        val repository = TtsRepository(speech)
        val cipher = TtsCredentialCipher(Dispatchers.IO)
        val transport = OnlineTtsTransport(OkHttpClient.Builder().addInterceptor {
            if (discovery != null) {
                discoveryStarted.set(true)
                discovery.await(10, TimeUnit.SECONDS)
                return@addInterceptor okhttp3.Response.Builder().request(it.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body("[]".toResponseBody()).build()
            }
            if (!online) throw IOException("UI fixture prohibits network")
            onlineRequests++
            refreshGate?.await(10, TimeUnit.SECONDS)
            if (onlineFailure) throw IOException("Fixture catalog unavailable")
            okhttp3.Response.Builder().request(it.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("Fixture")
                .body("""[{"Locale":"ja-JP","ShortName":"$onlineVoice"}]""".toResponseBody()).build()
        }.build())
        val engine = TtsEngineRouter(AndroidTtsEngine(context), OnlineTtsEngine(transport, cipher, Dispatchers.IO), CustomTtsEngine(transport, cipher::decrypt, Dispatchers.IO))
        val audio = TtsAudioRepository(engine, File(context.cacheDir, "tts-panel-state-fixture"), Dispatchers.IO, AndroidTtsExportFallback(context))
        val books = TtsBookRepository(Dispatchers.IO)
        val output = object : TtsPlaybackOutput {
            override var onEnded: () -> Unit = {}; override var onPaused: () -> Unit = {}; override var onError: () -> Unit = {}
            override suspend fun connect() = Unit
            override fun prepare(files: List<File>, title: String, sentence: String) = Unit
            override fun setPlaybackRate(rate: Float) = Unit
            override fun play() { playCount++ }; override fun pause() = Unit; override fun clear() = Unit; override fun disconnect() = Unit
        }
        compose.runOnUiThread {
            model = TtsViewModel(repository, SasayakiSettingsRepository(reading),
                engine, cipher, audio, books,
                TtsPlaybackController(books, repository, audio, output, scope), context)
            models.put("fixture", model)
        }
        compose.setContent { HoshiReaderTheme(darkTheme = false, dynamicColor = false) { if (panelVisible.value) TtsPanel(model, null, if (reader) ({ model.playback.play() }) else null, Modifier) } }
        if (discovery != null) {
            compose.waitUntil(5_000) { discoveryStarted.get() }
            return
        }
        compose.waitUntil(15_000) { !model.busy.value && model.readingSettings.value != null &&
            if (system || online) model.catalog.value?.voices?.isNotEmpty() == true else model.error.value == TtsFailure.Configuration }
    }

    @After fun dispose() { compose.runOnUiThread { models.clear(); scope.cancel() } }

    @Test fun optionalOpenAiDiscoveryDoesNotDisableManualPreview() {
        val discovery = CountDownLatch(1)
        try {
            panel(discovery = discovery)
            compose.runOnIdle { Assert.assertFalse(model.busy.value) }
            compose.onNodeWithText(context.getString(R.string.tts_preview_voice)).assertIsEnabled()
            compose.onNodeWithText("sample").performScrollTo().performClick()
            compose.onNodeWithText("Refresh voices").assertIsNotEnabled()
            compose.onNodeWithText("neutral").assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { model.preferences.value.settings.voice == "neutral" }
        } finally { discovery.countDown() }
    }

    @Test fun rubyWriteFailureIsShownInSettingsAndDoesNotOverwriteSourceFailure() {
        panel()
        compose.onNodeWithText("Settings").performClick()
        compose.runOnIdle { speech.failWrites = true }
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.waitUntil(5_000) { model.readingError.value == TtsFailure.Storage }
        compose.onNodeWithText(context.getString(TtsFailure.Storage.messageRes())).assertExists()
        compose.onAllNodes(isToggleable()).onFirst().assertIsOn()
        compose.runOnIdle { Assert.assertEquals(TtsFailure.Configuration, model.error.value) }
        compose.runOnIdle { speech.failWrites = false }
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.waitUntil(5_000) { !model.preferences.value.settings.useRubyReadings && model.readingError.value == null }
        compose.onAllNodes(isToggleable()).onFirst().assertIsOff()
    }

    @Test fun rubySwitchDefaultsOnAndRetainsItsOwnSettingAcrossTabsAndPanelReopen() {
        panel(online = true)
        val shared = model.readingSettings.value
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Use Ruby readings").assertIsDisplayed()
        compose.onAllNodes(isToggleable()).onFirst().assertIsOn().performClick()
        compose.waitUntil(5_000) { !model.preferences.value.settings.useRubyReadings }
        compose.onAllNodes(isToggleable()).onFirst().assertIsOff()
        compose.onNodeWithText("Replacements").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onAllNodes(isToggleable()).onFirst().assertIsOff()
        compose.runOnIdle { panelVisible.value = false }
        compose.runOnIdle { panelVisible.value = true }
        compose.onNodeWithText("Settings").performClick()
        compose.onAllNodes(isToggleable()).onFirst().assertIsOff()
        compose.runOnIdle { Assert.assertEquals(shared, model.readingSettings.value) }
    }

    @Test fun reopeningOnlinePanelKeepsCatalogWithoutLoadingOrNetworkRequests() {
        panel(online = true)
        val loading = mutableListOf<Boolean>()
        val catalogs = mutableListOf<TtsCatalog?>()
        compose.runOnIdle {
            scope.launch { model.busy.collect { loading += it } }
            scope.launch { model.catalog.collect { catalogs += it } }
            panelVisible.value = false
        }
        compose.runOnIdle { panelVisible.value = true }
        compose.waitForIdle()
        compose.runOnIdle {
            Assert.assertEquals(1, onlineRequests)
            Assert.assertFalse(loading.any { it })
            Assert.assertTrue(catalogs.all { it?.voices?.isNotEmpty() == true })
        }
    }

    @Test fun savingNewSourceSelectsItAndLoadsItsVoicesWithoutStartingPlayback() {
        panel(online = true)
        compose.onNodeWithText("Edge TTS").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Add service"))
        compose.onNodeWithText("Add service").performClick()
        compose.onNodeWithText("Service name").performTextReplacement("New local server")
        compose.onNodeWithText("API base URL").performTextReplacement("http://127.0.0.1:8088/v1")
        compose.onNodeWithText("Model ID").performScrollTo().performTextReplacement("tts")
        compose.onNodeWithText("Available voice IDs").performScrollTo().performTextReplacement("neutral\nbright")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { model.preferences.value.settings.engine.startsWith("custom:") && model.catalog.value?.voices?.any { it.id == "neutral" } == true }
        compose.onNodeWithText("New local server").assertIsDisplayed()
        compose.runOnIdle { Assert.assertEquals(0, playCount); Assert.assertEquals(TtsPreviewState.Idle, model.previewState.value) }
    }

    @Test fun manualVoiceRefreshUpdatesCachedVoicesAndRetainsThemAfterFailure() {
        panel(online = true)
        compose.onNodeWithText("Automatic").performClick()
        val refresh = compose.onNodeWithText("Refresh voices")
        refresh.assertIsDisplayed()
        val gate = CountDownLatch(1)
        try {
            onlineVoice = "ja-JP-KeitaNeural"
            refreshGate = gate
            refresh.performClick()
            compose.waitUntil(5_000) { model.busy.value }
            refresh.assertIsNotEnabled()
            compose.onNodeWithText("ja-JP-NanamiNeural").assertIsDisplayed()
            gate.countDown()
            compose.waitUntil(5_000) { model.catalog.value?.voices?.singleOrNull()?.id == "ja-JP-KeitaNeural" && !model.busy.value }
            refresh.assertIsEnabled()
            compose.onNodeWithText("ja-JP-KeitaNeural").assertIsDisplayed()
            compose.runOnIdle { Assert.assertEquals(2, onlineRequests); Assert.assertEquals("", model.preferences.value.settings.voice) }
            onlineFailure = true
            refresh.performClick()
            compose.waitUntil(5_000) { model.error.value != null && !model.busy.value }
            compose.onNodeWithText("ja-JP-KeitaNeural").assertIsDisplayed()
            refresh.assertIsEnabled()
            onlineFailure = false
            refresh.performClick()
            compose.waitUntil(5_000) { model.error.value == null && !model.busy.value }
            compose.onNodeWithText("ja-JP-KeitaNeural").performClick()
            compose.runOnIdle { model.refresh() }
            compose.waitUntil(5_000) { model.preferences.value.settings.voice == "ja-JP-KeitaNeural" }
            compose.runOnIdle { Assert.assertEquals(4, onlineRequests) }
        } finally { gate.countDown(); refreshGate = null }
    }

    @Test fun sourceFailureStaysNearVoiceCardAndDoesNotLeakIntoOtherTabs() {
        panel()
        val error = compose.onNodeWithText(configMessage).fetchSemanticsNode().boundsInRoot
        val rate = compose.onNodeWithText("Engine speech rate").fetchSemanticsNode().boundsInRoot
        Assert.assertTrue(error.bottom < rate.top)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText(configMessage).assertDoesNotExist()
        compose.onNodeWithText("Replacements").performClick()
        compose.onNodeWithText(configMessage).assertDoesNotExist()
        compose.onNode(hasText("Voice") and hasClickAction() and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
        compose.onNodeWithText(configMessage).assertExists()
    }

    @Test fun installedEnginePreviewReportsPlaybackStopAndNaturalCompletion() {
        panel(system = true)
        compose.runOnIdle { model.preview(TtsSentence(0, 0, 0, "こんにちは。日本語の読み上げです。こんにちは。日本語の読み上げです。"), null) }
        compose.waitUntil(15_000) { model.previewState.value == TtsPreviewState.Playing || model.error.value != null }
        compose.runOnIdle { Assert.assertNull(model.error.value); Assert.assertEquals(TtsPreviewState.Playing, model.previewState.value) }
        compose.runOnIdle { model.settings { it.copy(playbackRate = 1.5f) } }
        compose.waitUntil(5_000) { model.preferences.value.settings.playbackRate == 1.5f }
        compose.runOnIdle { Assert.assertEquals(TtsPreviewState.Playing, model.previewState.value) }
        compose.onNodeWithText("Stop preview").performClick()
        compose.runOnIdle { Assert.assertEquals(TtsPreviewState.Idle, model.previewState.value) }
        compose.runOnIdle { model.preview(TtsSentence(0, 0, 0, "はい。"), null) }
        compose.waitUntil(15_000) { model.previewState.value == TtsPreviewState.Idle }
        compose.runOnIdle { Assert.assertNull(model.error.value) }
    }

    @Test fun readerSentenceControlsAndVolumeKeysPreservePausedInstalledEnginePlayback() {
        panel(system = true, reader = true)
        val controller = model.playback
        val settings = ReaderSettings(volumeKeysSeekSasayaki = true)
        fun key() = readerHardwareKeyEventForKeyEvent(KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.ACTION_DOWN, 0, settings, false, false, hasStartedTts = controller.state.value.started)
        val book = EpubBook("Device fixture", chapters = listOf(
            EpubChapter("one", "one.xhtml", "application/xhtml+xml", "<p>今日は晴れです。次の文章です。最後の文章です。</p>")))
        compose.runOnIdle { controller.load("tts-key-fixture", book, false); controller.prepareAt(0, 0) }
        compose.waitUntil(5_000) { controller.state.value.sentences.size == 3 }
        compose.runOnIdle { Assert.assertFalse(key().consumed); controller.play() }
        compose.waitUntil(15_000) { !controller.state.value.preparing }
        compose.runOnIdle {
            Assert.assertNull(controller.state.value.error)
            Assert.assertTrue(controller.state.value.playing)
            Assert.assertEquals(1, playCount)
            controller.pause()
            Assert.assertEquals(ReaderHardwareKeyAction.TtsSentenceNavigation(ReaderNavigationDirection.Forward), key().action)
        }
        // The four unlabeled icon buttons above the tab row are replay/previous/play/next.
        fun controls() = compose.onAllNodes(hasClickAction() and SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.Role,
            androidx.compose.ui.semantics.Role.Button)).fetchSemanticsNodes().filter {
            it.boundsInRoot.bottom < compose.onNodeWithText("Replacements").fetchSemanticsNode().boundsInRoot.top
        }.sortedBy { it.boundsInRoot.left }
        Assert.assertEquals(4, controls().size)
        val nextId = controls().last().id
        compose.onNode(SemanticsMatcher("next sentence control") { it.id == nextId }).performClick()
        compose.runOnIdle { Assert.assertEquals(1, controller.state.value.index); Assert.assertFalse(controller.state.value.playing); Assert.assertEquals(1, playCount) }
        val previousId = controls()[1].id
        compose.onNode(SemanticsMatcher("previous sentence control") { it.id == previousId }).performClick()
        compose.runOnIdle { Assert.assertEquals(0, controller.state.value.index); Assert.assertFalse(controller.state.value.playing); Assert.assertEquals(1, playCount) }
        compose.runOnIdle { controller.stop(); controller.load("tts-key-fixture", book, false); Assert.assertFalse(key().consumed) }
    }

    @Test fun consecutiveReadingEditsPreserveBothFields() {
        panel()
        compose.runOnIdle {
            model.readingSettings { it.copy(autoScroll = false) }
            model.readingSettings { it.copy(autoPause = false) }
        }
        compose.waitUntil(5_000) { model.readingSettings.value?.let { !it.autoScroll && !it.autoPause } == true }
    }

    @Test fun readingWriteFailureAndPreviewFailureRemainIndependent() {
        panel()
        compose.runOnIdle { reading.failWrites = true; model.readingSettings { it.copy(autoScroll = false) } }
        compose.waitUntil(5_000) { model.readingError.value == TtsFailure.Storage }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText(context.getString(R.string.tts_storage_error)).assertExists()
        compose.onNodeWithText(configMessage).assertDoesNotExist()
        compose.runOnIdle { model.preview(TtsSentence(0, 0, 0, "日本語です。"), null) }
        compose.waitUntil(5_000) { model.previewState.value == TtsPreviewState.Idle && model.error.value == TtsFailure.Configuration }
        compose.onNode(hasText("Voice") and hasClickAction() and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
        compose.onNodeWithText("Preview voice").assertIsEnabled()
        compose.onNodeWithText(configMessage).assertExists()
        compose.onNodeWithText(context.getString(R.string.tts_storage_error)).assertDoesNotExist()
    }
}
