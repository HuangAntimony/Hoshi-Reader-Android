package moe.antimony.hoshi.features.dictionary

import android.os.SystemClock
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import moe.antimony.hoshi.features.anki.AnkiFormatIcon
import moe.antimony.hoshi.features.anki.AnkiPopupFormat
import moe.antimony.hoshi.features.anki.AnkiPopupSettings
import moe.antimony.hoshi.webview.applyHoshiWebViewSecurityDefaults
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Production popup HTML/assets, in-memory bridge responses; no app settings or Anki data writes. */
@RunWith(AndroidJUnit4::class)
class LookupPopupMiningWebViewTest {
    @Test
    fun popupResetSettlesRequestsAndRepliesRequireTheCurrentPopup() {
        withPopup(useAnkiConnect = false, preventAccidentalDuplicates = false) { webView ->
            evaluate(webView, """
                (async function() {
                    const send = data => window.dispatchEvent(new MessageEvent('message', {
                        data, origin: 'https://appassets.androidplatform.net', source: window
                    }));
                    function startRequest(popupId, recordResult) {
                        send({type: 'renderPopup', popupId, entriesCount: 0});
                        return new Promise(resolve => {
                            function requestPosted({data}) {
                                if (data.name !== 'duplicateCheck' || data.popupId !== popupId) return;
                                window.removeEventListener('message', requestPosted);
                                resolve(data.id);
                            }
                            window.addEventListener('message', requestPosted);
                            window.HoshiAndroidPopup.requestMessage('duplicateCheck', {}).then(recordResult);
                        });
                    }
                    let oldResult = 'pending';
                    let currentResult = 'pending';
                    const oldId = await startRequest('old', value => { oldResult = value; });
                    send({type: 'resetPopup'});
                    await Promise.resolve();
                    const resultAfterReset = oldResult;
                    const currentId = await startRequest('current', value => { currentResult = value; });
                    send({type: 'reply', popupId: 'old', id: oldId, body: 'late'});
                    send({type: 'reply', popupId: 'old', id: currentId, body: 'wrong-popup'});
                    await Promise.resolve();
                    const resultAfterLateReply = currentResult;
                    send({type: 'reply', popupId: 'current', id: currentId, body: 'accepted'});
                    await Promise.resolve();
                    return {resultAfterReset, oldResult, resultAfterLateReply, currentResult};
                })().then(result => { window.miningTestResult = result; })
                    .catch(error => { window.miningTestResult = {error: String(error)}; });
                true;
            """.trimIndent())
            val result = awaitResult(webView)
            assertTrue(result.toString(), result.isNull("resultAfterReset") && result.has("resultAfterReset"))
            assertTrue(result.toString(), result.isNull("oldResult") && result.has("oldResult"))
            assertEquals(result.toString(), "pending", result.getString("resultAfterLateReply"))
            assertEquals(result.toString(), "accepted", result.getString("currentResult"))
        }
    }

    @Test
    fun successfulMiningLocksOnlyItsFormatAndReopeningAllowsAnotherNote() {
        for (useAnkiConnect in listOf(false, true)) {
            for (preventAccidentalDuplicates in listOf(false, true)) {
                withPopup(useAnkiConnect, preventAccidentalDuplicates) { webView ->
                    evaluate(webView, """
                        window.miningTestResult = undefined;
                        (async function() {
                            let notes = 0;
                            window.HoshiAndroidPopup.requestMessage = async function(name) {
                                if (name === 'mineEntry') { notes++; return true; }
                                if (name === 'duplicateCheck') return {word: true, sentence: false};
                                return null;
                            };
                            function openResult() {
                                window.lookupEntries = [{expression: '猫', reading: 'ねこ', matched: '猫',
                                    frequencies: [], pitches: [], rules: [], glossaries: []}];
                                window.entryCount = 1;
                                const header = createEntryHeader(window.lookupEntries[0], 0);
                                document.getElementById('entries-container').appendChild(header);
                                return header.querySelector('.header-buttons');
                            }
                            const container = openResult();
                            await refreshAnkiDuplicateStates(0, container);
                            const button = container.querySelector('[data-kind="mine"][data-format-id="word"]');
                            const other = container.querySelector('[data-kind="mine"][data-format-id="sentence"]');
                            const initiallyEnabled = !button.disabled;
                            await mineEntryAtIndex(0, 'word', container);
                            const immediatelyDisabled = button.disabled;
                            await new Promise(resolve => setTimeout(resolve, 1100));
                            const disabledAfterRefresh = button.disabled;
                            const otherEnabled = !other.disabled;
                            button.click();
                            await new Promise(resolve => setTimeout(resolve, 1100));
                            const notesBeforeReopen = notes;
                            window.resetPopupResults();
                            const reopened = openResult();
                            await refreshAnkiDuplicateStates(0, reopened);
                            await mineEntryAtIndex(0, 'word', reopened);
                            return {initiallyEnabled, immediatelyDisabled, disabledAfterRefresh,
                                otherEnabled, notesBeforeReopen, notesAfterReopen: notes};
                        })().then(result => { window.miningTestResult = result; })
                            .catch(error => { window.miningTestResult = {error: String(error)}; });
                        true;
                    """.trimIndent())
                    val result = awaitResult(webView)
                    val scenario = "AnkiConnect=$useAnkiConnect safeguard=$preventAccidentalDuplicates: $result"
                    assertTrue(scenario, result.getBoolean("initiallyEnabled"))
                    assertEquals(scenario, preventAccidentalDuplicates || !useAnkiConnect, result.getBoolean("immediatelyDisabled"))
                    assertEquals(scenario, preventAccidentalDuplicates, result.getBoolean("disabledAfterRefresh"))
                    assertTrue(scenario, result.getBoolean("otherEnabled"))
                    assertEquals(scenario, if (preventAccidentalDuplicates) 1 else 2, result.getInt("notesBeforeReopen"))
                    assertEquals(scenario, if (preventAccidentalDuplicates) 2 else 3, result.getInt("notesAfterReopen"))
                }
            }
        }
    }

    private fun awaitResult(webView: WebView): JSONObject {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (evaluate(webView, "window.miningTestResult !== undefined") != "true") {
            assertTrue("Popup test produced a result", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(25)
        }
        val raw = evaluate(webView, "JSON.stringify(window.miningTestResult)")
        return JSONObject(JSONTokener(raw).nextValue() as String)
    }

    private fun withPopup(useAnkiConnect: Boolean, preventAccidentalDuplicates: Boolean, block: (WebView) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val html = LookupPopupHtml.renderIframeDocument(
            assets = LookupPopupAssets.load(context),
            ankiSettings = AnkiPopupSettings(
                isBackendAvailable = true,
                useAnkiConnect = useAnkiConnect,
                allowDupes = true,
                preventAccidentalDuplicates = preventAccidentalDuplicates,
                formats = listOf(
                    AnkiPopupFormat("word", AnkiFormatIcon.Square, isValid = true),
                    AnkiPopupFormat("sentence", AnkiFormatIcon.Circle, isValid = true),
                ),
            ),
        )
        val loaded = CountDownLatch(1)
        lateinit var webView: WebView
        instrumentation.runOnMainSync {
            webView = WebView(context).apply {
                applyHoshiWebViewSecurityDefaults()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                }
                loadDataWithBaseURL("https://appassets.androidplatform.net/popup/", html, "text/html", "utf-8", null)
            }
        }
        try {
            assertTrue("Popup page loaded", loaded.await(10, TimeUnit.SECONDS))
            block(webView)
        } finally {
            instrumentation.runOnMainSync { webView.destroy() }
        }
    }

    private fun evaluate(webView: WebView, script: String): String {
        val completed = CountDownLatch(1)
        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.evaluateJavascript(script) { result = it; completed.countDown() }
        }
        assertTrue("JavaScript evaluation completed", completed.await(5, TimeUnit.SECONDS))
        return result
    }
}
