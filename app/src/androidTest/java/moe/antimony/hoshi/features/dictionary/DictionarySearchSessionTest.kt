package moe.antimony.hoshi.features.dictionary

import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory pages only; does not read or change the installed app's data. */
@RunWith(AndroidJUnit4::class)
class DictionarySearchSessionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun tabRoundTripPreservesCurrentPageAndForwardHistory() {
        val visible = mutableStateOf(true)
        val shellVisible = mutableStateOf(true)
        lateinit var current: WebView
        lateinit var session: DictionarySearchSession
        composeRule.setContent {
            if (!shellVisible.value) return@setContent
            session = rememberDictionarySearchSession()
            if (visible.value) {
                DictionarySearchSessionWebView(
                    session = session,
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            loadDataWithBaseURL(null, """
                                <html><body style="min-height:6000px"><div id="word">A</div><script>
                                let back = [], forward = [];
                                function show(word) { document.getElementById('word').textContent = word; }
                                function redirect() { back.push('A'); show('B'); }
                                function goBack() { forward.push('B'); show(back.pop()); }
                                function goForward() { back.push('A'); show(forward.pop()); }
                                </script></body></html>
                            """.trimIndent(), "text/html", "utf-8", null)
                        }
                    },
                    update = { current = it },
                )
            }
        }
        composeRule.waitUntil(10_000) { evaluate(current, "document.getElementById('word')?.textContent") == "\"A\"" }
        evaluate(current, "redirect(); goBack(); window.scrollTo(0, 100); true")
        assertEquals("\"A\"", evaluate(current, "document.getElementById('word').textContent"))
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { visible.value = true }
        composeRule.waitForIdle()
        composeRule.waitUntil(10_000) { evaluate(current, "document.getElementById('word')?.textContent") == "\"A\"" }
        assertEquals("scroll position must survive leaving the Dictionary tab", "100", evaluate(current, "window.scrollY"))
        assertEquals("history must survive leaving the Dictionary tab", "1", evaluate(current, "forward.length"))
        evaluate(current, "goForward(); true")
        assertEquals("\"B\"", evaluate(current, "document.getElementById('word').textContent"))
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { visible.value = true }
        composeRule.waitForIdle()
        assertEquals("\"B\"", evaluate(current, "document.getElementById('word').textContent"))
        evaluate(current, "goBack(); true")
        assertEquals("\"A\"", evaluate(current, "document.getElementById('word').textContent"))
        composeRule.runOnIdle { shellVisible.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertNull(session.webView) }
    }

    private fun evaluate(webView: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.evaluateJavascript(script) { result = it; latch.countDown() }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return result
    }
}
