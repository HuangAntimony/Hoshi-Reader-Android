package moe.antimony.hoshi.features.anki

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebView
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One Bing image result. turl=Bing CDN thumbnail; murl=original image. */
internal data class ImageCandidate(
    val thumbUrl: String,
    val fullUrl: String,
    val title: String = "",
    val sourcePage: String = "",
)

internal interface ImageSearchSource {
    suspend fun candidates(term: String, limit: Int = IMAGE_SEARCH_DEFAULT_LIMIT): List<ImageCandidate>
}

internal const val IMAGE_SEARCH_DEFAULT_LIMIT = 18

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Pure: maps raw `a.iusc` `m`-attribute JSON strings to candidates, dropping malformed/urlless entries and deduping by full URL (order preserved). */
internal fun parseIusc(mAttrs: List<String>): List<ImageCandidate> {
    val out = mutableListOf<ImageCandidate>()
    val seen = mutableSetOf<String>()
    for (raw in mAttrs) {
        val candidate = runCatching {
            val obj = lenientJson.parseToJsonElement(raw).jsonObject
            fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val thumb = str("turl")
            val full = str("murl")
            if (thumb.isBlank() || full.isBlank()) null
            else ImageCandidate(thumbUrl = thumb, fullUrl = full, title = str("t"), sourcePage = str("purl"))
        }.getOrNull() ?: continue
        if (candidate.fullUrl in seen) continue
        seen += candidate.fullUrl
        out += candidate
    }
    return out
}

// A real desktop UA is MANDATORY: WebView's default UA makes Bing serve an unrelated
// default feed. See anki-enrich/docs/image-search-handoff.md. Do not remove.
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

// adlt=off + the SRCHHPGUSR cookie Bing honours -> SafeSearch off (single-user fork).
private const val BING_SEARCH_URL = "https://www.bing.com/images/search?form=HDRSC2&adlt=off&q="
private const val IMAGE_SEARCH_TIMEOUT_MS = 9_000L
private const val IMAGE_SEARCH_RESULT_FLOOR = 12
private const val SCRAPE_IUSC_JS =
    "Array.from(document.querySelectorAll('a.iusc')).map(e=>e.getAttribute('m')).filter(Boolean)"

/** Renders Bing image search in an offscreen WebView and scrapes `a.iusc` m-attrs. Returns [] on any failure/timeout — never throws. */
internal class BingImageSearchSource(private val appContext: Context) : ImageSearchSource {
    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun candidates(term: String, limit: Int): List<ImageCandidate> {
        if (term.isBlank()) return emptyList()
        return runCatching {
            withContext(Dispatchers.Main) {
                val webView = WebView(appContext)
                try {
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = DESKTOP_USER_AGENT
                        blockNetworkImage = true        // we need the JS-built DOM, not pixels
                        loadsImagesAutomatically = false
                        // AGENTS.md: do NOT enable allowUniversalAccessFromFileURLs.
                    }
                    CookieManager.getInstance()
                        .setCookie("https://www.bing.com", "SRCHHPGUSR=ADLT=OFF; domain=.bing.com; path=/")
                    webView.loadUrl(BING_SEARCH_URL + URLEncoder.encode(term, "UTF-8"))

                    // Poll the JS-built grid until a healthy batch or the deadline; return
                    // whatever we have at the deadline (partial results beat nothing).
                    val deadline = SystemClock.uptimeMillis() + IMAGE_SEARCH_TIMEOUT_MS
                    var attrs = emptyList<String>()
                    while (SystemClock.uptimeMillis() < deadline) {
                        delay(250)
                        attrs = scrapeMAttrs(webView)
                        if (attrs.size >= IMAGE_SEARCH_RESULT_FLOOR) break
                    }
                    parseIusc(attrs).take(limit)
                } finally {
                    webView.stopLoading()
                    webView.destroy()
                }
            }
        }.getOrNull() ?: emptyList()
    }

    private suspend fun scrapeMAttrs(webView: WebView): List<String> =
        withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine { cont ->
                webView.evaluateJavascript(SCRAPE_IUSC_JS) { json ->
                    // evaluateJavascript returns the JS value JSON-encoded; for an array of
                    // strings that is a JSON array ("null" before the script can run).
                    val parsed = runCatching {
                        lenientJson.parseToJsonElement(json).jsonArray
                            .mapNotNull { it.jsonPrimitive.contentOrNull }
                    }.getOrDefault(emptyList())
                    if (cont.isActive) cont.resume(parsed)
                }
            }
        } ?: emptyList()
}
