package moe.antimony.hoshi.features.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDownloadRecordTest {
    private val update = AvailableUpdate(
        versionName = "0.3.5",
        releaseUrl = "https://example.com/releases/tag/v0.3.5",
        assetName = "Hoshi-Reader-v0.3.5.apk",
        downloadUrl = "https://example.com/Hoshi-Reader-v0.3.5.apk",
        sha256 = "7977f9e95adec03fce35ef0640fdd2fe662c6521d625dc12242df5b66fb2254b",
    )

    @Test
    fun updateApkUsesStableFileName() {
        assertEquals("Hoshi-Reader-update.apk", AndroidUpdateDownloadManager.UpdateFileName)
    }

    @Test
    fun downloadedRecordMatchesOnlyTheSameVersionAssetAndDigest() {
        val record = UpdateDownloadRecord(
            versionName = "0.3.5",
            assetName = "Hoshi-Reader-v0.3.5.apk",
            fileName = AndroidUpdateDownloadManager.UpdateFileName,
            downloadId = 42L,
            sha256 = "7977f9e95adec03fce35ef0640fdd2fe662c6521d625dc12242df5b66fb2254b",
            status = UpdateDownloadRecordStatus.Downloaded,
        )

        assertTrue(record.matches(update))
        assertFalse(record.copy(versionName = "0.3.4").matches(update))
        assertFalse(record.copy(assetName = "hoshi-universal.apk").matches(update))
        assertFalse(record.copy(sha256 = null).matches(update))
        assertFalse(record.matches(update.copy(sha256 = null)))
    }

    @Test
    fun recordsWithoutDigestsMatchOnlyUpdatesWithoutDigests() {
        val record = UpdateDownloadRecord(
            versionName = "0.3.5",
            assetName = "Hoshi-Reader-v0.3.5.apk",
            fileName = AndroidUpdateDownloadManager.UpdateFileName,
            downloadId = 42L,
            sha256 = null,
            status = UpdateDownloadRecordStatus.Downloaded,
        )

        assertTrue(record.matches(update.copy(sha256 = null)))
    }

    @Test
    fun availableRecordsPromptOnlyForNewerUnskippedVersions() {
        val record = UpdateDownloadRecord(
            versionName = "0.3.5",
            releaseUrl = "https://example.com/releases/tag/v0.3.5",
            assetName = "Hoshi-Reader-v0.3.5.apk",
            fileName = AndroidUpdateDownloadManager.UpdateFileName,
            downloadId = null,
            sha256 = null,
            downloadUrl = "https://example.com/Hoshi-Reader-v0.3.5.apk",
            fallbackDownloadUrls = emptyList(),
            status = UpdateDownloadRecordStatus.Available,
        )

        assertTrue(record.shouldPromptForAvailable(currentVersionName = "0.3.4"))
        assertFalse(record.shouldPromptForAvailable(currentVersionName = "0.3.5"))
        assertFalse(record.shouldPromptForAvailable(currentVersionName = "0.3.6"))
        assertFalse(record.copy(status = UpdateDownloadRecordStatus.Skipped).shouldPromptForAvailable("0.3.4"))
        assertFalse(record.copy(versionName = "latest").shouldPromptForAvailable("0.3.4"))
    }

    @Test
    fun skippedRecordsCanStillBecomeManualUpdatePrompts() {
        val record = UpdateDownloadRecord(
            versionName = "0.3.5",
            releaseUrl = "https://example.com/releases/tag/v0.3.5",
            assetName = "Hoshi-Reader-v0.3.5.apk",
            fileName = AndroidUpdateDownloadManager.UpdateFileName,
            downloadId = null,
            sha256 = null,
            downloadUrl = "https://example.com/Hoshi-Reader-v0.3.5.apk",
            fallbackDownloadUrls = emptyList(),
            status = UpdateDownloadRecordStatus.Skipped,
        )

        assertFalse(record.shouldPromptForAvailable(currentVersionName = "0.3.4"))
        assertTrue(record.shouldPromptForManualAvailable(currentVersionName = "0.3.4"))
    }

    @Test
    fun recordsCanRestoreAvailableUpdateForDeferredDownloads() {
        val record = UpdateDownloadRecord(
            versionName = "0.3.5",
            releaseUrl = "https://example.com/releases/tag/v0.3.5",
            assetName = "Hoshi-Reader-v0.3.5.apk",
            fileName = AndroidUpdateDownloadManager.UpdateFileName,
            downloadId = null,
            sha256 = "7977f9e95adec03fce35ef0640fdd2fe662c6521d625dc12242df5b66fb2254b",
            downloadUrl = "https://example.com/Hoshi-Reader-v0.3.5.apk",
            fallbackDownloadUrls = listOf("https://mirror.example.com/Hoshi-Reader-v0.3.5.apk"),
            status = UpdateDownloadRecordStatus.Available,
        )

        val restored = record.toAvailableUpdate()

        assertEquals(update.copy(fallbackDownloadUrls = listOf("https://mirror.example.com/Hoshi-Reader-v0.3.5.apk")), restored)
    }

    @Test
    fun onlyDownloadedRecordsNewerThanCurrentVersionShouldPromptForInstall() {
        val record = UpdateDownloadRecord(
            versionName = "0.3.5",
            releaseUrl = "https://example.com/releases/tag/v0.3.5",
            assetName = "Hoshi-Reader-v0.3.5.apk",
            fileName = AndroidUpdateDownloadManager.UpdateFileName,
            downloadId = 42L,
            sha256 = null,
            downloadUrl = "https://example.com/Hoshi-Reader-v0.3.5.apk",
            fallbackDownloadUrls = emptyList(),
            status = UpdateDownloadRecordStatus.Downloaded,
        )

        assertTrue(record.shouldPromptForInstall(currentVersionName = "0.3.4"))
        assertFalse(record.shouldPromptForInstall(currentVersionName = "0.3.5"))
        assertFalse(record.shouldPromptForInstall(currentVersionName = "0.3.6"))
        assertFalse(record.copy(status = UpdateDownloadRecordStatus.Downloading).shouldPromptForInstall("0.3.4"))
        assertFalse(record.copy(versionName = "latest").shouldPromptForInstall("0.3.4"))
    }

    @Test
    fun onlyRecordsForNewerVersionsShouldSurfaceInAbout() {
        val record = UpdateDownloadRecord(
            versionName = "0.3.5",
            releaseUrl = "https://example.com/releases/tag/v0.3.5",
            assetName = "Hoshi-Reader-v0.3.5.apk",
            fileName = AndroidUpdateDownloadManager.UpdateFileName,
            downloadId = 42L,
            sha256 = null,
            downloadUrl = "https://example.com/Hoshi-Reader-v0.3.5.apk",
            fallbackDownloadUrls = emptyList(),
            status = UpdateDownloadRecordStatus.Downloaded,
        )

        assertTrue(record.shouldSurfaceInAbout(currentVersionName = "0.3.4"))
        assertFalse(record.shouldSurfaceInAbout(currentVersionName = "0.3.5"))
        assertFalse(record.shouldSurfaceInAbout(currentVersionName = "0.3.6"))
        assertFalse(record.copy(versionName = "latest").shouldSurfaceInAbout("0.3.4"))
    }
}
