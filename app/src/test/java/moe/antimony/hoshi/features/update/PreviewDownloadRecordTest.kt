package moe.antimony.hoshi.features.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PreviewDownloadRecordTest {
    @Test fun previewRecordSurvivesRestartAndCannotOverwriteStableRecordWithSameDownloadId() = runTest {
        val directory = java.nio.file.Files.createTempDirectory("hoshi-preview-records").toFile()
        try {
            fun datastore(name: String) = PreferenceDataStoreFactory.create(scope = backgroundScope,
                produceFile = { directory.resolve("$name.preferences_pb") })
            val stable = UpdateDownloadStore(datastore("stable"))
            val previewData = datastore("preview")
            val preview = UpdateDownloadStore(previewData)
            val sha = "a".repeat(40)
            val release = AvailableUpdate("1.4.0", "https://example/stable", "stable.apk", "https://example/stable.apk", sha256 = null)
            val build = release.copy(versionName = "", assetName = "preview.apk", previewCommitSha = sha,
                previewTag = "apk-preview-aaaaaaa-42-1", previewPublishedAt = "2026-10-21T10:00:00Z")
            stable.saveDownloading(release, "stable.apk", 1L, release.downloadUrl)
            preview.saveDownloading(build, "preview.apk", 1L, build.downloadUrl)
            preview.markDownloaded(1L)
            val restored = requireNotNull(UpdateDownloadStore(previewData).load())
            assertEquals(build, restored.toAvailableUpdate())
            assertEquals(UpdateDownloadRecordStatus.Downloading, stable.load()!!.status)
            assertFalse(shouldDeletePreviewApk(restored, "b".repeat(40)))
            assertFalse(shouldDeletePreviewApk(restored, ""))
            assertTrue(shouldDeletePreviewApk(restored, sha))
            assertFalse(restored.matches(build.copy(previewCommitSha = "b".repeat(40))))
            preview.clear()
            assertNotNull(stable.load())
        } finally { directory.deleteRecursively() }
    }
}
