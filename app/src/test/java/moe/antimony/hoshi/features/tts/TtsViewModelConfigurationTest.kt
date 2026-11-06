package moe.antimony.hoshi.features.tts

import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.antimony.hoshi.features.sasayaki.SasayakiSettingsRepository
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.resolve
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.features.settings.SettingsLoadState
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TtsViewModelConfigurationTest {
    @Test fun languageChangeCancelsPreviewClearsOldCatalogAndCapturesTheLanguageOfEdits() = runTest {
        val language = MutableStateFlow("ja")
        fixture(TtsOnlineSource.MicrosoftTranslator, language = language) { model, repository ->
            startPreview(model)
            model.settings { it.withVoice("japanese-edited") }
            language.value = "en"
            runCurrent()
            assertEquals(TtsPreviewState.Idle, model.previewState.value)
            assertEquals("en", model.preferences.value!!.settings.languageId)
            assertEquals("", model.preferences.value!!.settings.voice)
            assertEquals(listOf("english"), model.catalog.value?.voices?.map { it.id })
            assertEquals("japanese-edited", repository.preferences.first().settings.copy(languageId = "ja").voice)
        }
    }

    @Test fun deletingReplacementReturnsItsStorageReasonAndCanBeRetried() = runTest {
        val store = MemoryStore()
        fixture(TtsOnlineSource.MicrosoftTranslator, store) { model, repository ->
            repository.save(TtsWord("stored", "猫", "ねこ"))
            store.failure = java.io.IOException("replacement store is read only")

            val result: Any? = model.delete("stored")

            assertEquals(TtsError(TtsFailure.Storage, UiText.Joined(listOf(UiText.Resource(R.string.tts_storage_error), UiText.Literal("replacement store is read only")))), result)
            assertEquals(listOf("stored"), repository.preferences.first().words.map { it.id })
            assertNull("Replacement errors must not become source-tab errors", model.error.value)
            store.failure = null
            assertNull(model.delete("stored"))
            assertTrue(repository.preferences.first().words.isEmpty())
        }
    }

    @Test fun toggleWriteFailureRetainsActualValueAndOriginalDiagnosticsThenRetries() = runTest {
        val store = MemoryStore()
        fixture(TtsOnlineSource.MicrosoftTranslator, store) { model, repository ->
            ErrorDiagnosticEvents.buffer.drain()
            val failure = java.io.IOException("Cannot save TTS", IllegalStateException("disk is read only"))
            store.failure = failure
            model.enabled(false); runCurrent()
            assertTrue(repository.preferences.first().settings.enabled)
            assertTrue(model.preferences.value!!.settings.enabled)
            val message = model.toggleError.value!!.message.resolve({ _, _ -> "Storage" }, { _, _, _ -> "" })
            assertTrue(message.contains("Cannot save TTS"))
            assertTrue(message.contains("disk is read only"))
            assertSame(failure, ErrorDiagnosticEvents.buffer.drain().single().error)
            store.failure = null
            model.enabled(false); runCurrent()
            assertFalse(model.preferences.value!!.settings.enabled)
            assertNull(model.toggleError.value)
            model.enabled(true); runCurrent()
            assertTrue(model.preferences.value!!.settings.enabled)
            assertEquals(TtsPreviewState.Idle, model.previewState.value)
        }
    }

    @Test fun delayedAndFailedPreferencesNeverExposeDefaultsAndRetryLoadsActualSwitch() = runTest {
        val store = MemoryStore()
        val gate = CompletableDeferred<Unit>()
        fixture(TtsOnlineSource.MicrosoftTranslator, store, refresh = false, beforeModel = { store.readGate = gate }) { model, _ ->
            runCurrent()
            assertEquals(SettingsLoadState.Loading, model.preferencesLoadState.value)
            assertNull(model.preferences.value)
            val failure = java.io.IOException("Cannot read TTS", IllegalStateException("storage offline"))
            store.readFailure = failure
            ErrorDiagnosticEvents.buffer.drain()
            gate.complete(Unit); runCurrent()
            val state = model.preferencesLoadState.value as SettingsLoadState.Failed
            val message = state.message.resolve({ _, _ -> "Load" }, { _, _, _ -> "" })
            assertTrue(message, message.contains("Cannot read TTS"))
            assertTrue(message, message.contains("storage offline"))
            val events = ErrorDiagnosticEvents.buffer.drain()
            // combine introduces a coroutine boundary; stack-trace recovery may wrap the original.
            assertTrue(events.any { event -> generateSequence(event.error) { it.cause }.any { it === failure } })
            assertNull(model.preferences.value)
            store.readFailure = null
            model.retryPreferences(); runCurrent()
            assertTrue(model.preferences.value!!.settings.enabled)
        }
    }

    @Test fun settingsRetryRecoversRetainedReaderPreferencesWithoutRecreatingReader() = runTest {
        val store = MemoryStore()
        var retained: TtsViewModel? = null
        fixture(TtsOnlineSource.MicrosoftTranslator, store, refresh = false,
            beforeModel = { store.readFailure = java.io.IOException("transient storage read") },
            onSecondModel = { retained = it }) { settings, repository ->
            runCurrent()
            assertTrue(settings.preferencesLoadState.value is SettingsLoadState.Failed)
            assertTrue(retained!!.preferencesLoadState.value is SettingsLoadState.Failed)
            store.readFailure = null
            settings.retryPreferences(); runCurrent()
            assertTrue(settings.preferences.value!!.settings.enabled)
            assertTrue(retained!!.preferences.value!!.settings.enabled)
            repository.enabled(false); runCurrent()
            assertFalse(retained!!.preferences.value!!.settings.enabled)
        }
    }

    @Test fun disablingCancelsPreviewAndRejectsFurtherPreviewAndExport() = runTest {
        fixture(TtsOnlineSource.MicrosoftTranslator) { model, repository ->
            startPreview(model)
            repository.enabled(false); runCurrent()
            assertEquals(TtsPreviewState.Idle, model.previewState.value)
            model.preview(TtsSentence(0, 0, 0, "猫。"), null); runCurrent()
            assertEquals(TtsPreviewState.Idle, model.previewState.value)
            assertNull(model.sentenceAudio(null) { error("Disabled TTS must not read or parse the chapter") })
            assertNull(model.error.value)
        }
    }

    @get:Rule val files = TemporaryFolder()

    @Test fun savingUnselectedCloudConfigurationsKeepsTheCurrentCatalogReady() = runTest {
        fixture(TtsOnlineSource.MicrosoftTranslator) { model, repository ->
            val catalog = model.catalog.value
            startPreview(model)
            val loading = mutableListOf<Boolean>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                model.catalogLoading.collect { loading += it }
            }

            for (source in listOf(TtsOnlineSource.Azure, TtsOnlineSource.GoogleCloud)) {
                // An empty draft retains the saved key, without involving Android Keystore.
                assertNull(model.configure(source, if (source == TtsOnlineSource.Azure) "japaneast" else "", ""))
                runCurrent()

                val saved = repository.preferences.first().settings
                assertEquals(TtsOnlineSource.MicrosoftTranslator.id, saved.engine)
                assertEquals("saved-voice", saved.voice)
                assertEquals(if (source == TtsOnlineSource.Azure) "japaneast" else "", saved.online.getValue(source.id).region)
                assertEquals("saved-cipher", saved.online.getValue(source.id).encryptedKey)
                assertSame(catalog, model.catalog.value)
                assertFalse("Editing $source must not load the selected source", loading.any { it })
                assertEquals(TtsPreviewState.Preparing, model.previewState.value)
                assertNull(model.error.value)
            }
        }
    }

    @Test fun deletingUnselectedCloudKeysKeepsTheCurrentCatalogReady() = runTest {
        fixture(TtsOnlineSource.MicrosoftTranslator) { model, repository ->
            val catalog = model.catalog.value
            startPreview(model)
            val loading = mutableListOf<Boolean>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                model.catalogLoading.collect { loading += it }
            }

            for (source in listOf(TtsOnlineSource.Azure, TtsOnlineSource.GoogleCloud)) {
                assertNull(model.removeKey(source))
                runCurrent()

                val saved = repository.preferences.first().settings
                assertEquals("", saved.online.getValue(source.id).encryptedKey)
                assertEquals(TtsOnlineSource.MicrosoftTranslator.id, saved.engine)
                assertEquals("saved-voice", saved.voice)
                assertSame(catalog, model.catalog.value)
                assertFalse("Deleting $source's key must not load the selected source", loading.any { it })
                assertEquals(TtsPreviewState.Preparing, model.previewState.value)
                assertNull(model.error.value)
            }
        }
    }

    @Test fun savingSelectedCloudCredentialsStillRefreshesTheCatalog() = runTest {
        for (source in listOf(TtsOnlineSource.Azure, TtsOnlineSource.GoogleCloud)) {
            fixture(source) { model, _ ->
                startPreview(model)
                assertNull(model.configure(source, if (source == TtsOnlineSource.Azure) "japaneast" else "", ""))
                runCurrent()
                assertTrue(model.catalogLoading.value)
                assertEquals(TtsPreviewState.Idle, model.previewState.value)
            }
        }
    }

    @Test fun deletingSelectedCloudCredentialsStillRefreshesTheCatalog() = runTest {
        for (source in listOf(TtsOnlineSource.Azure, TtsOnlineSource.GoogleCloud)) {
            fixture(source) { model, repository ->
                startPreview(model)
                assertNull(model.removeKey(source))
                runCurrent()
                assertTrue(model.catalogLoading.value)
                assertEquals(TtsPreviewState.Idle, model.previewState.value)
                assertEquals("", repository.preferences.first().settings.online.getValue(source.id).encryptedKey)
            }
        }
    }

    private fun TestScope.startPreview(model: TtsViewModel) {
        // A request-local device source keeps synthesis pending without platform audio or network.
        model.preview(TtsSentence(0, 0, 0, "日本語です。"), null,
            TtsPreferences(settings = TtsSettings(engine = "fixture-device")))
        runCurrent()
        assertEquals(TtsPreviewState.Preparing, model.previewState.value)
    }

    private suspend fun TestScope.fixture(
        selected: TtsOnlineSource,
        store: MemoryStore = MemoryStore(),
        refresh: Boolean = true,
        language: MutableStateFlow<String> = MutableStateFlow("ja"),
        beforeModel: () -> Unit = {},
        onSecondModel: ((TtsViewModel) -> Unit)? = null,
        test: suspend (TtsViewModel, TtsRepository) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repository = TtsRepository(store, language)
        repository.configure(TtsOnlineSource.Azure, TtsOnlineConfig("eastasia", "saved-cipher"))
        repository.configure(TtsOnlineSource.GoogleCloud, TtsOnlineConfig(encryptedKey = "saved-cipher"))
        repository.settings { it.copy(enabled = true).selectSource(selected.id).withVoice("saved-voice") }
        val settings = repository.preferences.first().settings
        val catalogs = TtsVoiceCatalogCache()
        catalogs.load(settings.voiceCatalogKey()!!, false) { listOf(TtsVoiceInfo("saved-voice", true, languages = listOf("ja-JP")), TtsVoiceInfo("english", true, languages = listOf("en-GB"))) }
        var discoveryGate: CompletableDeferred<Unit>? = null
        val device = object : TtsDeviceEngine {
            override suspend fun installedEngines(): List<TtsEngineInfo> {
                discoveryGate?.await()
                return emptyList()
            }
            override suspend fun catalog(settings: TtsSettings): TtsCatalog = error("Unexpected device catalog")
            override suspend fun synthesize(text: String, settings: TtsSettings, directory: File): List<File> =
                awaitCancellation()
        }
        val transport = OnlineTtsTransport(OkHttpClient.Builder().addInterceptor { error("Unexpected network request") }.build())
        val engine = TtsEngineRouter(AndroidTtsEngine(device),
            OnlineTtsEngine(transport, { _, _ -> "fixture-key" }, dispatcher, catalogs = catalogs),
            CustomTtsEngine(transport, { _, _ -> "fixture-key" }, dispatcher))
        val audio = TtsAudioRepository(engine, files.root, dispatcher, TtsExportFallback { _, _ -> error("Unexpected export") })
        val books = TtsBookRepository(dispatcher)
        val playbackScope = CoroutineScope(SupervisorJob() + dispatcher)
        val output = object : TtsPlaybackOutput {
            override var onEnded: () -> Unit = {}
            override var onPaused: () -> Unit = {}
            override var onError: (Throwable) -> Unit = {}
            override var onTransition: (Long) -> Unit = {}
            override fun append(files: List<File>, title: String, sentence: String, token: Long) = Unit
            override fun discardUpcoming(): Long? = null
            override suspend fun connect() = Unit
            override fun prepare(files: List<File>, title: String, sentence: String) = Unit
            override fun setPlaybackRate(rate: Float) = Unit
            override fun play() = Unit
            override fun pause() = Unit
            override fun clear() = Unit
            override fun disconnect() = Unit
        }
        beforeModel()
        val model = TtsViewModel(repository, SasayakiSettingsRepository(MemoryStore()), engine,
            TtsCredentialCipher(dispatcher), audio, books,
            TtsPlaybackController(books, repository, audio, output, playbackScope), ContextWrapper(null))
        val secondModel = onSecondModel?.let { callback ->
            TtsViewModel(repository, SasayakiSettingsRepository(MemoryStore()), engine,
                TtsCredentialCipher(dispatcher), audio, books, model.playback, ContextWrapper(null)).also(callback)
        }
        try {
            runCurrent()
            if (refresh) model.refresh()
            runCurrent()
            if (refresh) assertEquals(listOf("saved-voice"), model.catalog.value?.voices?.map { it.id })
            assertFalse(model.catalogLoading.value)
            // Hold any subsequent discovery so a spurious refresh remains observable.
            discoveryGate = CompletableDeferred()
            test(model, repository)
        } finally {
            secondModel?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            model.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
            playbackScope.coroutineContext[Job]!!.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    private class MemoryStore : DataStore<Preferences> {
        var failure: Throwable? = null
        var readGate: CompletableDeferred<Unit>? = null
        var readFailure: Throwable? = null
        private val stored = MutableStateFlow(emptyPreferences())
        override val data = flow { readGate?.await(); readFailure?.let { throw it }; emitAll(stored) }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            failure?.let { throw it } ?: transform(stored.value).also { stored.value = it }
    }
}
