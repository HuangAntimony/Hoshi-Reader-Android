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
        val restoreController = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiAudioRestoreController.kt").readText()
        val handleCoordinator = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiMediaSessionHandleCoordinator.kt").readText()
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
        assertTrue(source.contains("private val mediaSessionHandle = SasayakiMediaSessionHandleCoordinator()"))
        assertTrue(source.contains("private val mediaSessionPublishing = SasayakiMediaSessionPublishingCoordinator("))
        assertTrue(source.contains("private val audioRestoreResult = SasayakiAudioRestoreResultCoordinator("))
        assertFalse(source.contains("private var mediaSession: SasayakiMediaSessionHandle? = null"))
        assertTrue(handleCoordinator.contains("private var mediaSession: SasayakiMediaSessionHandle? = null"))
        assertTrue(source.contains("private val audioRestore = SasayakiAudioRestoreController("))
        assertTrue(restoreAudio.contains("releaseExistingMediaSession = mediaSessionHandle::releaseExisting"))
        assertTrue(restoreAudio.contains("audioRestoreResult.handleSuccess("))
        assertFalse(restoreAudio.contains("mediaSessionHandle.replace(result.mediaSession)"))
        assertTrue(restoreController.contains("AndroidSasayakiMediaSessionHandle("))
        assertTrue(restoreController.contains("title = bookTitle ?: bookRoot.name"))
        assertTrue(restoreController.contains("artworkFile = bookCoverFile"))
        assertFalse(restoreAudio.contains("SasayakiMediaSession("))
        assertFalse(restoreAudio.contains("SasayakiMediaSession.loadCoverArt(bookCoverFile)"))
        assertTrue(source.contains("private val playbackStart = SasayakiPlaybackStartCoordinator("))
        assertTrue(startPlayback.contains("playbackStart.start("))
        assertTrue(startPlayback.contains("updateMediaSession = ::updateMediaSession"))
        assertFalse(startPlayback.contains("mediaSessionPublishing.activate()"))
        assertFalse(startPlayback.contains("mediaSessionHandle.activate()"))
        assertTrue(pausePlayback.contains("updateMediaSession = ::updateMediaSession"))
        assertTrue(source.contains("private fun updateMediaSession()"))
        assertTrue(source.contains("mediaSessionPublishing.update("))
        assertFalse(source.contains("private fun updateMediaSession() {\n        mediaSessionHandle.update("))
        assertTrue(source.contains("private val playbackTeardown = SasayakiPlaybackTeardownCoordinator("))
        assertTrue(teardown.contains("playbackTeardown.teardown("))
        assertFalse(teardown.contains("mediaSessionHandle.releaseAndClear()"))
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
