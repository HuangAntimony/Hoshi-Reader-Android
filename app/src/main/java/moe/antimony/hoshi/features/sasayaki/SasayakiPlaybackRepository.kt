package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiSidecarRepository
import java.io.File

interface SasayakiPlaybackRepository {
    fun load(): SasayakiPlaybackData?
    fun save(playback: SasayakiPlaybackData)
}

class BookSasayakiPlaybackRepository(
    private val bookRoot: File,
    private val sidecarRepository: SasayakiSidecarRepository,
) : SasayakiPlaybackRepository {
    override fun load(): SasayakiPlaybackData? =
        sidecarRepository.loadSasayakiPlayback(bookRoot)

    override fun save(playback: SasayakiPlaybackData) {
        sidecarRepository.saveSasayakiPlayback(bookRoot, playback)
    }
}
