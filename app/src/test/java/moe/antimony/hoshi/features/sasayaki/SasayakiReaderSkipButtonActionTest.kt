package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SasayakiReaderSkipButtonActionTest {
    @Test
    fun cueActionMatchesPreviousNextSentenceDefault() {
        assertEquals("1 sentence", SasayakiReaderSkipButtonAction.Cue.label)
        assertNull(SasayakiReaderSkipButtonAction.Cue.seconds)
    }

    @Test
    fun secondActionsExposeTheirSeekDistance() {
        assertEquals("5 seconds", SasayakiReaderSkipButtonAction.Seconds5.label)
        assertEquals("10 seconds", SasayakiReaderSkipButtonAction.Seconds10.label)
        assertEquals("15 seconds", SasayakiReaderSkipButtonAction.Seconds15.label)
        assertEquals("30 seconds", SasayakiReaderSkipButtonAction.Seconds30.label)
        assertEquals(5, SasayakiReaderSkipButtonAction.Seconds5.seconds)
        assertEquals(10, SasayakiReaderSkipButtonAction.Seconds10.seconds)
        assertEquals(15, SasayakiReaderSkipButtonAction.Seconds15.seconds)
        assertEquals(30, SasayakiReaderSkipButtonAction.Seconds30.seconds)
    }
}
