package moe.antimony.hoshi.features.wallpaper

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
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
    fun onlyDedicatedBookCoverWallpaperTypeIsAccepted() {
        assertTrue(isIReaderBookCoverScreenSaverSelected("2,0"))
        assertFalse(isIReaderBookCoverScreenSaverSelected("10,0"))
        assertFalse(isIReaderBookCoverScreenSaverSelected("16,0"))
        assertFalse(isIReaderBookCoverScreenSaverSelected(null))
    }

    @Test
    fun rawBitmapFileUsesIReaderHeaderAndPixelBytes() {
        val output = tempFolder.newFile("cover.rmb")
        val pixels = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        writeIReaderRawBitmap(
            output = output,
            width = 2,
            height = 1,
            configOrdinal = 3,
            pixels = ByteBuffer.wrap(pixels),
        )

        val bytes = output.readBytes()
        val header = ByteBuffer.wrap(bytes, 0, 16).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1_380_013_890, header.int)
        assertEquals(2, header.int)
        assertEquals(1, header.int)
        assertEquals(3, header.int)
        assertArrayEquals(pixels, bytes.copyOfRange(16, bytes.size))
    }

    @Test
    fun successfulPublishReplacesOldFileThenNotifiesSystemUi() = runBlocking {
        val directory = tempFolder.newFolder("book")
        val old = directory.resolve("old.rmb").apply { writeText("old") }
        val rendered = tempFolder.newFile("rendered.png")
        var notifications = 0
        val target = IReaderBookCoverFileTarget(
            directory = directory,
            isBookCoverScreenSaverSelected = { true },
            encoder = IReaderRawBitmapEncoder { _, output -> output.writeText("new-cover-payload") },
            notifier = IReaderBookCoverNotifier { notifications += 1 },
            outputName = { "hoshi-new.rmb" },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val failure = target.publish(rendered)

        assertEquals(null, failure)
        assertFalse(old.exists())
        val published = directory.resolve("hoshi-new.rmb")
        assertEquals("new-cover-payload", published.readText())
        val permissions = Files.getPosixFilePermissions(published.toPath())
        assertFalse(PosixFilePermission.GROUP_WRITE in permissions)
        assertFalse(PosixFilePermission.OTHERS_WRITE in permissions)
        assertEquals(1, notifications)
    }

    @Test
    fun encodeFailurePreservesPreviousSuccessfulCover() = runBlocking {
        val directory = tempFolder.newFolder("book")
        val old = directory.resolve("old.rmb").apply { writeText("old") }
        var notifications = 0
        val target = IReaderBookCoverFileTarget(
            directory = directory,
            isBookCoverScreenSaverSelected = { true },
            encoder = IReaderRawBitmapEncoder { _, _ -> error("decode failed") },
            notifier = IReaderBookCoverNotifier { notifications += 1 },
            outputName = { "hoshi-new.rmb" },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val failure = target.publish(tempFolder.newFile("rendered.png"))

        assertEquals(BookCoverPublishFailure.IReaderWriteFailed, failure)
        assertTrue(old.exists())
        assertEquals(listOf("old.rmb"), directory.list()?.toList())
        assertEquals(0, notifications)
    }

    @Test
    fun staleEntryThatCannotBeRemovedPreventsRefresh() = runBlocking {
        val directory = tempFolder.newFolder("book")
        directory.resolve("stale").mkdir()
        directory.resolve("stale/cover.rmb").writeText("old")
        var notifications = 0
        val target = IReaderBookCoverFileTarget(
            directory = directory,
            isBookCoverScreenSaverSelected = { true },
            encoder = IReaderRawBitmapEncoder { _, output -> output.writeText("new-cover-payload") },
            notifier = IReaderBookCoverNotifier { notifications += 1 },
            outputName = { "hoshi-new.rmb" },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val failure = target.publish(tempFolder.newFile("rendered.png"))

        assertEquals(BookCoverPublishFailure.IReaderWriteFailed, failure)
        assertEquals(0, notifications)
        assertTrue(directory.resolve("hoshi-new.rmb").exists())
    }

    @Test
    fun unselectedBookCoverScreenSaverDoesNotWrite() = runBlocking {
        val directory = tempFolder.newFolder("book")
        var encoded = false
        val target = IReaderBookCoverFileTarget(
            directory = directory,
            isBookCoverScreenSaverSelected = { false },
            encoder = IReaderRawBitmapEncoder { _, _ -> encoded = true },
            notifier = IReaderBookCoverNotifier {},
            outputName = { "hoshi-new.rmb" },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val failure = target.publish(tempFolder.newFile("rendered.png"))

        assertEquals(BookCoverPublishFailure.IReaderBookScreenSaverNotSelected, failure)
        assertFalse(encoded)
        assertTrue(directory.list().isNullOrEmpty())
    }
}
