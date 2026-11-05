package moe.antimony.hoshi.features.settings

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatDelegate
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import moe.antimony.hoshi.features.sasayaki.SasayakiPlaybackNotificationChannelId
import moe.antimony.hoshi.features.sasayaki.SasayakiPlaybackNotificationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/** Builds notifications without posting them, using an isolated player/session and restoring channel metadata. */
@OptIn(UnstableApi::class)
class AppLanguageNotificationDeviceTest {
    @Test
    fun existingProviderResolvesNewNotificationTextAndActionsInTheCurrentLanguage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val originalChannel = manager.getNotificationChannel(SasayakiPlaybackNotificationChannelId)
        ActivityScenario.launch(AppLanguageFixtureActivity::class.java).use { scenario ->
            val originalLocales = AppCompatDelegate.getApplicationLocales()
            lateinit var player: ExoPlayer
            lateinit var session: MediaSession
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build()
                session = MediaSession.Builder(context, player).setId("app-language-notification-fixture").build()
            }
            val provider = SasayakiPlaybackNotificationProvider(context, contentIntent = {
                PendingIntent.getActivity(context, 0, Intent(context, AppLanguageFixtureActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            })
            try {
                listOf(
                    AppLanguageMode.SimplifiedChinese to listOf("有声书", "播放", "有声书后退", "有声书快进"),
                    AppLanguageMode.English to listOf("Sasayaki", "Playback", "Sasayaki Rewind", "Sasayaki Fast-forward"),
                ).forEach { (mode, expected) ->
                    scenario.onActivity { setAppLanguageMode(mode) }
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync {
                        val notification = provider.buildNotification(session)
                        assertEquals(expected[0], notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                        assertEquals(expected[1], notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
                        assertEquals(expected[2], notification.actions.first().title.toString())
                        assertEquals(expected[3], notification.actions.last().title.toString())
                        assertEquals(expected[0], manager.getNotificationChannel(SasayakiPlaybackNotificationChannelId).name.toString())
                    }
                }
            } finally {
                instrumentation.runOnMainSync {
                    session.release()
                    player.release()
                    AppCompatDelegate.setApplicationLocales(originalLocales)
                }
                instrumentation.waitForIdleSync()
                if (originalChannel == null) manager.deleteNotificationChannel(SasayakiPlaybackNotificationChannelId)
                else manager.createNotificationChannel(originalChannel)
            }
        }
    }
}
