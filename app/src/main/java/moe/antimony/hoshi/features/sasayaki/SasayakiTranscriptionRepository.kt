package moe.antimony.hoshi.features.sasayaki

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.di.DefaultDispatcher
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.SasayakiMatchData

internal interface SasayakiTranscriptionRepository {
    suspend fun load(root: File): SasayakiTranscript?
    suspend fun save(root: File, transcript: SasayakiTranscript)
    suspend fun saveMatch(root: File, match: SasayakiMatchData)
    suspend fun clear(root: File)
    suspend fun openAlignment(root: File): SasayakiTranscriptionAlignment
}

internal fun interface SasayakiTranscriptionAlignment {
    suspend fun align(tokens: List<SasayakiToken>): SasayakiMatchData
}

@Singleton
internal class AndroidSasayakiTranscriptionRepository @Inject constructor(
    private val store: SasayakiTranscriptStore,
    private val books: BookRepository,
    private val parser: EpubBookParser,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : SasayakiTranscriptionRepository {
    override suspend fun load(root: File) = store.load(root)
    override suspend fun save(root: File, transcript: SasayakiTranscript) = store.save(root, transcript)
    override suspend fun clear(root: File) = store.clear(root)
    override suspend fun saveMatch(root: File, match: SasayakiMatchData) = withContext(ioDispatcher) {
        books.storageLock.withLock {
            try {
                check(root.isDirectory)
                if (books.loadSasayakiMatch(root) != match) books.saveSasayakiMatch(root, match)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw error.asSasayakiFailure(SasayakiFailureKind.Storage)
            }
        }
    }

    override suspend fun openAlignment(root: File): SasayakiTranscriptionAlignment {
        val book = withContext(ioDispatcher) {
            check(root.isDirectory)
            parser.parse(root)
        }
        val aligner = withContext(defaultDispatcher) { NakuruTranscriptAligner(book) }
        return SasayakiTranscriptionAlignment { tokens ->
            withContext(defaultDispatcher) { aligner.align(tokens) }
        }
    }
}
