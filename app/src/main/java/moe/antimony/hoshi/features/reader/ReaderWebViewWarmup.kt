package moe.antimony.hoshi.features.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import moe.antimony.hoshi.features.diagnostics.PerformanceLog

object ReaderWebViewWarmup {
    private var dummy: WebView? = null
    private var warmedUp = false

    fun warmUp(context: Context) {
        if (warmedUp) return
        warmedUp = true
        val applicationContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            if (dummy != null) return@post
            val warmupStart = PerformanceLog.start()
            dummy = WebView(applicationContext).apply {
                loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
            }
            PerformanceLog.dElapsed(
                PerformanceLog.ReaderTag,
                "reader WebView warmup created",
                warmupStart,
            )
        }
    }

    fun release() {
        val webView = dummy ?: return
        dummy = null
        webView.destroy()
        PerformanceLog.d(PerformanceLog.ReaderTag, "reader WebView warmup released")
    }
}
