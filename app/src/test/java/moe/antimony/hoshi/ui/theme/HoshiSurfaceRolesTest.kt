package moe.antimony.hoshi.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class HoshiSurfaceRolesTest {
    @Test fun accentSourceUsesSystemThenFallbackAndManualSeedOnEveryAndroidVersion() {
        val system = hoshiSeedColorScheme(0xFF00796B, false)
        assertEquals(system, hoshiColorScheme(false, false, systemColorScheme = system))
        assertEquals(hoshiColorScheme(false, false), hoshiColorScheme(false, false, systemColorScheme = null))
        val manual = hoshiSeedColorScheme(0xFFC62828, false)
        assertEquals(manual.primary, hoshiColorScheme(false, false, accentSeed = 0xFFC62828, systemColorScheme = system).primary)
        assertEquals(manual.primary, hoshiColorScheme(false, false, accentSeed = 0xFFC62828, systemColorScheme = null).primary)
        assertEquals(Color.Black, hoshiColorScheme(false, true, accentSeed = 0xFFC62828, systemColorScheme = system).primary)
    }

    @Test fun containersRemainBrighterThanThePageInBothModes() {
        for (dark in listOf(false, true)) {
            val roles = hoshiSurfaceRoles(hoshiColorScheme(dark, false), dark, false)
            assertTrue(roles.group.luminance() > roles.page.luminance())
            assertFalse(roles.outlineContainers)
        }
    }

    @Test fun eInkRequiresPhysicalOutlinesWhenContainerColorsCollapse() {
        for (dark in listOf(false, true)) {
            val roles = hoshiSurfaceRoles(hoshiColorScheme(dark, true), dark, true)
            assertEquals(roles.page, roles.group)
            assertEquals(roles.group, roles.nested)
            assertEquals(roles.nested, roles.overlay)
            assertTrue(roles.outlineContainers)
            assertNotEquals(roles.page, roles.outline)
        }
    }

    @Test fun seededSchemesProduceReadableCompletePalettes() {
        for (seed in listOf(0xFF6750A4, 0xFF1565C0, 0xFF00796B, 0xFF2E7D32, 0xFFF9A825, 0xFFEF6C00, 0xFFC62828, 0xFFAD1457, 0xFFFFFFFF, 0xFF000000)) {
            for (dark in listOf(false, true)) {
                val scheme = hoshiSeedColorScheme(seed, dark)
                val pairs = listOf(
                    scheme.primary to scheme.onPrimary,
                    scheme.primaryContainer to scheme.onPrimaryContainer,
                    scheme.secondary to scheme.onSecondary,
                    scheme.tertiary to scheme.onTertiary,
                    scheme.error to scheme.onError,
                    scheme.surface to scheme.onSurface,
                    scheme.surfaceContainerHigh to scheme.onSurfaceVariant,
                )
                pairs.forEach { (background, text) ->
                    val contrast = (maxOf(background.luminance(), text.luminance()) + .05f) /
                        (minOf(background.luminance(), text.luminance()) + .05f)
                    assertTrue("seed=$seed dark=$dark contrast=$contrast", contrast >= 4.4f)
                    assertEquals(1f, background.alpha)
                    assertEquals(1f, text.alpha)
                }
                val roles = hoshiSurfaceRoles(scheme, dark, false)
                assertTrue(roles.group.luminance() > roles.page.luminance())
                assertNotEquals(Color.Unspecified, scheme.surfaceContainerHighest)
                assertNotEquals(scheme.primary, scheme.onPrimary)
            }
        }
    }
}
