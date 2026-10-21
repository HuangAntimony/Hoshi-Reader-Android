package moe.antimony.hoshi.features.wallpaper

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookCoverWallpaperManifestTest {
    @Test
    fun declaresWallpaperPermissionWithoutModernStorageAccess() {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val permissions = document.getElementsByTagName("uses-permission")
        val names = (0 until permissions.length).map { index ->
            permissions.item(index).attributes.getNamedItem("android:name").nodeValue
        }

        assertTrue("android.permission.SET_WALLPAPER" in names)
        assertFalse("android.permission.MANAGE_EXTERNAL_STORAGE" in names)
        assertFalse("android.permission.READ_MEDIA_IMAGES" in names)
        // Legacy gallery export may request write access, but never on scoped-storage Android.
        val legacyWrite = (0 until permissions.length).map(permissions::item).single {
            it.attributes.getNamedItem("android:name").nodeValue == "android.permission.WRITE_EXTERNAL_STORAGE"
        }
        assertEquals("28", legacyWrite.attributes.getNamedItem("android:maxSdkVersion").nodeValue)
    }
}
