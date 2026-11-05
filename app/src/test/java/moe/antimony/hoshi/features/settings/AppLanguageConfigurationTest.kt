package moe.antimony.hoshi.features.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.w3c.dom.Element

class AppLanguageConfigurationTest {
    @Test
    fun appCompatStoresLegacyLocalesAndCanMigrateThemToTheFramework() {
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val services = manifest.getElementsByTagName("service")
        val service = (0 until services.length).map { services.item(it) as Element }
            .singleOrNull { it.getAttribute("android:name") == "androidx.appcompat.app.AppLocalesMetadataHolderService" }
        assertNotNull("Android 8–12 language choices must survive process death and OS upgrades.", service)
        requireNotNull(service)
        assertEquals("false", service.getAttribute("android:enabled"))
        assertEquals("false", service.getAttribute("android:exported"))
        val metadata = service.getElementsByTagName("meta-data")
        val autoStore = (0 until metadata.length).map { metadata.item(it) as Element }
            .single { it.getAttribute("android:name") == "autoStoreLocales" }
        assertEquals("true", autoStore.getAttribute("android:value"))
    }
}
