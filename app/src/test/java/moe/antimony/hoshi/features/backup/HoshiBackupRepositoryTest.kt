package moe.antimony.hoshi.features.backup

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.EpubArchiveExtractor
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.epub.writeMinimalExtractedEpub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class HoshiBackupRepositoryTest {
    @Test
    fun exportBooksWritesIosCompatibleArchiveContentsWithoutTopLevelBooksDirectory() = runBlocking {
        val filesDir = Files.createTempDirectory("hoshi-books-backup-export").toFile()
        val booksDir = filesDir.resolve("Books")
        booksDir.resolve("book-a").mkdirs()
        booksDir.resolve("shelves.json").writeText("""[{"name":"Shelf","bookIds":["book-a"]}]""")
        booksDir.resolve("book-a/metadata.json").writeText("""{"id":"book-a"}""")
        val output = ByteArrayOutputStream()

        HoshiBackupRepository(filesDir).exportBooks(output)

        val entries = zipEntryNames(output.toByteArray())
        assertEquals(listOf("book-a/", "book-a/metadata.json", "shelves.json"), entries)
        assertFalse(entries.any { it == "Books/" || it.startsWith("Books/") })
    }

    @Test
    fun exportBooksWritesAndroidReadableLocalHeadersWithoutZip64SizePlaceholders() = runBlocking {
        val filesDir = Files.createTempDirectory("hoshi-books-backup-headers").toFile()
        val booksDir = filesDir.resolve("Books")
        booksDir.resolve("book-a").mkdirs()
        booksDir.resolve("book-a/metadata.json").writeText("""{"id":"book-a"}""")
        val output = ByteArrayOutputStream()

        HoshiBackupRepository(filesDir).exportBooks(output)

        val firstHeader = firstLocalFileHeader(output.toByteArray())
        assertFalse(firstHeader.extraFieldIds.contains(ZIP64_EXTRA_FIELD_ID))
        assertFalse(firstHeader.compressedSize == ZIP64_SIZE_PLACEHOLDER)
        assertFalse(firstHeader.uncompressedSize == ZIP64_SIZE_PLACEHOLDER)
    }

    @Test
    fun restoreBooksReplacesCurrentBooksDirectoryWithArchiveContents() = runBlocking {
        val filesDir = Files.createTempDirectory("hoshi-books-backup-restore").toFile()
        val booksDir = filesDir.resolve("Books")
        booksDir.mkdirs()
        booksDir.resolve("old/metadata.json").also { file ->
            file.parentFile?.mkdirs()
            file.writeText("""{"id":"old"}""")
        }
        val archive = zipBytes(
            "shelves.json" to """[{"name":"Restored","bookIds":["new"]}]""".toByteArray(),
            "new/metadata.json" to """{"id":"new"}""".toByteArray(),
        )

        HoshiBackupRepository(filesDir).restoreBooks(ByteArrayInputStream(archive))

        assertFalse(booksDir.resolve("old/metadata.json").exists())
        assertEquals("""[{"name":"Restored","bookIds":["new"]}]""", booksDir.resolve("shelves.json").readText())
        assertEquals("""{"id":"new"}""", booksDir.resolve("new/metadata.json").readText())
    }

    @Test
    fun restoreBooksAcceptsIosZip64LocalHeaderArchive() = runBlocking {
        val filesDir = Files.createTempDirectory("hoshi-books-backup-ios-zip64").toFile()
        val booksDir = filesDir.resolve("Books").also { it.mkdirs() }
        booksDir.resolve("old/metadata.json").also { file ->
            file.parentFile?.mkdirs()
            file.writeText("""{"id":"old"}""")
        }
        val archive = zipWithIosStyleZip64LocalHeader(
            "book-a/metadata.json",
            """{"id":"book-a"}""".toByteArray(),
        )

        HoshiBackupRepository(filesDir).restoreBooks(ByteArrayInputStream(archive))

        assertFalse(booksDir.resolve("old/metadata.json").exists())
        assertEquals("""{"id":"book-a"}""", booksDir.resolve("book-a/metadata.json").readText())
    }

    @Test
    fun restoreBooksDeletesOldCopiedSasayakiAudioWhenRestoredBookHasNoAudio() = runBlocking {
        val filesDir = Files.createTempDirectory("hoshi-books-backup-restore-no-audio").toFile()
        val booksDir = filesDir.resolve("Books")
        booksDir.resolve("book-a/Sasayaki").mkdirs()
        booksDir.resolve("book-a/Sasayaki/sasayaki_audio.m4b").writeBytes(byteArrayOf(1, 2, 3))
        booksDir.resolve("book-a/sasayaki_playback.json").writeText(
            """{"lastPosition":1.0,"audioFileName":"sasayaki_audio.m4b"}""",
        )
        val archive = zipBytes(
            "book-a/metadata.json" to """{"id":"book-a"}""".toByteArray(),
            "book-a/sasayaki_playback.json" to """{"lastPosition":2.0}""".toByteArray(),
        )

        HoshiBackupRepository(filesDir).restoreBooks(ByteArrayInputStream(archive))

        assertFalse(booksDir.resolve("book-a/Sasayaki/sasayaki_audio.m4b").exists())
        assertEquals("""{"lastPosition":2.0}""", booksDir.resolve("book-a/sasayaki_playback.json").readText())
    }

    @Test
    fun restoreBooksDeletesOldCopiedSasayakiAudioWhenRestoredBookUsesExternalAudio() = runBlocking {
        val filesDir = Files.createTempDirectory("hoshi-books-backup-restore-external-audio").toFile()
        val booksDir = filesDir.resolve("Books")
        booksDir.resolve("book-a/Sasayaki").mkdirs()
        booksDir.resolve("book-a/Sasayaki/sasayaki_audio.m4b").writeBytes(byteArrayOf(1, 2, 3))
        booksDir.resolve("book-a/sasayaki_playback.json").writeText(
            """{"lastPosition":1.0,"audioFileName":"sasayaki_audio.m4b"}""",
        )
        val archive = zipBytes(
            "book-a/metadata.json" to """{"id":"book-a"}""".toByteArray(),
            "book-a/sasayaki_playback.json" to
                """{"lastPosition":2.0,"audioUri":"content://media/external/audio/media/1"}""".toByteArray(),
        )

        HoshiBackupRepository(filesDir).restoreBooks(ByteArrayInputStream(archive))

        assertFalse(booksDir.resolve("book-a/Sasayaki/sasayaki_audio.m4b").exists())
        assertEquals(
            """{"lastPosition":2.0,"audioUri":"content://media/external/audio/media/1"}""",
            booksDir.resolve("book-a/sasayaki_playback.json").readText(),
        )
    }

    @Test
    fun restoreBooksRejectsZipSlipArchiveWithoutDeletingCurrentBooks() = runBlocking {
        val filesDir = Files.createTempDirectory("hoshi-books-backup-zipslip").toFile()
        val booksDir = filesDir.resolve("Books")
        booksDir.mkdirs()
        booksDir.resolve("keep.txt").writeText("keep")
        val archive = zipBytes("../escape.txt" to "bad".toByteArray())

        try {
            HoshiBackupRepository(filesDir).restoreBooks(ByteArrayInputStream(archive))
            fail("Expected zip slip archive to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Unsafe backup entry"))
        }

        assertEquals("keep", booksDir.resolve("keep.txt").readText())
        assertFalse(filesDir.resolve("escape.txt").exists())
    }

    @Test
    fun exportBooksPreservesPlatformSpecificSasayakiAudioKeysWithoutCrossPlatformInterpretation() = runBlocking {
        val filesDir = Files.createTempDirectory("hoshi-books-backup-external-audio").toFile()
        val bookRoot = filesDir.resolve("Books/book-a").also { it.mkdirs() }
        val playback = """
            {
                "lastPosition": 12.5,
                "delay": 0.2,
                "rate": 1.1,
                "audioUri": "content://media/external/audio/media/1",
                "audioBookmark": "ios-bookmark-data",
                "audioFileName": null
            }
        """.trimIndent()
        bookRoot.resolve("sasayaki_playback.json").writeText(playback)
        val output = ByteArrayOutputStream()

        HoshiBackupRepository(filesDir).exportBooks(output)

        val exported = Json.parseToJsonElement(
            zipText(output.toByteArray(), "book-a/sasayaki_playback.json"),
        ).jsonObject
        assertEquals("12.5", exported.getValue("lastPosition").jsonPrimitive.content)
        assertEquals("content://media/external/audio/media/1", exported.getValue("audioUri").jsonPrimitive.content)
        assertEquals("ios-bookmark-data", exported.getValue("audioBookmark").jsonPrimitive.content)
        assertTrue(exported["audioFileName"].toString() == "null")
    }

    @Test
    fun exportBooksKeepsPortableCopiedSasayakiAudioFileInArchive() = runBlocking {
        val filesDir = Files.createTempDirectory("hoshi-books-backup-copied-audio").toFile()
        val bookRoot = filesDir.resolve("Books/book-a").also { it.mkdirs() }
        bookRoot.resolve("Sasayaki").mkdirs()
        bookRoot.resolve("Sasayaki/sasayaki_audio.m4b").writeBytes(byteArrayOf(1, 2, 3))
        bookRoot.resolve("sasayaki_playback.json").writeText(
            """
            {
                "lastPosition": 12.5,
                "delay": 0.2,
                "rate": 1.1,
                "audioUri": null,
                "audioFileName": "sasayaki_audio.m4b"
            }
            """.trimIndent(),
        )
        val output = ByteArrayOutputStream()

        HoshiBackupRepository(filesDir).exportBooks(output)

        val entries = zipEntryNames(output.toByteArray())
        val exported = Json.parseToJsonElement(
            zipText(output.toByteArray(), "book-a/sasayaki_playback.json"),
        ).jsonObject
        assertTrue(entries.contains("book-a/Sasayaki/sasayaki_audio.m4b"))
        assertEquals("null", exported["audioUri"].toString())
        assertEquals("sasayaki_audio.m4b", exported.getValue("audioFileName").jsonPrimitive.content)
    }

    @Test
    fun booksBackupFileNameMatchesIosTimestampShapeAndExtension() {
        val instant = Instant.parse("2026-05-10T07:08:09Z")

        val name = booksBackupFileName(instant, ZoneId.of("UTC"))

        assertEquals("Books_2026-05-10_07-08-09.hoshi", name)
    }

    @Test
    fun exportAndRestoreDictionariesUseIosFolderArchiveShape() = runBlocking {
        val sourceDir = Files.createTempDirectory("hoshi-dictionaries-backup-source").toFile()
        sourceDir.resolve("Dictionaries/Term/JMdict").mkdirs()
        sourceDir.resolve("Dictionaries/Term/JMdict/index.json").writeText("""{"title":"JMdict"}""")
        sourceDir.resolve("Dictionaries/config.json").writeText("""{"termDictionaries":[]}""")
        val output = ByteArrayOutputStream()
        HoshiBackupRepository(sourceDir).exportDictionaries(output)
        val targetDir = Files.createTempDirectory("hoshi-dictionaries-backup-target").toFile()
        targetDir.resolve("Dictionaries/old/index.json").also { file ->
            file.parentFile?.mkdirs()
            file.writeText("""{"title":"Old"}""")
        }

        HoshiBackupRepository(targetDir).restoreDictionaries(ByteArrayInputStream(output.toByteArray()))

        assertFalse(targetDir.resolve("Dictionaries/old/index.json").exists())
        assertEquals("""{"title":"JMdict"}""", targetDir.resolve("Dictionaries/Term/JMdict/index.json").readText())
        assertEquals("""{"termDictionaries":[]}""", targetDir.resolve("Dictionaries/config.json").readText())
        assertFalse(zipEntryNames(output.toByteArray()).any { it == "Dictionaries/" || it.startsWith("Dictionaries/") })
    }

    @Test
    fun exportAndRestoreTtuBookDataMergesBookdataProgressAndStatistics() = runBlocking {
        val sourceDir = Files.createTempDirectory("hoshi-ttu-backup-source").toFile()
        val sourceRepository = BookRepository(sourceDir)
        val sourceEntry = sourceRepository.createPackedTestBook("TTU Book")
        sourceRepository.saveBookmark(sourceEntry.root, Bookmark(0, 0.5, 5, 10.0))
        sourceRepository.saveStatistics(
            sourceEntry.root,
            listOf(ReadingStatistics(title = "TTU Book", dateKey = "2026-06-06", charactersRead = 5, lastStatisticModified = 100)),
        )
        val output = ByteArrayOutputStream()

        HoshiBackupRepository(sourceDir).exportTtuBookData(output)

        val entries = zipEntryNames(output.toByteArray())
        assertTrue(entries.any { it.startsWith("TTU Book/bookdata_") && it.endsWith(".zip") })
        assertTrue(entries.any { it.startsWith("TTU Book/progress_") && it.endsWith(".json") })
        assertTrue(entries.any { it.startsWith("TTU Book/statistics_") && it.endsWith(".json") })

        val targetDir = Files.createTempDirectory("hoshi-ttu-backup-target").toFile()
        val targetRepository = BookRepository(targetDir)
        HoshiBackupRepository(targetDir).restoreTtuBookData(ByteArrayInputStream(output.toByteArray()))
        val restored = targetRepository.loadBookEntries().single()

        assertEquals("TTU Book", restored.metadata.title)
        assertTrue(restored.root.resolve(restored.metadata.epub!!).isFile)
        assertEquals(5, targetRepository.loadBookmark(restored.root)?.characterCount)
        assertEquals(5, targetRepository.loadStatistics(restored.root).single().charactersRead)
    }

    @Test
    fun ttuBookDataBackupDoesNotExportOrRestoreSasayakiAudioBookSidecarsLikeIos() = runBlocking {
        val sourceDir = Files.createTempDirectory("hoshi-ttu-backup-audio-source").toFile()
        val sourceRepository = BookRepository(sourceDir)
        val sourceEntry = sourceRepository.createPackedTestBook("TTU Audio Book")
        sourceRepository.saveSasayakiPlayback(sourceEntry.root, SasayakiPlaybackData(lastPosition = 44.0))
        val output = ByteArrayOutputStream()

        HoshiBackupRepository(sourceDir).exportTtuBookData(output)

        val entries = zipEntryNames(output.toByteArray())
        assertFalse(entries.any { it.substringAfter('/').startsWith("audioBook_") })
        val targetDir = Files.createTempDirectory("hoshi-ttu-backup-audio-target").toFile()
        val targetRepository = BookRepository(targetDir)
        val existing = targetRepository.createPackedTestBook("TTU Audio Book")
        targetRepository.saveSasayakiPlayback(existing.root, SasayakiPlaybackData(lastPosition = 12.0))

        HoshiBackupRepository(targetDir).restoreTtuBookData(ByteArrayInputStream(output.toByteArray()))

        assertEquals(12.0, targetRepository.loadSasayakiPlayback(existing.root)?.lastPosition)
    }

    @Test
    fun restoreTtuBookDataReturnsZeroWhenArchiveContainsNoBookdata() = runBlocking {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.writeTextEntry("Not TTU/readme.txt", "not bookdata")
        }
        val targetDir = Files.createTempDirectory("hoshi-empty-ttu-backup-target").toFile()
        val targetRepository = BookRepository(targetDir)

        val restored = HoshiBackupRepository(targetDir).restoreTtuBookData(ByteArrayInputStream(output.toByteArray()))

        assertEquals(0, restored)
        assertTrue(targetRepository.loadBookEntries().isEmpty())
    }

    @Test
    fun exportTtuBookDataDisambiguatesDuplicateVisibleTitles() = runBlocking {
        val sourceDir = Files.createTempDirectory("hoshi-ttu-backup-duplicate-title").toFile()
        val sourceRepository = BookRepository(sourceDir)
        val first = sourceRepository.createPackedTestBook("First Book")
        val second = sourceRepository.createPackedTestBook("Second Book")
        listOf(first, second).forEachIndexed { index, entry ->
            val cover = entry.root.resolve("cover.jpg")
            cover.writeBytes(byteArrayOf(index.toByte()))
            sourceRepository.saveMetadata(
                entry.root,
                entry.metadata.copy(
                    renamedTitle = "Same Title",
                    cover = "Books/${entry.root.name}/cover.jpg",
                ),
            )
        }
        val output = ByteArrayOutputStream()

        HoshiBackupRepository(sourceDir).exportTtuBookData(output)

        val entries = zipEntryNames(output.toByteArray())
        val bookFolders = entries
            .filter { it.contains("/bookdata_") }
            .map { it.substringBefore('/') }
            .distinct()
        assertEquals(2, bookFolders.size)
        assertTrue("Same Title" in bookFolders)
        assertEquals(2, entries.count { it.endsWith("/cover_1_6.jpg") })
    }

    @Test
    fun ttuBookDataBackupFileNameMatchesIosTimestampShapeAndExtension() {
        val instant = Instant.parse("2026-05-10T07:08:09Z")

        val name = ttuBookDataBackupFileName(instant, ZoneId.of("UTC"))

        assertEquals("hoshi_ttu_export_2026-05-10_07-08-09.zip", name)
    }

    private fun zipEntryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                zip.closeEntry()
            }
        }
        return names
    }

    private fun zipText(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == name) {
                    return zip.readBytes().decodeToString()
                }
                zip.closeEntry()
            }
        }
        fail("Missing zip entry: $name")
        return ""
    }

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun firstLocalFileHeader(bytes: ByteArray): LocalFileHeader {
        assertEquals(LOCAL_FILE_HEADER_SIGNATURE, uint(0, bytes))
        val compressedSize = uint(18, bytes)
        val uncompressedSize = uint(22, bytes)
        val nameLength = ushort(26, bytes)
        val extraLength = ushort(28, bytes)
        val extraOffset = 30 + nameLength
        val extraFieldIds = buildList {
            var offset = extraOffset
            val end = extraOffset + extraLength
            while (offset + 4 <= end) {
                val id = ushort(offset, bytes)
                val size = ushort(offset + 2, bytes)
                add(id)
                offset += 4 + size
            }
        }
        return LocalFileHeader(compressedSize, uncompressedSize, extraFieldIds)
    }

    private fun zipWithIosStyleZip64LocalHeader(name: String, bytes: ByteArray): ByteArray {
        val normalZip = zipBytes(name to bytes)
        val nameLength = ushort(26, normalZip)
        val extraLength = ushort(28, normalZip)
        val extraOffset = 30 + nameLength
        val dataOffset = extraOffset + extraLength
        val zip64Extra = byteArrayOf(
            0x01, 0x00,
            0x10, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val patched = (normalZip.copyOfRange(0, dataOffset) + zip64Extra + normalZip.copyOfRange(dataOffset, normalZip.size))
            .copyOf()
        writeUShort(patched, 4, 45)
        writeUInt(patched, 18, ZIP64_SIZE_PLACEHOLDER)
        writeUInt(patched, 22, ZIP64_SIZE_PLACEHOLDER)
        writeUShort(patched, 28, extraLength + zip64Extra.size)

        val eocdOffset = findSignature(patched, END_OF_CENTRAL_DIRECTORY_SIGNATURE)
        val centralDirectoryOffset = uint(eocdOffset + 16, patched)
        writeUInt(patched, eocdOffset + 16, centralDirectoryOffset + zip64Extra.size)
        return patched
    }

    private suspend fun BookRepository.createPackedTestBook(title: String): moe.antimony.hoshi.epub.BookEntry {
        val root = createBookDirectory(title)
        val extracted = root.parentFile!!.resolve(".${root.name}-extracted").also { it.deleteRecursively() }
        writeMinimalExtractedEpub(extracted, title = title)
        val epub = root.resolve("${root.name}.epub")
        EpubArchiveExtractor().createArchive(extracted, epub)
        extracted.deleteRecursively()
        val parsed = EpubBookParser().parse(root)
        val metadata = BookMetadata(
            id = UUID.randomUUID().toString(),
            title = title,
            cover = null,
            folder = root.name,
            lastAccess = 0.0,
            epub = epub.name,
        )
        saveMetadata(root, metadata)
        saveBookInfo(root, parsed.bookInfo)
        return moe.antimony.hoshi.epub.BookEntry(root, metadata)
    }

    private fun ushort(offset: Int, bytes: ByteArray): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun uint(offset: Int, bytes: ByteArray): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun writeUShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun writeUInt(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun findSignature(bytes: ByteArray, signature: Long): Int {
        for (offset in bytes.size - 4 downTo 0) {
            if (uint(offset, bytes) == signature) return offset
        }
        fail("Missing zip signature: $signature")
        return -1
    }

    private fun ZipOutputStream.writeTextEntry(path: String, value: String) {
        putNextEntry(ZipEntry(path))
        write(value.toByteArray())
        closeEntry()
    }

    private data class LocalFileHeader(
        val compressedSize: Long,
        val uncompressedSize: Long,
        val extraFieldIds: List<Int>,
    )

    private companion object {
        const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50L
        const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50L
        const val ZIP64_EXTRA_FIELD_ID = 0x0001
        const val ZIP64_SIZE_PLACEHOLDER = 0xffffffffL
    }
}
