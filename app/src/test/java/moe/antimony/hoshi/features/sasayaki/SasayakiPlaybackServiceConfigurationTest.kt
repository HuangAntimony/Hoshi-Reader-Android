package moe.antimony.hoshi.features.sasayaki

import androidx.media3.session.MediaSessionService
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

class SasayakiPlaybackServiceConfigurationTest {
    @Test
    fun manifestDeclaresMediaPlaybackForegroundServicePermissions() {
        val permissions = sourceManifest()
            .getElementsByTagName("uses-permission")
            .let { nodes ->
                (0 until nodes.length).map { index ->
                    (nodes.item(index) as Element).getAttribute("android:name")
                }
            }

        assertTrue("Sasayaki playback needs foreground service permission.", "android.permission.FOREGROUND_SERVICE" in permissions)
        assertTrue(
            "Sasayaki playback must declare the Android 14+ media playback foreground-service permission.",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" in permissions,
        )
        assertTrue(
            "Sasayaki playback uses ExoPlayer wake mode for long-running background audio.",
            "android.permission.WAKE_LOCK" in permissions,
        )
    }

    @Test
    fun manifestDeclaresMediaSessionService() {
        val service = serviceElement(".features.sasayaki.SasayakiPlaybackService")

        assertEquals("true", service.getAttribute("android:exported"))
        assertEquals(
            "mediaPlayback",
            service.getAttribute("android:foregroundServiceType"),
        )
        assertTrue(
            "SasayakiPlaybackService must expose the Media3 MediaSessionService action.",
            service.hasAction("androidx.media3.session.MediaSessionService"),
        )
    }

    @Test
    fun oemRestrictedNotificationReceiverIsNotExported() {
        val receiver = receiverElement(".features.sasayaki.SasayakiOemRestrictedPlaybackNotificationReceiver")

        assertEquals("false", receiver.getAttribute("android:exported"))
    }

    @Test
    fun serviceExtendsMediaSessionService() {
        assertTrue(
            "SasayakiPlaybackService must extend MediaSessionService so Android can keep playback alive in the background.",
            MediaSessionService::class.java.isAssignableFrom(SasayakiPlaybackService::class.java),
        )
    }

    private fun serviceElement(name: String): Element {
        val services = sourceManifest().getElementsByTagName("service")
        for (index in 0 until services.length) {
            val element = services.item(index) as Element
            if (element.getAttribute("android:name") == name) {
                return element
            }
        }
        error("$name not found in AndroidManifest.xml")
    }

    private fun receiverElement(name: String): Element {
        val receivers = sourceManifest().getElementsByTagName("receiver")
        for (index in 0 until receivers.length) {
            val element = receivers.item(index) as Element
            if (element.getAttribute("android:name") == name) {
                return element
            }
        }
        error("$name not found in AndroidManifest.xml")
    }

    private fun Element.hasAction(name: String): Boolean {
        val actions = getElementsByTagName("action")
        for (index in 0 until actions.length) {
            val action = actions.item(index) as Element
            if (action.getAttribute("android:name") == name) {
                return true
            }
        }
        return false
    }

    private fun sourceManifest(): Document =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
}
