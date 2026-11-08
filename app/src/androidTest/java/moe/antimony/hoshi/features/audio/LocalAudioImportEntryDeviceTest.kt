package moe.antimony.hoshi.features.audio

import android.graphics.Bitmap
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.SettingsLoadState
import moe.antimony.hoshi.features.settings.GroupCard
import moe.antimony.hoshi.features.settings.SectionTitle
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Renders the real database controls with a scratch ViewModel; never accesses installed settings. */
@RunWith(AndroidJUnit4::class)
class LocalAudioImportEntryDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun noDatabaseShowsImport() {
        withSettings(Actions(isExternal = false, hasDatabase = false)) { _, selections ->
            node(R.string.action_import).assertIsDisplayed()
            screenshot("empty")
            node(R.string.action_import).performClick()
            compose.runOnIdle { assertEquals(1, selections()) }
        }
    }

    @Test fun privateCopyHidesImportUntilDeleted() = verifyRemoval(isExternal = false)

    @Test fun validLinkHidesImportUntilUnlinked() = verifyRemoval(isExternal = true)

    @Test fun unavailableLinkUsesImportAndRecoveryHidesIt() {
        val actions = Actions(isExternal = true).apply { readFailure = IOException("所选文件不可用。") }
        withSettings(actions) { model, selections ->
            node(R.string.audio_unlink_database).assertDoesNotExist()
            compose.onNodeWithText("android.db").assertDoesNotExist()
            node(R.string.audio_local_database_read_failed, substring = true).assertIsDisplayed()
            node(R.string.action_import).assertIsDisplayed()
            screenshot("unavailable-link")
            node(R.string.action_import).performClick()
            compose.runOnIdle { assertEquals(1, selections()) }

            actions.readFailure = null
            compose.runOnIdle { model.refresh() }
            awaitIdle(model)
            assertNoImportEntry()
            node(R.string.audio_unlink_database).assertIsDisplayed()
            screenshot("recovered-link")
        }
    }

    @Test fun sourceSettingFailureDoesNotExposeImportForHealthyLink() {
        withSettings(Actions(isExternal = true)) { model, _ ->
            compose.runOnIdle { model.setSourceEnabled("nhk16", false) }
            awaitIdle(model)
            assertNoImportEntry()
            node(R.string.audio_unlink_database).assertIsDisplayed()
            screenshot("source-save-failure")
        }
    }

    private fun verifyRemoval(isExternal: Boolean) {
        withSettings(Actions(isExternal)) { model, selections ->
            assertNoImportEntry()
            screenshot(if (isExternal) "linked" else "copied")
            node(if (isExternal) R.string.audio_unlink_database else R.string.action_delete).performClick()
            awaitIdle(model)
            node(R.string.action_import).assertIsDisplayed()
            screenshot(if (isExternal) "after-unlink" else "after-delete")
            node(R.string.action_import).performClick()
            compose.runOnIdle { assertEquals(1, selections()) }
        }
    }

    private fun withSettings(actions: Actions, check: (LocalAudioSettingsViewModel, () -> Int) -> Unit) {
        val model = LocalAudioSettingsViewModel(actions)
        var selections = 0
        try {
            compose.setContent {
                val state by model.state.collectAsState()
                HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                    Column(
                        Modifier.fillMaxWidth().testTag("local-audio-section")
                            .background(MaterialTheme.colorScheme.background).padding(16.dp),
                    ) {
                        SectionTitle(context.getString(R.string.audio_local_audio))
                        GroupCard {
                            if (state.database is SettingsLoadState.Loaded) {
                                LocalAudioDatabaseSettings(
                                    database = state.databaseInfo, state = state,
                                    onSelect = { selections++ }, onDelete = model::deleteDatabase,
                                    onDeleteRetainedCopy = model::deleteRetainedPrivateDatabase,
                                    onMoveSource = model::moveSource, onSourceEnabled = model::setSourceEnabled,
                                )
                            }
                        }
                    }
                }
            }
            awaitIdle(model)
            check(model) { selections }
        } finally {
            runBlocking { model.viewModelScope.coroutineContext.job.cancelAndJoin() }
        }
    }

    private fun awaitIdle(model: LocalAudioSettingsViewModel) {
        compose.waitUntil(5_000) { !model.state.value.isBusy }
        compose.waitForIdle()
    }

    private fun assertNoImportEntry() {
        node(R.string.audio_import_android_db).assertDoesNotExist()
        node(R.string.action_import).assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        val bitmap = compose.onNodeWithTag("local-audio-section").captureToImage().asAndroidBitmap()
        context.cacheDir.resolve("local-audio-entry-$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun node(resource: Int, substring: Boolean = false) = compose.onNodeWithText(context.getString(resource), substring = substring)

    private class Actions(isExternal: Boolean, hasDatabase: Boolean = true) : LocalAudioSettingsActions {
        private var database: LocalAudioDatabaseInfo? = LocalAudioDatabaseInfo("android.db", 6218461184, isExternal, "fixture").takeIf { hasDatabase }
        var readFailure: IOException? = null
        override suspend fun databaseInfo() = database
        override suspend fun ensureSourceConfig(): LocalAudioSourceConfig {
            readFailure?.let { throw it }
            return LocalAudioSourceConfig(sourceOrder = listOf("nhk16"))
        }
        override suspend fun hasRetainedPrivateDatabase() = false
        override suspend fun deleteDatabase() { database = null }
        override suspend fun deleteRetainedPrivateDatabase() = error("No retained copy")
        override suspend fun importDatabase(uri: Uri, mode: LocalAudioImportMode, onProgress: (LocalAudioImportProgress) -> Unit) =
            error("No document picker in this fixture")
        override suspend fun updateSourceOrder(sourceOrder: List<String>) = error("No source reordering in this fixture")
        override suspend fun updateSourceEnabled(source: String, enabled: Boolean): LocalAudioSourceConfig =
            throw IOException("暂时无法保存设置。")
    }
}
