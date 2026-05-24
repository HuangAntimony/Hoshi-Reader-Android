package moe.antimony.hoshi.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

val LocalHoshiEInkMode = staticCompositionLocalOf { false }
val LocalHoshiDarkTheme = staticCompositionLocalOf { false }

internal fun hoshiColorScheme(darkTheme: Boolean, eInkMode: Boolean) = when {
    eInkMode && darkTheme -> eInkColorScheme(dark = true)
    eInkMode -> eInkColorScheme(dark = false)
    darkTheme -> DarkColorScheme
    else -> LightColorScheme
}

private fun eInkColorScheme(dark: Boolean) = if (dark) {
    pureColorScheme(
        background = Color.Black,
        content = Color.White,
        inverseBackground = Color.White,
        inverseContent = Color.Black,
    )
} else {
    pureColorScheme(
        background = Color.White,
        content = Color.Black,
        inverseBackground = Color.Black,
        inverseContent = Color.White,
    )
}

private fun pureColorScheme(
    background: Color,
    content: Color,
    inverseBackground: Color,
    inverseContent: Color,
) = lightColorScheme(
    primary = content,
    onPrimary = background,
    primaryContainer = content,
    onPrimaryContainer = background,
    inversePrimary = inverseContent,
    secondary = content,
    onSecondary = background,
    secondaryContainer = content,
    onSecondaryContainer = background,
    tertiary = content,
    onTertiary = background,
    tertiaryContainer = content,
    onTertiaryContainer = background,
    background = background,
    onBackground = content,
    surface = background,
    onSurface = content,
    surfaceVariant = background,
    onSurfaceVariant = content,
    surfaceTint = Color.Transparent,
    inverseSurface = inverseBackground,
    inverseOnSurface = inverseContent,
    error = content,
    onError = background,
    errorContainer = content,
    onErrorContainer = background,
    outline = content,
    outlineVariant = content,
    scrim = content,
    surfaceBright = background,
    surfaceContainer = background,
    surfaceContainerHigh = background,
    surfaceContainerHighest = background,
    surfaceContainerLow = background,
    surfaceContainerLowest = background,
    surfaceDim = background,
    primaryFixed = content,
    primaryFixedDim = content,
    onPrimaryFixed = background,
    onPrimaryFixedVariant = background,
    secondaryFixed = content,
    secondaryFixedDim = content,
    onSecondaryFixed = background,
    onSecondaryFixedVariant = background,
    tertiaryFixed = content,
    tertiaryFixedDim = content,
    onTertiaryFixed = background,
    onTertiaryFixedVariant = background,
)

@Composable
fun HoshiReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    eInkMode: Boolean = false,
    useDarkSystemBarIcons: Boolean = !darkTheme,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val colorScheme = when {
        dynamicColor && !eInkMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        else -> hoshiColorScheme(darkTheme = darkTheme, eInkMode = eInkMode)
    }

    SideEffect {
        val activity = context.findActivity() ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightStatusBars = useDarkSystemBarIcons
        controller.isAppearanceLightNavigationBars = useDarkSystemBarIcons
    }

    CompositionLocalProvider(
        LocalHoshiEInkMode provides eInkMode,
        LocalHoshiDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
