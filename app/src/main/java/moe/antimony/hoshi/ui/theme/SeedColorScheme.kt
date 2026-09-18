package moe.antimony.hoshi.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeTonalSpot

/** Full Material palette: the user's seed is not a literal foreground/background color. */
internal fun hoshiSeedColorScheme(seedArgb: Long, dark: Boolean): ColorScheme {
    val scheme = SchemeTonalSpot(Hct.fromInt(seedArgb.toInt()), dark, 0.0, ColorSpec.SpecVersion.SPEC_2021)
    return lightColorScheme(
        primary = Color(scheme.primary), onPrimary = Color(scheme.onPrimary),
        primaryContainer = Color(scheme.primaryContainer), onPrimaryContainer = Color(scheme.onPrimaryContainer),
        inversePrimary = Color(scheme.inversePrimary),
        secondary = Color(scheme.secondary), onSecondary = Color(scheme.onSecondary),
        secondaryContainer = Color(scheme.secondaryContainer), onSecondaryContainer = Color(scheme.onSecondaryContainer),
        tertiary = Color(scheme.tertiary), onTertiary = Color(scheme.onTertiary),
        tertiaryContainer = Color(scheme.tertiaryContainer), onTertiaryContainer = Color(scheme.onTertiaryContainer),
        background = Color(scheme.background), onBackground = Color(scheme.onBackground),
        surface = Color(scheme.surface), onSurface = Color(scheme.onSurface),
        surfaceVariant = Color(scheme.surfaceVariant), onSurfaceVariant = Color(scheme.onSurfaceVariant),
        surfaceTint = Color(scheme.surfaceTint), inverseSurface = Color(scheme.inverseSurface),
        inverseOnSurface = Color(scheme.inverseOnSurface),
        error = Color(scheme.error), onError = Color(scheme.onError),
        errorContainer = Color(scheme.errorContainer), onErrorContainer = Color(scheme.onErrorContainer),
        outline = Color(scheme.outline), outlineVariant = Color(scheme.outlineVariant), scrim = Color(scheme.scrim),
        surfaceBright = Color(scheme.surfaceBright), surfaceDim = Color(scheme.surfaceDim),
        surfaceContainerLowest = Color(scheme.surfaceContainerLowest), surfaceContainerLow = Color(scheme.surfaceContainerLow),
        surfaceContainer = Color(scheme.surfaceContainer), surfaceContainerHigh = Color(scheme.surfaceContainerHigh),
        surfaceContainerHighest = Color(scheme.surfaceContainerHighest),
        primaryFixed = Color(scheme.primaryFixed), primaryFixedDim = Color(scheme.primaryFixedDim),
        onPrimaryFixed = Color(scheme.onPrimaryFixed), onPrimaryFixedVariant = Color(scheme.onPrimaryFixedVariant),
        secondaryFixed = Color(scheme.secondaryFixed), secondaryFixedDim = Color(scheme.secondaryFixedDim),
        onSecondaryFixed = Color(scheme.onSecondaryFixed), onSecondaryFixedVariant = Color(scheme.onSecondaryFixedVariant),
        tertiaryFixed = Color(scheme.tertiaryFixed), tertiaryFixedDim = Color(scheme.tertiaryFixedDim),
        onTertiaryFixed = Color(scheme.onTertiaryFixed), onTertiaryFixedVariant = Color(scheme.onTertiaryFixedVariant),
    )
}
