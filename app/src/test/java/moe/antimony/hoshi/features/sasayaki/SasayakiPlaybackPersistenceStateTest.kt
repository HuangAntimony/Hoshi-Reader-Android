package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SasayakiPlaybackPersistenceStateTest {
    @Test
    fun loadsPlaybackAndSavesDelayRateAndPositionChanges() {
        val initial = SasayakiPlaybackData(
            lastPosition = 12.5,
            delay = 0.25,
            rate = 1.1f,
            audioFileName = "sasayaki_audio.m4b",
        )
        val repository = FakePlaybackRepository(initial)
        val state = SasayakiPlaybackPersistenceState(
            playbackRepository = repository,
            audioSourceRepository = SasayakiAudioRepository(File("book-root")),
        )

        assertEquals(initial, state.playback)
        assertEquals(0.25, state.delay, 0.0)
        assertEquals(1.1f, state.rate)

        state.setDelay(-0.15)
        assertEquals(-0.15, repository.saved.last().delay, 0.0)
        assertEquals(1.1f, repository.saved.last().rate)

        state.setRate(1.35f)
        assertEquals(-0.15, repository.saved.last().delay, 0.0)
        assertEquals(1.35f, repository.saved.last().rate)

        state.savePosition(33.0)
        assertEquals(33.0, repository.saved.last().lastPosition, 0.0)
        assertEquals(1.35f, repository.saved.last().rate)
    }

    @Test
    fun clearsAudioMetadataAndPreservesStorageSummaryBehavior() {
        val bookRoot = Files.createTempDirectory("hoshi-sasayaki-persistence").toFile()
        val copiedAudio = File(bookRoot, "Audio/sasayaki_audio.mp3").also { file ->
            file.parentFile?.mkdirs()
            file.writeText("audio")
        }
        val repository = FakePlaybackRepository(
            SasayakiPlaybackData(
                lastPosition = 42.0,
                delay = 0.1,
                rate = 0.9f,
                audioFileName = "sasayaki_audio.mp3",
            ),
        )
        val state = SasayakiPlaybackPersistenceState(
            playbackRepository = repository,
            audioSourceRepository = SasayakiAudioRepository(bookRoot),
        )

        assertEquals("Copied to app storage. The original audiobook file can be deleted.", state.audioStorageSummary)

        state.clearAudioMetadata()

        assertEquals(0.0, state.playback.lastPosition, 0.0)
        assertEquals(0.1, state.playback.delay, 0.0)
        assertEquals(0.9f, state.playback.rate)
        assertEquals(null, state.playback.audioUri)
        assertEquals(null, state.playback.audioFileName)
        assertEquals("Select an .mp3 or .m4b audiobook", state.audioStorageSummary)
        assertEquals(state.playback, repository.saved.last())
        assertTrue(copiedAudio.exists())
    }

    private class FakePlaybackRepository(
        private val initial: SasayakiPlaybackData?,
    ) : SasayakiPlaybackRepository {
        val saved = mutableListOf<SasayakiPlaybackData>()

        override fun load(): SasayakiPlaybackData? = initial

        override fun save(playback: SasayakiPlaybackData) {
            saved += playback
        }
    }
}
