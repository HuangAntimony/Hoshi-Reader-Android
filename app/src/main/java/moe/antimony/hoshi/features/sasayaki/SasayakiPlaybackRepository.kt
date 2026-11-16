package moe.antimony.hoshi.features.sasayaki

import java.io.File
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.SasayakiPlaybackData

interface SasayakiPlaybackRepository {
    suspend fun load(): SasayakiPlaybackData?
    suspend fun save(playback: SasayakiPlaybackData)
    suspend fun saveVolumeGainDb(value: Int)
}

class BookSasayakiPlaybackRepository(
    private val bookRoot: File,
    private val sidecarRepository: BookRepository,
) : SasayakiPlaybackRepository {
    override suspend fun load(): SasayakiPlaybackData? =
        sidecarRepository.loadSasayakiPlayback(bookRoot)

    override suspend fun save(playback: SasayakiPlaybackData) {
        sidecarRepository.saveSasayakiPlayback(bookRoot, playback)
    }

    override suspend fun saveVolumeGainDb(value: Int) {
        sidecarRepository.saveSasayakiVolumeGain(bookRoot, value)
    }
}
