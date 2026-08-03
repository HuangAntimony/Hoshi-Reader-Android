package moe.antimony.hoshi.features.bookshelf

import android.graphics.ImageDecoder
import java.io.File
import java.io.IOException
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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
    fun imageDecoderIsSkippedOnApi28() {
        assertFalse(shouldUseImageDecoder(apiLevel = 28))
        assertTrue(shouldUseImageDecoder(apiLevel = 29))
    }

    @Test
    fun onlyMalformedOrIncompleteImageDecoderFailuresArePermanent() {
        assertFalse(isPermanentImageDecoderFailure(ImageDecoder.DecodeException.SOURCE_EXCEPTION))
        assertTrue(isPermanentImageDecoderFailure(ImageDecoder.DecodeException.SOURCE_INCOMPLETE))
        assertTrue(isPermanentImageDecoderFailure(ImageDecoder.DecodeException.SOURCE_MALFORMED_DATA))
    }

    @Test
    fun permanentDecodeFailureReturnsNull() {
        val failure = IOException("malformed source")

        val result = decodeOrNullOnPermanentFailure<String>(
            isPermanentFailure = { it === failure },
        ) { throw failure }

        assertNull(result)
    }

    @Test
    fun transientDecodeFailureIsRethrown() {
        val failure = IOException("source read failed")

        val thrown = assertThrows(IOException::class.java) {
            decodeOrNullOnPermanentFailure<String>(isPermanentFailure = { false }) { throw failure }
        }

        assertSame(failure, thrown)
    }

    @Test
    fun fatalDecodeFailureIsNotCaught() {
        val failure = OutOfMemoryError("decode allocation failed")

        val thrown = assertThrows(OutOfMemoryError::class.java) {
            decodeOrNullOnPermanentFailure<String>(isPermanentFailure = { true }) { throw failure }
        }

        assertSame(failure, thrown)
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
    fun transientEncoderFailureDoesNotPoisonTheSourceFingerprint() = runBlocking {
        val source = sourceFile("cover.jpg", "original")
        val encodeCount = AtomicInteger()
        var elapsedRealtimeMillis = 0L
        val store = store(
            encoder = BookCoverThumbnailEncoder { input, output, maxDimensionPx ->
                if (encodeCount.incrementAndGet() == 1) throw IOException("cache write failed")
                output.writeText("${input.readText()}:$maxDimensionPx")
                true
            },
            elapsedRealtimeMillis = { elapsedRealtimeMillis },
            transientRetryDelayMillis = 30_000L,
        )

        assertEquals(null, store.thumbnail(source.toBookCoverSource(), 256))
        assertEquals(null, store.thumbnail(source.toBookCoverSource(), 256))
        assertEquals(1, encodeCount.get())

        elapsedRealtimeMillis = 30_000L
        val recovered = store.thumbnail(source.toBookCoverSource(), 256)

        assertNotNull(recovered)
        assertEquals("original:256", recovered!!.readText())
        assertEquals(2, encodeCount.get())
    }

    @Test
    fun concurrentTransientFailuresEnterCooldownSingleFlight() = runBlocking {
        val source = sourceFile("cover.jpg", "original")
        val encodeCount = AtomicInteger()
        val store = store(
            encoder = BookCoverThumbnailEncoder { _, _, _ ->
                encodeCount.incrementAndGet()
                Thread.sleep(40)
                throw IOException("cache write failed")
            },
        )

        List(8) {
            async(Dispatchers.Default) {
                store.thumbnail(source.toBookCoverSource(), 256)
            }
        }.awaitAll()

        assertEquals(1, encodeCount.get())
    }

    @Test
    fun transientFailureIsReportedAsCacheUnavailable() = runBlocking {
        val source = sourceFile("cover.jpg", "original")
        val store = store(
            encoder = BookCoverThumbnailEncoder { _, _, _ ->
                throw IOException("cache write failed")
            },
        )

        val result = store.openThumbnailResult(source.toBookCoverSource(), 256)

        assertTrue(result is BookCoverThumbnailOpenResult.CacheUnavailable)
    }

    @Test
    fun thumbnailOpenIoFailureIsReportedAsCacheUnavailable() = runBlocking {
        val source = sourceFile("cover.jpg", "original")
        val store = store(
            encoder = copyingEncoder(AtomicInteger()),
            inputStreamProvider = { throw IOException("cache read failed") },
        )

        val result = store.openThumbnailResult(source.toBookCoverSource(), 256)

        assertTrue(result is BookCoverThumbnailOpenResult.CacheUnavailable)
    }

    @Test
    fun invalidSourceIsReportedWithoutOriginalFallback() = runBlocking {
        val source = sourceFile("broken.jpg", "broken")
        val store = store(
            encoder = BookCoverThumbnailEncoder { _, _, _ -> false },
        )

        val result = store.openThumbnailResult(source.toBookCoverSource(), 256)

        assertTrue(result is BookCoverThumbnailOpenResult.InvalidSource)
    }

    @Test
    fun decodeFailureMarkerInvalidatesOnlyTheServedBucket() = runBlocking {
        val source = sourceFile("cover.jpg", "original")
        val coverSource = source.toBookCoverSource()
        val encodeCount = AtomicInteger()
        val store = store(copyingEncoder(encodeCount))
        store.thumbnail(coverSource, 256)
        store.thumbnail(coverSource, 512)

        store.markDerivativeDecodeFailed(coverSource, bucket = 256)
        store.thumbnail(coverSource, 256)
        store.thumbnail(coverSource, 512)

        assertEquals(3, encodeCount.get())
    }

    @Test
    fun openedThumbnailRemainsReadableWhenLaterGenerationTrimsItsFile() = runBlocking {
        val firstSource = sourceFile("first.jpg", "first-cover")
        val secondSource = sourceFile("second.jpg", "second-cover")
        val store = store(
            encoder = copyingEncoder(AtomicInteger()),
            maxDiskBytes = 24,
        )

        store.openReady(firstSource.toBookCoverSource(), 256).use { firstThumbnail ->
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
        elapsedRealtimeMillis: () -> Long = { 0L },
        transientRetryDelayMillis: Long = 30_000L,
        inputStreamProvider: (File) -> java.io.InputStream = File::inputStream,
    ): BookCoverThumbnailStore =
        BookCoverThumbnailStore(
            cacheDirectory = temporaryFolder.root.resolve("thumbnails"),
            encoder = encoder,
            ioDispatcher = Dispatchers.IO,
            maxDiskBytes = maxDiskBytes,
            elapsedRealtimeMillis = elapsedRealtimeMillis,
            transientRetryDelayMillis = transientRetryDelayMillis,
            inputStreamProvider = inputStreamProvider,
        )

    private fun copyingEncoder(encodeCount: AtomicInteger) =
        BookCoverThumbnailEncoder { source, destination, maxDimensionPx ->
            encodeCount.incrementAndGet()
            destination.writeText("${source.readText()}:$maxDimensionPx")
            true
        }

    private suspend fun BookCoverThumbnailStore.openReady(
        source: BookCoverSource,
        requestedMaxDimensionPx: Int,
    ) = (openThumbnailResult(source, requestedMaxDimensionPx) as BookCoverThumbnailOpenResult.Ready).input

}
