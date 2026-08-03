package moe.antimony.hoshi.features.bookshelf

import android.content.ContextWrapper
import coil3.Extras
import coil3.ImageLoader
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.CachePolicy
import coil3.request.Options
import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookCoverImagePipelineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun transientThumbnailFailureFallsBackToOriginalCover() = runBlocking {
        val original = temporaryFolder.newFile("cover.jpg").apply { writeText("original-cover") }
        val store = BookCoverThumbnailStore(
            cacheDirectory = temporaryFolder.root.resolve("thumbnails"),
            encoder = BookCoverThumbnailEncoder { _, _, _ ->
                throw IOException("cache unavailable")
            },
            ioDispatcher = Dispatchers.IO,
            maxDiskBytes = 1024,
            elapsedRealtimeMillis = { 0L },
        )
        val fetcher = BookCoverFetcher(
            data = original.toBookCoverSource(),
            options = options(),
            thumbnailStore = store,
        )

        val result = fetcher.fetch()

        assertTrue(result is SourceFetchResult)
        assertTrue((result as SourceFetchResult).source.metadata === BookCoverOriginalMetadata)
        result.source.use { source ->
            assertEquals("original-cover", source.source().readUtf8())
        }
    }

    @Test
    fun derivativeDecodeFailureFallsBackToOriginalAndInvalidatesDerivative() = runBlocking {
        val original = temporaryFolder.newFile("cover.jpg").apply { writeText("original-cover") }
        val source = original.toBookCoverSource()
        val encodeCount = AtomicInteger()
        val encoder = BookCoverThumbnailEncoder { input, output, _ ->
            encodeCount.incrementAndGet()
            output.writeText(input.readText())
            true
        }
        val cacheDirectory = temporaryFolder.root.resolve("thumbnails")
        val store = BookCoverThumbnailStore(
            cacheDirectory = cacheDirectory,
            encoder = encoder,
            ioDispatcher = Dispatchers.IO,
            maxDiskBytes = 1024,
        )
        val derivative = store.thumbnail(source, 256)!!
        val options = options()
        val fetchResult = BookCoverFetcher(source, options, store).fetch()!!
        var fallbackPayload: String? = null
        val delegateCalls = AtomicInteger()
        val delegateFactory = object : Decoder.Factory {
            override fun create(
                result: SourceFetchResult,
                options: Options,
                imageLoader: ImageLoader,
            ): Decoder {
                val call = delegateCalls.incrementAndGet()
                if (call == 2) {
                    fallbackPayload = result.source.source().readUtf8()
                }
                return Decoder {
                    if (call == 1) throw IOException("corrupt derivative")
                    null
                }
            }
        }
        val decoder = BookCoverRecoveryDecoderFactory(store, delegateFactory)
            .create(fetchResult, options, unusedImageLoader())!!

        decoder.decode()

        assertEquals("original-cover", fallbackPayload)
        assertEquals(2, delegateCalls.get())
        assertFalse(derivative.exists())

        val restartedStore = BookCoverThumbnailStore(
            cacheDirectory = cacheDirectory,
            encoder = encoder,
            ioDispatcher = Dispatchers.IO,
            maxDiskBytes = 1024,
        )
        assertNotNull(restartedStore.thumbnail(source, 256))
        assertEquals(2, encodeCount.get())
    }

    private fun options(): Options = Options(
        context = object : ContextWrapper(null) {},
        size = Size(Dimension(256), Dimension(256)),
        scale = Scale.FIT,
        precision = Precision.INEXACT,
        diskCacheKey = null,
        fileSystem = FileSystem.SYSTEM,
        memoryCachePolicy = CachePolicy.ENABLED,
        diskCachePolicy = CachePolicy.ENABLED,
        networkCachePolicy = CachePolicy.ENABLED,
        extras = Extras.EMPTY,
    )

    private fun unusedImageLoader(): ImageLoader = Proxy.newProxyInstance(
        ImageLoader::class.java.classLoader,
        arrayOf(ImageLoader::class.java),
    ) { _, method, _ -> error("Unexpected ImageLoader.${method.name} call") } as ImageLoader
}
