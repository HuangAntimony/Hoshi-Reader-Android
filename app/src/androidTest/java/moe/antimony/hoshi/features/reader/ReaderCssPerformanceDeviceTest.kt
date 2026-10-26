package moe.antimony.hoshi.features.reader

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderCssPerformanceDeviceTest {
    // Generated CSS only: no Activity, settings, library or account is touched.
    @Test
    fun largePublisherStylesheetDoesNotStallChapterLoading() {
        val css = buildString {
            repeat(12_000) { index ->
                append(".c$index {\nwriting-mode: vertical-rl; height: 100%; text-indent: 1em; color: #123456;\n}\n")
                append(".annotation$index {\n-epub-text-emphasis-style: filled sesame;\n}\n")
            }
        }
        val expected = buildString {
            repeat(12_000) { index ->
                append(".c$index { text-indent: 0; color: #123456;\n}\n")
                append(".annotation$index {\n-webkit-text-emphasis-style: filled sesame;\ntext-emphasis-style: filled sesame;\n}\n")
            }
        }
        sanitizeReaderCss(".c0 { color: black; }")
        val started = SystemClock.elapsedRealtime()
        val actual = sanitizeReaderCss(css)
        val elapsed = SystemClock.elapsedRealtime() - started
        Log.i("HoshiCssPerformance", "bytes=${css.length} elapsedMs=$elapsed")
        assertEquals(expected, actual)
        // Wide margin over a linear pass; catches repeated whole-input ICU setup on Android.
        assertTrue("CSS sanitizing took ${elapsed}ms", elapsed < 2_000)
    }
}
