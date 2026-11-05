package moe.antimony.hoshi.features.settings

import android.app.LocaleManager
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.sasayaki.sasayakiServiceMediaButtons
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.resolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue

class AppLanguageDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun pickerRecreatesTheHostAndBackgroundTextUsesTheSelectedLanguage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(AppLanguageFixtureActivity::class.java).use { scenario ->
            val original = AppCompatDelegate.getApplicationLocales()
            try {
                var initialLabel = ""
                var languageLabel = ""
                scenario.onActivity {
                    languageLabel = it.getString(R.string.settings_language)
                    initialLabel = when (currentAppLanguageMode()) {
                        AppLanguageMode.System -> it.getString(R.string.language_follow_system)
                        AppLanguageMode.English -> "English"
                        AppLanguageMode.SimplifiedChinese -> "简体中文"
                    }
                }
                waitForLanguage(languageLabel)
                if (initialLabel != "English") {
                    compose.onNodeWithText(initialLabel).performClick()
                    compose.onNodeWithText("English").performClick()
                }
                waitForLanguage("Language")
                var englishHost: AppLanguageFixtureActivity? = null
                scenario.onActivity { englishHost = it }
                compose.onNodeWithText("English").performClick()
                compose.onNodeWithText("简体中文").performClick()
                waitForLanguage("语言")
                var chineseHost: AppLanguageFixtureActivity? = null
                scenario.onActivity {
                    chineseHost = it
                    assertNotSame(englishHost, it)
                    assertEquals(AppLanguageMode.SimplifiedChinese, currentAppLanguageMode())
                    setAppLanguageMode(AppLanguageMode.SimplifiedChinese)
                }
                compose.waitForIdle()
                scenario.onActivity { assertSame("Selecting the current language must not recreate the host.", chineseHost, it) }
                assertEquals("正在下载更新", UiText.Resource(R.string.update_downloading_notification).resolve(context))
                assertEquals(listOf("有声书后退", "有声书快进"), sasayakiServiceMediaButtons(context).map { it.displayName.toString() })

                scenario.onActivity { setAppLanguageMode(AppLanguageMode.English) }
                waitForLanguage("Language")
                assertEquals("Downloading update", UiText.Resource(R.string.update_downloading_notification).resolve(context))
                scenario.onActivity { setAppLanguageMode(AppLanguageMode.System) }
                compose.waitUntil(10_000) { AppCompatDelegate.getApplicationLocales().isEmpty }
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { AppCompatDelegate.setApplicationLocales(original) }
                compose.waitForIdle()
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun frameworkLanguageChangesAndThePickerShareTheSamePreference() {
        ActivityScenario.launch(AppLanguageFixtureActivity::class.java).use { scenario ->
            val original = AppCompatDelegate.getApplicationLocales()
            try {
                scenario.moveToState(Lifecycle.State.STARTED)
                scenario.onActivity {
                    it.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("zh-CN")
                }
                scenario.moveToState(Lifecycle.State.RESUMED)
                waitForLanguage("语言")
                scenario.onActivity { assertEquals(AppLanguageMode.SimplifiedChinese, currentAppLanguageMode()) }
                compose.onNodeWithText("简体中文").performClick()
                compose.onNodeWithText("English").performClick()
                waitForLanguage("Language")
                scenario.onActivity {
                    assertEquals("en-US", it.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags())
                }
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { AppCompatDelegate.setApplicationLocales(original) }
                compose.waitForIdle()
            }
        }
    }

    @Test
    @SdkSuppress(maxSdkVersion = 32)
    fun matchingSystemLanguageUpdatesTheSelectionWithoutRecreatingTheHost() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(LocaleManagerCompat.getSystemLocales(context)[0]?.toLanguageTag() == "en-US")
        ActivityScenario.launch(AppLanguageFixtureActivity::class.java).use { scenario ->
            val original = AppCompatDelegate.getApplicationLocales()
            try {
                scenario.onActivity { setAppLanguageMode(AppLanguageMode.System) }
                waitForLanguage("Language")
                var host: AppLanguageFixtureActivity? = null
                scenario.onActivity { host = it }
                compose.onNodeWithText("Follow system").performClick()
                compose.onNodeWithText("English").performClick()
                compose.onNodeWithText("English").assertExists()
                scenario.onActivity { assertSame(host, it) }
                compose.onNodeWithText("English").performClick()
                compose.onNodeWithText("Follow system").performClick()
                compose.onNodeWithText("Follow system").assertExists()
                scenario.onActivity { assertSame(host, it) }
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { AppCompatDelegate.setApplicationLocales(original) }
                compose.waitForIdle()
            }
        }
    }

    private fun waitForLanguage(label: String) {
        compose.waitUntil(10_000) {
            // The language row is the last item in the actual Advanced settings page.
            runCatching {
                compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
                compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onAllNodesWithText(label).onFirst().assertExists()
    }
}
