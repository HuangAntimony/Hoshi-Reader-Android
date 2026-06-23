package moe.antimony.hoshi.features.sasayaki

import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaNotification
import androidx.media3.common.Player
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun playbackNotificationUsesMedia3ProviderContract() {
        assertTrue(
            "Sasayaki notification rendering may be customized, but foreground-service ownership must stay with Media3.",
            MediaNotification.Provider::class.java.isAssignableFrom(SasayakiPlaybackNotificationProvider::class.java),
        )
    }

    @Test
    fun playbackServiceDelegatesForegroundLifecycleThroughMedia3NotificationUpdates() {
        assertTrue(
            "SasayakiPlaybackService may strengthen Media3's foreground requirement for active Sasayaki audio, but foreground-service start/stop ownership must stay in Media3.",
            SasayakiPlaybackService::class.java.declaredMethods.any { it.name == "onUpdateNotification" },
        )
    }

    @Test
    fun playbackNotificationDoesNotDeclarePrivateActionReceiver() {
        assertFalse(
            "Sasayaki notification actions must route through MediaSessionService PendingIntents, not an in-process BroadcastReceiver.",
            receiverNames().any { it.contains(".features.sasayaki.") },
        )
    }

    @Test
    fun serviceExtendsMediaSessionService() {
        assertTrue(
            "SasayakiPlaybackService must extend MediaSessionService so Android can keep playback alive in the background.",
            MediaSessionService::class.java.isAssignableFrom(SasayakiPlaybackService::class.java),
        )
    }

    @Test
    fun playbackForegroundPolicyMatchesOngoingAudioStates() {
        assertTrue(
            sasayakiShouldRunPlaybackServiceInForeground(
                foregroundPlaybackRequested = true,
                playWhenReady = false,
                playbackState = Player.STATE_IDLE,
            ),
        )
        assertTrue(
            sasayakiShouldRunPlaybackServiceInForeground(
                foregroundPlaybackRequested = true,
                playWhenReady = true,
                playbackState = Player.STATE_ENDED,
            ),
        )
        assertTrue(
            sasayakiShouldRunPlaybackServiceInForeground(
                foregroundPlaybackRequested = false,
                playWhenReady = true,
                playbackState = Player.STATE_BUFFERING,
            ),
        )
        assertTrue(
            sasayakiShouldRunPlaybackServiceInForeground(
                foregroundPlaybackRequested = false,
                playWhenReady = true,
                playbackState = Player.STATE_READY,
            ),
        )
        assertFalse(
            sasayakiShouldRunPlaybackServiceInForeground(
                foregroundPlaybackRequested = false,
                playWhenReady = false,
                playbackState = Player.STATE_READY,
            ),
        )
        assertFalse(
            sasayakiShouldRunPlaybackServiceInForeground(
                foregroundPlaybackRequested = false,
                playWhenReady = true,
                playbackState = Player.STATE_IDLE,
            ),
        )
        assertFalse(
            sasayakiShouldRunPlaybackServiceInForeground(
                foregroundPlaybackRequested = false,
                playWhenReady = true,
                playbackState = Player.STATE_ENDED,
            ),
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

    private fun receiverNames(): List<String> {
        val receivers = sourceManifest().getElementsByTagName("receiver")
        val names = mutableListOf<String>()
        for (index in 0 until receivers.length) {
            val element = receivers.item(index) as Element
            names += element.getAttribute("android:name")
        }
        return names
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
