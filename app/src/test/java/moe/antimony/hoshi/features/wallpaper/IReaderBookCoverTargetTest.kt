package moe.antimony.hoshi.features.wallpaper

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IReaderBookCoverTargetTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun detectsIReaderBrandCaseInsensitively() {
        assertTrue(isIReaderDeviceBrand(manufacturer = "iReader", brand = "unknown"))
        assertTrue(isIReaderDeviceBrand(manufacturer = "unknown", brand = "IREADER"))
        assertFalse(isIReaderDeviceBrand(manufacturer = "Onyx", brand = "BOOX"))
    }

    @Test
    fun detectsMusnapOverseasBrandCombinationCaseInsensitively() {
        assertTrue(isIReaderDeviceBrand(manufacturer = "Chitech", brand = "Byybuo"))
        assertTrue(isIReaderDeviceBrand(manufacturer = "CHITECH", brand = "BYYBUO"))
        assertFalse(isIReaderDeviceBrand(manufacturer = "Chitech", brand = "other"))
        assertFalse(isIReaderDeviceBrand(manufacturer = "other", brand = "Byybuo"))
    }

    @Test
    fun onlyDedicatedBookCoverWallpaperTypeIsAccepted() {
        assertTrue(isIReaderBookCoverScreenSaverSelected("2,0"))
        assertFalse(isIReaderBookCoverScreenSaverSelected("10,0"))
        assertFalse(isIReaderBookCoverScreenSaverSelected("16,0"))
        assertFalse(isIReaderBookCoverScreenSaverSelected(null))
    }

    @Test
    fun successfulPublishCopiesPngRemovesPreviousCoverThenNotifiesSystemUi() = runBlocking {
        val directory = tempFolder.newFolder("book")
        val previous = directory.resolve("previous.png").apply { writeText("old") }
        val rendered = tempFolder.newFile("rendered.png").apply {
            writeBytes(PngBytes)
        }
        var notifications = 0
        val target = IReaderBookCoverFileTarget(
            directory = directory,
            isBookCoverScreenSaverSelected = { true },
            notifier = IReaderBookCoverNotifier { notifications += 1 },
            outputName = { "hoshi-new.png" },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val failure = target.publish(rendered)

        assertEquals(null, failure)
        assertFalse(previous.exists())
        val published = directory.resolve("hoshi-new.png")
        assertTrue(PngBytes.contentEquals(published.readBytes()))
        val permissions = Files.getPosixFilePermissions(published.toPath())
        assertFalse(PosixFilePermission.GROUP_WRITE in permissions)
        assertFalse(PosixFilePermission.OTHERS_WRITE in permissions)
        assertEquals(1, notifications)
    }

    @Test
    fun copyFailurePreservesPreviousSuccessfulCover() = runBlocking {
        val directory = tempFolder.newFolder("book")
        val previous = directory.resolve("previous.png").apply { writeText("old") }
        var notifications = 0
        val target = IReaderBookCoverFileTarget(
            directory = directory,
            isBookCoverScreenSaverSelected = { true },
            notifier = IReaderBookCoverNotifier { notifications += 1 },
            outputName = { "hoshi-new.png" },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val failure = target.publish(File(tempFolder.root, "missing.png"))

        assertEquals(BookCoverPublishFailure.IReaderWriteFailed, failure)
        assertTrue(previous.exists())
        assertEquals(listOf("previous.png"), directory.list()?.toList())
        assertEquals(0, notifications)
    }

    @Test
    fun staleEntryThatCannotBeRemovedPreventsRefresh() = runBlocking {
        val directory = tempFolder.newFolder("book")
        directory.resolve("stale").mkdir()
        directory.resolve("stale/cover.png").writeText("old")
        var notifications = 0
        val target = IReaderBookCoverFileTarget(
            directory = directory,
            isBookCoverScreenSaverSelected = { true },
            notifier = IReaderBookCoverNotifier { notifications += 1 },
            outputName = { "hoshi-new.png" },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val failure = target.publish(
            tempFolder.newFile("rendered.png").apply { writeBytes(PngBytes) },
        )

        assertEquals(BookCoverPublishFailure.IReaderWriteFailed, failure)
        assertEquals(0, notifications)
        assertTrue(directory.resolve("hoshi-new.png").exists())
    }

    @Test
    fun unselectedBookCoverScreenSaverDoesNotWrite() = runBlocking {
        val directory = tempFolder.newFolder("book")
        val target = IReaderBookCoverFileTarget(
            directory = directory,
            isBookCoverScreenSaverSelected = { false },
            notifier = IReaderBookCoverNotifier {},
            outputName = { "hoshi-new.png" },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val failure = target.publish(
            tempFolder.newFile("rendered.png").apply { writeBytes(PngBytes) },
        )

        assertEquals(BookCoverPublishFailure.IReaderBookScreenSaverNotSelected, failure)
        assertTrue(directory.list().isNullOrEmpty())
    }

    private companion object {
        val PngBytes = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
            0x01,
        )
    }
}
