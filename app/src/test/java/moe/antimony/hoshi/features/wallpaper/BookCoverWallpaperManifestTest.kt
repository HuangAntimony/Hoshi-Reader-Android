package moe.antimony.hoshi.features.wallpaper

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookCoverWallpaperManifestTest {
    @Test
    fun declaresOnlyNormalWallpaperPermissionForCoverPublishing() {
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
        assertFalse("android.permission.WRITE_EXTERNAL_STORAGE" in names)
    }
}
