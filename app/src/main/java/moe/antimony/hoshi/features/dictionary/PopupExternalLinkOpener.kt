package moe.antimony.hoshi.features.dictionary

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.net.URI

internal data class ExternalBrowserRequest(
    val url: String,
)

internal fun openPopupExternalLink(
    url: String,
    openExternalBrowser: (ExternalBrowserRequest) -> Unit,
): Boolean {
    val request = externalBrowserRequest(url) ?: return false
    openExternalBrowser(request)
    return true
}

internal fun Context.openPopupExternalLink(url: String): Boolean {
    val request = externalBrowserRequest(url) ?: return false
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(request.url)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        if (this@openPopupExternalLink !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    return try {
        startActivity(intent)
        true
    } catch (error: ActivityNotFoundException) {
        Log.w("PopupExternalLink", "No activity can open popup link: ${request.url}", error)
        false
    }
}

private fun externalBrowserRequest(url: String): ExternalBrowserRequest? {
    val normalizedUrl = url.trim()
    val uri = runCatching { URI(normalizedUrl) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    if (uri.host.isNullOrBlank()) return null
    return ExternalBrowserRequest(normalizedUrl)
}
