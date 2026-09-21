package moe.antimony.hoshi.features.sasayaki.transcription

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Overlap decoding with one ordered recognition consumer. Decoder chunks contain
 * at most 4096 samples, so queued PCM uses at most 4 MiB regardless of book length. */
internal suspend fun feedTranscriptionAudio(
    decode: suspend (suspend (AudioSamples) -> Unit) -> Unit,
    consume: suspend (AudioSamples) -> Unit,
) = coroutineScope {
    val chunks = Channel<AudioSamples>(256)
    launch {
        try { decode { chunks.send(it) } }
        finally { chunks.close() }
    }
    for (chunk in chunks) consume(chunk)
}
