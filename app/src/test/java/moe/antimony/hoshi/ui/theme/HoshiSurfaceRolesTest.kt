package moe.antimony.hoshi.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import org.junit.Assert.*
import org.junit.Test

class HoshiSurfaceRolesTest {
    @Test fun accentSourceUsesSystemThenFallbackAndManualSeedOnEveryAndroidVersion() {
        val system = hoshiSeedColorScheme(0xFF00796B, false)
        assertEquals(system.primary, hoshiColorScheme(false, false, systemColorScheme = system).primary)
        assertEquals(Purple40, hoshiColorScheme(false, false, systemColorScheme = null).primary)
        val manual = hoshiSeedColorScheme(0xFFC62828, false)
        assertEquals(manual.primary, hoshiColorScheme(false, false, accentSeed = 0xFFC62828, systemColorScheme = system).primary)
        assertEquals(manual.primary, hoshiColorScheme(false, false, accentSeed = 0xFFC62828, systemColorScheme = null).primary)
        assertEquals(Color.Black, hoshiColorScheme(false, true, accentSeed = 0xFFC62828, systemColorScheme = system).primary)
    }

    @Test fun nativeSurfacesKeepTonalHierarchyWithOnlyASubtleAccentTint() {
        for (dark in listOf(false, true)) {
            val sources = listOf(
                hoshiSeedColorScheme(0xFF1565C0, dark),
                hoshiSeedColorScheme(0xFFC62828, dark),
                hoshiSeedColorScheme(0xFF2E7D32, dark),
            ) + if (dark) emptyList() else listOf(
                // Surface values captured from the Samsung system dynamic palette.
                hoshiSeedColorScheme(0xFF1565C0, false).copy(
                    surfaceContainerLow = Color(0xFFEEF4FB),
                    surfaceContainer = Color(0xFFE8EFF6),
                    outlineVariant = Color(0xFFC0C7CD),
                ),
            )
            for (source in sources) {
                val resolved = hoshiColorScheme(dark, false, systemColorScheme = source)
                val before = hoshiSurfaceRoles(source, dark, false)
                val after = hoshiSurfaceRoles(resolved, dark, false)
                listOf(before.page to after.page, before.group to after.group,
                    before.nested to after.nested, before.overlay to after.overlay).forEach { (original, softened) ->
                    val originalHct = Hct.fromInt(original.toArgb())
                    val softenedHct = Hct.fromInt(softened.toArgb())
                    // Allow for HCT to 8-bit RGB quantization, especially near black.
                    assertTrue("chroma=${softenedHct.chroma}", softenedHct.chroma <= 5.0)
                    assertEquals(originalHct.tone, softenedHct.tone, 0.3)
                }
                assertEquals(source.primary, resolved.primary)
                assertEquals(source.secondaryContainer, resolved.secondaryContainer)
                assertEquals(source.onSurface, resolved.onSurface)
                assertEquals(source.outline, resolved.outline)
                assertTrue(after.group.luminance() > after.page.luminance())
                val dividerContrast = contrast(after.divider, after.group)
                assertTrue("dividerContrast=$dividerContrast", dividerContrast in 1.15f..1.6f)
                assertTrue(dividerContrast < contrast(before.divider, before.group))
                assertTrue(contrast(after.group, after.content) >= 4.4f)
            }
        }
    }

    private fun contrast(first: Color, second: Color) =
        (maxOf(first.luminance(), second.luminance()) + .05f) /
            (minOf(first.luminance(), second.luminance()) + .05f)

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
                val scheme = hoshiColorScheme(dark, false, accentSeed = seed)
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
