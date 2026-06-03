package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderColorTest {
    @Test
    fun parsesReaderColorInputAsRgbOrRgbaLikeIosHexStrings() {
        assertEquals(0xFF112233L, readerColorFromHexInput("#112233"))
        assertEquals(0x44112233L, readerColorFromHexInput("#11223344"))
        assertEquals(0xFFAABBCCL, readerColorFromHexInput("AABBCC"))
    }

    @Test
    fun rejectsInvalidReaderColorInputWithoutProducingAColor() {
        assertNull(readerColorFromHexInput(""))
        assertNull(readerColorFromHexInput("#12345"))
        assertNull(readerColorFromHexInput("#GGGGGG"))
    }

    @Test
    fun formatsReaderColorAsIosStyleHexAndCssColor() {
        assertEquals("#112233", 0xFF112233L.toReaderColorHexInput(includeAlpha = false))
        assertEquals("#11223344", 0x44112233L.toReaderColorHexInput(includeAlpha = true))
        assertEquals("#11223344", 0x44112233L.toReaderCssColor(includeAlpha = true))
    }

    @Test
    fun updatesReaderColorChannelsWithoutChangingOtherChannels() {
        val color = 0x44112233L

        assertEquals(0xAA112233L, color.withReaderColorAlpha(0xAA))
        assertEquals(0x44BB2233L, color.withReaderColorRed(0xBB))
        assertEquals(0x4411CC33L, color.withReaderColorGreen(0xCC))
        assertEquals(0x441122DDL, color.withReaderColorBlue(0xDD))
    }
}
