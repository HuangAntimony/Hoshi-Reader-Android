package moe.antimony.hoshi.features.sasayaki

import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import moe.antimony.hoshi.epub.EpubBookParser
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Opt-in read-only comparison using an existing book; reports go to cache, never book sidecars. */
class SasayakiIncrementalMatchDeviceTest {
    @Test fun incrementalUpdatesEqualFullAlignment() = runBlocking<Unit> {
        val bookPath = InstrumentationRegistry.getArguments().getString("matchBookRoot")
        assumeNotNull(bookPath)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(requireNotNull(bookPath))
        val scratch = File(context.cacheDir, "incremental-match-benchmark").apply { mkdirs() }
        val transcript = checkNotNull(SasayakiTranscriptStore(Dispatchers.IO).load(root))
        val book = EpubBookParser(scratch).parse(root)
        fun now() = SystemClock.elapsedRealtimeNanos()
        val preparing = now()
        val session = SasayakiTranscriptAligner.Session(book)
        val prepareMs = (now() - preparing) / 1e6
        val firstThrough = (transcript.through - 1800).coerceAtLeast(0.0)
        val firstTokens = transcript.tokens.takeWhile { it.end <= firstThrough }
        val firstStart = now()
        var result = session.align(firstTokens)
        val firstMs = (now() - firstStart) / 1e6
        val measurements = buildJsonArray {
            for (step in 1..30) {
                val through = firstThrough + (transcript.through - firstThrough) * step / 30
                val tokens = transcript.tokens.takeWhile { it.end <= through }
                val cpu = Debug.threadCpuTimeNanos()
                val allocated = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
                val start = now()
                result = session.align(tokens)
                add(buildJsonObject {
                    put("tokens", tokens.size)
                    put("wallMs", (now() - start) / 1e6)
                    put("cpuMs", (Debug.threadCpuTimeNanos() - cpu) / 1e6)
                    put("allocatedBytes", Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - allocated)
                })
            }
        }
        val fullStart = now()
        val full = SasayakiTranscriptAligner.align(book, transcript.tokens)
        val fullMs = (now() - fullStart) / 1e6
        assertEquals("Incremental matches, timings, ranges and unmatched count must equal a fresh full pass", full, result)
        assertEquals(full, session.align(transcript.tokens, complete = true))
        val report = buildJsonObject {
            put("prepareMs", prepareMs)
            put("firstMs", firstMs)
            put("fullMs", fullMs)
            put("audioThrough", transcript.through)
            put("cues", full.matches.size)
            put("measurements", measurements)
        }
        File(scratch, "report.json").writeText(report.toString())
    }
}
