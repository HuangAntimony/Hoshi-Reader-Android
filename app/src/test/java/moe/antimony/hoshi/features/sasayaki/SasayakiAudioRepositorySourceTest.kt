package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiAudioRepositorySourceTest {
    @Test
    fun repositoryBuildsPlaybackStateForExternalUriOrPrivateCopyImport() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiAudioRepository.kt").readText()
        val importedPlayback = source.substringAfter("fun importedPlayback(")
            .substringBefore("fun playbackSource(")

        assertTrue(importedPlayback.contains("audioUri: Uri"))
        assertTrue(importedPlayback.contains("copiedAudioFileName: String? = null"))
        assertTrue(importedPlayback.contains("audioUri = if (copiedAudioFileName == null) audioUri.toString() else null"))
        assertTrue(importedPlayback.contains("audioFileName = copiedAudioFileName"))
    }

    @Test
    fun repositoryResolvesExternalUriBeforePrivateCopiedAudioFile() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiAudioRepository.kt").readText()
        val playbackSource = source.substringAfter("fun playbackSource(")
            .substringBefore("fun clearAudioSource(")

        assertTrue(playbackSource.contains("playback.audioUri?.let"))
        assertTrue(playbackSource.contains("SasayakiPlaybackSource.ExternalUri(Uri.parse(it))"))
        assertTrue(playbackSource.contains("audioFile(playback)?.let { SasayakiPlaybackSource.PrivateFile(it) }"))
        assertTrue(playbackSource.indexOf("playback.audioUri?.let") < playbackSource.indexOf("audioFile(playback)?.let"))
        assertTrue(source.contains("if (file.path != audioRoot.path && !file.path.startsWith(audioRoot.path + File.separator)) return null"))
        assertTrue(source.contains("return file.takeIf { it.isFile }"))
    }

    @Test
    fun repositoryClearsPrivateAudioAndReleasesPersistedUriPermission() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiAudioRepository.kt").readText()
        val clearSource = source.substringAfter("fun clearAudioSource(")
            .substringBefore("fun storageSummary(")

        assertTrue(source.contains("import android.content.Intent"))
        assertTrue(clearSource.contains("deleteAudio(playback)"))
        assertTrue(clearSource.contains("playback.audioUri?.let { uriString ->"))
        assertTrue(clearSource.contains("contentResolver.releasePersistableUriPermission("))
        assertTrue(clearSource.contains("Uri.parse(uriString)"))
        assertTrue(clearSource.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"))
    }

    @Test
    fun repositoryOwnsAudioStorageSummaryText() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiAudioRepository.kt").readText()
        val summary = source.substringAfter("fun storageSummary(")
            .substringBefore("fun audioFile(")

        assertTrue(summary.contains("playback.audioFileName != null"))
        assertTrue(summary.contains("Copied to app storage. The original audiobook file can be deleted."))
        assertTrue(summary.contains("playback.audioUri != null"))
        assertTrue(summary.contains("Linked to the external audiobook file. Keep the original file available."))
        assertTrue(summary.contains("Select an .mp3 or .m4b audiobook"))
    }
}
