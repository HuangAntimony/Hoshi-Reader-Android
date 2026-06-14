package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSasayakiColorsTest {
    private val colors = SasayakiSettings(
        lightTextColor = 0xFF010203,
        lightBackgroundColor = 0x44040506,
        darkTextColor = 0xFF070809,
        darkBackgroundColor = 0xAA0A0B0C,
    )

    @Test
    fun lightAndDarkReaderThemesSelectMatchingSasayakiColorGroups() {
        assertEquals(
            ReaderSasayakiColors(textColor = 0xFF010203, backgroundColor = 0x44040506),
            readerSasayakiColors(
                settings = ReaderSettings(theme = ReaderTheme.Light),
                sasayakiSettings = colors,
                systemDark = true,
            ),
        )
        assertEquals(
            ReaderSasayakiColors(textColor = 0xFF070809, backgroundColor = 0xAA0A0B0C),
            readerSasayakiColors(
                settings = ReaderSettings(theme = ReaderTheme.Dark),
                sasayakiSettings = colors,
                systemDark = false,
            ),
        )
    }

    @Test
    fun systemReaderThemeSelectsSasayakiColorGroupFromSystemDarkMode() {
        assertEquals(
            ReaderSasayakiColors(textColor = 0xFF010203, backgroundColor = 0x44040506),
            readerSasayakiColors(
                settings = ReaderSettings(theme = ReaderTheme.System),
                sasayakiSettings = colors,
                systemDark = false,
            ),
        )
        assertEquals(
            ReaderSasayakiColors(textColor = 0xFF070809, backgroundColor = 0xAA0A0B0C),
            readerSasayakiColors(
                settings = ReaderSettings(theme = ReaderTheme.System),
                sasayakiSettings = colors,
                systemDark = true,
            ),
        )
    }

    @Test
    fun customReaderThemeSelectsSasayakiColorGroupFromInterfaceTheme() {
        assertEquals(
            ReaderSasayakiColors(textColor = 0xFF010203, backgroundColor = 0x44040506),
            readerSasayakiColors(
                settings = ReaderSettings(
                    theme = ReaderTheme.Custom,
                    uiTheme = ReaderInterfaceTheme.Light,
                ),
                sasayakiSettings = colors,
                systemDark = true,
            ),
        )
        assertEquals(
            ReaderSasayakiColors(textColor = 0xFF070809, backgroundColor = 0xAA0A0B0C),
            readerSasayakiColors(
                settings = ReaderSettings(
                    theme = ReaderTheme.Custom,
                    uiTheme = ReaderInterfaceTheme.Dark,
                ),
                sasayakiSettings = colors,
                systemDark = false,
            ),
        )
    }

    @Test
    fun sepiaInvertInSystemDarkSelectsDarkSasayakiColorsLikeIos() {
        val settings = ReaderSettings(theme = ReaderTheme.Sepia, sepiaInvertInDark = true)

        assertEquals(
            ReaderSasayakiColors(textColor = 0xFF010203, backgroundColor = 0x44040506),
            readerSasayakiColors(settings = settings, sasayakiSettings = colors, systemDark = false),
        )
        assertEquals(
            ReaderSasayakiColors(textColor = 0xFF070809, backgroundColor = 0xAA0A0B0C),
            readerSasayakiColors(settings = settings, sasayakiSettings = colors, systemDark = true),
        )
    }
}
