package moe.antimony.hoshi.features.bookshelf

import android.os.Build
import coil3.ImageLoader
import coil3.decode.BitmapFactoryDecoder
import coil3.decode.DataSource
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.decode.StaticImageDecoder
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.Dimension
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import okio.buffer
import okio.Path.Companion.toOkioPath
import okio.source

internal class BookCoverFetcher(
    private val data: BookCoverSource,
    private val options: Options,
    private val thumbnailStore: BookCoverThumbnailStore,
) : Fetcher {
    override suspend fun fetch(): SourceFetchResult? {
        val requestedDimension = options.requestedCoverDimension()
        return when (val result = thumbnailStore.openThumbnailResult(data, requestedDimension)) {
            is BookCoverThumbnailOpenResult.Ready -> SourceFetchResult(
                source = ImageSource(
                    source = result.input.source().buffer(),
                    fileSystem = options.fileSystem,
                    metadata = BookCoverDerivativeMetadata(
                        source = data,
                        bucket = coverThumbnailBucket(requestedDimension),
                        generation = result.generation,
                    ),
                ),
                mimeType = "image/webp",
                dataSource = DataSource.DISK,
            )
            BookCoverThumbnailOpenResult.InvalidSource -> null
            BookCoverThumbnailOpenResult.CacheUnavailable -> originalCoverResult()
        }
    }

    private fun originalCoverResult(): SourceFetchResult? {
        val source = File(data.path).takeIf { it.isFile } ?: return null
        return SourceFetchResult(
            source = ImageSource(
                file = source.toOkioPath(),
                fileSystem = options.fileSystem,
                metadata = BookCoverOriginalMetadata,
            ),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(
        private val thumbnailStore: BookCoverThumbnailStore,
    ) : Fetcher.Factory<BookCoverSource> {
        override fun create(data: BookCoverSource, options: Options, imageLoader: ImageLoader): Fetcher =
            BookCoverFetcher(data, options, thumbnailStore)
    }
}

internal class BookCoverDerivativeMetadata(
    val source: BookCoverSource,
    val bucket: Int,
    val generation: Long,
) : ImageSource.Metadata()

internal data object BookCoverOriginalMetadata : ImageSource.Metadata()

internal class BookCoverRecoveryDecoderFactory(
    private val thumbnailStore: BookCoverThumbnailStore,
    private val delegateFactory: Decoder.Factory = platformBookCoverDecoderFactory(),
) : Decoder.Factory {
    override fun create(
        result: SourceFetchResult,
        options: Options,
        imageLoader: ImageLoader,
    ): Decoder? {
        if (result.source.metadata === BookCoverOriginalMetadata) {
            return delegateFactory.create(result, options, imageLoader)
        }
        val metadata = result.source.metadata as? BookCoverDerivativeMetadata ?: return null
        val derivativeDecoder = delegateFactory.create(result, options, imageLoader) ?: return null
        return Decoder {
            val decoded = try {
                derivativeDecoder.decode()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            decoded ?: recoverFromDerivativeFailure(metadata, options, imageLoader)
        }
    }

    private suspend fun recoverFromDerivativeFailure(
        metadata: BookCoverDerivativeMetadata,
        options: Options,
        imageLoader: ImageLoader,
    ): DecodeResult? {
        thumbnailStore.invalidateDerivative(
            coverSource = metadata.source,
            bucket = metadata.bucket,
            generation = metadata.generation,
        )
        val original = File(metadata.source.path).takeIf { it.isFile } ?: return null
        val originalSource = ImageSource(file = original.toOkioPath(), fileSystem = options.fileSystem)
        val originalResult = SourceFetchResult(
            source = originalSource,
            mimeType = null,
            dataSource = DataSource.DISK,
        )
        return originalSource.use {
            delegateFactory.create(originalResult, options, imageLoader)?.decode()
        }
    }
}

private fun platformBookCoverDecoderFactory(): Decoder.Factory {
    val semaphore = Semaphore(permits = 2)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        StaticImageDecoder.Factory(semaphore)
    } else {
        BitmapFactoryDecoder.Factory(semaphore)
    }
}

internal object BookCoverKeyer : Keyer<BookCoverSource> {
    override fun key(data: BookCoverSource, options: Options): String =
        bookCoverMemoryCacheKey(data, options.requestedCoverDimension())
}

internal fun bookCoverMemoryCacheKey(data: BookCoverSource, requestedMaxDimensionPx: Int): String =
    "hoshi-book-cover:${data.cacheKey}:${coverThumbnailBucket(requestedMaxDimensionPx)}"

private fun Options.requestedCoverDimension(): Int = max(
    size.width.pixelValueOrZero(),
    size.height.pixelValueOrZero(),
).takeIf { it > 0 } ?: 768

private fun Dimension.pixelValueOrZero(): Int = when (this) {
    is Dimension.Pixels -> px
    Dimension.Undefined -> 0
}
