package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiAudioRepositorySourceTest {
    @Test
    fun sourceShapeGuardRepositoryBuildsPlaybackStateForExternalUriOrPrivateCopyImport() {
        // Android Uri behavior is not executable in this JVM test suite, so this
        // source-shape guard only protects the framework branch that behavior tests
        // cannot cover without instrumentation.
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiAudioRepository.kt").readText()
        val importedPlayback = source.substringAfter("fun importedPlayback(")
            .substringBefore("fun playbackSource(")

        assertTrue(importedPlayback.contains("audioUri: Uri"))
        assertTrue(importedPlayback.contains("copiedAudioFileName: String? = null"))
        assertTrue(importedPlayback.contains("audioUri = if (copiedAudioFileName == null) audioUri.toString() else null"))
        assertTrue(importedPlayback.contains("audioFileName = copiedAudioFileName"))
    }

    @Test
    fun sourceShapeGuardRepositoryResolvesExternalUriBeforePrivateCopiedAudioFile() {
        // `Uri.parse` is an Android framework seam in local JVM tests. Private
        // copied-file resolution is covered by SasayakiAudioRepositoryTest.
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
    fun sourceShapeGuardRepositoryReleasesPersistedUriPermissionWhenClearingExternalAudio() {
        // Persisted Uri permission release depends on Android ContentResolver and
        // is intentionally left as a narrow source-shape guard.
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

}
