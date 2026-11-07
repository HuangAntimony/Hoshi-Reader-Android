package moe.antimony.hoshi.features.playback

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.features.bookshelf.BookCoverSource
import moe.antimony.hoshi.features.bookshelf.BookCoverThumbnailOpenResult
import moe.antimony.hoshi.features.bookshelf.BookCoverThumbnailStore
import moe.antimony.hoshi.features.bookshelf.toBookCoverSource
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.R

@Singleton
internal class ReadingMediaArtworkRepository @Inject constructor(
    private val thumbnails: BookCoverThumbnailStore,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun source(file: File?): BookCoverSource? = withContext(ioDispatcher) {
        file?.takeIf { it.isFile }?.toBookCoverSource()
    }

    suspend fun load(source: BookCoverSource): ByteArray? = withContext(ioDispatcher) {
        try {
            when (val result = thumbnails.openThumbnailResult(source, 512)) {
                is BookCoverThumbnailOpenResult.Ready -> result.input.use { it.readBytes() }
                BookCoverThumbnailOpenResult.InvalidSource, BookCoverThumbnailOpenResult.CacheUnavailable -> null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ErrorDiagnosticEvents.buffer.record(UiText.Resource(R.string.reader_cover_restore_failed), error)
            null
        }
    }
}
