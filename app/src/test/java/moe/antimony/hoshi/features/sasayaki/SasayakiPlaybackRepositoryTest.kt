package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.epub.SasayakiMatchData

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.SasayakiSidecarRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

class SasayakiPlaybackRepositoryTest {
    @Test
    fun bookPlaybackRepositoryDelegatesLoadAndSaveToBookSidecar() = runBlocking {
        val root = File("book-root")
        val saved = SasayakiPlaybackData(
            lastPosition = 24.5,
            delay = -0.1,
            rate = 1.2f,
            audioUri = "content://audio/book.m4b",
        )
        val sidecar = FakeSasayakiSidecarRepository(saved)
        val repository = BookSasayakiPlaybackRepository(root, sidecar)

        assertEquals(saved, repository.load())

        val next = saved.copy(
            lastPosition = 42.0,
            audioUri = null,
            audioFileName = "sasayaki_audio.m4b",
        )
        repository.save(next)

        assertSame(root, sidecar.loadedRoot)
        assertSame(root, sidecar.savedRoot)
        assertEquals(next, sidecar.savedPlayback)
    }

    private class FakeSasayakiSidecarRepository(
        private val playback: SasayakiPlaybackData?,
    ) : SasayakiSidecarRepository {
        var loadedRoot: File? = null
            private set
        var savedRoot: File? = null
            private set
        var savedPlayback: SasayakiPlaybackData? = null
            private set

        override suspend fun loadSasayakiMatch(bookRoot: File): SasayakiMatchData? = null

        override suspend fun saveSasayakiMatch(bookRoot: File, match: SasayakiMatchData) = Unit

        override suspend fun loadSasayakiPlayback(bookRoot: File): SasayakiPlaybackData? {
            loadedRoot = bookRoot
            return playback
        }

        override suspend fun saveSasayakiPlayback(bookRoot: File, playback: SasayakiPlaybackData) {
            savedRoot = bookRoot
            savedPlayback = playback
        }
    }
}
