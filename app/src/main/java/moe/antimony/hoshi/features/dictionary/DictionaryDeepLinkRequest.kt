package moe.antimony.hoshi.features.dictionary

import android.content.Intent
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal const val OpenDictionaryLookupAction = "moe.antimony.hoshi.action.OPEN_DICTIONARY_LOOKUP"
internal const val OpenDictionaryLookupTextExtra = "moe.antimony.hoshi.extra.DICTIONARY_LOOKUP_TEXT"

internal enum class DictionaryDeepLinkDestination {
    Overlay,
    MainApp,
}

internal data class DictionaryDeepLinkRequest(
    val text: String,
    val destination: DictionaryDeepLinkDestination,
) {
    companion object {
        fun from(action: String?, uri: String?): DictionaryDeepLinkRequest? {
            if (action != Intent.ACTION_VIEW || uri == null) return null

            return runCatching {
                val parsedUri = URI(uri)
                if (parsedUri.scheme != "hoshi" || parsedUri.host != "search") return null

                val queryParameters = parsedUri.rawQuery
                    ?.split('&')
                    .orEmpty()
                    .map { parameter ->
                        val parts = parameter.split('=', limit = 2)
                        decode(parts[0]) to decode(parts.getOrElse(1) { "" })
                    }
                val text = queryParameters.firstOrNull { it.first == "text" }?.second.orEmpty()
                val mode = queryParameters.firstOrNull { it.first == "mode" }?.second
                DictionaryDeepLinkRequest(
                    text = text,
                    destination = if (mode == "app" || text.isBlank()) {
                        DictionaryDeepLinkDestination.MainApp
                    } else {
                        DictionaryDeepLinkDestination.Overlay
                    },
                )
            }.getOrNull()
        }

        fun fromIntent(intent: Intent?): DictionaryDeepLinkRequest? =
            from(
                action = intent?.action,
                uri = intent?.dataString,
            )

        private fun decode(value: String): String =
            URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }
}

data class PendingDictionaryLookupRequest(
    val query: String,
    val requestId: Long,
) {
    companion object {
        fun from(
            action: String?,
            query: String?,
            requestId: Long,
        ): PendingDictionaryLookupRequest? =
            if (action == OpenDictionaryLookupAction) {
                PendingDictionaryLookupRequest(
                    query = query.orEmpty(),
                    requestId = requestId,
                )
            } else {
                null
            }

        fun fromIntent(intent: Intent?, requestId: Long): PendingDictionaryLookupRequest? =
            from(
                action = intent?.action,
                query = intent?.getStringExtra(OpenDictionaryLookupTextExtra),
                requestId = requestId,
            )
    }
}
