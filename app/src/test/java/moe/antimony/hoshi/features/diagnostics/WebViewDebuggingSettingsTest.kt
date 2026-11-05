package moe.antimony.hoshi.features.diagnostics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.features.settings.SettingsLoadState
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class WebViewDebuggingSettingsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun releaseDefaultsToOffWithoutInitializingWebView() = runTest {
        val job = Job()
        try {
            val store = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(job + StandardTestDispatcher(testScheduler)),
                produceFile = { temporary.newFile("default.preferences_pb") },
            )
            val settings = WebViewDebuggingSettings(store, StandardTestDispatcher(testScheduler), false) {
                fail("Default-off startup must not initialize WebView")
            }
            assertEquals(SettingsLoadState.Loading, settings.state.value)
            settings.initialize()
            assertEquals(SettingsLoadState.Loaded(false), settings.state.value)
        } finally { job.cancelAndJoin() }
    }

    @Test fun togglesApplyImmediatelyAndSurviveNewSettingsInstances() = runTest {
        val job = Job()
        try {
            val store = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(job + StandardTestDispatcher(testScheduler)),
                produceFile = { temporary.newFile("persisted.preferences_pb") },
            )
            var runtimeEnabled = false
            val settings = WebViewDebuggingSettings(store, StandardTestDispatcher(testScheduler), false) { runtimeEnabled = it }
            settings.initialize()
            settings.setEnabled(true)
            assertTrue(runtimeEnabled)
            assertEquals(SettingsLoadState.Loaded(true), settings.state.value)

            runtimeEnabled = false
            val restored = WebViewDebuggingSettings(store, StandardTestDispatcher(testScheduler), false) { runtimeEnabled = it }
            restored.initialize()
            assertTrue(runtimeEnabled)
            assertEquals(SettingsLoadState.Loaded(true), restored.state.value)
            restored.setEnabled(false)
            assertFalse(runtimeEnabled)
            val reopened = WebViewDebuggingSettings(store, StandardTestDispatcher(testScheduler), false) { runtimeEnabled = it }
            reopened.initialize()
            assertEquals(SettingsLoadState.Loaded(false), reopened.state.value)
        } finally { job.cancelAndJoin() }
    }

    @Test fun failedSaveRestoresRuntimeAndKeepsConfirmedChoice() = runTest {
        val job = Job()
        try {
            val store = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(job + StandardTestDispatcher(testScheduler)),
                produceFile = { temporary.newFile("failed.preferences_pb") },
            )
            var failWrite = true
            val failure = IOException("Storage is full")
            val failingStore = object : DataStore<Preferences> by store {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    if (failWrite) throw failure
                    return store.updateData(transform)
                }
            }
            var runtimeEnabled = false
            val settings = WebViewDebuggingSettings(failingStore, StandardTestDispatcher(testScheduler), false) { runtimeEnabled = it }
            settings.initialize()
            val reported = runCatching { settings.setEnabled(true) }.exceptionOrNull()
            assertTrue(reported is IOException)
            assertTrue(generateSequence(reported) { it.cause }.any { it === failure })
            assertFalse(runtimeEnabled)
            assertEquals(SettingsLoadState.Loaded(false), settings.state.value)
            failWrite = false
            settings.setEnabled(true)
            assertTrue(runtimeEnabled)
        } finally { job.cancelAndJoin() }
    }

    @Test fun debugBuildKeepsAutomaticDebuggingUntilExplicitlyDisabled() = runTest {
        val job = Job()
        try {
            val store = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(job + StandardTestDispatcher(testScheduler)),
                produceFile = { temporary.newFile("debug.preferences_pb") },
            )
            var runtimeEnabled = false
            val settings = WebViewDebuggingSettings(store, StandardTestDispatcher(testScheduler), true) { runtimeEnabled = it }
            settings.initialize()
            assertTrue(runtimeEnabled)
            settings.setEnabled(false)
            assertFalse(runtimeEnabled)
            runtimeEnabled = true // A new debug process starts with automatic debugging.
            val restored = WebViewDebuggingSettings(store, StandardTestDispatcher(testScheduler), true) { runtimeEnabled = it }
            restored.initialize()
            assertFalse(runtimeEnabled)
            assertEquals(SettingsLoadState.Loaded(false), restored.state.value)
        } finally { job.cancelAndJoin() }
    }

    @Test fun failedLoadCanRetryWithoutPublishingAnUnconfirmedDefault() = runTest {
        val job = Job()
        ErrorDiagnosticEvents.buffer.drain()
        try {
            val store = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(job + StandardTestDispatcher(testScheduler)),
                produceFile = { temporary.newFile("retry.preferences_pb") },
            )
            var failRead = true
            val failure = IOException("Cannot read diagnostic preferences")
            val failingStore = object : DataStore<Preferences> by store {
                override val data: Flow<Preferences>
                    get() = if (failRead) flow { throw failure } else store.data
            }
            val settings = WebViewDebuggingSettings(failingStore, StandardTestDispatcher(testScheduler), false) {
                fail("No enabled preference was read")
            }
            settings.initialize()
            assertTrue(settings.state.value is SettingsLoadState.Failed)
            assertSame(failure, ErrorDiagnosticEvents.buffer.drain().single().error)
            failRead = false
            settings.initialize()
            assertEquals(SettingsLoadState.Loaded(false), settings.state.value)
        } finally {
            job.cancelAndJoin()
            ErrorDiagnosticEvents.buffer.drain()
        }
    }

    @Test fun leavingScreenAfterCommitKeepsRuntimeAndPublishedChoiceConsistentWithDisk() = runTest {
        val job = Job()
        val committed = CompletableDeferred<Unit>()
        val acknowledge = CompletableDeferred<Unit>()
        try {
            val store = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(job + StandardTestDispatcher(testScheduler)),
                produceFile = { temporary.newFile("cancel-after-commit.preferences_pb") },
            )
            val delayedStore = object : DataStore<Preferences> by store {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    val result = store.updateData(transform)
                    committed.complete(Unit)
                    acknowledge.await()
                    return result
                }
            }
            var runtimeEnabled = false
            val settings = WebViewDebuggingSettings(delayedStore, StandardTestDispatcher(testScheduler), false) { runtimeEnabled = it }
            settings.initialize()
            val save = launch { settings.setEnabled(true) }
            committed.await()
            save.cancel()
            acknowledge.complete(Unit)
            save.join()
            assertTrue(runtimeEnabled)
            assertEquals(SettingsLoadState.Loaded(true), settings.state.value)
            val restored = WebViewDebuggingSettings(store, StandardTestDispatcher(testScheduler), false) { runtimeEnabled = it }
            restored.initialize()
            assertEquals(settings.state.value, restored.state.value)
        } finally {
            acknowledge.complete(Unit)
            job.cancelAndJoin()
        }
    }
}
