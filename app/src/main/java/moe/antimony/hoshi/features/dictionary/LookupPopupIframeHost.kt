package moe.antimony.hoshi.features.dictionary

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import moe.antimony.hoshi.features.reader.ReaderLookupPopupResourceHandler

internal class LookupPopupIframeWebViewClient(
    private val resourceHandler: ReaderLookupPopupResourceHandler,
) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        resourceHandler.handle(request.url)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
        resourceHandler.handle(Uri.parse(url))
}

internal fun lookupPopupIframeHostHtml(
    dismissTopPopupOnOutsideTap: Boolean = false,
): String {
    val outsideTapFlag = if (dismissTopPopupOnOutsideTap) "true" else "false"
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
                html,
                body {
                    margin: 0;
                    width: 100%;
                    height: 100%;
                    background: transparent;
                    overflow: hidden;
                    overscroll-behavior: none;
                }
            </style>
            <script>
                window.__hoshiReaderPopupHostDismissTopPopupOnOutsideTap = $outsideTapFlag;
            </script>
            <script src="https://appassets.androidplatform.net/popup/reader-popup-host.js"></script>
        </head>
        <body></body>
        </html>
    """.trimIndent()
}
