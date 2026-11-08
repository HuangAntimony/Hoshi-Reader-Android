package moe.antimony.hoshi.features.tts

import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.compose.ui.test.junit4.StateRestorationTester
import moe.antimony.hoshi.navigation.AppRoute
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
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
        private val stored = MutableStateFlow(initial)
        var readGate: CompletableDeferred<Unit>? = null
        override val data = flow { readGate?.await(); emitAll(stored) }
        var failWrites = false
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (failWrites) throw IOException("Fixture write failure")
            return transform(stored.value).also { stored.value = it }
        }
    }
    private val restoration = StateRestorationTester(compose)
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

    private fun panel(system: Boolean = false, reader: Boolean = false, online: Boolean = false, discovery: CountDownLatch? = null, storageGate: CompletableDeferred<Unit>? = null, editor: Boolean = false, home: Boolean = false, enabled: Boolean = true, device: TtsDeviceEngine? = null) {
        val source = if (discovery != null) "custom:manual" else if (device != null) "" else if (system) androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("ttsEngine").orEmpty() else if (online) TtsOnlineSource.Edge.id else TtsOnlineSource.GoogleCloud.id
        val profiles = if (discovery == null) "" else """, "routingBySource":{"custom:manual":{"narration":"sample"}}, "customSources":[{"id":"custom:manual","name":"Local","baseUrl":"http://127.0.0.1:8088/v1","model":"irodori-tts","voiceIds":["neutral","bright"]}]"""
        speech = MemoryStore(preferencesOf(stringPreferencesKey("preferences") to """{"settings":{"enabled":$enabled,"engine":"$source"$profiles}}"""))
        speech.readGate = storageGate
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
        val engine = TtsEngineRouter(device?.let(::AndroidTtsEngine) ?: AndroidTtsEngine(context), OnlineTtsEngine(transport, cipher, Dispatchers.IO), CustomTtsEngine(transport, cipher::decrypt, Dispatchers.IO))
        val audio = TtsAudioRepository(engine, File(context.cacheDir, "tts-panel-state-fixture"), Dispatchers.IO, AndroidTtsExportFallback(context))
        val books = TtsBookRepository(Dispatchers.IO)
        val output = object : TtsPlaybackOutput {
            override var onEnded: () -> Unit = {}; override var onPaused: () -> Unit = {}; override var onError: (Throwable) -> Unit = {}
            override var onTransition: (Long) -> Unit = {}
            override fun append(files: List<File>, title: String, sentence: String, token: Long) = Unit
            override fun discardUpcoming(): Long? = null
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
        restoration.setContent { HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
            if (panelVisible.value) {
                if (home) {
                    val stack = rememberNavBackStack(AppRoute.TtsSettingsRoute)
                    NavDisplay(backStack = stack, onBack = { if (stack.size > 1) stack.removeLastOrNull() },
                        entryProvider = { route -> NavEntry(route) {
                            when (route) {
                                AppRoute.TtsSettingsRoute -> TtsSettingsView({}, { section -> stack.add(when (section) {
                                    TtsSettingsSection.Voice -> AppRoute.TtsVoiceSettingsRoute
                                    TtsSettingsSection.Replacements -> AppRoute.TtsReplacementsSettingsRoute
                                    TtsSettingsSection.Reading -> AppRoute.TtsReadingSettingsRoute
                                }) }, viewModel = model)
                                else -> TtsSettingsSectionView(when (route) {
                                    AppRoute.TtsVoiceSettingsRoute -> TtsSettingsSection.Voice
                                    AppRoute.TtsReplacementsSettingsRoute -> TtsSettingsSection.Replacements
                                    else -> TtsSettingsSection.Reading
                                }, { if (stack.size > 1) stack.removeLastOrNull() }, model)
                            }
                        } })
                } else if (editor) TtsWordEditor(model, TtsWord("fixture", "猫", "ねこ"), null, null, { panelVisible.value = false })
                else TtsPanel(model, null, if (reader) ({ model.playback.play() }) else null, Modifier)
            }
        } }
        if (storageGate != null) return
        if (home) { compose.waitForIdle(); return }
        if (discovery != null) {
            compose.waitUntil(5_000) { discoveryStarted.get() }
            return
        }
        compose.waitUntil(15_000) { !model.busy.value && model.readingSettings.value != null &&
            if (system || online || device != null) model.catalog.value?.voices?.isNotEmpty() == true else model.error.value?.failure == TtsFailure.Configuration }
    }

    @After fun dispose() { compose.runOnUiThread { models.clear(); scope.cancel() } }

    @Test fun settingsHomeOnlyShowsSwitchUntilEnabledAndNeverDiscoversVoices() {
        panel(home = true, enabled = false, online = true)
        compose.onNodeWithText(context.getString(R.string.tts_enabled)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tts_tab_voice)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.tts_tab_dictionary)).assertDoesNotExist()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText(context.getString(R.string.tts_tab_voice)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tts_tab_dictionary)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_title)).assertIsDisplayed()
        Assert.assertEquals(0, onlineRequests)
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText(context.getString(R.string.tts_tab_dictionary)).assertDoesNotExist()
        Assert.assertEquals(0, onlineRequests)
    }

    @Test fun settingsEntriesNavigateAndRestoreWithoutTabsOrGlobalSwitchInChildren() {
        panel(home = true)
        compose.onNodeWithText(context.getString(R.string.tts_tab_dictionary)).performClick()
        compose.onNodeWithText(context.getString(R.string.tts_add_word)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tts_tab_voice)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.tts_enabled)).assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(context.getString(R.string.tts_add_word)).assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText(context.getString(R.string.tts_enabled)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_title)).performClick()
        compose.onNodeWithText(context.getString(R.string.tts_use_ruby)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tts_tab_dictionary)).assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText(context.getString(R.string.tts_tab_voice)).performClick()
        compose.onNodeWithText(context.getString(R.string.tts_tab_dictionary)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.tts_enabled)).assertDoesNotExist()
    }

    @Test fun delayedSettingsHomeDoesNotShowDefaultSwitch() {
        val gate = CompletableDeferred<Unit>()
        panel(home = true, storageGate = gate)
        compose.onNodeWithText(context.getString(R.string.tts_enabled)).assertDoesNotExist()
        compose.runOnIdle { gate.complete(Unit) }
        compose.onNodeWithText(context.getString(R.string.tts_enabled)).assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOn()
    }

    @Test fun settingsHomeShowsWriteFailureAndKeepsSavedSwitchUntilRetrySucceeds() {
        panel(home = true, enabled = false)
        compose.runOnIdle { speech.failWrites = true }
        compose.onNode(isToggleable()).performClick()
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText("Fixture write failure", substring = true).assertIsDisplayed()
        compose.runOnIdle { speech.failWrites = false }
        compose.onNode(isToggleable()).performClick()
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText("Fixture write failure", substring = true).assertDoesNotExist()
    }

    @Test fun delayedStoredPreferencesDoNotRenderDefaultSettingsControls() {
        val gate = CompletableDeferred<Unit>()
        panel(storageGate = gate)
        compose.runOnIdle { Assert.assertNull(model.preferences.value) }
        compose.onNodeWithText(context.getString(R.string.tts_tab_voice)).assertDoesNotExist()
        gate.complete(Unit)
        compose.waitUntil(5_000) { model.preferences.value?.settings?.engine == TtsOnlineSource.GoogleCloud.id }
        compose.onAllNodesWithText(context.getString(R.string.tts_tab_voice)).onFirst().assertExists()
    }

    @Test fun loadingReplacementEditorCanBeDismissedWithoutCreatingSettingsControls() {
        panel(storageGate = CompletableDeferred(), editor = true)
        compose.onNodeWithText(context.getString(R.string.tts_regex_enabled)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        compose.onNodeWithText(context.getString(R.string.tts_replacement_title)).assertDoesNotExist()
    }

    @Test fun azureConfigurationRejectsUnknownRegionsAndPersistsTheSelectedRegion() {
        panel()
        compose.runOnIdle { runBlocking {
            val repository = TtsRepository(speech)
            val original = repository.preferences.first()
            for (region in listOf("", "east", "southindia", "spaincentral", "eastus/evil")) {
                Assert.assertEquals(TtsFailure.Configuration, model.configure(TtsOnlineSource.Azure, region, "fixture-key")?.failure)
                Assert.assertEquals(TtsFailure.Configuration, model.testConfiguration(TtsOnlineSource.Azure, region, "fixture-key", "日本語です。")?.failure)
                Assert.assertEquals(original, repository.preferences.first())
            }
            Assert.assertNull(model.configure(TtsOnlineSource.Azure, "japaneast", "fixture-key"))
            val saved = TtsRepository(speech).preferences.first().settings
            Assert.assertEquals("japaneast", saved.online.getValue(TtsOnlineSource.Azure.id).region)
            Assert.assertTrue(saved.online.getValue(TtsOnlineSource.Azure.id).encryptedKey.isNotBlank())
            Assert.assertEquals(original.settings.engine, saved.engine)
        } }
    }

    @Test fun cloudKeyRemovalReportsWriteFailureAndBlankDraftsCannotRestoreIt() {
        panel()
        compose.runOnIdle { runBlocking {
            val repository = TtsRepository(speech)
            repository.configure(TtsOnlineSource.GoogleCloud, TtsOnlineConfig("saved-region", "saved-cipher"))
            repository.configure(TtsOnlineSource.Azure, TtsOnlineConfig("eastasia", "other-cipher"))
            val original = repository.preferences.first()
            speech.failWrites = true
            Assert.assertEquals(TtsFailure.Storage, model.removeKey(TtsOnlineSource.GoogleCloud)?.failure)
            Assert.assertEquals(original, repository.preferences.first())
            speech.failWrites = false
            Assert.assertNull(model.removeKey(TtsOnlineSource.GoogleCloud))
            val removed = repository.preferences.first()
            Assert.assertEquals(TtsOnlineConfig("saved-region", ""), removed.settings.online.getValue(TtsOnlineSource.GoogleCloud.id))
            Assert.assertEquals(TtsOnlineConfig("eastasia", "other-cipher"), removed.settings.online.getValue(TtsOnlineSource.Azure.id))
            Assert.assertEquals(TtsFailure.Configuration, model.configure(TtsOnlineSource.GoogleCloud, "", "")?.failure)
            Assert.assertEquals(TtsFailure.Configuration, model.testConfiguration(TtsOnlineSource.GoogleCloud, "", "", "日本語です。")?.failure)
            Assert.assertEquals(removed, repository.preferences.first())
        } }
    }

    @Test fun optionalOpenAiDiscoveryDoesNotDisableManualPreview() {
        val discovery = CountDownLatch(1)
        try {
            panel(discovery = discovery)
            compose.runOnIdle { Assert.assertFalse(model.busy.value) }
            compose.onNodeWithText(context.getString(R.string.tts_preview_voice)).assertIsEnabled()
            compose.onNodeWithText("sample").performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.tts_refresh_voices)).assertIsNotEnabled()
            compose.onNodeWithText("neutral").assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { model.preferences.value?.settings?.voice == "neutral" }
        } finally { discovery.countDown() }
    }

    @Test fun rubyWriteFailureIsShownInSettingsAndDoesNotOverwriteSourceFailure() {
        panel()
        compose.onNodeWithText(context.getString(R.string.settings_title)).performClick()
        compose.runOnIdle { speech.failWrites = true }
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.waitUntil(5_000) { model.readingError.value?.failure == TtsFailure.Storage }
        compose.onNodeWithText(context.getString(TtsFailure.Storage.messageRes()), substring = true).assertExists()
        compose.onAllNodes(isToggleable()).onFirst().assertIsOn()
        compose.runOnIdle { Assert.assertEquals(TtsFailure.Configuration, model.error.value?.failure) }
        compose.runOnIdle { speech.failWrites = false }
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.waitUntil(5_000) { model.preferences.value?.settings?.useRubyReadings == false && model.readingError.value == null }
        compose.onAllNodes(isToggleable()).onFirst().assertIsOff()
    }

    @Test fun rubySwitchDefaultsOnAndRetainsItsOwnSettingAcrossTabsAndPanelReopen() {
        panel(online = true)
        val shared = model.readingSettings.value
        compose.onNodeWithText(context.getString(R.string.settings_title)).performClick()
        compose.onNodeWithText(context.getString(R.string.tts_use_ruby)).assertIsDisplayed()
        compose.onAllNodes(isToggleable()).onFirst().assertIsOn().performClick()
        compose.waitUntil(5_000) { model.preferences.value?.settings?.useRubyReadings == false }
        compose.onAllNodes(isToggleable()).onFirst().assertIsOff()
        compose.onNodeWithText(context.getString(R.string.tts_tab_dictionary)).performClick()
        compose.onNodeWithText(context.getString(R.string.settings_title)).performClick()
        compose.onAllNodes(isToggleable()).onFirst().assertIsOff()
        compose.runOnIdle { panelVisible.value = false }
        compose.runOnIdle { panelVisible.value = true }
        compose.onNodeWithText(context.getString(R.string.settings_title)).performClick()
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
        compose.onNodeWithText(context.getString(R.string.tts_source_edge)).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(context.getString(R.string.tts_add_service)))
        compose.onNodeWithText(context.getString(R.string.tts_add_service)).performClick()
        compose.onNodeWithText(context.getString(R.string.tts_service_name)).performTextReplacement("New local server")
        compose.onNodeWithText(context.getString(R.string.tts_service_url)).performTextReplacement("http://127.0.0.1:8088/v1")
        compose.onNodeWithText(context.getString(R.string.tts_service_model)).performScrollTo().performTextReplacement("tts")
        compose.onNodeWithText(context.getString(R.string.tts_service_voice_ids)).performScrollTo().performTextReplacement("neutral\nbright")
        compose.onNodeWithText(context.getString(R.string.action_save)).performClick()
        compose.waitUntil(5_000) { model.preferences.value?.settings?.engine?.startsWith("custom:") == true && model.catalog.value?.voices?.any { it.id == "neutral" } == true }
        compose.onNodeWithText("New local server").assertIsDisplayed()
        compose.runOnIdle { Assert.assertEquals(0, playCount); Assert.assertEquals(TtsPreviewState.Idle, model.previewState.value) }
    }

    @Test fun manualVoiceRefreshUpdatesCachedVoicesAndRetainsThemAfterFailure() {
        panel(online = true)
        compose.onNodeWithText(context.getString(R.string.tts_system_default)).performClick()
        val refresh = compose.onNodeWithText(context.getString(R.string.tts_refresh_voices))
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
            compose.runOnIdle { Assert.assertEquals(2, onlineRequests); Assert.assertEquals("", model.preferences.value?.settings?.voice) }
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
            compose.waitUntil(5_000) { model.preferences.value?.settings?.voice == "ja-JP-KeitaNeural" }
            compose.runOnIdle { Assert.assertEquals(4, onlineRequests) }
        } finally { gate.countDown(); refreshGate = null }
    }

    @Test fun sourceFailureStaysNearVoiceCardAndDoesNotLeakIntoOtherTabs() {
        panel()
        val error = compose.onNodeWithText(configMessage).fetchSemanticsNode().boundsInRoot
        val rate = compose.onNodeWithText(context.getString(R.string.tts_rate_label)).fetchSemanticsNode().boundsInRoot
        Assert.assertTrue(error.bottom < rate.top)
        compose.onNodeWithText(context.getString(R.string.settings_title)).performClick()
        compose.onNodeWithText(configMessage).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.tts_tab_dictionary)).performClick()
        compose.onNodeWithText(configMessage).assertDoesNotExist()
        compose.onNode(hasText(context.getString(R.string.tts_tab_voice)) and hasClickAction() and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
        compose.onNodeWithText(configMessage).assertExists()
    }

    @Test fun installedEnginePreviewReportsPlaybackStopAndNaturalCompletion() {
        panel(system = true)
        compose.runOnIdle { model.preview(TtsSentence(0, 0, 0, "こんにちは。日本語の読み上げです。こんにちは。日本語の読み上げです。"), null) }
        compose.waitUntil(15_000) { model.previewState.value == TtsPreviewState.Playing || model.error.value != null }
        compose.runOnIdle { Assert.assertNull(model.error.value); Assert.assertEquals(TtsPreviewState.Playing, model.previewState.value) }
        compose.runOnIdle { model.settings { it.copy(playbackRate = 1.5f) } }
        compose.waitUntil(5_000) { model.preferences.value?.settings?.playbackRate == 1.5f }
        compose.runOnIdle { Assert.assertEquals(TtsPreviewState.Playing, model.previewState.value) }
        compose.onNodeWithText(context.getString(R.string.tts_stop_preview)).performClick()
        compose.runOnIdle { Assert.assertEquals(TtsPreviewState.Idle, model.previewState.value) }
        compose.runOnIdle { model.preview(TtsSentence(0, 0, 0, "はい。"), null) }
        compose.waitUntil(15_000) { model.previewState.value == TtsPreviewState.Idle }
        compose.runOnIdle { Assert.assertNull(model.error.value) }
    }

    @Test fun pickerPreviewsOneVoiceAndKeepsTheSavedDialogueRouting() {
        val voices = listOf(TtsVoiceInfo("a", false, label = "Voice A"), TtsVoiceInfo("b", false, label = "Voice B"))
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        panel(device = object : TtsDeviceEngine {
            override suspend fun installedEngines() = emptyList<TtsEngineInfo>()
            override suspend fun catalog(settings: TtsSettings) = TtsCatalog("fixture", voices)
            override suspend fun synthesize(text: String, settings: TtsSettings, directory: File): List<File> {
                requests += settings.voice to text
                val rate = 16_000
                val frames = rate * 5
                val wav = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVEfmt ".toByteArray()).putInt(16)
                    putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
                    put("data".toByteArray()).putInt(frames * 2)
                    val frequency = if (settings.voice == "b") 550 else 440
                    repeat(frames) { putShort((kotlin.math.sin(it * 2 * Math.PI * frequency / rate) * 1500).toInt().toShort()) }
                }
                return listOf(File.createTempFile("picker-voice-", ".wav", directory.apply { mkdirs() }).apply { writeBytes(wav.array()) })
            }
        })
        val routing = TtsVoiceRouting(enabled = true, narration = "a", dialogue = "b")
        compose.runOnIdle { model.settings { it.withRouting(routing) } }
        compose.waitUntil(5_000) { model.preferences.value?.settings?.routing() == routing }

        fun openPicker(role: Int) {
            val label = context.getString(R.string.tts_voice_status, context.getString(role), context.getString(R.string.tts_offline))
            compose.onNodeWithText(label).performScrollTo().performClick()
        }
        fun preview(title: String, count: Int) {
            compose.onNodeWithContentDescription(context.getString(R.string.tts_preview_named_voice, title)).performTouchInput { click() }
            compose.waitUntil(5_000) { requests.size >= count && model.previewState.value == TtsPreviewState.Playing }
            compose.onNodeWithText(context.getString(R.string.tts_refresh_voices)).assertIsDisplayed()
        }

        openPicker(R.string.tts_narration_voice)
        preview("Voice B", 1)
        preview(context.getString(R.string.tts_system_default), 2)
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText(context.getString(R.string.tts_stop_preview)).assertIsDisplayed().performClick()
        openPicker(R.string.tts_dialogue_voice)
        preview("Voice A", 3)
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText(context.getString(R.string.tts_stop_preview)).performClick()
        val sample = context.getString(R.string.tts_sample)
        Assert.assertEquals(listOf("b" to sample, "" to sample, "a" to sample), requests.toList())
        Assert.assertEquals(routing, model.preferences.value?.settings?.routing())

        compose.onNodeWithText(context.getString(R.string.tts_preview_voice)).performClick()
        compose.waitUntil(5_000) { model.previewState.value == TtsPreviewState.Playing }
        Assert.assertEquals(listOf("a", "b", "a"), requests.drop(3).map { it.first })
        compose.onNodeWithText(context.getString(R.string.tts_stop_preview)).performClick()
        openPicker(R.string.tts_dialogue_voice)
        compose.onNode(hasText("Voice A") and hasText("a")).performClick()
        compose.waitUntil(5_000) { model.preferences.value?.settings?.routing()?.dialogue == "a" }
        compose.onNodeWithText(context.getString(R.string.tts_refresh_voices)).assertDoesNotExist()
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
            it.boundsInRoot.bottom < compose.onNodeWithText(context.getString(R.string.tts_tab_dictionary)).fetchSemanticsNode().boundsInRoot.top
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
        compose.waitUntil(5_000) { model.readingError.value?.failure == TtsFailure.Storage }
        compose.onNodeWithText(context.getString(R.string.settings_title)).performClick()
        compose.onNodeWithText(context.getString(R.string.tts_storage_error), substring = true).assertExists()
        compose.onNodeWithText(configMessage).assertDoesNotExist()
        compose.runOnIdle { model.preview(TtsSentence(0, 0, 0, "日本語です。"), null) }
        compose.waitUntil(5_000) { model.previewState.value == TtsPreviewState.Idle && model.error.value?.failure == TtsFailure.Configuration }
        compose.onNode(hasText(context.getString(R.string.tts_tab_voice)) and hasClickAction() and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
        compose.onNodeWithText(context.getString(R.string.tts_preview_voice)).assertIsEnabled()
        compose.onNodeWithText(configMessage).assertExists()
        compose.onNodeWithText(context.getString(R.string.tts_storage_error), substring = true).assertDoesNotExist()
    }
}
