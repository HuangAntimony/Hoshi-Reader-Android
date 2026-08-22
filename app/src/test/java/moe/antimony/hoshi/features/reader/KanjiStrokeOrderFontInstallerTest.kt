package moe.antimony.hoshi.features.reader

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

class KanjiStrokeOrderFontInstallerTest {
    @Test
    fun verifiedDownloadIsImportedIntoTheUserFontLibrary() = runBlocking {
        val root = createTempDirectory().toFile()
        val bytes = sfntFixture(
            family = "KanjiStrokeOrders",
            vendorId = "KSOF",
            weight = 500,
        )
        val spec = KanjiStrokeOrderFontSpec(
            url = "https://example.invalid/stroke-order.ttf",
            fileName = "KanjiStrokeOrders_v4.005.ttf",
            expectedSize = bytes.size.toLong(),
            sha256 = bytes.sha256(),
        )
        val manager = ReaderFontManager(root)
        val installer = KanjiStrokeOrderFontInstaller(
            fontManager = manager,
            remoteDataSource = FakeKanjiStrokeOrderFontRemoteDataSource(bytes),
            ioDispatcher = Dispatchers.Unconfined,
            spec = spec,
        )

        installer.install()

        assertTrue(installer.isInstalled())
        assertTrue(manager.fontFamilies().any { it.displayName == "KanjiStrokeOrders" })
        assertEquals(1, manager.storedFonts().size)
        assertFalse(File(manager.managedFontsDirectory(), ".stroke-order-download").exists())
    }

    @Test
    fun integrityFailureLeavesTheFontLibraryUnchangedAndCleansTemporaryFiles() {
        val root = createTempDirectory().toFile()
        val bytes = sfntFixture(
            family = "KanjiStrokeOrders",
            vendorId = "KSOF",
            weight = 500,
        )
        val manager = ReaderFontManager(root)
        val installer = KanjiStrokeOrderFontInstaller(
            fontManager = manager,
            remoteDataSource = FakeKanjiStrokeOrderFontRemoteDataSource(bytes),
            ioDispatcher = Dispatchers.Unconfined,
            spec = KanjiStrokeOrderFontSpec(
                url = "https://example.invalid/stroke-order.ttf",
                fileName = "KanjiStrokeOrders_v4.005.ttf",
                expectedSize = bytes.size.toLong(),
                sha256 = "0".repeat(64),
            ),
        )

        assertThrows(ReaderFontDownloadException::class.java) {
            runBlocking { installer.install() }
        }

        assertFalse(installer.isInstalled())
        assertTrue(manager.storedFonts().isEmpty())
        assertFalse(File(manager.managedFontsDirectory(), ".stroke-order-download").exists())
    }
}

private class FakeKanjiStrokeOrderFontRemoteDataSource(
    private val bytes: ByteArray,
) : KanjiStrokeOrderFontRemoteDataSource {
    override suspend fun open(url: String): ReaderFontRemoteResponse =
        ReaderFontRemoteResponse(
            statusCode = 200,
            contentLength = bytes.size.toLong(),
            input = ByteArrayInputStream(bytes),
        )
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
