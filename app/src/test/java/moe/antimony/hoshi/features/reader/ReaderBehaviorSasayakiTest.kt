package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderBehaviorSasayakiTest {
    @Test
    fun behaviorAlwaysShowsSasayakiVolumeSeek() {
        assertEquals(
            listOf("Volume Keys Seek Sasayaki"),
            readerBehaviorSasayakiRows(),
        )
    }
}
