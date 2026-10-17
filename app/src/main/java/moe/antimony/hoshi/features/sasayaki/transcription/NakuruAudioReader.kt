package moe.antimony.hoshi.features.sasayaki.transcription

import kotlinx.coroutines.channels.ReceiveChannel

internal class NakuruAudioReader(private val input: ReceiveChannel<AudioSamples>) {
    private val chunks = ArrayDeque<AudioSamples>()
    private var ended = false

    suspend fun read(offset: Long, count: Int): FloatArray {
        while (!ended && (chunks.lastOrNull()?.let { it.startSample + it.samples.size } ?: 0) < offset + count) {
            val chunk = input.receiveCatching().getOrNull()
            if (chunk == null) ended = true else chunks.addLast(chunk)
        }
        while (chunks.firstOrNull()?.let { it.startSample + it.samples.size <= offset } == true) chunks.removeFirst()
        val samples = FloatArray(count)
        chunks.forEach { chunk ->
            val start = maxOf(offset, chunk.startSample)
            val end = minOf(offset + count, chunk.startSample + chunk.samples.size)
            for (index in start until end) samples[(index - offset).toInt()] = chunk.samples[(index - chunk.startSample).toInt()] * 32768f
        }
        return samples
    }
}
