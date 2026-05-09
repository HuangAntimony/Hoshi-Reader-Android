package moe.antimony.hoshi.features.anki

import android.net.Uri
import androidx.core.content.FileProvider

class HoshiFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? =
        when (uri.lastPathSegment?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()) {
            "aac" -> "audio/aac"
            "m4a" -> "audio/mp4"
            else -> super.getType(uri)
        }
}
