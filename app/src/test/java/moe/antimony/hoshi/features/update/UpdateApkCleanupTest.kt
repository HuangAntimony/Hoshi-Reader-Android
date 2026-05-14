package moe.antimony.hoshi.features.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateApkCleanupTest {
    @Test
    fun deletesOnlyApksForTheCurrentlyInstalledHoshiVersion() {
        assertTrue(
            shouldDeleteUpdateApk(
                archiveInfo = UpdateApkArchiveInfo(
                    packageName = "moe.antimony.hoshi",
                    versionName = "0.6.1",
                ),
                currentPackageName = "moe.antimony.hoshi",
                currentVersionName = "0.6.1",
            ),
        )
        assertFalse(
            shouldDeleteUpdateApk(
                archiveInfo = UpdateApkArchiveInfo(
                    packageName = "moe.antimony.hoshi",
                    versionName = "0.6.2",
                ),
                currentPackageName = "moe.antimony.hoshi",
                currentVersionName = "0.6.1",
            ),
        )
        assertFalse(
            shouldDeleteUpdateApk(
                archiveInfo = UpdateApkArchiveInfo(
                    packageName = "com.example.other",
                    versionName = "0.6.1",
                ),
                currentPackageName = "moe.antimony.hoshi",
                currentVersionName = "0.6.1",
            ),
        )
        assertFalse(
            shouldDeleteUpdateApk(
                archiveInfo = null,
                currentPackageName = "moe.antimony.hoshi",
                currentVersionName = "0.6.1",
            ),
        )
    }
}
