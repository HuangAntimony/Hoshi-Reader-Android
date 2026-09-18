package moe.antimony.hoshi.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct

/** Keep Material's tonal hierarchy, with quieter neutral surfaces around white cards. */
internal fun ColorScheme.withHoshiSurfaceColors(dark: Boolean): ColorScheme {
    val group = (if (dark) surfaceContainerLow else surfaceContainerLowest).subtleTint()
    return copy(
        background = background.subtleTint(),
        surface = surface.subtleTint(),
        surfaceVariant = surfaceVariant.subtleTint(),
        surfaceBright = surfaceBright.subtleTint(),
        surfaceDim = surfaceDim.subtleTint(),
        inverseSurface = inverseSurface.subtleTint(),
        surfaceContainerLowest = surfaceContainerLowest.subtleTint(),
        surfaceContainerLow = surfaceContainerLow.subtleTint(),
        surfaceContainer = surfaceContainer.subtleTint(),
        surfaceContainerHigh = surfaceContainerHigh.subtleTint(),
        surfaceContainerHighest = surfaceContainerHighest.subtleTint(),
        // Decorative separators recede; outline keeps its full strength for input boundaries.
        outlineVariant = lerp(outlineVariant.subtleTint(), group, 0.55f),
    )
}

private fun Color.subtleTint(): Color {
    val hct = Hct.fromInt(toArgb())
    return if (hct.chroma <= 4.0) this else Color(Hct.from(hct.hue, 4.0, hct.tone).toInt())
}
