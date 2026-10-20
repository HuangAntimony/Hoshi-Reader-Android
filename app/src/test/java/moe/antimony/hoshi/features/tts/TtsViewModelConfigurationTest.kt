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
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TtsViewModelConfigurationTest {
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
        test: suspend (TtsViewModel, TtsRepository) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repository = TtsRepository(store)
        repository.configure(TtsOnlineSource.Azure, TtsOnlineConfig("eastasia", "saved-cipher"))
        repository.configure(TtsOnlineSource.GoogleCloud, TtsOnlineConfig(encryptedKey = "saved-cipher"))
        repository.settings { it.selectSource(selected.id).withVoice("saved-voice") }
        val settings = repository.preferences.first().settings
        val catalogs = TtsVoiceCatalogCache()
        catalogs.load(settings.voiceCatalogKey()!!, false) { listOf(TtsVoiceInfo("saved-voice", true)) }
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
            override suspend fun connect() = Unit
            override fun prepare(files: List<File>, title: String, sentence: String) = Unit
            override fun setPlaybackRate(rate: Float) = Unit
            override fun play() = Unit
            override fun pause() = Unit
            override fun clear() = Unit
            override fun disconnect() = Unit
        }
        val model = TtsViewModel(repository, SasayakiSettingsRepository(MemoryStore()), engine,
            TtsCredentialCipher(dispatcher), audio, books,
            TtsPlaybackController(books, repository, audio, output, playbackScope), ContextWrapper(null))
        try {
            model.refresh()
            runCurrent()
            assertEquals(listOf("saved-voice"), model.catalog.value?.voices?.map { it.id })
            assertFalse(model.catalogLoading.value)
            // Hold any subsequent discovery so a spurious refresh remains observable.
            discoveryGate = CompletableDeferred()
            test(model, repository)
        } finally {
            model.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
            playbackScope.coroutineContext[Job]!!.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    private class MemoryStore : DataStore<Preferences> {
        var failure: Throwable? = null
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            failure?.let { throw it } ?: transform(data.value).also { data.value = it }
    }
}
