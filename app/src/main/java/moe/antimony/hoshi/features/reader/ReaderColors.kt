package moe.antimony.hoshi.features.reader

import java.util.Locale

private const val ReaderColorAlphaMask = 0xFF000000L
private const val ReaderColorRedMask = 0x00FF0000L
private const val ReaderColorGreenMask = 0x0000FF00L
private const val ReaderColorBlueMask = 0x000000FFL
private const val ReaderColorRgbMask = 0x00FFFFFFL

internal fun readerColorFromHexInput(input: String): Long? {
    val hex = input.trim().removePrefix("#")
    if (hex.length != 6 && hex.length != 8) return null
    if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    val value = hex.toLong(16)
    return if (hex.length == 6) {
        ReaderColorAlphaMask or value
    } else {
        val rgb = value ushr 8
        val alpha = value and 0xFFL
        (alpha shl 24) or rgb
    }
}

internal fun Long.toReaderColorHexInput(includeAlpha: Boolean): String {
    val rgb = this and ReaderColorRgbMask
    if (!includeAlpha) {
        return String.format(Locale.US, "#%06X", rgb)
    }
    val alpha = readerColorAlpha()
    return String.format(Locale.US, "#%06X%02X", rgb, alpha)
}

internal fun Long.readerColorAlpha(): Int =
    ((this and ReaderColorAlphaMask) ushr 24).toInt()

internal fun Long.readerColorRed(): Int =
    ((this and ReaderColorRedMask) ushr 16).toInt()

internal fun Long.readerColorGreen(): Int =
    ((this and ReaderColorGreenMask) ushr 8).toInt()

internal fun Long.readerColorBlue(): Int =
    (this and ReaderColorBlueMask).toInt()

internal fun Long.withReaderColorAlpha(alpha: Int): Long =
    (this and ReaderColorAlphaMask.inv()) or ((alpha.coerceIn(0, 255).toLong() and 0xFFL) shl 24)

internal fun Long.withReaderColorRed(red: Int): Long =
    (this and ReaderColorRedMask.inv()) or ((red.coerceIn(0, 255).toLong() and 0xFFL) shl 16)

internal fun Long.withReaderColorGreen(green: Int): Long =
    (this and ReaderColorGreenMask.inv()) or ((green.coerceIn(0, 255).toLong() and 0xFFL) shl 8)

internal fun Long.withReaderColorBlue(blue: Int): Long =
    (this and ReaderColorBlueMask.inv()) or (blue.coerceIn(0, 255).toLong() and 0xFFL)
