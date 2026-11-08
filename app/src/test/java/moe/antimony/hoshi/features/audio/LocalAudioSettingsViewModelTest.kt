package moe.antimony.hoshi.features.audio

import android.net.Uri
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.SettingsLoadState
import moe.antimony.hoshi.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalAudioSettingsViewModelTest {
    @Test
    fun unavailableLinkKeepsItsIdentityAndCanBeUnlinked() = runTest {
        val actions = FakeActions().apply {
            loadError = IOException("Read access revoked")
            retained = true
        }
        withViewModel(actions) { model ->
            runCurrent()
            val unavailable = model.state.value
            assertEquals(SettingsLoadState.Loaded(linkedDatabase), unavailable.database)
            assertNull(unavailable.source.config)
            assertEquals(
                UiText.Joined(
                    listOf(UiText.Resource(R.string.audio_local_database_read_failed), UiText.Literal("Read access revoked")),
                ),
                unavailable.readError,
            )

            model.deleteDatabase()
            runCurrent()
            assertEquals(SettingsLoadState.Loaded(null), model.state.value.database)
            assertNull(model.state.value.readError)
            assertTrue(model.state.value.hasRetainedPrivateDatabase)
        }
    }

    @Test
    fun reloadClearsStaleSourceControlsWhenLinkedFileBecomesUnavailable() = runTest {
        val actions = FakeActions()
        withViewModel(actions) { model ->
            runCurrent()
            assertEquals(config, model.state.value.source.config)

            actions.loadError = IOException("Document missing")
            model.refresh()
            runCurrent()
            assertEquals(SettingsLoadState.Loaded(linkedDatabase), model.state.value.database)
            assertNull(model.state.value.source.config)
            assertNotNull(model.state.value.readError)

            actions.loadError = null
            model.refresh()
            runCurrent()
            assertEquals(config, model.state.value.source.config)
            assertNull(model.state.value.readError)
        }
    }

    @Test
    fun failedSourceWritePreservesConfigAndBlocksAnotherMutationWhilePending() = runTest {
        val actions = FakeActions()
        withViewModel(actions) { model ->
            runCurrent()
            val write = CompletableDeferred<LocalAudioSourceConfig>()
            actions.nextWrite = write
            model.moveSource("forvo", -1)
            runCurrent()
            assertTrue(model.state.value.isBusy)

            model.deleteDatabase()
            write.completeExceptionally(IOException("Disk full"))
            runCurrent()
            val failed = model.state.value
            assertFalse(failed.isBusy)
            assertEquals(SettingsLoadState.Loaded(linkedDatabase), failed.database)
            assertEquals(config, failed.source.config)
            assertNotNull(failed.source.errorMessage)
        }
    }

    @Test
    fun removeRetainedCopyLeavesActiveLinkAndItsSourceOrder() = runTest {
        val actions = FakeActions().apply { retained = true }
        withViewModel(actions) { model ->
            runCurrent()
            model.deleteRetainedPrivateDatabase()
            runCurrent()

            assertFalse(model.state.value.hasRetainedPrivateDatabase)
            assertEquals(SettingsLoadState.Loaded(linkedDatabase), model.state.value.database)
            assertEquals(config, model.state.value.source.config)
        }
    }

    @Test
    fun failedPrivateDeleteReflectsUnboundDatabaseAndRetainedCopy() = runTest {
        val actions = FakeActions(selected = linkedDatabase.copy(isExternal = false)).apply {
            deleteError = IOException("Copy could not be deleted")
        }
        withViewModel(actions) { model ->
            runCurrent()
            model.deleteDatabase()
            runCurrent()

            val failed = model.state.value
            assertEquals(SettingsLoadState.Loaded(null), failed.database)
            assertTrue(failed.hasRetainedPrivateDatabase)
            assertNull(failed.source.config)
            assertNotNull(failed.operationError)
        }
    }

    private suspend fun TestScope.withViewModel(
        actions: FakeActions,
        block: (LocalAudioSettingsViewModel) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = LocalAudioSettingsViewModel(actions)
        try {
            block(model)
        } finally {
            model.viewModelScope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    private class FakeActions(private var selected: LocalAudioDatabaseInfo? = linkedDatabase) : LocalAudioSettingsActions {
        var loadError: IOException? = null
        var deleteError: IOException? = null
        var retained = false
        var nextWrite: CompletableDeferred<LocalAudioSourceConfig>? = null

        override suspend fun databaseInfo() = selected

        override suspend fun ensureSourceConfig(): LocalAudioSourceConfig {
            loadError?.let { throw it }
            return config
        }

        override suspend fun importDatabase(
            uri: Uri,
            mode: LocalAudioImportMode,
            onProgress: (LocalAudioImportProgress) -> Unit,
        ): LocalAudioDatabaseInfo = error("No document picker in this fixture")

        override suspend fun deleteDatabase() {
            selected = null
            deleteError?.let {
                retained = true
                throw it
            }
        }

        override suspend fun updateSourceOrder(sourceOrder: List<String>) =
            nextWrite?.await() ?: config.copy(sourceOrder = sourceOrder)

        override suspend fun updateSourceEnabled(source: String, enabled: Boolean) =
            config.copy(disabledSources = if (enabled) emptySet() else setOf(source))

        override suspend fun hasRetainedPrivateDatabase() = retained

        override suspend fun deleteRetainedPrivateDatabase() {
            retained = false
        }
    }

    companion object {
        private val linkedDatabase = LocalAudioDatabaseInfo(
            displayName = "android.db",
            sizeBytes = 8192,
            isExternal = true,
            generation = "linked",
        )
        private val config = LocalAudioSourceConfig(sourceOrder = listOf("nhk16", "forvo"))
    }
}
