package moe.antimony.hoshi.features.bookshelf

import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookCoverThumbnailStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sizeBucketAvoidsOneCacheEntryPerMeasuredPixelSize() {
        assertEquals(256, coverThumbnailBucket(42))
        assertEquals(256, coverThumbnailBucket(256))
        assertEquals(512, coverThumbnailBucket(257))
        assertEquals(512, coverThumbnailBucket(512))
        assertEquals(768, coverThumbnailBucket(513))
        assertEquals(768, coverThumbnailBucket(2_000))
    }

    @Test
    fun generatedThumbnailIsReusedAcrossStoreInstances() = runBlocking {
        val source = sourceFile("cover.jpg", "original")
        val encodeCount = AtomicInteger()
        val encoder = copyingEncoder(encodeCount)

        val firstStore = store(encoder)
        val first = firstStore.thumbnail(source.toBookCoverSource(), requestedMaxDimensionPx = 400)
        val secondStore = store(encoder)
        val second = secondStore.thumbnail(source.toBookCoverSource(), requestedMaxDimensionPx = 400)

        assertNotNull(first)
        assertEquals(first, second)
        assertEquals("original:512", first!!.readText())
        assertEquals(1, encodeCount.get())
    }

    @Test
    fun concurrentMissesForSameSourceAreSingleFlight() = runBlocking {
        val source = sourceFile("cover.jpg", "original")
        val encodeCount = AtomicInteger()
        val destinations = Collections.synchronizedList(mutableListOf<File>())
        val store = store(
            BookCoverThumbnailEncoder { input, output, maxDimensionPx ->
                encodeCount.incrementAndGet()
                destinations += output
                Thread.sleep(40)
                output.writeText("${input.readText()}:$maxDimensionPx")
                true
            },
        )

        val results = List(8) {
            async(Dispatchers.Default) {
                store.thumbnail(source.toBookCoverSource(), requestedMaxDimensionPx = 700)
            }
        }.awaitAll()

        assertEquals(1, encodeCount.get())
        assertEquals(1, results.distinct().size)
        assertNotNull(results.first())
        assertTrue(results.first()!!.isFile)
        assertTrue(destinations.all { it.name.endsWith(".tmp") })
        assertFalse(store.cacheDirectory.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun newlyGeneratedThumbnailIsKeptWhenItAloneExceedsDiskBudget() = runBlocking {
        val source = sourceFile("large-cover.jpg", "larger-than-budget")
        val store = store(
            encoder = copyingEncoder(AtomicInteger()),
            maxDiskBytes = 4,
        )

        val thumbnail = store.thumbnail(source.toBookCoverSource(), 256)

        assertNotNull(thumbnail)
        assertTrue(thumbnail!!.isFile)
        assertTrue(thumbnail.length() > 4)
    }

    @Test
    fun sourceFingerprintChangeCreatesANewThumbnail() = runBlocking {
        val source = sourceFile("cover.jpg", "first")
        val encodeCount = AtomicInteger()
        val store = store(copyingEncoder(encodeCount))

        val first = store.thumbnail(source.toBookCoverSource(), requestedMaxDimensionPx = 700)
        source.writeText("second version")
        source.setLastModified(source.lastModified() + 2_000)
        val second = store.thumbnail(source.toBookCoverSource(), requestedMaxDimensionPx = 700)

        assertNotEquals(first, second)
        assertEquals(2, encodeCount.get())
    }

    @Test
    fun failedDecodeIsSuppressedUntilSourceFingerprintChanges() = runBlocking {
        val source = sourceFile("broken.jpg", "broken")
        val encodeCount = AtomicInteger()
        val store = store(
            BookCoverThumbnailEncoder { _, _, _ ->
                encodeCount.incrementAndGet()
                false
            },
        )

        assertEquals(null, store.thumbnail(source.toBookCoverSource(), 256))
        assertEquals(null, store.thumbnail(source.toBookCoverSource(), 256))
        assertEquals(1, encodeCount.get())

        source.writeText("still broken but changed")
        source.setLastModified(source.lastModified() + 2_000)
        assertEquals(null, store.thumbnail(source.toBookCoverSource(), 256))
        assertEquals(2, encodeCount.get())
    }

    @Test
    fun openedThumbnailRemainsReadableWhenLaterGenerationTrimsItsFile() = runBlocking {
        val firstSource = sourceFile("first.jpg", "first-cover")
        val secondSource = sourceFile("second.jpg", "second-cover")
        val store = store(
            encoder = copyingEncoder(AtomicInteger()),
            maxDiskBytes = 24,
        )

        store.openThumbnail(firstSource.toBookCoverSource(), 256)!!.use { firstThumbnail ->
            store.thumbnail(secondSource.toBookCoverSource(), 256)

            assertEquals("first-cover:256", firstThumbnail.bufferedReader().readText())
        }
    }

    @Test
    fun memoryCacheKeyKeepsThumbnailBucketsIndependent() {
        val source = BookCoverSource(path = "/cover.jpg", cacheKey = "fingerprint")

        assertNotEquals(
            bookCoverMemoryCacheKey(source, requestedMaxDimensionPx = 200),
            bookCoverMemoryCacheKey(source, requestedMaxDimensionPx = 400),
        )
        assertEquals(
            bookCoverMemoryCacheKey(source, requestedMaxDimensionPx = 300),
            bookCoverMemoryCacheKey(source, requestedMaxDimensionPx = 500),
        )
    }

    private fun sourceFile(name: String, content: String): File =
        temporaryFolder.newFile(name).apply { writeText(content) }

    private fun store(
        encoder: BookCoverThumbnailEncoder,
        maxDiskBytes: Long = 16L * 1024L * 1024L,
    ): BookCoverThumbnailStore =
        BookCoverThumbnailStore(
            cacheDirectory = temporaryFolder.root.resolve("thumbnails"),
            encoder = encoder,
            ioDispatcher = Dispatchers.IO,
            maxDiskBytes = maxDiskBytes,
        )

    private fun copyingEncoder(encodeCount: AtomicInteger) =
        BookCoverThumbnailEncoder { source, destination, maxDimensionPx ->
            encodeCount.incrementAndGet()
            destination.writeText("${source.readText()}:$maxDimensionPx")
            true
        }
}
