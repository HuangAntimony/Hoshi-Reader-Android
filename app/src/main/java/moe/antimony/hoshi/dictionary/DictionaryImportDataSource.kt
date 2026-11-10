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
            if (input == null) throw DictionaryImportException(DictionaryImportFailureKind.FileAccess)
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
        lowRamImport: Boolean,
        resolveTarget: (DictionaryType, ImportedDictionary) -> File,
        publish: (commit: () -> Boolean) -> Unit,
    ) {
        contentResolver.validateImportFile(uri, ImportFileType.DictionaryArchive)
        contentResolver.openInputStream(uri).use { input ->
            if (input == null) throw DictionaryImportException(DictionaryImportFailureKind.FileAccess)
            importDictionaryByDetectedTypes(input, importRootDirectory, lowRamImport, resolveTarget, publish)
        }
    }

    fun importDictionaryByDetectedTypes(
        input: InputStream,
        importRootDirectory: File,
        lowRamImport: Boolean,
        resolveTarget: (DictionaryType, ImportedDictionary) -> File,
        publish: (commit: () -> Boolean) -> Unit,
    ) {
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
            if (!result.success) throw DictionaryImportException.fromNative(result.error)
            val targetTypes = result.detectedTypes()
            if (targetTypes.isEmpty()) throw DictionaryImportException(DictionaryImportFailureKind.UnsupportedContents)
            commitStagedDictionariesByType(stagingRoot, targetTypes, resolveTarget, publish)
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
            if (!result.success) throw DictionaryImportException.fromNative(result.error)
            return commitStagedDictionaries(stagingRoot, typeDirectory)
        } finally {
            tempZip.delete()
            stagingRoot.deleteRecursively()
        }
    }

    private fun readDictionaryIndexFromZip(zipFile: File): DictionaryIndex {
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry("index.json")
                ?: throw DictionaryImportException(DictionaryImportFailureKind.InvalidIndex)
            zip.getInputStream(entry).use { input ->
                return json.decodeFromString<DictionaryIndex>(input.readBytes().decodeToString())
            }
        }
    }

    private fun commitStagedDictionaries(stagingRoot: File, typeDirectory: File): List<ImportedDictionary> {
        val importedDictionaries = stagingRoot.listFiles()?.filter(File::isDirectory).orEmpty()
        if (importedDictionaries.isEmpty()) throw DictionaryImportException(DictionaryImportFailureKind.UnsupportedContents)
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
        targetTypes: Set<DictionaryType>,
        resolveTarget: (DictionaryType, ImportedDictionary) -> File,
        publish: (commit: () -> Boolean) -> Unit,
    ) {
        val stagedDictionaries = stagingRoot.listFiles()?.filter(File::isDirectory).orEmpty()
        if (stagedDictionaries.isEmpty()) throw DictionaryImportException(DictionaryImportFailureKind.UnsupportedContents)
        stagedDictionaries.forEach { stagedDictionary ->
            val imported = ImportedDictionary(
                fileName = stagedDictionary.name,
                index = readDictionaryIndexFile(stagedDictionary.resolve("index.json")),
            )
            targetTypes.forEach { type ->
                val copyRoot = stagingRoot.resolve(".dictionary-copy-${UUID.randomUUID()}")
                copyDirectory(stagedDictionary.toPath(), copyRoot.toPath())
                // The repository coordinates publication with lookup and records new directories.
                publish {
                    val target = resolveTarget(type, imported)
                    val isNew = !target.exists()
                    commitStagedDictionary(copyRoot, target)
                    isNew
                }
            }
        }
    }

    private fun readDictionaryIndexFile(indexFile: File): DictionaryIndex =
        json.decodeFromString<DictionaryIndex>(indexFile.readText())

    private fun commitStagedDictionary(stagedDictionary: File, target: File) {
        val replacementBackup = target.takeIf(File::exists)?.let { existing ->
            requireNotNull(target.parentFile).resolve(".dictionary-replace-${UUID.randomUUID()}")
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
