package moe.antimony.hoshi.features.sasayaki.transcription

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class NakuruAudioReaderTest {
    @Test fun preservesOverlappingWindowsAndResumeOriginInPcm16Scale() = runTest {
        val chunks = Channel<AudioSamples>(Channel.UNLIMITED)
        chunks.send(AudioSamples(16000, floatArrayOf(.1f, .2f, .3f)))
        chunks.send(AudioSamples(16003, floatArrayOf(.4f, .5f, .6f)))
        chunks.send(AudioSamples(16006, floatArrayOf(.7f, .8f)))
        chunks.close()
        val reader = NakuruAudioReader(chunks)
        assertArrayEquals(floatArrayOf(.1f, .2f, .3f, .4f, .5f).map { it * 32768f }.toFloatArray(), reader.read(16000, 5), 0f)
        assertArrayEquals(floatArrayOf(.4f, .5f, .6f, .7f).map { it * 32768f }.toFloatArray(), reader.read(16003, 4), 0f)
        assertArrayEquals(floatArrayOf(.7f * 32768f, .8f * 32768f, 0f), reader.read(16006, 3), 0f)
    }
}
