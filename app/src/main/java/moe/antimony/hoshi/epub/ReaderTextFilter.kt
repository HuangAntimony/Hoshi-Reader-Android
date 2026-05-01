package moe.antimony.hoshi.epub

internal fun String.filteredReaderText(): String {
    var text = Regex("(?s)<body.*?</body>").find(this)?.value ?: this
    text = text.replace(Regex("(?s)<rt[^>]*>.*?</rt>"), "")
    text = text.replace(Regex("(?s)<(script|style)[^>]*>.*?</\\1>"), "")
    text = text.replace(Regex("<[^>]+>"), "")
    text = text
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
    return text.replace(
        Regex("[^0-9A-Za-z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚａ-ｚｦ-ﾝ\\u2E80-\\u2FDF\\p{IsHan}]"),
        "",
    )
}
