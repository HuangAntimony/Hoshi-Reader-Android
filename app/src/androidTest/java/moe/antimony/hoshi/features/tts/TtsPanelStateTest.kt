package moe.antimony.hoshi.features.tts

import android.content.Context
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.sasayaki.SasayakiSettingsRepository
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import okhttp3.OkHttpClient
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
    private lateinit var model: TtsViewModel
    private val configMessage get() = context.getString(R.string.tts_configuration_error)

    private fun panel(system: Boolean = false) {
        val source = if (system) androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("ttsEngine").orEmpty() else TtsOnlineSource.GoogleCloud.id
        val repository = TtsRepository(MemoryStore(preferencesOf(stringPreferencesKey("preferences") to """{"settings":{"engine":"$source"}}""")))
        val cipher = TtsCredentialCipher(Dispatchers.IO)
        val transport = OnlineTtsTransport(OkHttpClient.Builder().addInterceptor { throw IOException("UI fixture prohibits network") }.build())
        val engine = TtsEngineRouter(AndroidTtsEngine(context), OnlineTtsEngine(transport, cipher, Dispatchers.IO))
        val audio = TtsAudioRepository(engine, File(context.cacheDir, "tts-panel-state-fixture"), Dispatchers.IO)
        val books = TtsBookRepository(Dispatchers.IO)
        val output = object : TtsPlaybackOutput {
            override var onEnded: () -> Unit = {}; override var onPaused: () -> Unit = {}; override var onError: () -> Unit = {}
            override suspend fun connect() = Unit
            override fun prepare(files: List<File>, title: String, sentence: String) = Unit
            override fun play() = Unit; override fun pause() = Unit; override fun clear() = Unit; override fun disconnect() = Unit
        }
        compose.runOnUiThread {
            model = TtsViewModel(repository, SasayakiSettingsRepository(reading),
                engine, cipher, audio, books,
                TtsPlaybackController(books, repository, audio, output, scope), context)
            models.put("fixture", model)
        }
        compose.setContent { HoshiReaderTheme(darkTheme = false, dynamicColor = false) { TtsPanel(model, null, null, Modifier) } }
        compose.waitUntil(15_000) { !model.busy.value && model.readingSettings.value != null &&
            if (system) model.catalog.value?.voices?.isNotEmpty() == true else model.error.value == TtsFailure.Configuration }
    }

    @After fun dispose() { compose.runOnUiThread { models.clear(); scope.cancel() } }

    @Test fun sourceFailureStaysNearVoiceCardAndDoesNotLeakIntoOtherTabs() {
        panel()
        val error = compose.onNodeWithText(configMessage).fetchSemanticsNode().boundsInRoot
        val rate = compose.onNodeWithText("Speech rate").fetchSemanticsNode().boundsInRoot
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
        compose.onNodeWithText("Stop preview").performScrollTo().performClick()
        compose.runOnIdle { Assert.assertEquals(TtsPreviewState.Idle, model.previewState.value) }
        compose.runOnIdle { model.preview(TtsSentence(0, 0, 0, "はい。"), null) }
        compose.waitUntil(15_000) { model.previewState.value == TtsPreviewState.Idle }
        compose.runOnIdle { Assert.assertNull(model.error.value) }
    }

    @Test fun readingWriteFailureAndPreviewFailureRemainIndependent() {
        panel()
        compose.runOnIdle { reading.failWrites = true; model.readingSettings(requireNotNull(model.readingSettings.value).copy(autoScroll = false)) }
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
