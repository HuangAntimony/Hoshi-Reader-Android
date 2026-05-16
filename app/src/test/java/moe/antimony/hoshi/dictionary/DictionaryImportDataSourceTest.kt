package moe.antimony.hoshi.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DictionaryImportDataSourceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun importWritesToStagingThenCommitsImportedDictionaryToTypeDirectory() {
        val typeDirectory = temporaryFolder.newFolder("Term")
        val bridge = StagingDictionaryBridge("JMdict")
        val dataSource = DictionaryImportDataSource(bridge)

        dataSource.importDictionary(ByteArrayInputStream(dictionaryArchive("JMdict")), typeDirectory)

        assertEquals(1, bridge.outputDirs.size)
        assertTrue(bridge.outputDirs.single().startsWith(typeDirectory.resolve(".dictionary-import-").absolutePath))
        assertTrue(typeDirectory.resolve("JMdict/index.json").isFile)
        assertFalse(typeDirectory.listFiles().orEmpty().any { it.name.startsWith(".dictionary-import-") })
    }

    @Test
    fun failedImportRemovesStagingWithoutTouchingExistingDictionary() {
        val typeDirectory = temporaryFolder.newFolder("failure-Term")
        typeDirectory.resolve("Existing/index.json").also { file ->
            file.parentFile!!.mkdirs()
            file.writeText("keep")
        }
        val dataSource = DictionaryImportDataSource(FailingDictionaryBridge())

        try {
            dataSource.importDictionary(ByteArrayInputStream(dictionaryArchive("Partial")), typeDirectory)
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Failed to import dictionary"))
        }

        assertEquals("keep", typeDirectory.resolve("Existing/index.json").readText())
        assertFalse(typeDirectory.listFiles().orEmpty().any { it.name.startsWith(".dictionary-import-") })
    }

    @Test
    fun importReplacesExistingDictionaryAfterStagingCommit() {
        val typeDirectory = temporaryFolder.newFolder("replace-Term")
        typeDirectory.resolve("Existing/index.json").also { file ->
            file.parentFile!!.mkdirs()
            file.writeText("old")
        }
        val dataSource = DictionaryImportDataSource(StagingDictionaryBridge("Existing"))

        dataSource.importDictionary(ByteArrayInputStream(dictionaryArchive("Existing")), typeDirectory)

        assertEquals(
            """{"title":"Existing","format":3,"revision":"rev"}""",
            typeDirectory.resolve("Existing/index.json").readText(),
        )
        assertFalse(typeDirectory.listFiles().orEmpty().any { it.name.contains("-replace-") })
        assertFalse(typeDirectory.listFiles().orEmpty().any { it.name.startsWith(".dictionary-import-") })
    }

    @Test
    fun importSkipsNativeImporterWhenIncomingIndexMatchesInstalledDictionary() {
        val typeDirectory = temporaryFolder.newFolder("duplicate-Term")
        val bridge = StagingDictionaryBridge("JMdict")
        val dataSource = DictionaryImportDataSource(bridge)
        val archive = zipBytes(
            "index.json" to """{"title":"JMdict","format":3,"revision":"rev"}""",
        )

        val imported = dataSource.importDictionary(
            input = ByteArrayInputStream(archive),
            typeDirectory = typeDirectory,
            shouldSkip = { index -> index.title == "JMdict" && index.revision == "rev" },
        )

        assertFalse(imported)
        assertEquals(emptyList<String>(), bridge.outputDirs)
        assertFalse(typeDirectory.listFiles().orEmpty().any { it.name.startsWith(".dictionary-import-") })
    }

    @Test
    fun importReadsIndexWithoutValidatingUnrelatedDictionaryBankCrc() {
        val typeDirectory = temporaryFolder.newFolder("crc-Pitch")
        val bridge = StagingDictionaryBridge("新明解日本語アクセント辞典")
        val dataSource = DictionaryImportDataSource(bridge)
        val archive = dictionaryArchiveWithCorruptUnusedBank()

        val imported = dataSource.importDictionary(ByteArrayInputStream(archive), typeDirectory)

        assertTrue(imported)
        assertEquals(1, bridge.outputDirs.size)
        assertTrue(typeDirectory.resolve("新明解日本語アクセント辞典/index.json").isFile)
    }

    private fun zipBytes(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun dictionaryArchive(title: String, revision: String = "rev"): ByteArray =
        zipBytes("index.json" to """{"title":"$title","format":3,"revision":"$revision"}""")

    private fun dictionaryArchiveWithCorruptUnusedBank(): ByteArray {
        val archive = storedZipBytes(
            "index.json" to """{"title":"新明解日本語アクセント辞典","format":3,"revision":"rev"}""",
            "term_bank_1.json" to """[["壊れた未使用バンク"]]""",
        )
        return archive.copyOf().also { bytes ->
            val offset = localFileDataOffset(bytes, "term_bank_1.json")
            bytes[offset] = (bytes[offset].toInt() xor 0x01).toByte()
        }
    }

    private fun storedZipBytes(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                val bytes = content.toByteArray()
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    crc = bytes.crc32()
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun ByteArray.crc32(): Long {
        val crc = CRC32()
        crc.update(this)
        return crc.value
    }

    private fun localFileDataOffset(zipBytes: ByteArray, name: String): Int {
        var offset = 0
        while (offset + LOCAL_FILE_HEADER_SIZE < zipBytes.size) {
            if (zipBytes.littleEndianInt(offset) != LOCAL_FILE_HEADER_SIGNATURE) break
            val nameLength = zipBytes.littleEndianShort(offset + LOCAL_FILE_NAME_LENGTH_OFFSET)
            val extraLength = zipBytes.littleEndianShort(offset + LOCAL_FILE_EXTRA_LENGTH_OFFSET)
            val dataOffset = offset + LOCAL_FILE_HEADER_SIZE + nameLength + extraLength
            val entryName = zipBytes.decodeToString(
                startIndex = offset + LOCAL_FILE_HEADER_SIZE,
                endIndex = offset + LOCAL_FILE_HEADER_SIZE + nameLength,
            )
            if (entryName == name) return dataOffset
            val compressedSize = zipBytes.littleEndianInt(offset + LOCAL_FILE_COMPRESSED_SIZE_OFFSET)
            offset = dataOffset + compressedSize
        }
        error("Missing local ZIP entry: $name")
    }

    private fun ByteArray.littleEndianShort(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.littleEndianInt(offset: Int): Int =
        littleEndianShort(offset) or (littleEndianShort(offset + 2) shl 16)

    private class StagingDictionaryBridge(
        private val dictionaryName: String,
    ) : DictionaryNativeBridge {
        val outputDirs = mutableListOf<String>()

        override fun importDictionary(zipPath: String, outputDir: String): NativeDictionaryImportResult {
            outputDirs += outputDir
            File(outputDir, "$dictionaryName/index.json").also { file ->
                file.parentFile!!.mkdirs()
                file.writeText("""{"title":"$dictionaryName","format":3,"revision":"rev"}""")
            }
            return NativeDictionaryImportResult(
                success = true,
                title = dictionaryName,
                termCount = 1,
                metaCount = 0,
                freqCount = 0,
                pitchCount = 0,
                mediaCount = 0,
            )
        }

        override fun rebuildQuery(termPaths: Array<String>, freqPaths: Array<String>, pitchPaths: Array<String>) = Unit
    }

    private class FailingDictionaryBridge : DictionaryNativeBridge {
        override fun importDictionary(zipPath: String, outputDir: String): NativeDictionaryImportResult {
            File(outputDir, "Partial/index.json").also { file ->
                file.parentFile!!.mkdirs()
                file.writeText("""{"title":"Partial"}""")
            }
            return NativeDictionaryImportResult(
                success = false,
                title = "Partial",
                termCount = 0,
                metaCount = 0,
                freqCount = 0,
                pitchCount = 0,
                mediaCount = 0,
            )
        }

        override fun rebuildQuery(termPaths: Array<String>, freqPaths: Array<String>, pitchPaths: Array<String>) = Unit
    }
}

private const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034B50
private const val LOCAL_FILE_HEADER_SIZE = 30
private const val LOCAL_FILE_COMPRESSED_SIZE_OFFSET = 18
private const val LOCAL_FILE_NAME_LENGTH_OFFSET = 26
private const val LOCAL_FILE_EXTRA_LENGTH_OFFSET = 28
