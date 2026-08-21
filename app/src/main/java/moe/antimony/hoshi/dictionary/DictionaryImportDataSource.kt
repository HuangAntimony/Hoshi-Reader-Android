package moe.antimony.hoshi.dictionary

import android.content.ContentResolver
import android.net.Uri
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.validateImportFile
import java.io.File
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.zip.ZipFile
import javax.inject.Inject

internal class DictionaryImportDataSource(
    private val nativeBridge: DictionaryNativeBridge,
    private val json: Json,
) {
    @Inject
    constructor(nativeBridge: DictionaryNativeBridge) : this(
        nativeBridge = nativeBridge,
        json = Json { ignoreUnknownKeys = true },
    )

    fun importDictionary(
        contentResolver: ContentResolver,
        uri: Uri,
        typeDirectory: File,
        lowRamImport: Boolean = false,
        shouldSkip: (DictionaryIndex) -> Boolean = { false },
    ): Boolean {
        contentResolver.validateImportFile(uri, ImportFileType.DictionaryArchive)
        return contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open dictionary file." }
            importDictionary(input, typeDirectory, lowRamImport, shouldSkip)
        }
    }

    fun importDictionary(
        input: InputStream,
        typeDirectory: File,
        lowRamImport: Boolean = false,
        shouldSkip: (DictionaryIndex) -> Boolean = { false },
    ): Boolean = importDictionaryWithResult(
        input = input,
        typeDirectory = typeDirectory,
        lowRamImport = lowRamImport,
        shouldSkip = shouldSkip,
    ).isNotEmpty()

    fun importDictionaryByDetectedTypes(
        contentResolver: ContentResolver,
        uri: Uri,
        importRootDirectory: File,
        typeDirectories: Map<DictionaryType, File>,
        lowRamImport: Boolean = false,
        shouldSkip: (DictionaryType, DictionaryIndex) -> Boolean = { _, _ -> false },
    ): Map<DictionaryType, List<ImportedDictionary>> {
        contentResolver.validateImportFile(uri, ImportFileType.DictionaryArchive)
        return contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open dictionary file." }
            importDictionaryByDetectedTypes(input, importRootDirectory, typeDirectories, lowRamImport, shouldSkip)
        }
    }

    fun importDictionaryByDetectedTypes(
        input: InputStream,
        importRootDirectory: File,
        typeDirectories: Map<DictionaryType, File>,
        lowRamImport: Boolean = false,
        shouldSkip: (DictionaryType, DictionaryIndex) -> Boolean = { _, _ -> false },
    ): Map<DictionaryType, List<ImportedDictionary>> {
        importRootDirectory.mkdirs()
        val importId = UUID.randomUUID()
        val tempZip = importRootDirectory.resolve(".dictionary-import-$importId.zip")
        val stagingRoot = importRootDirectory.resolve(".dictionary-import-$importId")
        try {
            input.use { source ->
                tempZip.outputStream().use { output -> source.copyTo(output) }
            }
            stagingRoot.mkdirs()
            val result = nativeBridge.importDictionary(tempZip.absolutePath, stagingRoot.absolutePath, lowRamImport)
            require(result.success) { "Failed to import dictionary." }
            val targetTypes = result.detectedTypes()
            require(targetTypes.isNotEmpty()) { "Failed to detect dictionary type." }
            return commitStagedDictionariesByType(stagingRoot, typeDirectories, targetTypes, shouldSkip)
        } finally {
            tempZip.delete()
            stagingRoot.deleteRecursively()
        }
    }

    fun importDictionaryWithResult(
        input: InputStream,
        typeDirectory: File,
        lowRamImport: Boolean = false,
        shouldSkip: (DictionaryIndex) -> Boolean = { false },
    ): List<ImportedDictionary> {
        typeDirectory.mkdirs()
        val importId = UUID.randomUUID()
        val tempZip = typeDirectory.resolve(".dictionary-import-$importId.zip")
        val stagingRoot = typeDirectory.resolve(".dictionary-import-$importId")
        try {
            input.use { source ->
                tempZip.outputStream().use { output -> source.copyTo(output) }
            }
            val index = readDictionaryIndexFromZip(tempZip)
            if (shouldSkip(index)) return emptyList()
            stagingRoot.mkdirs()
            val result = nativeBridge.importDictionary(tempZip.absolutePath, stagingRoot.absolutePath, lowRamImport)
            require(result.success) { "Failed to import dictionary." }
            return commitStagedDictionaries(stagingRoot, typeDirectory)
        } finally {
            tempZip.delete()
            stagingRoot.deleteRecursively()
        }
    }

    private fun readDictionaryIndexFromZip(zipFile: File): DictionaryIndex {
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry("index.json")
                ?: error("Unable to read dictionary index.")
            zip.getInputStream(entry).use { input ->
                return json.decodeFromString<DictionaryIndex>(input.readBytes().decodeToString())
            }
        }
    }

    private fun commitStagedDictionaries(stagingRoot: File, typeDirectory: File): List<ImportedDictionary> {
        val importedDictionaries = stagingRoot.listFiles()?.filter(File::isDirectory).orEmpty()
        require(importedDictionaries.isNotEmpty()) { "Failed to import dictionary." }
        return importedDictionaries.map { stagedDictionary ->
            val imported = ImportedDictionary(
                fileName = stagedDictionary.name,
                index = readDictionaryIndexFile(stagedDictionary.resolve("index.json")),
            )
            commitStagedDictionary(stagedDictionary, typeDirectory.resolve(stagedDictionary.name))
            imported
        }
    }

    private fun commitStagedDictionariesByType(
        stagingRoot: File,
        typeDirectories: Map<DictionaryType, File>,
        targetTypes: Set<DictionaryType>,
        shouldSkip: (DictionaryType, DictionaryIndex) -> Boolean,
    ): Map<DictionaryType, List<ImportedDictionary>> {
        val stagedDictionaries = stagingRoot.listFiles()?.filter(File::isDirectory).orEmpty()
        require(stagedDictionaries.isNotEmpty()) { "Failed to import dictionary." }
        return targetTypes.associateWith { type ->
            val typeDirectory = requireNotNull(typeDirectories[type]) { "Missing ${type.directoryName} dictionary directory." }
            typeDirectory.mkdirs()
            stagedDictionaries.mapNotNull { stagedDictionary ->
                val imported = ImportedDictionary(
                    fileName = stagedDictionary.name,
                    index = readDictionaryIndexFile(stagedDictionary.resolve("index.json")),
                )
                if (shouldSkip(type, imported.index)) {
                    null
                } else {
                    val copyRoot = typeDirectory.resolve(".${stagedDictionary.name}-copy-${UUID.randomUUID()}")
                    copyDirectory(stagedDictionary.toPath(), copyRoot.toPath())
                    commitStagedDictionary(copyRoot, typeDirectory.resolve(stagedDictionary.name))
                    imported
                }
            }
        }.filterValues { it.isNotEmpty() }
    }

    private fun readDictionaryIndexFile(indexFile: File): DictionaryIndex =
        json.decodeFromString<DictionaryIndex>(indexFile.readText())

    private fun commitStagedDictionary(stagedDictionary: File, target: File) {
        val replacementBackup = target.takeIf(File::exists)?.let { existing ->
            requireNotNull(target.parentFile).resolve(".${target.name}-replace-${UUID.randomUUID()}")
                .also { backup -> moveReplacing(existing, backup) }
        }
        try {
            moveReplacing(stagedDictionary, target)
            replacementBackup?.deleteRecursively()
        } catch (error: Throwable) {
            target.deleteRecursively()
            if (replacementBackup?.exists() == true) {
                moveReplacing(replacementBackup, target)
            }
            throw error
        }
    }

    private fun copyDirectory(source: Path, target: Path) {
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.createDirectories(target.resolve(source.relativize(dir)))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun moveReplacing(source: File, target: File) {
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching { error ->
            if (error !is AtomicMoveNotSupportedException) throw error
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrThrow()
    }
}

private fun NativeDictionaryImportResult.detectedTypes(): Set<DictionaryType> =
    buildSet {
        if (termCount > 0) add(DictionaryType.Term)
        if (freqCount > 0) add(DictionaryType.Frequency)
        if (pitchCount > 0) add(DictionaryType.Pitch)
        if (kanjiCount > 0) add(DictionaryType.Kanji)
    }
