package moe.antimony.hoshi.network

internal fun String.isPrivateHttpHost(): Boolean =
    this == "localhost" ||
        endsWith(".localhost") ||
        endsWith(".local") ||
        isPrivateIpv4Literal() ||
        isPrivateIpv6Literal()

private fun String.isPrivateIpv4Literal(): Boolean {
    val parts = split(".")
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    if (octets.any { it !in 0..255 }) return false
    return octets[0] == 10 ||
        octets[0] == 127 ||
        octets[0] == 192 && octets[1] == 168 ||
        octets[0] == 172 && octets[1] in 16..31 ||
        octets[0] == 169 && octets[1] == 254
}

private fun String.isPrivateIpv6Literal(): Boolean {
    val normalized = removePrefix("[").removeSuffix("]").lowercase()
    val firstHextet = normalized.substringBefore(":").toIntOrNull(16)
    return normalized == "::1" ||
        normalized == "0:0:0:0:0:0:0:1" ||
        normalized.startsWith("fe80:") ||
        (firstHextet != null && firstHextet in 0xfc00..0xfdff)
}
