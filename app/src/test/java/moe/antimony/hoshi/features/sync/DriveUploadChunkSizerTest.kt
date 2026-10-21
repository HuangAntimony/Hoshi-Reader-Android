package moe.antimony.hoshi.features.sync

import org.junit.Assert.*
import org.junit.Test

class DriveUploadChunkSizerTest {
    @Test fun targetsThirtySecondsUsingAcknowledgedBytesAndBoundsAlignedChunks() {
        val sizing = DriveUploadChunkSizer()
        assertEquals(4L * 1024 * 1024, sizing.bytes)
        sizing.confirmed(4L * 1024 * 1024, 15_000)
        assertEquals(8L * 1024 * 1024, sizing.bytes)
        sizing.confirmed(4L * 1024 * 1024, 60_000)
        assertEquals(2L * 1024 * 1024, sizing.bytes)
        sizing.confirmed(256 * 1024, 120_000)
        assertEquals(256L * 1024, sizing.bytes)
        sizing.confirmed(1024 * 1024, 7000)
        assertEquals(0L, sizing.bytes % (256 * 1024))
    }
    @Test fun timeoutHalvesSizeWithoutGoingBelowProtocolMinimum() {
        val sizing = DriveUploadChunkSizer()
        sizing.failed()
        assertEquals(2L * 1024 * 1024, sizing.bytes)
        repeat(10) { sizing.failed() }
        assertEquals(256L * 1024, sizing.bytes)
    }
}
