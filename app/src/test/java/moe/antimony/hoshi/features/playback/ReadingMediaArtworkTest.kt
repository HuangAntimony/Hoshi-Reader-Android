package moe.antimony.hoshi.features.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.features.bookshelf.BookCoverSource
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReadingMediaArtworkTest {
    @Test fun repeatedSentencesReuseArtworkAndChangedCoverReloads() = runTest {
        var reads = 0
        val artwork = ReadingMediaArtwork(backgroundScope, { byteArrayOf((++reads).toByte()) }, {})
        val first = BookCoverSource("cover", "version-1")
        artwork.update("book", first)
        runCurrent()
        repeat(20) { artwork.update("book", first) }
        runCurrent()
        assertEquals(1, reads)
        assertArrayEquals(byteArrayOf(1), artwork.data)
        artwork.update("book", first.copy(cacheKey = "version-2"))
        assertNull(artwork.data)
        runCurrent()
        assertEquals(2, reads)
        assertArrayEquals(byteArrayOf(2), artwork.data)
    }

    @Test fun lateArtworkCannotReturnAfterSwitchingAwayAndBackOrClearing() = runTest {
        val pending = mutableListOf<CompletableDeferred<ByteArray?>>()
        val artwork = ReadingMediaArtwork(backgroundScope, {
            val result = CompletableDeferred<ByteArray?>().also(pending::add)
            withContext(NonCancellable) { result.await() }
        }, {})
        val cover = BookCoverSource("cover", "version")
        artwork.update("a", cover)
        runCurrent()
        artwork.update("b", null)
        artwork.update("a", cover)
        runCurrent()
        pending[1].complete(byteArrayOf(2))
        runCurrent()
        pending[0].complete(byteArrayOf(1))
        runCurrent()
        assertArrayEquals(byteArrayOf(2), artwork.data)
        artwork.update("b", cover)
        runCurrent()
        artwork.clear()
        pending[2].complete(byteArrayOf(3))
        runCurrent()
        assertNull(artwork.data)
    }
}
