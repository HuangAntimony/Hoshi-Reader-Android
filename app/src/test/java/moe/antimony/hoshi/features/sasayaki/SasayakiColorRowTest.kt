package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertEquals
import org.junit.Test

class SasayakiColorRowTest {
    @Test
    fun colorRowsExposeIosDefaultsInLightAndDarkGroups() {
        assertEquals(0xFF000000, SasayakiColorRow.LightText.defaultColor)
        assertEquals(0x6687CEEB, SasayakiColorRow.LightBackground.defaultColor)
        assertEquals(0xFFFFFFFF, SasayakiColorRow.DarkText.defaultColor)
        assertEquals(0x6687CEEB, SasayakiColorRow.DarkBackground.defaultColor)
    }

    @Test
    fun colorRowsReadConfiguredSasayakiColors() {
        val settings = SasayakiSettings(
            lightTextColor = 0xFF010203,
            lightBackgroundColor = 0x44040506,
            darkTextColor = 0xFF070809,
            darkBackgroundColor = 0xAA0A0B0C,
        )

        assertEquals(0xFF010203, SasayakiColorRow.LightText.color(settings))
        assertEquals(0x44040506, SasayakiColorRow.LightBackground.color(settings))
        assertEquals(0xFF070809, SasayakiColorRow.DarkText.color(settings))
        assertEquals(0xAA0A0B0C, SasayakiColorRow.DarkBackground.color(settings))
    }

    @Test
    fun colorRowsUpdateOnlyTheirOwnedColor() {
        val settings = SasayakiSettings(
            lightTextColor = 0xFF010203,
            lightBackgroundColor = 0x44040506,
            darkTextColor = 0xFF070809,
            darkBackgroundColor = 0xAA0A0B0C,
        )

        assertEquals(
            settings.copy(lightTextColor = 0xFF111111),
            SasayakiColorRow.LightText.updated(settings, 0xFF111111),
        )
        assertEquals(
            settings.copy(lightBackgroundColor = 0x22123456),
            SasayakiColorRow.LightBackground.updated(settings, 0x22123456),
        )
        assertEquals(
            settings.copy(darkTextColor = 0xFFEEEEEE),
            SasayakiColorRow.DarkText.updated(settings, 0xFFEEEEEE),
        )
        assertEquals(
            settings.copy(darkBackgroundColor = 0x88456789),
            SasayakiColorRow.DarkBackground.updated(settings, 0x88456789),
        )
    }
}
