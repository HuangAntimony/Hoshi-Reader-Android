package moe.antimony.hoshi.features.tts

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Real decoder/output fixtures with local PCM audio; no credentials or device preferences. */
@OptIn(UnstableApi::class)
class TtsPlaybackRateDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun rateSurvivesPlayerAttachmentAndChangesWithoutResumingPausedAudioOrShiftingPitch() {
        instrumentation.runOnMainSync {
            val output = AndroidTtsPlaybackOutput(context)
            output.setPlaybackRate(2.5f)
            val first = ExoPlayer.Builder(context).build()
            try {
                output.attach(first)
                assertEquals(2.5f, first.playbackParameters.speed)
                assertEquals(1f, first.playbackParameters.pitch)
                first.play()
                output.setPlaybackRate(3f)
                assertTrue(first.playWhenReady)
                assertEquals(3f, first.playbackParameters.speed)
                first.pause()
                output.setPlaybackRate(.5f)
                assertFalse(first.playWhenReady)
                assertEquals(.5f, first.playbackParameters.speed)
                assertEquals(1f, first.playbackParameters.pitch)
            } finally { output.detach(); first.release() }
            val next = ExoPlayer.Builder(context).build()
            try {
                output.attach(next)
                assertEquals(.5f, next.playbackParameters.speed)
                assertFalse(next.playWhenReady)
                output.setPlaybackRate(Float.NaN)
                assertEquals(1f, next.playbackParameters.speed)
            } finally { output.detach(); next.release() }
        }
    }

    @Test fun realChunkedAudioPlaysFasterAndSlowerAtRangeEndpoints() {
        val file = File.createTempFile("tts-rate-fixture-", ".wav", context.cacheDir)
        // One second of mono 16-bit PCM; two chunks form one navigable sentence.
        val samples = 8_000
        file.writeBytes(ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(samples); putInt(samples * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
            repeat(samples) { putShort(0) }
        }.array())
        fun duration(rate: Float): Long {
            val ended = CountDownLatch(1)
            var start = 0L
            var elapsed = 0L
            var failed = false
            lateinit var player: ExoPlayer
            lateinit var output: AndroidTtsPlaybackOutput
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build()
                output = AndroidTtsPlaybackOutput(context)
                output.onEnded = { elapsed = SystemClock.elapsedRealtime() - start; ended.countDown() }
                output.onError = { failed = true; ended.countDown() }
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying && start == 0L) start = SystemClock.elapsedRealtime()
                    }
                })
                output.setPlaybackRate(rate)
                output.attach(player)
                output.prepare(listOf(file, file), "Fixture", "Fixture sentence")
                output.play()
            }
            try {
                assertTrue("Local audio did not finish", ended.await(12, TimeUnit.SECONDS))
                assertFalse("Local audio decoder failed", failed)
                assertTrue(start > 0)
                return elapsed
            } finally { instrumentation.runOnMainSync { output.detach(); player.release() } }
        }
        try {
            val slow = duration(.5f)
            val fast = duration(3f)
            assertTrue("Slow endpoint duration: $slow ms", slow in 3_200..5_500)
            assertTrue("Fast endpoint duration: $fast ms", fast in 300..1_800)
            assertTrue("Endpoints did not change playback duration: $slow / $fast", slow > fast * 2)
        } finally { file.delete() }
    }
}
