package moe.antimony.hoshi.features.anki

import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.antimony.hoshi.dictionary.DictionaryCategory
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import moe.antimony.hoshi.features.settings.SettingsLoadState
import moe.antimony.hoshi.features.dictionary.DictionaryUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AnkiViewModelReadinessTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun latestClientSelectionWinsBeforePendingWritesHaveBeenReadBack() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val settings = DelayedSettings(AnkiSettings())
        val viewModel = AnkiViewModel(repository(settings))
        try {
            settings.firstRead.complete(Unit)
            runCurrent()
            // Both selections arrive before the queued writes/readback can run.
            viewModel.updateAnkiDroidClient(AnkiDroidClient.Kuma3)
            viewModel.updateAnkiDroidClient(AnkiDroidClient.AnkiDroid)
            runCurrent()
            assertEquals(AnkiDroidClient.AnkiDroid, settings.settings.first().ankiDroidClient)
            assertEquals(AnkiDroidClient.AnkiDroid, viewModel.uiState.value.settings.ankiDroidClient)
            assertEquals(2L, settings.settings.first().ankiDroidClientRevision)
        } finally {
            viewModel.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun permissionResultFromBeforeClientSwitchIsIgnoredAndCachesAreCleared() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val settings = DelayedSettings(AnkiSettings(
            availableDecks = listOf(AnkiDeck(1, "Old deck")),
            availableNoteTypes = listOf(AnkiNoteType(2, "Old type", listOf("Front"))),
        ))
        val viewModel = AnkiViewModel(repository(settings))
        try {
            settings.firstRead.complete(Unit)
            runCurrent()
            val request = requireNotNull(viewModel.createFetchRequest())
            assertTrue(viewModel.isFetchRequestCurrent(request))
            viewModel.updateAnkiDroidClient(AnkiDroidClient.Kuma3)
            assertFalse(viewModel.isFetchRequestCurrent(request))
            runCurrent()
            assertTrue(viewModel.uiState.value.availableDecks.isEmpty())
            assertTrue(viewModel.uiState.value.availableNoteTypes.isEmpty())
            viewModel.onFetchPermissionResult(request, false)
            viewModel.onFetchPermissionResult(request, true)
            runCurrent()
            assertEquals(null, viewModel.uiState.value.errorMessage)
            assertFalse(viewModel.uiState.value.isFetching)
            viewModel.updateAnkiDroidClient(AnkiDroidClient.AnkiDroid)
            runCurrent()
            assertFalse(viewModel.isFetchRequestCurrent(request))
        } finally {
            viewModel.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun firstVisibleSettingsUseTheStoredValuesAndKeepFollowingUpdates() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val settings = DelayedSettings(AnkiSettings(allowDupes = true, preventAccidentalDuplicates = true))
        val viewModel = AnkiViewModel(repository(settings))
        val visibleSettings = mutableListOf<AnkiSettings>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { state ->
                (state.settingsPageState as? SettingsLoadState.Loaded)?.value?.settings?.let(visibleSettings::add)
            }
        }
        try {
            runCurrent()
            assertEquals(SettingsLoadState.Loading, viewModel.uiState.value.settingsLoadState)
            assertTrue(visibleSettings.isEmpty())
            settings.firstRead.complete(Unit)
            runCurrent()

            val firstVisible = viewModel.uiState.value.settingsLoadState as SettingsLoadState.Loaded
            assertTrue(firstVisible.value.allowDupes)
            assertTrue(firstVisible.value.preventAccidentalDuplicates)
            assertTrue(visibleSettings.all { it.allowDupes && it.preventAccidentalDuplicates })
            viewModel.updatePreventAccidentalDuplicates(false)
            runCurrent()
            val updated = viewModel.uiState.value.settingsLoadState as SettingsLoadState.Loaded
            assertTrue(updated.value.allowDupes)
            assertFalse(updated.value.preventAccidentalDuplicates)
        } finally {
            viewModel.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aNewSettingsEntryWaitsForItsOwnReadBeforeShowingSavedValues() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val firstSettings = DelayedSettings(AnkiSettings(allowDupes = true))
        val first = AnkiViewModel(repository(firstSettings))
        var next: AnkiViewModel? = null
        try {
            firstSettings.firstRead.complete(Unit)
            runCurrent()
            assertTrue((first.uiState.value.settingsLoadState as SettingsLoadState.Loaded).value.allowDupes)
            first.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()

            val nextSettings = DelayedSettings(AnkiSettings(compactGlossaries = false))
            next = AnkiViewModel(repository(nextSettings))
            runCurrent()
            assertEquals(SettingsLoadState.Loading, next.uiState.value.settingsLoadState)
            nextSettings.firstRead.complete(Unit)
            runCurrent()
            assertFalse((next.uiState.value.settingsLoadState as SettingsLoadState.Loaded).value.compactGlossaries)
        } finally {
            first.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
            next?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aFailedReadCanBeRetriedWithoutShowingDefaultSettings() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val settings = DelayedSettings(AnkiSettings(allowDupes = true))
        settings.failure = IOException("unreadable")
        val viewModel = AnkiViewModel(repository(settings))
        try {
            settings.firstRead.complete(Unit)
            runCurrent()
            assertTrue(viewModel.uiState.value.settingsLoadState is SettingsLoadState.Failed)
            settings.failure = null
            settings.firstRead = CompletableDeferred()
            viewModel.retrySettings()
            runCurrent()
            assertEquals(SettingsLoadState.Loading, viewModel.uiState.value.settingsLoadState)
            settings.firstRead.complete(Unit)
            runCurrent()
            assertTrue((viewModel.uiState.value.settingsLoadState as SettingsLoadState.Loaded).value.allowDupes)
        } finally {
            viewModel.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun profileChangesCancelThePreviousReadAndWaitForTheNewProfile() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val profiles = MutableStateFlow("first")
        val first = MutableStateFlow(AnkiSettings(allowDupes = true))
        val second = MutableStateFlow(AnkiSettings(compactGlossaries = false))
        val secondRead = CompletableDeferred<Unit>()
        val settings = object : AnkiSettingsRepository {
            override val profileIds: Flow<String> = profiles
            override val settings: Flow<AnkiSettings> = flow {
                if (profiles.value == "first") emitAll(first) else {
                    secondRead.await()
                    emitAll(second)
                }
            }
            override suspend fun update(transform: (AnkiSettings) -> AnkiSettings) = Unit
        }
        val viewModel = AnkiViewModel(repository(settings))
        try {
            runCurrent()
            assertTrue((viewModel.uiState.value.settingsLoadState as SettingsLoadState.Loaded).value.allowDupes)
            profiles.value = "second"
            runCurrent()
            assertEquals("second", viewModel.uiState.value.profileId)
            assertEquals(SettingsLoadState.Loading, viewModel.uiState.value.settingsLoadState)
            first.value = first.value.copy(allowDupes = false)
            runCurrent()
            assertEquals(SettingsLoadState.Loading, viewModel.uiState.value.settingsLoadState)
            secondRead.complete(Unit)
            runCurrent()
            assertFalse((viewModel.uiState.value.settingsLoadState as SettingsLoadState.Loaded).value.compactGlossaries)
        } finally {
            viewModel.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedExclusionRefreshRetainsConfirmedListOnlyForTheCurrentProfile() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val profiles = MutableStateFlow("first")
        val dictionaryChanges = MutableStateFlow(0L)
        val failReads = AtomicBoolean(false)
        val settings = object : AnkiSettingsRepository {
            override val profileIds: Flow<String> = profiles
            override val settings: Flow<AnkiSettings> = MutableStateFlow(AnkiSettings())
            override suspend fun update(transform: (AnkiSettings) -> AnkiSettings) = Unit
        }
        val viewModel = AnkiViewModel(repository(
            settings = settings,
            loadTermDictionaries = {
                if (failReads.get()) throw IOException("unreadable")
                listOf(AnkiTermDictionary("Excluded dictionary", DictionaryCategory.Exclude))
            },
            dictionaryChangeVersions = dictionaryChanges,
        ))
        val completedCollectors = CompletableDeferred<Unit>()
        val completionHandlers = mutableListOf<kotlinx.coroutines.DisposableHandle>()
        try {
            // The repository uses Dispatchers.IO; use a real-clock wait for its delivered value.
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    viewModel.uiState.first { it.excludedDictionaries == listOf("Excluded dictionary") }
                }
            }
            viewModel.viewModelScope.coroutineContext[Job]!!.children.forEach { job ->
                completionHandlers += job.invokeOnCompletion { completedCollectors.complete(Unit) }
            }
            failReads.set(true)
            dictionaryChanges.value = 1L
            // The settings observer stays active; the failing exclusion collector completes after catch.
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { completedCollectors.await() }
            }
            assertEquals(listOf("Excluded dictionary"), viewModel.uiState.value.popupSettings.excludedDictionaries)

            profiles.value = "second"
            runCurrent()
            assertEquals("second", viewModel.uiState.value.profileId)
            assertTrue(viewModel.uiState.value.popupSettings.excludedDictionaries.isEmpty())
        } finally {
            completionHandlers.forEach { it.dispose() }
            viewModel.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun formatAndAdvancedSettingsWaitForDictionaryDataFromTheSameProfile() {
        val saved = AnkiSettings(allowDupes = true)
        val anki = AnkiUiState(profileId = "second", settings = saved, settingsLoadState = SettingsLoadState.Loaded(saved))
        val dictionary = DictionaryUiState(profileId = "second")
        assertEquals(SettingsLoadState.Loading, ankiDictionarySettingsState(anki, dictionary))
        val ready = dictionary.copy(dictionariesLoadState = SettingsLoadState.Loaded(emptyMap()))
        assertTrue(ankiDictionarySettingsState(anki, ready) is SettingsLoadState.Loaded)
        assertEquals(SettingsLoadState.Loading, ankiDictionarySettingsState(anki, ready.copy(profileId = "first")))
    }

    @Test
    fun dictionaryFailureIsVisibleWhileAnkiIsStillLoadingForTheSameProfile() {
        val failure = SettingsLoadState.Failed(moe.antimony.hoshi.ui.UiText.Resource(moe.antimony.hoshi.R.string.settings_load_failed))
        val anki = AnkiUiState(profileId = "current")
        val dictionary = DictionaryUiState(profileId = "current", dictionariesLoadState = failure)
        assertEquals(failure, ankiDictionarySettingsState(anki, dictionary))
        assertEquals(SettingsLoadState.Loading, ankiDictionarySettingsState(anki, dictionary.copy(profileId = "old")))
    }

    private fun repository(
        settings: AnkiSettingsRepository,
        loadTermDictionaries: () -> List<AnkiTermDictionary> = { emptyList() },
        dictionaryChangeVersions: Flow<Long> = flowOf(0L),
    ): AnkiRepository = AnkiRepository(
        context = object : ContextWrapper(null) {
            override fun getCacheDir(): File = files.root
        },
        backend = AnkiDroidBackendAdapter(object : AnkiContentApi {
            override fun deckList() = emptyMap<Long, String>()
            override fun modelList() = emptyMap<Long, String>()
            override fun fieldList(modelId: Long) = emptyList<String>()
            override fun findDuplicateNotes(deck: AnkiDeck, modelId: Long, key: String, duplicateScope: AnkiDuplicateScope, checkAllModels: Boolean) = false
            override fun addNote(modelId: Long, deckId: Long, fields: Array<String>, tags: Set<String>): Long? = null
        }),
        settingsRepository = settings,
        localAudioRepository = LocalAudioRepository(files.root),
        loadTermDictionaries = loadTermDictionaries,
        dictionaryChangeVersions = dictionaryChangeVersions,
    )

    private class DelayedSettings(initial: AnkiSettings) : AnkiSettingsRepository {
        var firstRead = CompletableDeferred<Unit>()
        var failure: IOException? = null
        private val stored = MutableStateFlow(initial)
        override val settings: Flow<AnkiSettings> = flow {
            firstRead.await()
            failure?.let { throw it }
            emitAll(stored)
        }
        override suspend fun update(transform: (AnkiSettings) -> AnkiSettings) {
            stored.value = transform(stored.value)
        }
    }
}
