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
    fun remoteCoverSourcesDownloadConcurrentlyBeforePublishingRemoteSection() = runBlocking {
        val cacheDir = Files.createTempDirectory("hoshi-remote-covers").toFile()
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val entries = listOf(
            remoteEntry("first"),
            remoteEntry("second"),
        )

        val sources = withTimeout(1_000) {
            loadRemoteCoverSources(
                remoteEntries = entries,
                cacheDir = cacheDir,
            ) { fileId, destination, _ ->
                when (fileId) {
                    "cover-first" -> {
                        firstStarted.complete(Unit)
                        secondStarted.await()
                    }
                    "cover-second" -> {
                        firstStarted.await()
                        secondStarted.complete(Unit)
                    }
                }
                destination.writeText(fileId)
            }
        }

        assertEquals(setOf("first", "second"), sources.keys)
    }

    private fun remoteEntry(id: String): RemoteBookEntry =
        RemoteBookEntry(
            id = id,
            folderId = id,
            folderName = id,
            title = id,
            syncFiles = DriveSyncFiles(
                bookData = DriveFile("bookdata-$id", "bookdata_1_6_10_1000_1000.zip"),
                cover = DriveFile("cover-$id", "cover_1_6.jpg"),
                progress = null,
                statistics = null,
                audioBook = null,
            ),
        )
}
