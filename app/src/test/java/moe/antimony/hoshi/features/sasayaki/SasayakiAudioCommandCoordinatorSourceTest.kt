package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiAudioCommandCoordinatorSourceTest {
    @Test
    fun audioCommandCoordinatorOwnsImportRestoreSequencing() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiAudioCommandCoordinator.kt").readText()
        val importAudio = source.substringAfter("fun importAudio(")
            .substringBefore("fun clearAudio(")

        assertTrue(source.contains("private val audioSourceRepository: SasayakiAudioRepository"))
        assertTrue(source.contains("private val playbackPersistence: SasayakiPlaybackPersistenceState"))
        assertTrue(source.contains("private val playbackState: SasayakiPlaybackStateCoordinator"))
        assertTrue(source.contains("private val audioAvailability: SasayakiAudioAvailabilityState"))
        assertTrue(source.contains("private val contentResolver: ContentResolver"))
        assertTrue(importAudio.contains("teardownPlayer(false)"))
        assertTrue(importAudio.contains("playbackPersistence.importAudio(audioUri, copiedAudioFileName)"))
        assertTrue(importAudio.contains("restoreAudio()"))
        assertTrue(importAudio.indexOf("teardownPlayer(false)") < importAudio.indexOf("playbackPersistence.importAudio(audioUri, copiedAudioFileName)"))
        assertTrue(importAudio.indexOf("playbackPersistence.importAudio(audioUri, copiedAudioFileName)") < importAudio.indexOf("restoreAudio()"))
        assertFalse(source.contains("mutableStateOf"))
    }

    @Test
    fun audioCommandCoordinatorOwnsClearAudioSequencing() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiAudioCommandCoordinator.kt").readText()
        val clearAudio = source.substringAfter("fun clearAudio(")

        assertTrue(clearAudio.contains("audioSourceRepository.clearAudioSource(playback, contentResolver)"))
        assertTrue(clearAudio.contains("teardownPlayer(true)"))
        assertTrue(clearAudio.contains("playbackPersistence.clearAudioMetadata()"))
        assertTrue(clearAudio.contains("playbackState.clearAudioState()"))
        assertTrue(clearAudio.contains("audioAvailability.markAudioCleared()"))
        assertTrue(clearAudio.indexOf("audioSourceRepository.clearAudioSource(playback, contentResolver)") < clearAudio.indexOf("teardownPlayer(true)"))
        assertTrue(clearAudio.indexOf("teardownPlayer(true)") < clearAudio.indexOf("playbackPersistence.clearAudioMetadata()"))
        assertTrue(clearAudio.indexOf("playbackPersistence.clearAudioMetadata()") < clearAudio.indexOf("playbackState.clearAudioState()"))
        assertTrue(clearAudio.indexOf("playbackState.clearAudioState()") < clearAudio.indexOf("audioAvailability.markAudioCleared()"))
        assertFalse(source.contains("mutableStateOf"))
    }
}
