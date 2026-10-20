package moe.antimony.hoshi.features.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.reader.ReaderAppearanceScreen
import moe.antimony.hoshi.features.reader.ReaderAppearanceSheet
import moe.antimony.hoshi.features.reader.ReaderAppearanceViewModel
import moe.antimony.hoshi.features.reader.ReaderFontManager
import moe.antimony.hoshi.features.reader.ReaderFontDownloaderFactory
import moe.antimony.hoshi.features.reader.HttpReaderFontRemoteDataSource
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder

/** Delayed in-memory settings only; does not read/write installed app preferences. */
@RunWith(AndroidJUnit4::class)
class SettingsReadinessTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun appearanceScreenWaitsForBothReaderAndPlaybackPreferences() = delayedAppearanceInputs(sheet = false)

    @Test fun appearanceSheetWaitsForPlaybackPreferences() = delayedAppearanceInputs(sheet = true)

    private fun delayedAppearanceInputs(sheet: Boolean) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fonts = ReaderFontManager(temporary.root, scope, Dispatchers.IO)
        val model = ReaderAppearanceViewModel(fonts, ReaderFontDownloaderFactory(HttpReaderFontRemoteDataSource(), Dispatchers.IO), Dispatchers.IO)
        val models = ViewModelStore().apply { put("fixture", model) }
        val reader = mutableStateOf<SettingsLoadState<ReaderSettings>>(SettingsLoadState.Loading)
        val playback = mutableStateOf<SettingsLoadState<SasayakiSettings>>(SettingsLoadState.Loading)
        val savedReader = ReaderSettings(verticalWriting = true)
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.reader_appearance_vertical)
        try {
            compose.setContent {
                MaterialTheme {
                    if (sheet) {
                        ReaderAppearanceSheet(savedReader, "Fixture", onSettingsChange = {},
                            sasayakiSettings = playback.value, onRetrySasayakiSettings = {}, onSasayakiSettingsChange = {},
                            fontManager = fonts, onDismiss = {}, viewModel = model)
                    } else {
                        ReaderAppearanceScreen(reader.value, onRetrySettings = {}, profileName = "Fixture", onSettingsChange = {},
                            sasayakiSettings = playback.value, onRetrySasayakiSettings = {}, onSasayakiSettingsChange = {},
                            fontManager = fonts, onClose = {}, viewModel = model)
                    }
                }
            }
            compose.onNodeWithText(label).assertDoesNotExist()
            compose.runOnIdle { reader.value = SettingsLoadState.Loaded(savedReader) }
            compose.onNodeWithText(label).assertDoesNotExist()
            compose.runOnIdle { playback.value = SettingsLoadState.Loaded(SasayakiSettings(showReaderToggle = false)) }
            compose.onNodeWithText(label).assertExists()
        } finally {
            compose.runOnUiThread { models.clear() }
            scope.cancel()
        }
    }

    @Test
    fun enteringAndReenteringNeverShowsDefaultSwitchBeforeStorageResponds() {
        val stored = MutableSharedFlow<Boolean>()
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    val loader = stored.rememberSettingsLoader()
                    SettingsDetailScaffold(state = loader.state, title = "Settings fixture", onClose = {}, onRetry = loader.retry) { _, saved ->
                        Switch(saved, {}, Modifier.testTag("saved-switch"))
                    }
                }
            }
        }
        compose.onNodeWithTag("saved-switch").assertDoesNotExist()
        runBlocking { stored.emit(true) }
        compose.onNodeWithTag("saved-switch").assertIsOn()
        runBlocking { stored.emit(false) }
        compose.onNodeWithTag("saved-switch").assertIsOff()
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithTag("saved-switch").assertDoesNotExist()
        runBlocking { stored.emit(true) }
        compose.onNodeWithTag("saved-switch").assertIsOn()
    }

    @Test
    fun replacingProfileSourceHidesOldProfileUntilNewSettingsArrive() {
        val first = MutableSharedFlow<Boolean>()
        val next = MutableSharedFlow<Boolean>()
        val source = mutableStateOf<Flow<Boolean>>(first)
        compose.setContent {
            MaterialTheme {
                val loader = source.value.rememberSettingsLoader()
                SettingsContent(loader.state, onRetry = loader.retry) { saved ->
                    Switch(saved, {}, Modifier.testTag("profile-switch"))
                }
            }
        }
        runBlocking { first.emit(true) }
        compose.onNodeWithTag("profile-switch").assertIsOn()
        compose.runOnIdle { source.value = next }
        compose.onNodeWithTag("profile-switch").assertDoesNotExist()
        runBlocking { first.emit(true) }
        compose.onNodeWithTag("profile-switch").assertDoesNotExist()
        runBlocking { next.emit(false) }
        compose.onNodeWithTag("profile-switch").assertIsOff()
    }

    @Test
    fun failedStorageReadShowsLocalizedRetryAndThenActualValue() {
        var unavailable = true
        val source = flow {
            if (unavailable) error("fixture storage failure")
            emit(true)
        }
        compose.setContent {
            MaterialTheme {
                val loader = source.rememberSettingsLoader()
                SettingsContent(loader.state, onRetry = loader.retry) { saved ->
                    Switch(saved, {}, Modifier.testTag("retry-switch"))
                }
            }
        }
        compose.onNodeWithTag("retry-switch").assertDoesNotExist()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.settings_load_failed)).assertExists()
        compose.runOnIdle { unavailable = false }
        compose.onNodeWithText(context.getString(R.string.settings_retry)).performClick()
        compose.onNodeWithTag("retry-switch").assertIsOn()
    }

    @Test
    fun successfullyLoadedNullIsContentRatherThanLoading() {
        compose.setContent {
            SettingsContent(SettingsLoadState.Loaded<String?>(null)) { value ->
                if (value == null) Text("No record", Modifier.testTag("empty-record"))
            }
        }
        compose.onNodeWithTag("empty-record").assertExists()
    }
}
