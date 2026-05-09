package moe.antimony.hoshi

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class MainActivityConfigurationTest {
    @Test
    fun mainActivityHandlesReaderOrientationChangesInPlace() {
        val activity = mainActivityManifestElement()
        val configChanges = activity
            .getAttribute("android:configChanges")
            .split('|')
            .filter { it.isNotBlank() }
            .toSet()

        assertTrue("MainActivity must keep the reader host alive on rotation.", "orientation" in configChanges)
        assertTrue("MainActivity must also handle the screen size change emitted with rotation.", "screenSize" in configChanges)
    }

    @Test
    fun mainActivityKeepsExternalOpensAndMediaReturnsInTheExistingTask() {
        val activity = mainActivityManifestElement()

        assertTrue(
            "MainActivity must receive new EPUB ACTION_VIEW intents in the existing task.",
            activity.getAttribute("android:launchMode") == "singleTop",
        )
        assertTrue(
            "External EPUB senders must not be able to split Hoshi into document tasks.",
            activity.getAttribute("android:documentLaunchMode") == "never",
        )
    }

    @Test
    fun processTextLookupActivityAppearsInAndroidSelectedTextProcessMenu() {
        val activity = processTextLookupActivityManifestElement()

        assertTrue(
            "The overlay lookup activity must be offered in Android's selected-text PROCESS_TEXT menu for plain text.",
            activity.hasIntentFilter(
                actionName = "android.intent.action.PROCESS_TEXT",
                mimeType = "text/plain",
            ),
        )
        assertTrue(
            "The selected-text lookup entry must render as an overlay instead of opening the main app shell.",
            activity.getAttribute("android:theme") == "@style/Theme.HoshiReader.ProcessTextOverlay",
        )
        assertTrue(
            "External lookup actions must not reuse Hoshi's main task, otherwise SEND/TRANSLATE can show MainActivity behind the popup.",
            activity.getAttribute("android:taskAffinity").isEmpty(),
        )
    }

    @Test
    fun processTextLookupActivityHandlesTranslateAndSendWithSeparateFilters() {
        val activity = processTextLookupActivityManifestElement()

        assertTrue(
            "ACTION_TRANSLATE must be handled without a MIME type so Android can offer Hoshi as a Translate target.",
            activity.hasIntentFilter(
                actionName = "android.intent.action.TRANSLATE",
                mimeType = null,
            ),
        )
        assertTrue(
            "ACTION_SEND must be handled for shared plain text.",
            activity.hasIntentFilter(
                actionName = "android.intent.action.SEND",
                mimeType = "text/plain",
            ),
        )
        assertFalse(
            "PROCESS_TEXT, TRANSLATE, and SEND actions must stay in separate intent filters.",
            activity.hasIntentFilterWithMultipleActions(),
        )
    }

    @Test
    fun processTextLookupActivityDoesNotRouteThroughMainAppShell() {
        val processTextActivity = File("src/main/java/moe/antimony/hoshi/features/dictionary/ProcessTextLookupActivity.kt")
            .readText()
        val mainActivity = File("src/main/java/moe/antimony/hoshi/MainActivity.kt").readText()
        val appShell = File("src/main/java/moe/antimony/hoshi/navigation/AppShell.kt").readText()

        assertTrue(processTextActivity.contains("ProcessTextLookupRequest.fromIntent(intent)"))
        assertTrue(processTextActivity.contains("LookupPopupStackView("))
        assertTrue(processTextActivity.contains("finish()"))
        assertTrue(processTextActivity.contains("WindowCompat.setDecorFitsSystemWindows(window, false)"))
        assertTrue(processTextActivity.contains("ColorDrawable(Color.TRANSPARENT)"))
        assertTrue(processTextActivity.contains("dictionaryRepository.rebuildLookupQuery()"))
        assertTrue(processTextActivity.contains("LookupEngine.lookup("))
        assertFalse(mainActivity.contains("processTextLookupRequest"))
        assertFalse(appShell.contains("processTextLookupRequest"))
    }

    @Test
    fun launchThemeHasNightResourceVariant() {
        val lightTheme = themeElement(File("src/main/res/values/themes.xml"))
        val nightTheme = themeElement(File("src/main/res/values-night/themes.xml"))

        assertTrue(lightTheme.getAttribute("parent") == "android:Theme.Material.Light.NoActionBar")
        assertTrue(nightTheme.getAttribute("parent") == "android:Theme.Material.NoActionBar")
    }

    private fun mainActivityManifestElement(): Element {
        return activityManifestElement(".MainActivity")
    }

    private fun processTextLookupActivityManifestElement(): Element {
        return activityManifestElement(".features.dictionary.ProcessTextLookupActivity")
    }

    private fun Element.hasIntentFilter(
        actionName: String,
        mimeType: String? = null,
    ): Boolean {
        val filters = getElementsByTagName("intent-filter")
        for (filterIndex in 0 until filters.length) {
            val filter = filters.item(filterIndex) as Element
            val actions = filter.getElementsByTagName("action")
            val data = filter.getElementsByTagName("data")
            val actionNames = (0 until actions.length).map { index ->
                val action = actions.item(index) as Element
                action.getAttribute("android:name")
            }
            val mimeTypes = (0 until data.length).map { index ->
                val item = data.item(index) as Element
                item.getAttribute("android:mimeType")
            }
            if (actionNames != listOf(actionName)) continue
            if (mimeType == null && mimeTypes.isNotEmpty()) continue
            if (mimeType != null && mimeType !in mimeTypes) continue
            return true
        }
        return false
    }

    private fun Element.hasIntentFilterWithMultipleActions(): Boolean {
        val filters = getElementsByTagName("intent-filter")
        for (filterIndex in 0 until filters.length) {
            val filter = filters.item(filterIndex) as Element
            val actionCount = filter.getElementsByTagName("action").length
            if (actionCount > 1) return true
        }
        return false
    }

    private fun activityManifestElement(name: String): Element {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val activities = document.getElementsByTagName("activity")
        for (index in 0 until activities.length) {
            val element = activities.item(index) as Element
            if (element.getAttribute("android:name") == name) {
                return element
            }
        }
        error("$name not found in AndroidManifest.xml")
    }

    private fun themeElement(file: File): Element {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val styles = document.getElementsByTagName("style")
        for (index in 0 until styles.length) {
            val element = styles.item(index) as Element
            if (element.getAttribute("name") == "Theme.HoshiReader") {
                return element
            }
        }
        error("Theme.HoshiReader not found in ${file.path}")
    }
}
