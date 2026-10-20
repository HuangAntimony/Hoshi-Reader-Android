package moe.antimony.hoshi.features.anki

import java.net.URI
import moe.antimony.hoshi.network.isPrivateHttpHost

class AnkiConnectUrlException(
    override val message: String,
) : IllegalArgumentException(message)

object AnkiConnectUrlValidator {
    fun requireValidEndpoint(rawUrl: String): URI {
        val trimmed = rawUrl.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: throw AnkiConnectUrlException(InvalidUrlMessage)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if (scheme !in setOf("http", "https") || host.isNullOrBlank()) {
            throw AnkiConnectUrlException(InvalidUrlMessage)
        }
        if (scheme == "http" && !host.isPrivateHttpHost()) {
            throw AnkiConnectUrlException(PublicHttpMessage)
        }
        return uri
    }

    private const val InvalidUrlMessage = "Enter a valid http:// or https:// AnkiConnect URL."
    private const val PublicHttpMessage = "Public AnkiConnect HTTP URLs are blocked. Use HTTPS for internet hosts."
}
