package moe.antimony.hoshi.features.settings

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.resolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsLoadStateTest {
    @Test
    fun combinedReadinessWaitsForBothSourcesAndDoesNotHideFailureBehindLoading() {
        val saved = SettingsLoadState.Loaded(true)
        val failure = SettingsLoadState.Failed(UiText.Resource(R.string.settings_load_failed))
        var calls = 0
        val combine: (Boolean, Boolean) -> Boolean = { a, b -> calls++; a && b }
        assertEquals(SettingsLoadState.Loading, combineSettingsLoadStates(saved, SettingsLoadState.Loading, combine))
        assertEquals(SettingsLoadState.Loading, combineSettingsLoadStates(SettingsLoadState.Loading, saved, combine))
        assertEquals(failure, combineSettingsLoadStates(SettingsLoadState.Loading, failure, combine))
        assertEquals(failure, combineSettingsLoadStates(failure, SettingsLoadState.Loading, combine))
        assertEquals(0, calls)
        assertEquals(saved, combineSettingsLoadStates(saved, saved, combine))
        assertEquals(1, calls)
    }

    @Test
    fun delayedSettingsHaveNoLoadedValueUntilEveryRequiredSourceEmits() = runTest {
        val switches = MutableSharedFlow<Boolean>()
        val profile = MutableSharedFlow<String>()
        val result = async {
            combine(switches, profile) { enabled, id -> id to enabled }.settingsLoadStates().first()
        }
        runCurrent()
        assertFalse(result.isCompleted)
        switches.emit(true)
        runCurrent()
        assertFalse(result.isCompleted)
        profile.emit("saved-profile")
        assertEquals(SettingsLoadState.Loaded("saved-profile" to true), result.await())
    }

    @Test
    fun loadFailureIsAnErrorRatherThanDefaultSettings() = runTest {
        val result = flow<Boolean> {
            throw IllegalStateException("Unable to read preferences", java.io.IOException("disk quota exceeded"))
        }.settingsLoadStates().first() as SettingsLoadState.Failed
        val message = result.message.resolve({ _, _ -> "Unable to load settings" }, { _, _, _ -> "" })
        assertTrue(message.contains("Unable to load settings"))
        assertTrue(message.contains("Unable to read preferences"))
        assertTrue(message.contains("disk quota exceeded"))
    }

    @Test
    fun loadedSettingsUpdatesNeverRevertToLoadingOrDefaults() = runTest {
        val states = flow { emit(true); emit(false); emit(true) }.settingsLoadStates().toList()
        assertEquals(listOf(SettingsLoadState.Loaded(true), SettingsLoadState.Loaded(false), SettingsLoadState.Loaded(true)), states)
    }

    @Test
    fun mappingSettingsNeverInvokesContentUntilLoaded() {
        var calls = 0
        val loading: SettingsLoadState<Boolean> = SettingsLoadState.Loading
        assertEquals(SettingsLoadState.Loading, loading.map { calls++; !it })
        assertEquals(0, calls)
        assertEquals(SettingsLoadState.Loaded(false), SettingsLoadState.Loaded(true).map { calls++; !it })
        assertEquals(1, calls)
    }

    @Test
    fun settingsContentIsReadyOnlyWhenAllRequiredValuesAreLoaded() {
        assertFalse(settingsContentReady(null))
        assertFalse(settingsContentReady("settings", null))
        assertTrue(settingsContentReady("settings"))
        assertTrue(settingsContentReady("settings", "sync"))
    }

    @Test
    fun nullableSettingsCanBeLoadedWithNullValue() {
        assertFalse(SettingsLoadState.Loading.isLoaded)
        assertTrue(SettingsLoadState.Loaded(null).isLoaded)
        assertTrue(SettingsLoadState.Loaded("settings").isLoaded)
    }
}
