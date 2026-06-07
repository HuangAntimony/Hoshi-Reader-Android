package moe.antimony.hoshi.features.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GoogleDriveCacheTest {
    @Test
    fun clearGoogleDriveCoverCacheRemovesRemoteCoverThumbnails() {
        val cacheDir = Files.createTempDirectory("hoshi-drive-cache").toFile()
        val coverCache = googleDriveCoverCacheDirectory(cacheDir)
        coverCache.mkdirs()
        coverCache.resolve("cover-id.jpg").writeText("partial")

        clearGoogleDriveCoverCache(cacheDir)

        assertFalse(coverCache.exists())
    }

    @Test
    fun googleDriveCoverCacheDirectoryMatchesBookshelfRemoteCoverCacheLocation() {
        val cacheDir = Files.createTempDirectory("hoshi-drive-cache-path").toFile()

        val coverCache = googleDriveCoverCacheDirectory(cacheDir)

        assertTrue(coverCache.absolutePath.endsWith("gdrive-covers"))
    }
}
