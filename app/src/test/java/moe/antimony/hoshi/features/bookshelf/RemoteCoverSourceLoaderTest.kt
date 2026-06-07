package moe.antimony.hoshi.features.bookshelf

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.antimony.hoshi.features.sync.DriveFile
import moe.antimony.hoshi.features.sync.DriveSyncFiles
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class RemoteCoverSourceLoaderTest {
    @Test
    fun remoteCoverSourcesUseDriveThumbnailLinkResizedLikeIos() = runBlocking {
        val cacheDir = Files.createTempDirectory("hoshi-remote-covers").toFile()
        val requestedLinks = mutableListOf<String>()
        val entry = remoteEntry(
            id = "first",
            thumbnailLink = "https://drive.google.com/thumbnail?id=cover-first=s220",
        )

        val sources = withTimeout(1_000) {
            loadRemoteCoverSources(
                remoteEntries = listOf(entry),
                cacheDir = cacheDir,
            ) { cover, destination, _ ->
                requestedLinks += cover.thumbnailLink.orEmpty()
                destination.writeText(cover.id)
            }
        }

        assertEquals(listOf("https://drive.google.com/thumbnail?id=cover-first=s768"), requestedLinks)
        assertEquals(setOf("first"), sources.keys)
    }

    @Test
    fun remoteCoverSourcesStillDownloadConcurrentlyWhenUsingThumbnails() = runBlocking {
        val cacheDir = Files.createTempDirectory("hoshi-remote-covers-concurrent").toFile()
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val entries = listOf(
            remoteEntry("first", "https://drive.google.com/thumbnail?id=first=s220"),
            remoteEntry("second", "https://drive.google.com/thumbnail?id=second=s220"),
        )

        val sources = withTimeout(1_000) {
            loadRemoteCoverSources(
                remoteEntries = entries,
                cacheDir = cacheDir,
            ) { cover, destination, _ ->
                when (cover.id) {
                    "cover-first" -> {
                        firstStarted.complete(Unit)
                        secondStarted.await()
                    }
                    "cover-second" -> {
                        firstStarted.await()
                        secondStarted.complete(Unit)
                    }
                }
                destination.writeText(cover.id)
            }
        }

        assertEquals(setOf("first", "second"), sources.keys)
    }

    private fun remoteEntry(id: String, thumbnailLink: String?): RemoteBookEntry =
        RemoteBookEntry(
            id = id,
            folderId = id,
            folderName = id,
            title = id,
            syncFiles = DriveSyncFiles(
                bookData = DriveFile("bookdata-$id", "bookdata_1_6_10_1000_1000.zip"),
                cover = DriveFile("cover-$id", "cover_1_6.jpg", thumbnailLink = thumbnailLink),
                progress = null,
                statistics = null,
                audioBook = null,
            ),
        )
}
