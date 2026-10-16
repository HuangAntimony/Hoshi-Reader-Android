package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import moe.antimony.hoshi.features.settings.SettingsLoadState
import moe.antimony.hoshi.ui.resolve

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSettingsHostViewModelTest {
    @Test fun delayedFirstSettingsRemainLoadingAndFirstLoadedStateUsesStoredValues() = runTest {
        val firstRead = CompletableDeferred<Unit>()
        val stored = MutableStateFlow(ReaderSettings(volumeKeysTurnPages = true, fontSize = 30))
        val model = ReaderSettingsHostViewModel(flow {
            firstRead.await()
            emitAll(stored)
        }, { edit -> stored.value = edit(stored.value) }, backgroundScope)
        runCurrent()
        assertNull(model.uiState.value.settings)
        assertEquals(SettingsLoadState.Loading, model.uiState.value.loadState)
        model.update { it.copy(fontSize = 50) }
        runCurrent()
        assertEquals(30, stored.value.fontSize)
        firstRead.complete(Unit)
        runCurrent()
        val firstVisible = model.uiState.value.loadState as SettingsLoadState.Loaded
        assertTrue(firstVisible.value.volumeKeysTurnPages)
        assertEquals(30, firstVisible.value.fontSize)
        stored.value = stored.value.copy(fontSize = 32)
        runCurrent()
        assertEquals(32, (model.uiState.value.loadState as SettingsLoadState.Loaded).value.fontSize)
        assertEquals(stored.value, model.uiState.value.settings)
    }

    @Test fun staleCallbackCannotRevertAnUnrelatedConfirmedChange() = runTest {
        val stored = MutableStateFlow(ReaderSettings())
        val model = ReaderSettingsHostViewModel(stored, { edit -> stored.value = edit(stored.value) }, backgroundScope)
        runCurrent()
        model.update { it.copy(volumeKeysTurnPages = true) }
        runCurrent()
        model.update { it.copy(keepScreenOnWhileReading = true) }
        runCurrent()
        assertTrue(stored.value.volumeKeysTurnPages)
        assertTrue(stored.value.keepScreenOnWhileReading)
    }

    @Test fun rapidIncrementsAccumulateWhileStorageIsDelayed() = runTest {
        val stored = MutableStateFlow(ReaderSettings(fontSize = 22))
        val model = ReaderSettingsHostViewModel(stored, { edit ->
            delay(100)
            stored.value = edit(stored.value)
        }, backgroundScope)
        runCurrent()
        repeat(2) { model.update { it.copy(fontSize = it.fontSize + 1) } }
        advanceTimeBy(201)
        runCurrent()
        assertEquals(24, stored.value.fontSize)
    }

    @Test fun separateActivityHostsObserveTheSameDisplayChanges() = runTest {
        val stored = MutableStateFlow(ReaderSettings())
        val main = ReaderSettingsHostViewModel(stored, {}, backgroundScope)
        val processText = ReaderSettingsHostViewModel(stored, {}, backgroundScope)
        runCurrent()
        stored.value = ReaderSettings(
            displaySettings = moe.antimony.hoshi.features.display.AppDisplaySettings(
                autoSwitch = false,
                manualPaletteSlot = moe.antimony.hoshi.features.display.DisplayPaletteSlot.Dark,
                darkPalette = moe.antimony.hoshi.features.display.DisplayPaletteSelection(
                    moe.antimony.hoshi.features.display.DisplayPalettePreset.DarkSepia,
                ),
            ),
        )
        runCurrent()
        assertTrue(main.uiState.value.settings!!.usesDarkInterface(false))
        assertEquals(main.uiState.value.settings, processText.uiState.value.settings)
    }

    @Test fun queuedEditsPreserveIndependentSettingsAfterDelayedPersistence() = runTest {
        val stored = MutableStateFlow(ReaderSettings())
        val model = ReaderSettingsHostViewModel(stored, { edit ->
            delay(100)
            stored.value = edit(stored.value)
        }, backgroundScope)
        runCurrent()
        model.update { it.copy(volumeKeysTurnPages = true) }
        model.update { it.copy(keepScreenOnWhileReading = true) }
        advanceTimeBy(201)
        runCurrent()
        assertTrue(stored.value.volumeKeysTurnPages)
        assertTrue(stored.value.keepScreenOnWhileReading)
    }

    @Test fun failedInitialMigrationRemainsUnloadedUntilRetrySucceeds() = runTest {
        var failed = true
        val model = ReaderSettingsHostViewModel(
            flow { if (failed) throw IOException("reader settings invalid JSON") else emit(ReaderSettings(fontSize = 30)) },
            {}, backgroundScope,
        )
        runCurrent()
        assertNull(model.uiState.value.settings)
        assertTrue(model.uiState.value.loadState is SettingsLoadState.Failed)
        assertNotNull(model.uiState.value.error)
        assertTrue(requireNotNull(model.uiState.value.error).resolve({ _, _ -> "Load failed" }, { _, _, _ -> "" })
            .contains("reader settings invalid JSON"))
        failed = false
        model.retry()
        assertEquals(SettingsLoadState.Loading, model.uiState.value.loadState)
        runCurrent()
        assertEquals(30, model.uiState.value.settings?.fontSize)
        assertNull(model.uiState.value.error)
        assertEquals(30, (model.uiState.value.loadState as SettingsLoadState.Loaded).value.fontSize)
    }

    @Test fun profileChangesRetainConfirmedSettingsForNavigationWhileLoadingNewControls() = runTest {
        val profiles = MutableStateFlow("first")
        val first = MutableStateFlow(ReaderSettings(fontSize = 30, volumeKeysTurnPages = true))
        val nextRead = CompletableDeferred<Unit>()
        var saves = 0
        val source = flow {
            if (profiles.value == "first") emitAll(first) else {
                nextRead.await()
                emit(ReaderSettings(fontSize = 26, keepScreenOnWhileReading = true))
            }
        }
        val model = ReaderSettingsHostViewModel(source, { saves++ }, backgroundScope, profiles)
        runCurrent()
        val confirmed = model.uiState.value.settings
        assertEquals(30, (model.uiState.value.loadState as SettingsLoadState.Loaded).value.fontSize)
        profiles.value = "second"
        runCurrent()
        assertSame(confirmed, model.uiState.value.settings)
        assertEquals(SettingsLoadState.Loading, model.uiState.value.loadState)
        model.update { it.copy(fontSize = 50) }
        first.value = first.value.copy(fontSize = 45)
        runCurrent()
        assertEquals(0, saves)
        assertSame(confirmed, model.uiState.value.settings)
        nextRead.complete(Unit)
        runCurrent()
        assertEquals(26, (model.uiState.value.loadState as SettingsLoadState.Loaded).value.fontSize)
        assertTrue(model.uiState.value.settings!!.keepScreenOnWhileReading)
    }

    @Test fun aLateOldProfileReadCannotReplaceTheNewProfileSettings() = runTest {
        val profiles = MutableStateFlow("first")
        val finishOldRead = CompletableDeferred<Unit>()
        val source = flow {
            if (profiles.value == "first") {
                emit(ReaderSettings(fontSize = 30))
                withContext(NonCancellable) { finishOldRead.await() }
                emit(ReaderSettings(fontSize = 45))
            } else {
                emit(ReaderSettings(fontSize = 26))
            }
        }
        val model = ReaderSettingsHostViewModel(source, {}, backgroundScope, profiles)
        try {
            runCurrent()
            profiles.value = "second"
            runCurrent()
            assertEquals(26, model.uiState.value.settings?.fontSize)
            finishOldRead.complete(Unit)
            runCurrent()
            assertEquals(26, (model.uiState.value.loadState as SettingsLoadState.Loaded).value.fontSize)
            assertEquals(26, model.uiState.value.settings?.fontSize)
        } finally {
            finishOldRead.complete(Unit)
        }
    }

    @Test fun failedNewProfileReadsKeepTheAppSettingsAndRetryOnlyTheControls() = runTest {
        val profiles = MutableStateFlow("first")
        var failNext = true
        val retryRead = CompletableDeferred<Unit>()
        val source = flow {
            if (profiles.value == "first") emit(ReaderSettings(fontSize = 30)) else {
                if (failNext) throw IOException()
                retryRead.await()
                emit(ReaderSettings(fontSize = 26))
            }
        }
        val model = ReaderSettingsHostViewModel(source, {}, backgroundScope, profiles)
        runCurrent()
        val confirmed = model.uiState.value.settings
        profiles.value = "second"
        runCurrent()
        assertSame(confirmed, model.uiState.value.settings)
        assertTrue(model.uiState.value.loadState is SettingsLoadState.Failed)
        assertNotNull(model.uiState.value.error)
        failNext = false
        model.retry()
        assertSame(confirmed, model.uiState.value.settings)
        assertEquals(SettingsLoadState.Loading, model.uiState.value.loadState)
        runCurrent()
        retryRead.complete(Unit)
        runCurrent()
        assertEquals(26, (model.uiState.value.loadState as SettingsLoadState.Loaded).value.fontSize)
        assertNull(model.uiState.value.error)
    }

    @Test fun delayedEditsFromThePreviousProfileCannotMutateTheNewProfile() = runTest {
        val profiles = MutableStateFlow("first")
        val first = MutableStateFlow(ReaderSettings(fontSize = 30))
        val second = MutableStateFlow(ReaderSettings(fontSize = 26))
        val saveReady = CompletableDeferred<Unit>()
        val source: Flow<ReaderSettings> = flow { emitAll(if (profiles.value == "first") first else second) }
        val model = ReaderSettingsHostViewModel(source, { edit ->
            saveReady.await()
            val target = if (profiles.value == "first") first else second
            target.value = edit(target.value)
        }, backgroundScope, profiles)
        runCurrent()
        model.update { it.copy(fontSize = 50) }
        runCurrent()
        profiles.value = "second"
        runCurrent()
        saveReady.complete(Unit)
        runCurrent()
        assertEquals(26, second.value.fontSize)
        assertEquals(30, first.value.fontSize)
        assertEquals(26, (model.uiState.value.loadState as SettingsLoadState.Loaded).value.fontSize)
    }

    @Test fun failedWriteKeepsLastConfirmedSettings() = runTest {
        val stored = MutableStateFlow(ReaderSettings(fontSize = 24))
        val model = ReaderSettingsHostViewModel(stored, { throw IOException("reader settings write failed", IOException("permission denied")) }, backgroundScope)
        runCurrent()
        model.update { it.copy(fontSize = 32) }
        runCurrent()
        assertEquals(24, model.uiState.value.settings?.fontSize)
        assertNotNull(model.uiState.value.error)
        val message = requireNotNull(model.uiState.value.error).resolve({ _, _ -> "Save failed" }, { _, _, _ -> "" })
        assertTrue(message.contains("reader settings write failed"))
        assertTrue(message.contains("permission denied"))
    }
}
