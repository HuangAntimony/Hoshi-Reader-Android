package moe.antimony.hoshi.features.bookshelf

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.Dimension
import kotlin.math.max
import okio.buffer
import okio.source

internal class BookCoverFetcher(
    private val data: BookCoverSource,
    private val options: Options,
    private val thumbnailStore: BookCoverThumbnailStore,
) : Fetcher {
    override suspend fun fetch(): SourceFetchResult? {
        val requestedDimension = options.requestedCoverDimension()
        val thumbnail = thumbnailStore.openThumbnail(data, requestedDimension) ?: return null
        return SourceFetchResult(
            source = ImageSource(
                source = thumbnail.source().buffer(),
                fileSystem = options.fileSystem,
            ),
            mimeType = "image/webp",
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
