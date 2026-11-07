package moe.antimony.hoshi.features.playback

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadata as PlatformMetadata
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import moe.antimony.hoshi.MainActivity
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.features.bookshelf.AndroidBookCoverThumbnailEncoder
import moe.antimony.hoshi.features.bookshelf.BookCoverThumbnailStore
import moe.antimony.hoshi.features.sasayaki.*
import moe.antimony.hoshi.features.tts.TtsPlaybackState
import moe.antimony.hoshi.features.tts.TtsSentence
import moe.antimony.hoshi.features.tts.TtsSessionPlayer
import moe.antimony.hoshi.ui.appLanguageContext
import org.junit.Assert.*
import org.junit.Test

/** Real session metadata and generated media only. Optional screenshots use a test-owned notification. */
@OptIn(UnstableApi::class)
class ReadingMediaCardDeviceTest {
    @Test fun sharedArtworkAndSentencesReachBothPlatformSessions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "reading-media-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val manager = context.getSystemService(NotificationManager::class.java)
        val originalChannel = manager.getNotificationChannel(SasayakiPlaybackNotificationChannelId)
        val cover = File(root, "cover.png")
        Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.rgb(25, 100, 170))
            cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val audio = File(root, "Sasayaki/fixture.wav").apply { parentFile!!.mkdirs() }
        val pcmBytes = 16000 * 12 * 2
        audio.writeBytes(ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray()).putInt(16)
            putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            put("data".toByteArray()).putInt(pcmBytes)
        }.array())
        val artworkRepository = ReadingMediaArtworkRepository(
            BookCoverThumbnailStore(root, AndroidBookCoverThumbnailEncoder(), Dispatchers.IO), Dispatchers.IO,
        )
        var runtime: SasayakiPlaybackServiceRuntime? = null
        var ttsSession: MediaSession? = null
        var ttsDecoder: ExoPlayer? = null
        try {
            assertNull(artworkRepository.source(File(root, "missing.png")))
            val invalid = File(root, "invalid.png").apply { writeText("invalid") }
            assertNull(artworkRepository.load(requireNotNull(artworkRepository.source(invalid))))
            val bytes = requireNotNull(artworkRepository.load(requireNotNull(artworkRepository.source(cover))))
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
            assertEquals(512, maxOf(dimensions.outWidth, dimensions.outHeight))
            val first = SasayakiMatch("first", 0.0, 1.5, "First matched sentence", 0, 0, 22)
            val second = SasayakiMatch("second", 2.0, 4.0, "Second matched sentence", 1, 0, 23)
            val matchData = SasayakiMatchData(listOf(first, second), 0)
            withContext(Dispatchers.Main) {
                val serviceRuntime = SasayakiPlaybackServiceRuntime(context, scope, Dispatchers.IO, artworkRepository)
                runtime = serviceRuntime
                val session = serviceRuntime.createServiceSession(context)
                val playbackRepository = object : SasayakiPlaybackRepository {
                    override suspend fun load(): SasayakiPlaybackData? = null
                    override suspend fun save(playback: SasayakiPlaybackData) = Unit
                }
                val playback = serviceRuntime.load(
                    SasayakiPlaybackRuntimeLoadRequest("fixture", root, playbackRepository,
                        "Sasayaki media fixture", cover, matchData, SasayakiPlaybackData(0.25, audioFileName = audio.name)),
                    { 8 }, { _, _, _ -> fail("Reader is detached") }, {},
                ) as SasayakiPlaybackController
                serviceRuntime.detachReader()
                playback.autoScroll = false
                assertTrue(playback.restoreAudio())
                val platform = android.media.session.MediaController(context, session.platformToken)
                await { session.player.playbackState == Player.STATE_READY && session.player.mediaMetadata.artworkData != null }
                await { platform.metadata?.getString(PlatformMetadata.METADATA_KEY_ARTIST) == first.text }
                assertEquals("Sasayaki media fixture", platform.metadata?.getString(PlatformMetadata.METADATA_KEY_TITLE))
                assertNotNull(platform.metadata?.getBitmap(PlatformMetadata.METADATA_KEY_ALBUM_ART))

                var discontinuities = 0
                var buffering = 0
                session.player.addListener(object : Player.Listener {
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) { discontinuities++ }
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_BUFFERING) buffering++ }
                })
                val pausedPosition = session.player.currentPosition
                playback.updateMatchData(matchData.copy(matches = listOf(first.copy(text = "文".repeat(180)), second)))
                await { platform.metadata?.getString(PlatformMetadata.METADATA_KEY_ARTIST) == "文".repeat(160) }
                assertEquals(pausedPosition, session.player.currentPosition)
                assertFalse(session.player.playWhenReady)
                assertEquals(0, discontinuities)
                assertEquals(0, buffering)
                playback.updateMatchData(matchData)
                serviceRuntime.playFromSession()
                await { session.player.isPlaying }
                await { platform.metadata?.getString(PlatformMetadata.METADATA_KEY_ARTIST).isNullOrEmpty() }
                await { platform.metadata?.getString(PlatformMetadata.METADATA_KEY_ARTIST) == second.text }
                assertEquals(0, discontinuities)
                assertEquals(0, buffering)
                serviceRuntime.pauseFromSession()
                await { !session.player.playWhenReady }
                assertEquals(second.text, session.player.mediaMetadata.artist)
                serviceRuntime.seekToFromSession(1750)
                await { session.player.mediaMetadata.artist.isNullOrEmpty() }
                serviceRuntime.seekToFromSession(2250)
                await { session.player.mediaMetadata.artist == second.text }
                playback.setDelay(2.0)
                await { session.player.mediaMetadata.artist == first.text }
                playback.updateMatchData(null)
                await { session.player.mediaMetadata.artist.isNullOrEmpty() }
                val provider = SasayakiPlaybackNotificationProvider(context, { returnIntent(context) })
                assertEquals(context.appLanguageContext().getString(R.string.sasayaki_playback),
                    provider.buildNotification(session).extras.getCharSequence(Notification.EXTRA_TEXT))
                playback.setDelay(0.0)
                playback.updateMatchData(matchData)
                await { session.player.mediaMetadata.artist == second.text }
                val notification = provider.buildNotification(session)
                assertEquals(second.text, notification.extras.getCharSequence(Notification.EXTRA_TEXT))
                assertNotNull(notification.getLargeIcon())
                captureCard(context, manager, notification, "sasayaki-media-card")
                serviceRuntime.release()
                runtime = null

                val decoder = ExoPlayer.Builder(context).build().also { ttsDecoder = it }
                var state = TtsPlaybackState(bookId = "tts-fixture", title = "TTS media fixture",
                    sentences = listOf(TtsSentence(0, 0, 24, "Current narrated sentence")), started = true, playing = true, preparing = true)
                val transport = TtsSessionPlayer(decoder, { state }, {}, {}, {}, {}, {}, { bytes })
                val speechSession = MediaSession.Builder(context, transport).setId("reading-media-tts-fixture")
                    .setSessionActivity(returnIntent(context)).build().also { ttsSession = it }
                val speechPlatform = android.media.session.MediaController(context, speechSession.platformToken)
                await { speechPlatform.metadata?.getBitmap(PlatformMetadata.METADATA_KEY_ALBUM_ART) != null }
                assertEquals("Current narrated sentence", speechPlatform.metadata?.getString(PlatformMetadata.METADATA_KEY_ARTIST))
                assertEquals("TTS media fixture", speechPlatform.metadata?.getString(PlatformMetadata.METADATA_KEY_TITLE))
                // System UI on API 33 reads the real TtsSessionPlayer metadata from this token.
                val speechNotification = Notification.Builder(context, SasayakiPlaybackNotificationChannelId)
                    .setSmallIcon(R.drawable.ic_stat_hoshi).setContentTitle("TTS media fixture")
                    .setContentIntent(returnIntent(context)).setStyle(Notification.MediaStyle().setMediaSession(speechSession.platformToken)).build()
                captureCard(context, manager, speechNotification, "tts-media-card")
                state = state.copy(sentences = listOf(TtsSentence(0, 24, 13, "Next sentence")))
                transport.refresh()
                await { speechPlatform.metadata?.getString(PlatformMetadata.METADATA_KEY_ARTIST) == "Next sentence" }
                assertNotNull(speechPlatform.metadata?.getBitmap(PlatformMetadata.METADATA_KEY_ALBUM_ART))
            }
        } finally {
            withContext(Dispatchers.Main) { runtime?.release(); ttsSession?.release(); ttsDecoder?.release() }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            manager.cancel(NotificationId)
            if (originalChannel == null) manager.deleteNotificationChannel(SasayakiPlaybackNotificationChannelId)
            else manager.createNotificationChannel(originalChannel)
            root.deleteRecursively()
        }
    }

    private suspend fun await(check: () -> Boolean) = withTimeout(10_000) {
        while (!check()) delay(25)
    }

    private fun returnIntent(context: Context): PendingIntent = PendingIntent.getActivity(context, NotificationId,
        Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private suspend fun captureCard(context: Context, manager: NotificationManager, notification: Notification, name: String) {
        if (InstrumentationRegistry.getArguments().getString("mediaCardEvidence") != "true") return
        manager.notify(NotificationId, notification)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("cmd statusbar expand-notifications").close()
        delay(1500)
        val bitmap = requireNotNull(automation.takeScreenshot())
        File(context.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        automation.executeShellCommand("cmd statusbar collapse").close()
        manager.cancel(NotificationId)
    }

    private companion object { const val NotificationId = 49321 }
}
