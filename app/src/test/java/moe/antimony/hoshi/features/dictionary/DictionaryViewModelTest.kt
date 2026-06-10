package moe.antimony.hoshi.features.dictionary

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import moe.antimony.hoshi.R
import moe.antimony.hoshi.dictionary.DictionaryIndex
import moe.antimony.hoshi.dictionary.DictionaryInfo
import moe.antimony.hoshi.dictionary.DictionaryRename
import moe.antimony.hoshi.dictionary.RecommendedDictionary
import moe.antimony.hoshi.dictionary.DictionaryType
import moe.antimony.hoshi.dictionary.DictionaryUpdateCandidate
import moe.antimony.hoshi.dictionary.DictionaryUpdateFailure
import moe.antimony.hoshi.dictionary.DictionaryUpdateProgress
import moe.antimony.hoshi.dictionary.DictionaryUpdateStage
import moe.antimony.hoshi.dictionary.DictionaryUpdateSummary
import moe.antimony.hoshi.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DictionaryViewModelTest {
    @Test
    fun reloadPublishesDictionariesSettingsAndRebuildsLookupQuery() {
        val term = dictionary("term", "JMdict")
        val repository = FakeDictionaryRepository(
            dictionaries = mapOf(DictionaryType.Term to listOf(term)),
            settings = DictionarySettings(dictionaryTabDefault = true, maxResults = 7),
        )
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.reload()

        assertEquals(DictionaryType.Term, viewModel.uiState.value.selectedType)
        assertEquals(listOf(term), viewModel.uiState.value.currentDictionaries)
        assertEquals(7, viewModel.uiState.value.settings.maxResults)
        assertFalse(viewModel.uiState.value.isImporting)
        assertNull(viewModel.uiState.value.errorMessage.testString())
        assertEquals(1, repository.rebuildCount)
    }

    @Test
    fun selectedTypeControlsCurrentDictionaries() {
        val frequency = dictionary("freq", "Jiten")
        val repository = FakeDictionaryRepository(
            dictionaries = mapOf(DictionaryType.Frequency to listOf(frequency)),
        )
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.reload()

        viewModel.selectType(DictionaryType.Frequency)

        assertEquals(DictionaryType.Frequency, viewModel.uiState.value.selectedType)
        assertEquals(listOf(frequency), viewModel.uiState.value.currentDictionaries)
    }

    @Test
    fun importSuccessRefreshesDictionariesAndClearsImportState() {
        val repository = FakeDictionaryRepository()
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )
        val imported = dictionary("pitch", "Pitch")
        repository.onImport = {
            repository.dictionaries = repository.dictionaries + (DictionaryType.Pitch to listOf(imported))
        }
        val item = DictionaryImportItem(displayName = "pitch.zip")

        viewModel.importDictionaries(
            importItems = listOf(item),
            importOperation = { onProgress ->
                onProgress(item)
                repository.onImport!!.invoke()
                repository.publishCompletedChange()
                DictionaryImportBatchResult(imported = listOf(item), failed = emptyList())
            },
        )

        assertFalse(viewModel.uiState.value.isImporting)
        assertNull(viewModel.uiState.value.currentImportMessage.testString())
        assertNull(viewModel.uiState.value.errorMessage.testString())
        assertEquals(listOf(imported), viewModel.uiState.value.dictionaries[DictionaryType.Pitch])
        assertEquals(1, repository.rebuildCount)
    }

    @Test
    fun importPublishesCurrentDictionaryProgressForEachItem() {
        val repository = FakeDictionaryRepository()
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )
        val first = DictionaryImportItem(displayName = "JMdict.zip")
        val second = DictionaryImportItem(displayName = "Jiten.zip")
        val messages = mutableListOf<String?>()

        viewModel.importDictionaries(
            importItems = listOf(first, second),
            importOperation = { onProgress ->
                onProgress(first)
                messages += viewModel.uiState.value.currentImportMessage.testString()
                onProgress(second)
                messages += viewModel.uiState.value.currentImportMessage.testString()
                DictionaryImportBatchResult(imported = listOf(first, second), failed = emptyList())
            },
        )

        assertEquals(listOf("Importing JMdict.zip", "Importing Jiten.zip"), messages)
        assertFalse(viewModel.uiState.value.isImporting)
        assertNull(viewModel.uiState.value.currentImportMessage.testString())
    }

    @Test
    fun publicImportPassesItemsToRepositoryAndPublishesProgress() {
        val first = DictionaryImportItem(displayName = "First.zip")
        val second = DictionaryImportItem(displayName = "Second.zip")
        val repository = FakeDictionaryRepository()
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.importDictionaries(listOf(first, second))

        assertEquals(listOf(first, second), repository.importedItems)
        assertEquals(
            listOf("Importing First.zip", "Importing Second.zip"),
            repository.progressMessages,
        )
        assertFalse(viewModel.uiState.value.isImporting)
        assertNull(viewModel.uiState.value.currentImportMessage.testString())
    }

    @Test
    fun publicImportContinuesAfterItemFailureAndReportsFailedNames() {
        val first = DictionaryImportItem(displayName = "Bad.zip")
        val second = DictionaryImportItem(displayName = "Good.zip")
        val imported = dictionary("good", "Good")
        val repository = FakeDictionaryRepository(
            failedImportItems = setOf(first),
        )
        repository.onImport = {
            repository.dictionaries = repository.dictionaries + (DictionaryType.Term to listOf(imported))
        }
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.importDictionaries(listOf(first, second))

        assertEquals(listOf(first, second), repository.importedItems)
        assertEquals(
            listOf("Importing Bad.zip", "Importing Good.zip"),
            repository.progressMessages,
        )
        assertEquals("Failed to import:\nBad.zip", viewModel.uiState.value.errorMessage.testString())
        assertEquals(listOf(imported), viewModel.uiState.value.currentDictionaries)
        assertFalse(viewModel.uiState.value.isImporting)
        assertNull(viewModel.uiState.value.currentImportMessage.testString())
    }

    @Test
    fun publicImportReportsAllFailedNamesWhenEveryItemFails() {
        val first = DictionaryImportItem(displayName = "Bad.zip")
        val second = DictionaryImportItem(displayName = "Worse.zip")
        val repository = FakeDictionaryRepository(
            failedImportItems = setOf(first, second),
        )
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.importDictionaries(listOf(first, second))

        assertEquals(listOf(first, second), repository.importedItems)
        assertEquals("Failed to import:\nBad.zip\nWorse.zip", viewModel.uiState.value.errorMessage.testString())
        assertFalse(viewModel.uiState.value.isImporting)
        assertNull(viewModel.uiState.value.currentImportMessage.testString())
    }

    @Test
    fun importRecommendedDictionariesPassesSelectedItemsAndPublishesProgress() {
        val first = RecommendedDictionary(
            id = "jmdict",
            name = "JMdict",
            type = DictionaryType.Term,
            indexUrl = "https://example.invalid/jmdict.json",
        )
        val second = RecommendedDictionary(
            id = "jitendex",
            name = "Jitendex",
            type = DictionaryType.Term,
            indexUrl = "https://jitendex.org/static/yomitan.json",
        )
        val repository = FakeDictionaryRepository()
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.importRecommendedDictionaries(listOf(first, second))

        assertEquals(listOf(first, second), repository.importedRecommendedDictionaries)
        assertEquals(
            listOf("Fetching JMdict", "Downloading Jitendex", "Importing Jitendex"),
            repository.recommendedProgressMessages,
        )
        assertFalse(viewModel.uiState.value.isImporting)
        assertNull(viewModel.uiState.value.currentImportMessage.testString())
        assertEquals(1, repository.rebuildCount)
    }

    @Test
    fun reloadPublishesUpdatableDictionaries() {
        val updatable = dictionary(
            fileName = "jmdict",
            title = "JMdict [2026-04-27]",
            isUpdatable = true,
        )
        val repository = FakeDictionaryRepository(
            dictionaries = mapOf(DictionaryType.Term to listOf(updatable)),
            updatableDictionaries = listOf(DictionaryUpdateCandidate(updatable, DictionaryType.Term)),
        )
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.reload()

        assertEquals(listOf(DictionaryUpdateCandidate(updatable, DictionaryType.Term)), viewModel.uiState.value.updatableDictionaries)
    }

    @Test
    fun autoUpdateMutationStatePublishesUpdatingProgress() {
        val repository = FakeDictionaryRepository()
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )

        repository.publishMutationState(
            DictionaryMutationState(
                operation = DictionaryMutationOperation.AutoUpdate,
                progress = DictionaryUpdateProgress(DictionaryUpdateStage.Checking, "JMdict"),
            ),
        )

        assertTrue(viewModel.uiState.value.isMutationInProgress)
        assertTrue(viewModel.uiState.value.isUpdating)
        assertFalse(viewModel.uiState.value.isImporting)
        assertTrue(viewModel.uiState.value.showBlockingProgress)
        assertEquals("Checking JMdict", viewModel.uiState.value.currentImportMessage.testString())
    }

    @Test
    fun completedMutationChangeReloadsDictionariesWithoutClearingCurrentError() {
        val existing = dictionary("old", "Old", isUpdatable = true)
        val updated = dictionary("new", "New")
        val repository = FakeDictionaryRepository(
            dictionaries = mapOf(DictionaryType.Term to listOf(existing)),
            updatableDictionaries = listOf(DictionaryUpdateCandidate(existing, DictionaryType.Term)),
        )
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.reload()
        viewModel.importDictionaries(
            importItems = listOf(DictionaryImportItem(displayName = "bad.zip")),
            importOperation = { error("bad archive") },
        )
        repository.dictionaries = mapOf(DictionaryType.Term to listOf(updated))
        repository.updatableDictionaries = emptyList()

        repository.publishMutationState(DictionaryMutationState(completedChangeVersion = 1L))

        assertEquals(listOf(updated), viewModel.uiState.value.currentDictionaries)
        assertEquals(emptyList<DictionaryUpdateCandidate>(), viewModel.uiState.value.updatableDictionaries)
        assertEquals("bad archive", viewModel.uiState.value.errorMessage.testString())
    }

    @Test
    fun editMutationDisablesConflictingActionsWithoutBlockingProgressOverlay() {
        val repository = FakeDictionaryRepository()
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )

        repository.publishMutationState(DictionaryMutationState(operation = DictionaryMutationOperation.Edit))

        assertTrue(viewModel.uiState.value.isMutationInProgress)
        assertFalse(viewModel.uiState.value.isImporting)
        assertFalse(viewModel.uiState.value.isUpdating)
        assertFalse(viewModel.uiState.value.showBlockingProgress)
        assertNull(viewModel.uiState.value.currentImportMessage.testString())
    }

    @Test
    fun updateDictionariesPublishesProgressAndRefreshesDictionaries() {
        val old = dictionary(
            fileName = "old-jmdict",
            title = "JMdict [2026-04-27]",
            isUpdatable = true,
        )
        val updated = dictionary("new-jmdict", "JMdict [2099-01-01]")
        val repository = FakeDictionaryRepository(
            dictionaries = mapOf(DictionaryType.Term to listOf(old)),
            settings = DictionarySettings(collapsedDictionaries = setOf(old.index.title, "Other")),
            updatableDictionaries = listOf(DictionaryUpdateCandidate(old, DictionaryType.Term)),
        )
        repository.onUpdate = { onProgress ->
            onProgress(DictionaryUpdateProgress(DictionaryUpdateStage.Checking, old.index.title))
            onProgress(DictionaryUpdateProgress(DictionaryUpdateStage.Downloading, updated.index.title))
            repository.dictionaries = mapOf(DictionaryType.Term to listOf(updated))
            repository.updatableDictionaries = emptyList()
            repository.publishCompletedChange()
            DictionaryUpdateSummary(
                checkedCount = 1,
                updatedCount = 1,
                renamedDictionaries = listOf(DictionaryRename(old.index.title, updated.index.title)),
            )
        }
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.reload()
        val messages = mutableListOf<String?>()

        viewModel.updateDictionaries(
            updateOperation = { onProgress ->
                repository.onUpdate!!.invoke(onProgress).also {
                    messages += viewModel.uiState.value.currentImportMessage.testString()
                }
            },
        )

        assertFalse(viewModel.uiState.value.isUpdating)
        assertNull(viewModel.uiState.value.currentImportMessage.testString())
        assertEquals(listOf(updated), viewModel.uiState.value.currentDictionaries)
        assertEquals(emptyList<DictionaryUpdateCandidate>(), viewModel.uiState.value.updatableDictionaries)
        assertEquals(setOf(old.index.title, "Other"), viewModel.uiState.value.settings.collapsedDictionaries)
        assertNull(repository.savedSettings)
        assertTrue(messages.contains("Downloading ${updated.index.title}"))
    }

    @Test
    fun updateDictionariesReportsPartialFailuresAfterRefreshingSuccessfulUpdates() {
        val old = dictionary(
            fileName = "old-jmdict",
            title = "JMdict [2026-04-27]",
            isUpdatable = true,
        )
        val updated = dictionary("new-jmdict", "JMdict [2099-01-01]")
        val repository = FakeDictionaryRepository(
            dictionaries = mapOf(DictionaryType.Term to listOf(old)),
            updatableDictionaries = listOf(DictionaryUpdateCandidate(old, DictionaryType.Term)),
        )
        repository.onUpdate = { onProgress ->
            onProgress(DictionaryUpdateProgress(DictionaryUpdateStage.Checking, old.index.title))
            repository.dictionaries = mapOf(DictionaryType.Term to listOf(updated))
            repository.updatableDictionaries = emptyList()
            repository.publishCompletedChange()
            DictionaryUpdateSummary(
                checkedCount = 2,
                successfulCount = 1,
                updatedCount = 1,
                failures = listOf(DictionaryUpdateFailure("Jiten", "fetch failed")),
            )
        }
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.reload()

        viewModel.updateDictionaries()

        assertFalse(viewModel.uiState.value.isUpdating)
        assertEquals(listOf(updated), viewModel.uiState.value.currentDictionaries)
        assertEquals(emptyList<DictionaryUpdateCandidate>(), viewModel.uiState.value.updatableDictionaries)
        assertEquals("Failed to update:\nJiten: fetch failed", viewModel.uiState.value.errorMessage.testString())
    }

    @Test
    fun publicUpdateRefreshesOnceFromCompletedMutationChange() {
        val old = dictionary("old-jmdict", "JMdict [2026-04-27]", isUpdatable = true)
        val updated = dictionary("new-jmdict", "JMdict [2099-01-01]")
        val repository = FakeDictionaryRepository(
            dictionaries = mapOf(DictionaryType.Term to listOf(old)),
            updatableDictionaries = listOf(DictionaryUpdateCandidate(old, DictionaryType.Term)),
        )
        repository.onUpdate = {
            repository.dictionaries = mapOf(DictionaryType.Term to listOf(updated))
            repository.updatableDictionaries = emptyList()
            repository.publishCompletedChange()
            DictionaryUpdateSummary(
                checkedCount = 1,
                successfulCount = 1,
                updatedCount = 1,
            )
        }
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.reload()
        val loadCountAfterInitialReload = repository.loadDictionariesCount

        viewModel.updateDictionaries()

        assertEquals(listOf(updated), viewModel.uiState.value.currentDictionaries)
        assertEquals(loadCountAfterInitialReload + 1, repository.loadDictionariesCount)
        assertEquals(loadCountAfterInitialReload + 1, repository.rebuildCount)
    }

    @Test
    fun importFailureKeepsExistingDictionariesAndCanRetry() {
        val existing = dictionary("term", "Existing")
        val repository = FakeDictionaryRepository(
            dictionaries = mapOf(DictionaryType.Term to listOf(existing)),
        )
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.reload()
        val bad = DictionaryImportItem(displayName = "bad.zip")

        viewModel.importDictionaries(
            importItems = listOf(bad),
            importOperation = { onProgress ->
                onProgress(bad)
                error("bad archive")
            },
        )

        assertFalse(viewModel.uiState.value.isImporting)
        assertNull(viewModel.uiState.value.currentImportMessage.testString())
        assertEquals("bad archive", viewModel.uiState.value.errorMessage.testString())
        assertEquals(listOf(existing), viewModel.uiState.value.currentDictionaries)

        viewModel.importDictionaries(
            importItems = listOf(DictionaryImportItem(displayName = "good.zip")),
            importOperation = { _ ->
                DictionaryImportBatchResult(
                    imported = listOf(DictionaryImportItem(displayName = "good.zip")),
                    failed = emptyList(),
                )
            },
        )

        assertNull(viewModel.uiState.value.errorMessage.testString())
        assertFalse(viewModel.uiState.value.isImporting)
        assertNull(viewModel.uiState.value.currentImportMessage.testString())
    }

    @Test
    fun consumeErrorMessageClearsCurrentError() {
        val repository = FakeDictionaryRepository()
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.importDictionaries(
            importItems = listOf(DictionaryImportItem(displayName = "bad.zip")),
            importOperation = { onProgress ->
                onProgress(DictionaryImportItem(displayName = "bad.zip"))
                error("bad archive")
            },
        )

        assertEquals("bad archive", viewModel.uiState.value.errorMessage.testString())

        viewModel.consumeErrorMessage()

        assertNull(viewModel.uiState.value.errorMessage.testString())
    }

    @Test
    fun enabledDeleteMoveAndSettingsChangesPersistThenReload() {
        val dictionary = dictionary("term", "JMdict")
        val repository = FakeDictionaryRepository(
            dictionaries = mapOf(DictionaryType.Term to listOf(dictionary)),
        )
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.reload()

        viewModel.setDictionaryEnabled(dictionary, false)
        viewModel.moveDictionary(0, 0)
        viewModel.deleteDictionary(dictionary)
        viewModel.updateSettings { it.copy(maxResults = 100, compactPitchAccents = false) }

        assertEquals(listOf("term:false"), repository.enabledCalls)
        assertEquals(listOf(DictionaryType.Term to (0 to 0)), repository.moveCalls)
        assertEquals(listOf("term"), repository.deleteCalls)
        assertEquals(50, viewModel.uiState.value.settings.maxResults)
        assertFalse(viewModel.uiState.value.settings.compactPitchAccents)
        assertEquals(viewModel.uiState.value.settings, repository.savedSettings)
        assertTrue(repository.loadDictionariesCount >= 4)
    }

    @Test
    fun deleteDictionaryRemovesCollapsedTitleFromSettings() {
        val dictionary = dictionary("term", "JMdict")
        val repository = FakeDictionaryRepository(
            dictionaries = mapOf(DictionaryType.Term to listOf(dictionary)),
            settings = DictionarySettings(collapsedDictionaries = setOf("JMdict", "Other")),
        )
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.reload()

        viewModel.deleteDictionary(dictionary)

        assertEquals(listOf("term"), repository.deleteCalls)
        assertEquals(setOf("Other"), viewModel.uiState.value.settings.collapsedDictionaries)
        assertEquals(viewModel.uiState.value.settings, repository.savedSettings)
    }

    @Test
    fun moveDictionaryOptimisticallyReordersUiStateBeforeRepositoryReload() {
        val first = dictionary("first", "First")
        val second = dictionary("second", "Second")
        val third = dictionary("third", "Third")
        val repository = FakeDictionaryRepository(
            dictionaries = mapOf(DictionaryType.Term to listOf(first, second, third)),
        )
        val viewModel = DictionaryViewModel(
            repository = repository,
            coroutineScope = testScope,
            ioDispatcher = Dispatchers.Unconfined,
        )
        viewModel.reload()
        val moveGate = CompletableDeferred<Unit>()
        repository.onMove = { moveGate.await() }

        viewModel.moveDictionary(0, 2)

        assertEquals(listOf(second, third, first), viewModel.uiState.value.currentDictionaries)

        repository.dictionaries = mapOf(DictionaryType.Term to listOf(second, third, first))
        moveGate.complete(Unit)
    }

    private companion object {
        val testScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)

        fun dictionary(
            fileName: String,
            title: String,
            isUpdatable: Boolean = false,
        ) = DictionaryInfo(
            index = DictionaryIndex(
                title = title,
                format = 3,
                revision = "rev",
                isUpdatable = isUpdatable,
                indexUrl = if (isUpdatable) "https://example.invalid/index.json" else "",
                downloadUrl = if (isUpdatable) "https://example.invalid/dict.zip" else "",
            ),
            path = File(fileName),
        )
    }
}

private fun UiText?.testString(): String? =
    when (this) {
        null -> null
        is UiText.Literal -> value
        is UiText.Resource -> when (id) {
            R.string.dictionary_fetching_named_format -> "Fetching ${args[0]}"
            R.string.dictionary_checking_named_format -> "Checking ${args[0]}"
            R.string.dictionary_importing_named_format -> "Importing ${args[0]}"
            R.string.dictionary_downloading_named_format -> "Downloading ${args[0]}"
            R.string.dictionary_import_failed_list_format -> "Failed to import:\n${args[0]}"
            R.string.dictionary_update_failed_list_format -> "Failed to update:\n${args[0]}"
            else -> "resource:$id:${args.joinToString()}"
        }
        is UiText.Plural -> "plural:$id:$quantity:${args.joinToString()}"
    }

private class FakeDictionaryRepository(
    var dictionaries: Map<DictionaryType, List<DictionaryInfo>> = emptyMap(),
    settings: DictionarySettings = DictionarySettings(),
    var updatableDictionaries: List<DictionaryUpdateCandidate> = emptyList(),
    private val failedImportItems: Set<DictionaryImportItem> = emptySet(),
) : DictionaryViewModelRepository {
    private val settingsFlow = MutableStateFlow(settings)
    override val settings: StateFlow<DictionarySettings> = settingsFlow
    private val mutationStateFlow = MutableStateFlow(DictionaryMutationState())
    override val mutationState: StateFlow<DictionaryMutationState> = mutationStateFlow
    var rebuildCount = 0
    var loadDictionariesCount = 0
    var onImport: (() -> Unit)? = null
    var onMove: (suspend () -> Unit)? = null
    var onUpdate: (suspend ((DictionaryUpdateProgress) -> Unit) -> DictionaryUpdateSummary)? = null
    val enabledCalls = mutableListOf<String>()
    val deleteCalls = mutableListOf<String>()
    val moveCalls = mutableListOf<Pair<DictionaryType, Pair<Int, Int>>>()
    val importedItems = mutableListOf<DictionaryImportItem>()
    val progressMessages = mutableListOf<String?>()
    val importedRecommendedDictionaries = mutableListOf<RecommendedDictionary>()
    val recommendedProgressMessages = mutableListOf<String?>()
    var savedSettings: DictionarySettings? = null

    override suspend fun loadDictionaries(): Map<DictionaryType, List<DictionaryInfo>> {
        loadDictionariesCount += 1
        return dictionaries
    }

    override suspend fun importDictionaries(
        items: List<DictionaryImportItem>,
        onProgress: (DictionaryImportItem) -> Unit,
    ): DictionaryImportBatchResult {
        val imported = mutableListOf<DictionaryImportItem>()
        val failed = mutableListOf<DictionaryImportItem>()
        items.forEach { item ->
            importedItems += item
            onProgress(item)
            progressMessages += "Importing ${item.displayName}"
            if (item in failedImportItems) {
                failed += item
            } else {
                imported += item
                onImport?.invoke()
            }
        }
        if (imported.isNotEmpty()) {
            publishCompletedChange()
        }
        return DictionaryImportBatchResult(imported = imported, failed = failed)
    }

    override suspend fun importRecommendedDictionaries(
        dictionaries: List<RecommendedDictionary>,
        onProgress: (DictionaryUpdateProgress) -> Unit,
    ) {
        importedRecommendedDictionaries += dictionaries
        onProgress(DictionaryUpdateProgress(DictionaryUpdateStage.Fetching, dictionaries.first().name))
        recommendedProgressMessages += "Fetching ${dictionaries.first().name}"
        onProgress(DictionaryUpdateProgress(DictionaryUpdateStage.Downloading, dictionaries.last().name))
        recommendedProgressMessages += "Downloading ${dictionaries.last().name}"
        onProgress(DictionaryUpdateProgress(DictionaryUpdateStage.Importing, dictionaries.last().name))
        recommendedProgressMessages += "Importing ${dictionaries.last().name}"
        publishCompletedChange()
    }

    override suspend fun updatableDictionaries(): List<DictionaryUpdateCandidate> =
        updatableDictionaries

    override suspend fun updateDictionaries(
        onProgress: (DictionaryUpdateProgress) -> Unit,
    ): DictionaryUpdateSummary =
        onUpdate?.invoke(onProgress) ?: DictionaryUpdateSummary(
            checkedCount = updatableDictionaries.size,
            successfulCount = updatableDictionaries.size,
            updatedCount = 0,
        )

    fun publishMutationState(state: DictionaryMutationState) {
        mutationStateFlow.value = state
    }

    fun publishCompletedChange() {
        mutationStateFlow.value = mutationStateFlow.value.copy(
            operation = null,
            progress = null,
            completedChangeVersion = mutationStateFlow.value.completedChangeVersion + 1L,
        )
    }

    override suspend fun setDictionaryEnabled(type: DictionaryType, fileName: String, enabled: Boolean): Boolean {
        enabledCalls += "$fileName:$enabled"
        publishCompletedChange()
        return true
    }

    override suspend fun deleteDictionary(type: DictionaryType, fileName: String, title: String): Boolean {
        deleteCalls += fileName
        updateSettings { current ->
            current.copy(collapsedDictionaries = current.collapsedDictionaries - title)
        }
        publishCompletedChange()
        return true
    }

    override suspend fun moveDictionary(type: DictionaryType, fromIndex: Int, toIndex: Int): Boolean {
        moveCalls += type to (fromIndex to toIndex)
        onMove?.invoke()
        publishCompletedChange()
        return true
    }

    override suspend fun rebuildLookupQuery() {
        rebuildCount += 1
    }

    override suspend fun updateSettings(transform: (DictionarySettings) -> DictionarySettings) {
        val next = transform(settingsFlow.value).normalized()
        settingsFlow.value = next
        savedSettings = next
    }
}
