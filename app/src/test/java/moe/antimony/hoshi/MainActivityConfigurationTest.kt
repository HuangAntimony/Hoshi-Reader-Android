package moe.antimony.hoshi

import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.inject.Inject

class MainActivityConfigurationTest {
    @Test
    fun appLabelsComeFromBuildVariantPlaceholder() {
        val expectedLabel = "\${appLabel}"

        assertTrue(
            "The application label must come from the build variant so localized app_name resources cannot override the debug label.",
            applicationManifestElement().getAttribute("android:label") == expectedLabel,
        )
        assertTrue(
            "MainActivity must use the same build variant label as the application.",
            mainActivityManifestElement().getAttribute("android:label") == expectedLabel,
        )
        assertTrue(
            "ProcessTextLookupActivity must use the same build variant label as the application.",
            processTextLookupActivityManifestElement().getAttribute("android:label") == expectedLabel,
        )
    }

    @Test
    fun applicationUsesHoshiApplicationForHiltStartup() {
        assertTrue(
            "The manifest application class must be HoshiApplication so Hilt can install the app component before activities, receivers, and workers run.",
            applicationManifestElement(debugMergedManifestDocument()).getAttribute("android:name") ==
                "moe.antimony.hoshi.HoshiApplication",
        )
    }

    @Test
    fun workManagerDefaultInitializerIsRemovedForHiltConfiguration() {
        val provider = providerManifestElement(
            name = "androidx.startup.InitializationProvider",
            document = debugMergedManifestDocument(),
        )

        assertFalse(
            "WorkManager's default initializer must be removed when HoshiApplication supplies a HiltWorkerFactory configuration.",
            provider.hasMetaData(
                name = "androidx.work.WorkManagerInitializer",
                value = "androidx.startup",
            ),
        )
        assertTrue(
            "The AndroidX Startup provider must remain merged for other Startup initializers.",
            provider.hasMetaData(
                name = "androidx.lifecycle.ProcessLifecycleInitializer",
                value = "androidx.startup",
            ),
        )
    }

    @Test
    fun hoshiApplicationSuppliesHiltWorkerFactoryConfigurationContract() {
        assertTrue(
            "HoshiApplication must implement WorkManager's Configuration.Provider for Hilt worker injection.",
            Configuration.Provider::class.java.isAssignableFrom(HoshiApplication::class.java),
        )

        val workerFactoryField = HoshiApplication::class.java.declaredFields.singleOrNull { field ->
            field.type == HiltWorkerFactory::class.java
        }
        assertTrue(
            "HoshiApplication must receive HiltWorkerFactory from Hilt instead of manually constructing a WorkerFactory.",
            workerFactoryField != null,
        )
        assertTrue(
            "The HiltWorkerFactory field must be annotated with @Inject.",
            workerFactoryField!!.isAnnotationPresent(Inject::class.java),
        )
        assertTrue(
            "HoshiApplication must expose the Configuration property consumed by WorkManager.",
            HoshiApplication::class.java
                .getDeclaredMethod("getWorkManagerConfiguration")
                .returnType == Configuration::class.java,
        )
    }

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
    fun mainActivityAcceptsStandardAndLegacyEpubMimeTypes() {
        val activity = mainActivityManifestElement()

        assertTrue(
            "External EPUB opens must support the standard EPUB MIME type.",
            activity.hasIntentFilter(
                actionName = "android.intent.action.VIEW",
                mimeType = "application/epub+zip",
            ),
        )
        assertTrue(
            "External EPUB opens must support vendor file managers that send the non-standard application/epub MIME type.",
            activity.hasIntentFilter(
                actionName = "android.intent.action.VIEW",
                mimeType = "application/epub",
            ),
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

    private fun mainActivityManifestElement(): Element {
        return activityManifestElement(".MainActivity")
    }

    private fun processTextLookupActivityManifestElement(): Element {
        return activityManifestElement(".features.dictionary.ProcessTextLookupActivity")
    }

    private fun applicationManifestElement(document: Document = sourceManifestDocument()): Element {
        return document.documentElement
            .getElementsByTagName("application")
            .item(0) as Element
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

    private fun activityManifestElement(
        name: String,
        document: Document = sourceManifestDocument(),
    ): Element {
        val activities = document.getElementsByTagName("activity")
        for (index in 0 until activities.length) {
            val element = activities.item(index) as Element
            if (element.getAttribute("android:name") == name) {
                return element
            }
        }
        error("$name not found in AndroidManifest.xml")
    }

    private fun providerManifestElement(
        name: String,
        document: Document = sourceManifestDocument(),
    ): Element {
        val providers = document.getElementsByTagName("provider")
        for (index in 0 until providers.length) {
            val element = providers.item(index) as Element
            if (element.getAttribute("android:name") == name) {
                return element
            }
        }
        error("$name not found in AndroidManifest.xml")
    }

    private fun Element.hasMetaData(
        name: String,
        value: String,
    ): Boolean {
        val metadata = getElementsByTagName("meta-data")
        for (index in 0 until metadata.length) {
            val element = metadata.item(index) as Element
            val matches = element.getAttribute("android:name") == name &&
                element.getAttribute("android:value") == value
            if (matches) return true
        }
        return false
    }

    private fun sourceManifestDocument() = manifestDocument(File("src/main/AndroidManifest.xml"))

    private fun debugMergedManifestDocument() = manifestDocument(
        File("build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"),
    )

    private fun manifestDocument(file: File) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(file)

}
