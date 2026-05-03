package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiMediaSessionSourceTest {
    @Test
    fun mediaSessionPublishesAudiobookTransportControlsForSystemUi() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiMediaSession.kt").readText()

        assertTrue(source.contains("import android.media.MediaMetadata"))
        assertTrue(source.contains("import android.app.Notification"))
        assertTrue(source.contains("import android.app.NotificationChannel"))
        assertTrue(source.contains("import android.app.NotificationManager"))
        assertTrue(source.contains("import android.graphics.Bitmap"))
        assertTrue(source.contains("import android.graphics.BitmapFactory"))
        assertTrue(source.contains("import android.media.session.MediaSession"))
        assertTrue(source.contains("import android.media.session.PlaybackState"))
        assertTrue(source.contains("MediaSession(context.applicationContext, \"Hoshi Sasayaki\")"))
        assertTrue(source.contains("Notification.MediaStyle().setMediaSession(session.sessionToken)"))
        assertTrue(source.contains("notificationManager.notify(NotificationId, notification)"))
        assertTrue(source.contains("notificationManager.cancel(NotificationId)"))
        assertTrue(source.contains("setCallback("))
        assertTrue(source.contains("override fun onPlay()"))
        assertTrue(source.contains("override fun onPause()"))
        assertTrue(source.contains("override fun onSkipToPrevious()"))
        assertTrue(source.contains("override fun onSkipToNext()"))
        assertTrue(source.contains("override fun onSeekTo(pos: Long)"))
        assertTrue(source.contains("PlaybackState.ACTION_PLAY"))
        assertTrue(source.contains("PlaybackState.ACTION_PAUSE"))
        assertTrue(source.contains("PlaybackState.ACTION_PLAY_PAUSE"))
        assertTrue(source.contains("PlaybackState.ACTION_SKIP_TO_PREVIOUS"))
        assertTrue(source.contains("PlaybackState.ACTION_SKIP_TO_NEXT"))
        assertTrue(source.contains("PlaybackState.ACTION_SEEK_TO"))
        assertTrue(source.contains("MediaMetadata.METADATA_KEY_TITLE"))
        assertTrue(source.contains("MediaMetadata.METADATA_KEY_DURATION"))
        assertTrue(source.contains("MediaMetadata.METADATA_KEY_ALBUM_ART"))
        assertTrue(source.contains("putBitmap(MediaMetadata.METADATA_KEY_ART, artwork)"))
        assertTrue(source.contains("setLargeIcon(artwork)"))
        assertTrue(source.contains("fun loadCoverArt(file: File?): Bitmap?"))
        assertTrue(source.contains("BitmapFactory.decodeFile(file.absolutePath"))
        assertTrue(source.contains("session.isActive = true"))
        assertTrue(source.contains("session.release()"))
    }

    @Test
    fun sasayakiPlayerUsesMediaSessionHandleBoundary() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
        val restoreAudio = source.substringAfter("private fun restoreAudio()")
            .substringBefore("private fun tick()")
        val startPlayback = source.substringAfter("private fun startPlayback()")
            .substringBefore("private fun seek(")
        val pausePlayback = source.substringAfter("fun pausePlayback(")
            .substringBefore("fun nextCue()")
        val teardown = source.substringAfter("private fun teardownPlayer(clearCue: Boolean)")
            .substringBefore("private fun playbackParams(")

        assertTrue(source.contains("bookTitle: String?"))
        assertTrue(source.contains("bookCoverFile: File?"))
        assertTrue(source.contains("private var mediaSession: SasayakiMediaSessionHandle? = null"))
        assertTrue(restoreAudio.contains("AndroidSasayakiMediaSessionHandle("))
        assertTrue(restoreAudio.contains("title = bookTitle ?: bookRoot.name"))
        assertTrue(restoreAudio.contains("artworkFile = bookCoverFile"))
        assertFalse(restoreAudio.contains("SasayakiMediaSession("))
        assertFalse(restoreAudio.contains("SasayakiMediaSession.loadCoverArt(bookCoverFile)"))
        assertTrue(startPlayback.contains("mediaSession?.activate()"))
        assertTrue(startPlayback.contains("updateMediaSession()"))
        assertTrue(pausePlayback.contains("updateMediaSession = ::updateMediaSession"))
        assertTrue(source.contains("private fun updateMediaSession()"))
        assertTrue(teardown.contains("mediaSession?.release()"))
        assertTrue(teardown.contains("mediaSession = null"))
    }

    @Test
    fun mediaSessionHandleWrapsAndroidMediaSessionImplementation() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiMediaSessionController.kt").readText()
        val handle = source.substringAfter("class AndroidSasayakiMediaSessionHandle(")

        assertTrue(source.contains("interface SasayakiMediaSessionHandle"))
        assertTrue(source.contains("fun activate()"))
        assertTrue(source.contains("fun update("))
        assertTrue(source.contains("fun release()"))
        assertTrue(handle.contains("private val session = SasayakiMediaSession("))
        assertTrue(handle.contains("artwork = SasayakiMediaSession.loadCoverArt(artworkFile)"))
        assertTrue(handle.contains("override fun activate()"))
        assertTrue(handle.contains("session.activate()"))
        assertTrue(handle.contains("override fun update("))
        assertTrue(handle.contains("session.update("))
        assertTrue(handle.contains("override fun release()"))
        assertTrue(handle.contains("session.release()"))
    }

    @Test
    fun mediaSessionActivityReturnsToExistingReaderTask() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiMediaSession.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val contentIntent = source.substringAfter("private fun contentIntent()")
            .substringBefore("companion object")

        assertTrue(manifest.contains("android:launchMode=\"singleTop\""))
        assertTrue(source.contains("session.setSessionActivity(contentIntent())"))
        assertTrue(contentIntent.contains("Intent.FLAG_ACTIVITY_SINGLE_TOP"))
        assertTrue(contentIntent.contains("Intent.FLAG_ACTIVITY_REORDER_TO_FRONT"))
        assertTrue(contentIntent.contains("PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE"))
    }
}
