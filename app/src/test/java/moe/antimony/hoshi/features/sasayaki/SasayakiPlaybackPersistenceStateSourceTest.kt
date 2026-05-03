package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiPlaybackPersistenceStateSourceTest {
    @Test
    fun persistenceStateOwnsPlaybackDataAndRepositorySaves() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackPersistenceState.kt").readText()

        assertTrue(source.contains("var playback by mutableStateOf(playbackRepository.load() ?: SasayakiPlaybackData(lastPosition = 0.0))"))
        assertTrue(source.contains("private set"))
        assertTrue(source.contains("val delay: Double get() = playback.delay"))
        assertTrue(source.contains("val rate: Float get() = playback.rate"))
        assertTrue(source.contains("get() = audioSourceRepository.storageSummary(playback)"))
        assertTrue(source.contains("fun setDelay(value: Double)"))
        assertTrue(source.contains("playback = playback.copy(delay = value)"))
        assertTrue(source.contains("fun setRate(value: Float)"))
        assertTrue(source.contains("playback = playback.copy(rate = value)"))
        assertTrue(source.contains("fun savePosition(seconds: Double)"))
        assertTrue(source.contains("playback = playback.copy(lastPosition = seconds)"))
        assertTrue(source.contains("playbackRepository.save(playback)"))
    }

    @Test
    fun persistenceStateOwnsAudioMetadataUpdatesButNotAudioSourceDeletion() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackPersistenceState.kt").readText()
        val importAudio = source.substringAfter("fun importAudio(")
            .substringBefore("fun clearAudioMetadata(")
        val clearAudio = source.substringAfter("fun clearAudioMetadata(")
            .substringBefore("fun savePosition(")

        assertTrue(importAudio.contains("audioSourceRepository.importedPlayback(playback, audioUri, copiedAudioFileName)"))
        assertTrue(clearAudio.contains("lastPosition = 0.0"))
        assertTrue(clearAudio.contains("audioUri = null"))
        assertTrue(clearAudio.contains("audioFileName = null"))
        assertTrue(!source.contains("clearAudioSource("))
        assertTrue(!source.contains("ContentResolver"))
    }
}
