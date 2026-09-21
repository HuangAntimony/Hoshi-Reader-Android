package moe.antimony.hoshi.features.sasayaki

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.SasayakiMatchData

internal interface SasayakiTranscriptionRepository {
    suspend fun load(root: File): SasayakiTranscript?
    suspend fun save(root: File, transcript: SasayakiTranscript)
    suspend fun clear(root: File)
    suspend fun align(root: File, tokens: List<SasayakiToken>): SasayakiMatchData
}

@Singleton
internal class AndroidSasayakiTranscriptionRepository @Inject constructor(
    private val store: SasayakiTranscriptStore,
    private val books: BookRepository,
    private val parser: EpubBookParser,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SasayakiTranscriptionRepository {
    override suspend fun load(root: File) = store.load(root)
    override suspend fun save(root: File, transcript: SasayakiTranscript) = store.save(root, transcript)
    override suspend fun clear(root: File) = store.clear(root)
    override suspend fun align(root: File, tokens: List<SasayakiToken>): SasayakiMatchData = withContext(ioDispatcher) {
        check(root.isDirectory)
        val result = SasayakiTranscriptAligner.align(parser.parse(root), tokens)
        check(root.isDirectory)
        books.saveSasayakiMatch(root, result)
        result
    }
}
