package moe.antimony.hoshi.features.backup

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import moe.antimony.hoshi.di.FilesDir
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.STATISTICS_ARCHIVE_DIRECTORY
import moe.antimony.hoshi.epub.fitBookStorageName
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.MAX_PATH_COMPONENT_UTF8_BYTES
import moe.antimony.hoshi.epub.fitUtf8PathComponent
import moe.antimony.hoshi.features.sync.TtuBookDataConverter
import moe.antimony.hoshi.features.sync.TtuProgress
import moe.antimony.hoshi.features.sync.TtuSyncRules
import moe.antimony.hoshi.features.sync.resolveTtuCharacterPosition
import moe.antimony.hoshi.profiles.ProfileRepository

@Singleton
class HoshiBackupRepository @Inject constructor(
    @param:FilesDir private val filesDir: File,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val bookRepository: BookRepository,
    private val ttuConverter: TtuBookDataConverter,
    private val profileRepository: ProfileRepository,
) {
    constructor(filesDir: File) : this(
        filesDir = filesDir,
        ioDispatcher = Dispatchers.IO,
        bookRepository = BookRepository(filesDir),
        ttuConverter = createStandaloneTtuConverter(filesDir, Dispatchers.IO),
        profileRepository = ProfileRepository(filesDir),
    )

    constructor(filesDir: File, ioDispatcher: CoroutineDispatcher) : this(
        filesDir = filesDir,
        ioDispatcher = ioDispatcher,
        bookRepository = BookRepository(filesDir),
        ttuConverter = createStandaloneTtuConverter(filesDir, ioDispatcher),
        profileRepository = ProfileRepository(filesDir),
    )

    suspend fun exportBooks(contentResolver: ContentResolver, uri: Uri) {
        exportFolder(contentResolver, uri, BackupTarget.Books)
    }

    suspend fun exportBooks(output: OutputStream) {
        exportFolder(BackupTarget.Books, output)
    }

    suspend fun restoreBooks(contentResolver: ContentResolver, uri: Uri) {
        restoreFolder(contentResolver, uri, BackupTarget.Books)
    }

    suspend fun restoreBooks(input: InputStream) {
        restoreFolder(BackupTarget.Books, input)
    }

    suspend fun exportDictionaries(contentResolver: ContentResolver, uri: Uri) {
        exportFolder(contentResolver, uri, BackupTarget.Dictionaries)
    }

    suspend fun exportDictionaries(output: OutputStream) {
        exportFolder(BackupTarget.Dictionaries, output)
    }

    suspend fun restoreDictionaries(contentResolver: ContentResolver, uri: Uri) {
        restoreFolder(contentResolver, uri, BackupTarget.Dictionaries)
    }

    suspend fun restoreDictionaries(input: InputStream) {
        restoreFolder(BackupTarget.Dictionaries, input)
    }

    suspend fun exportTtuBookData(contentResolver: ContentResolver, uri: Uri) {
        withContext(ioDispatcher) {
            contentResolver.openOutputStream(uri)?.use { output ->
                exportTtuBookData(output)
            } ?: error("Unable to open backup destination.")
        }
    }

    suspend fun exportTtuBookData(output: OutputStream) {
        withContext(ioDispatcher) {
            val tempRoot = filesDir.resolve(".ttu-export-${UUID.randomUUID()}")
            tempRoot.deleteRecursively()
            tempRoot.mkdirs()
            try {
                ZipOutputStream(output.buffered()).use { zip ->
                    val usedTitleFolders = mutableSetOf<String>()
                    bookRepository.loadBookEntries().forEach { entry ->
                        if (bookRepository.epubFile(entry) == null) return@forEach
                        val titleFolder = uniqueTtuBackupFolderName(
                            baseName = TtuSyncRules.sanitizeTtuFilename(entry.displayTitle),
                            bookId = entry.metadata.id,
                            usedNames = usedTitleFolders,
                        )
                        val bookTemp = tempRoot.resolve(entry.metadata.id).also { it.mkdirs() }
                        val bookData = ttuConverter.exportBookData(entry, bookTemp) ?: return@forEach
                        zip.writeFile("$titleFolder/${bookData.name}", bookData)
                        bookRepository.coverFile(entry)?.takeIf(File::isFile)?.let { cover ->
                            zip.writeFile("$titleFolder/cover_1_6.${cover.extension.ifBlank { "jpg" }}", cover)
                        }
                        val stats = bookRepository.loadStatistics(entry.root)
                        if (stats.isNotEmpty()) {
                            zip.writeText(
                                "$titleFolder/${TtuSyncRules.statisticsFileName(stats)}",
                                backupJson.encodeToString(ListSerializer(ReadingStatistics.serializer()), stats),
                            )
                        }
                        val bookmark = bookRepository.loadBookmark(entry.root)
                        val bookInfo = bookRepository.loadBookInfo(entry.root)
                        if (bookmark != null && bookInfo != null) {
                            val lastModified = bookmark.lastModified ?: entry.metadata.lastAccess
                            val unixTimestamp = TtuSyncRules.appleReferenceSecondsToUnixMillis(lastModified)
                            val progress = TtuProgress(
                                dataId = 0,
                                exploredCharCount = bookmark.characterCount,
                                progress = if (bookInfo.characterCount > 0) {
                                    bookmark.characterCount.toDouble() / bookInfo.characterCount.toDouble()
                                } else {
                                    0.0
                                },
                                lastBookmarkModified = unixTimestamp,
                            )
                            zip.writeText("$titleFolder/${TtuSyncRules.progressFileName(progress)}", backupJson.encodeToString(TtuProgress.serializer(), progress))
                        }
                    }
                }
            } finally {
                tempRoot.deleteRecursively()
            }
        }
    }

    suspend fun restoreTtuBookData(contentResolver: ContentResolver, uri: Uri): Int =
        withContext(ioDispatcher) {
            contentResolver.openInputStream(uri)?.use { input ->
                restoreTtuBookData(input)
            } ?: error("Unable to open backup file.")
        }

    suspend fun restoreTtuBookData(input: InputStream): Int =
        withContext(ioDispatcher) {
            val archiveFile = filesDir.resolve(".ttu-restore-${UUID.randomUUID()}.zip")
            val tempRoot = filesDir.resolve(".ttu-restore-${UUID.randomUUID()}")
            archiveFile.delete()
            tempRoot.deleteRecursively()
            tempRoot.mkdirs()
            try {
                archiveFile.outputStream().use { output -> input.copyTo(output) }
                unzipInto(archiveFile, tempRoot, ::remapTtuRestoreEntryName)
                var restoredCount = 0
                tempRoot.listFiles().orEmpty().filter(File::isDirectory).forEach { folder ->
                    val files = folder.listFiles().orEmpty()
                    val bookData = files.firstOrNull { it.isFile && it.name.startsWith("bookdata_") && it.extension == "zip" }
                        ?: return@forEach
                    val entry = ttuConverter.importBookData(bookData)
                    restoredCount += 1
                    files.firstOrNull { it.name.startsWith("statistics_") }?.let { statsFile ->
                        val stats = backupJson.decodeFromString(ListSerializer(ReadingStatistics.serializer()), statsFile.readText())
                        bookRepository.saveStatistics(entry.root, stats)
                    }
                    files.firstOrNull { it.name.startsWith("progress_") }?.let { progressFile ->
                        val progress = backupJson.decodeFromString(TtuProgress.serializer(), progressFile.readText())
                        val bookInfo = bookRepository.loadBookInfo(entry.root) ?: return@let
                        val resolved = bookInfo.resolveTtuCharacterPosition(progress.exploredCharCount)
                        bookRepository.saveBookmark(
                            entry.root,
                            Bookmark(
                                chapterIndex = resolved?.spineIndex ?: 0,
                                progress = resolved?.progress ?: 0.0,
                                characterCount = progress.exploredCharCount,
                                lastModified = TtuSyncRules.unixMillisToAppleReferenceSeconds(progress.lastBookmarkModified),
                            ),
                        )
                    }
                    bookRepository.restoreArchivedStatistics(entry.root.name)
                }
                restoredCount
            } finally {
                archiveFile.delete()
                tempRoot.deleteRecursively()
            }
        }

    private suspend fun exportFolder(contentResolver: ContentResolver, uri: Uri, target: BackupTarget) {
        withContext(ioDispatcher) {
            contentResolver.openOutputStream(uri)?.use { output ->
                exportFolder(target, output)
            } ?: error("Unable to open backup destination.")
        }
    }

    private suspend fun exportFolder(target: BackupTarget, output: OutputStream) {
        withContext(ioDispatcher) {
            ZipOutputStream(output.buffered()).use { zip ->
                if (target == BackupTarget.Dictionaries) {
                    exportDictionariesFolder(zip)
                } else {
                    exportBackupTargetFolder(zip, target)
                }
            }
        }
    }

    private fun exportBackupTargetFolder(zip: ZipOutputStream, target: BackupTarget) {
        val root = filesDir.resolve(target.folderName)
        if (!root.isDirectory) return
        zip.writeFolderContents(root)
    }

    private suspend fun exportDictionariesFolder(zip: ZipOutputStream) {
        val root = filesDir.resolve(BackupTarget.Dictionaries.folderName)
        if (root.isDirectory) {
            zip.writeFolderContents(root) { relativePath ->
                relativePath != LegacyDictionaryConfigEntryName &&
                    relativePath != ProfileRepository.DictionaryBackupProfilesDirectoryName &&
                    !relativePath.startsWith("${ProfileRepository.DictionaryBackupProfilesDirectoryName}/")
            }
        }
        profileRepository.defaultDictionaryConfigFileForBackup()?.let { config ->
            zip.writeFile(LegacyDictionaryConfigEntryName, config)
        }
        val profilePayload = filesDir.resolve(".dictionary-profile-backup-${UUID.randomUUID()}")
        try {
            profileRepository.writeDictionaryBackupProfilePayload(profilePayload)
            zip.writeFolderContents(
                root = profilePayload,
                entryPrefix = "${ProfileRepository.DictionaryBackupProfilesDirectoryName}/",
            )
        } finally {
            profilePayload.deleteRecursively()
        }
    }

    private suspend fun restoreFolder(contentResolver: ContentResolver, uri: Uri, target: BackupTarget) {
        withContext(ioDispatcher) {
            contentResolver.openInputStream(uri)?.use { input ->
                restoreFolder(target, input)
            } ?: error("Unable to open backup file.")
        }
    }

    private suspend fun restoreFolder(target: BackupTarget, input: InputStream) {
        if (target == BackupTarget.Dictionaries) {
            restoreDictionariesFolder(input)
            return
        }
        withContext(ioDispatcher) {
            val destination = filesDir.resolve(target.folderName)
            val archiveFile = filesDir.resolve(".${target.folderName.lowercase()}-restore-${UUID.randomUUID()}.hoshi")
            val tempRoot = filesDir.resolve(".${target.folderName.lowercase()}-restore-${UUID.randomUUID()}")
            archiveFile.delete()
            tempRoot.deleteRecursively()
            check(tempRoot.mkdirs()) { "Unable to create restore directory." }
            try {
                archiveFile.outputStream().use { output -> input.copyTo(output) }
                unzipInto(archiveFile, tempRoot, ::remapBooksRestoreEntryName)
                reconcileRestoredBookMetadata(tempRoot)
                replaceDestinationWithRestoredFolder(target, tempRoot, destination)
            } catch (error: Throwable) {
                tempRoot.deleteRecursively()
                throw error
            } finally {
                archiveFile.delete()
            }
        }
    }

    private fun reconcileRestoredBookMetadata(restoredBooksRoot: File) {
        val archive = restoredBooksRoot.resolve(STATISTICS_ARCHIVE_DIRECTORY)
        val archivedRoots = archive.listFiles().orEmpty().filter(File::isDirectory)
        archivedRoots.forEach { root ->
            reconcileRestoredBookMetadataFile(root, archivedRoots.map(File::getName), archived = true)
        }
        val bookRoots = restoredBooksRoot.listFiles().orEmpty()
            .filter { it.isDirectory && it.name != STATISTICS_ARCHIVE_DIRECTORY }
        val actualFolderNames = bookRoots.map(File::getName)
        bookRoots.forEach { bookRoot ->
            reconcileRestoredBookMetadataFile(bookRoot, actualFolderNames)
        }
    }

    private fun reconcileRestoredBookMetadataFile(
        bookRoot: File,
        actualFolderNames: List<String>,
        archived: Boolean = false,
    ) {
        val metadataFile = bookRoot.resolve("metadata.json").takeIf(File::isFile) ?: return
        val rawMetadata = metadataFile.readText()
        val metadata = runCatching {
            backupJson.parseToJsonElement(rawMetadata) as? JsonObject
        }.getOrNull() ?: return
        val actualFileNames = bookRoot.listFiles().orEmpty()
            .filter(File::isFile)
            .map(File::getName)
        val updated = metadata.toMutableMap()

        val recordedFolderName = metadata.stringValue("folder")
        val resolvedFolderName = recordedFolderName?.let {
            resolveRestoredPathComponent(it, actualFolderNames)
                ?: resolveRestoredPathComponent(it.fitBookStorageName(), actualFolderNames)
        }
        if (resolvedFolderName == bookRoot.name && recordedFolderName != bookRoot.name) {
            updated["folder"] = JsonPrimitive(bookRoot.name)
        }

        metadata.stringValue("epub")
            ?.let { resolveRestoredPathComponent(it, actualFileNames)
                ?: resolveRestoredPathComponent(it.fitUtf8PathComponent(MAX_PATH_COMPONENT_UTF8_BYTES), actualFileNames) }
            ?.takeIf { it != metadata.stringValue("epub") }
            ?.let { updated["epub"] = JsonPrimitive(it) }

        metadata.stringValue("cover")
            ?.let { recorded ->
                val prefix = "Books/$STATISTICS_ARCHIVE_DIRECTORY/"
                val regularPath = if (archived && recorded.startsWith(prefix)) "Books/" + recorded.removePrefix(prefix) else recorded
                resolveRestoredCoverPath(regularPath, bookRoot.name, actualFolderNames, actualFileNames)
                    ?.let { if (archived) it.replaceFirst("Books/", prefix) else it }
            }
            ?.takeIf { it != metadata.stringValue("cover") }
            ?.let { updated["cover"] = JsonPrimitive(it) }

        val updatedMetadata = JsonObject(updated)
        if (updatedMetadata != metadata) {
            metadataFile.writeText(backupJson.encodeToString(JsonObject.serializer(), updatedMetadata))
        }
    }

    private suspend fun restoreDictionariesFolder(input: InputStream) {
        withContext(ioDispatcher) {
            val destination = filesDir.resolve(BackupTarget.Dictionaries.folderName)
            val profilesDestination = profileRepository.profilesDirectory
            val archiveFile = filesDir.resolve(".dictionaries-restore-${UUID.randomUUID()}.hoshi")
            val tempDictionaries = filesDir.resolve(".dictionaries-restore-${UUID.randomUUID()}")
            val tempProfiles = filesDir.resolve(".profiles-dictionary-restore-${UUID.randomUUID()}")
            archiveFile.delete()
            tempDictionaries.deleteRecursively()
            tempProfiles.deleteRecursively()
            check(tempDictionaries.mkdirs()) { "Unable to create restore directory." }
            try {
                archiveFile.outputStream().use { output -> input.copyTo(output) }
                unzipInto(archiveFile, tempDictionaries)
                profileRepository.prepareDictionaryBackupProfilesRestore(
                    restoredDictionariesDir = tempDictionaries,
                    destinationProfilesDir = tempProfiles,
                )
                replaceDestinationsWithRestoredFolders(
                    listOf(
                        RestoredFolder(tempDictionaries, destination, BackupTarget.Dictionaries.folderName.lowercase()),
                        RestoredFolder(tempProfiles, profilesDestination, "profiles"),
                    ),
                )
                profileRepository.reloadProfilesFromDisk()
            } catch (error: Throwable) {
                tempDictionaries.deleteRecursively()
                tempProfiles.deleteRecursively()
                throw error
            } finally {
                archiveFile.delete()
            }
        }
    }

    private fun replaceDestinationWithRestoredFolder(
        target: BackupTarget,
        restoredFolder: File,
        destination: File,
    ) {
        replaceDestinationsWithRestoredFolders(
            listOf(RestoredFolder(restoredFolder, destination, target.folderName.lowercase())),
        )
    }

    private fun replaceDestinationsWithRestoredFolders(restoredFolders: List<RestoredFolder>) {
        val replacementBackups = mutableListOf<Pair<File, File>>()
        var movingRestoredFolders = false
        try {
            restoredFolders.forEach { restored ->
                restored.destination.takeIf(File::exists)?.let { existing ->
                    val backup = filesDir.resolve(".${restored.backupName}-restore-backup-${UUID.randomUUID()}")
                    moveReplacing(existing, backup)
                    replacementBackups += restored.destination to backup
                }
            }
            movingRestoredFolders = true
            restoredFolders.forEach { restored ->
                moveReplacing(restored.source, restored.destination)
            }
            replacementBackups.forEach { (_, backup) -> backup.deleteRecursively() }
        } catch (error: Throwable) {
            if (movingRestoredFolders) {
                restoredFolders.forEach { restored -> restored.destination.deleteRecursively() }
            }
            replacementBackups.asReversed().forEach { (destination, backup) ->
                if (backup.exists()) {
                    moveReplacing(backup, destination)
                }
            }
            throw error
        }
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

    private fun unzipInto(
        archiveFile: File,
        destinationRoot: File,
        entryNameMapper: (String) -> String = { it },
    ) {
        val destinationCanonical = destinationRoot.canonicalFile
        ZipFile(archiveFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val entryName = entryNameMapper(entry.name)
                val target = destinationCanonical.resolve(entryName).canonicalFile
                require(target.path == destinationCanonical.path || target.path.startsWith(destinationCanonical.path + File.separator)) {
                    "Unsafe backup entry: ${entry.name}"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    private fun File.toZipEntry(entryName: String): ZipEntry {
        val entry = ZipEntry(entryName)
        if (isDirectory) {
            entry.size = 0
            entry.crc = 0
            return entry
        }

        entry.size = length()
        entry.crc = crc32()
        return entry
    }

    private fun File.crc32(): Long {
        val crc = CRC32()
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    private fun ZipOutputStream.writeFile(path: String, file: File) {
        putNextEntry(file.toZipEntry(path))
        file.inputStream().use { input -> input.copyTo(this) }
        closeEntry()
    }

    private fun ZipOutputStream.writeFolderContents(
        root: File,
        entryPrefix: String = "",
        include: (String) -> Boolean = { true },
    ) {
        root.walkTopDown()
            .filter { it != root }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .forEach { file ->
                val relativePath = file.relativeTo(root).invariantSeparatorsPath
                if (!include(relativePath)) return@forEach
                val entryName = entryPrefix + if (file.isDirectory) "$relativePath/" else relativePath
                putNextEntry(file.toZipEntry(entryName))
                if (file.isFile) {
                    file.inputStream().use { input -> input.copyTo(this) }
                }
                closeEntry()
            }
    }

    private fun ZipOutputStream.writeText(path: String, value: String) {
        val bytes = value.toByteArray()
        val entry = ZipEntry(path)
        entry.size = bytes.size.toLong()
        val crc = CRC32().apply { update(bytes) }
        entry.crc = crc.value
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    private enum class BackupTarget(val folderName: String) {
        Books("Books"),
        Dictionaries("Dictionaries"),
    }

    private data class RestoredFolder(
        val source: File,
        val destination: File,
        val backupName: String,
    )

    private companion object {
        const val LegacyDictionaryConfigEntryName = "config.json"
    }
}

private fun uniqueTtuBackupFolderName(
    baseName: String,
    bookId: String,
    usedNames: MutableSet<String>,
): String {
    val normalizedBase = baseName.ifBlank { "Book" }
    val firstCandidate = normalizedBase.fitUtf8PathComponent(MAX_PATH_COMPONENT_UTF8_BYTES)
    if (usedNames.add(firstCandidate)) return firstCandidate

    val stableSuffix = bookId.take(8).ifBlank { UUID.randomUUID().toString().take(8) }
    val suffixBase = "$normalizedBase-$stableSuffix"
    var candidate = suffixBase.fitUtf8PathComponent(MAX_PATH_COMPONENT_UTF8_BYTES)
    var index = 2
    while (!usedNames.add(candidate)) {
        candidate = "$suffixBase-$index".fitUtf8PathComponent(MAX_PATH_COMPONENT_UTF8_BYTES)
        index += 1
    }
    return candidate
}

internal fun resolveRestoredPathComponent(recordedName: String, actualNames: List<String>): String? {
    if (!isSafePathComponent(recordedName)) return null
    if (recordedName in actualNames) return recordedName
    return actualNames
        .filter { canonicallyEquals(it, recordedName) }
        .singleOrNull()
}

private fun resolveRestoredCoverPath(
    recordedPath: String,
    actualFolderName: String,
    actualFolderNames: List<String>,
    actualFileNames: List<String>,
): String? {
    val segments = recordedPath.split('/')
    if (segments.size != 3 || segments[0] != "Books") return null
    val recordedFolderName = segments[1]
    val resolvedFolderName = resolveRestoredPathComponent(recordedFolderName, actualFolderNames)
        ?: resolveRestoredPathComponent(recordedFolderName.fitBookStorageName(), actualFolderNames)
    if (resolvedFolderName != actualFolderName) return null
    val actualFileName = resolveRestoredPathComponent(segments[2], actualFileNames)
        ?: resolveRestoredPathComponent(segments[2].fitUtf8PathComponent(MAX_PATH_COMPONENT_UTF8_BYTES), actualFileNames)
        ?: return null
    return "Books/$actualFolderName/$actualFileName"
}

private fun JsonObject.stringValue(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.content.takeIf { primitive.isString }
}

private fun isSafePathComponent(value: String): Boolean =
    value.isNotEmpty() && value != "." && value != ".." && '/' !in value && '\\' !in value && '\u0000' !in value

private fun canonicallyEquals(first: String, second: String): Boolean =
    Normalizer.normalize(first, Normalizer.Form.NFC) == Normalizer.normalize(second, Normalizer.Form.NFC)

internal fun remapTtuRestoreEntryName(entryName: String): String {
    val isDirectory = entryName.endsWith('/')
    val path = entryName.removeSuffix("/")
    require(path.isNotEmpty() && !path.startsWith('/')) { "Unsafe TTU backup entry: $entryName" }
    val segments = path.split('/')
    require(segments.none { it.isEmpty() || it == "." || it == ".." || '\u0000' in it }) {
        "Unsafe TTU backup entry: $entryName"
    }
    val mapped = buildList {
        add(segments.first().fitUtf8PathComponent(MAX_PATH_COMPONENT_UTF8_BYTES))
        addAll(segments.drop(1))
    }.joinToString(separator = "/")
    return mapped + if (isDirectory) "/" else ""
}

private val backupJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun booksBackupFileName(
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val timestamp = DateTimeFormatter
        .ofPattern("yyyy-MM-dd_HH-mm-ss")
        .withZone(zoneId)
        .format(now)
    return "Books_$timestamp.hoshi"
}

fun dictionariesBackupFileName(
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val timestamp = DateTimeFormatter
        .ofPattern("yyyy-MM-dd_HH-mm-ss")
        .withZone(zoneId)
        .format(now)
    return "Dictionaries_$timestamp.hoshi"
}

fun ttuBookDataBackupFileName(
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val timestamp = DateTimeFormatter
        .ofPattern("yyyy-MM-dd_HH-mm-ss")
        .withZone(zoneId)
        .format(now)
    return "hoshi_ttu_export_$timestamp.zip"
}

private fun createStandaloneTtuConverter(
    filesDir: File,
    ioDispatcher: CoroutineDispatcher,
): TtuBookDataConverter {
    val repository = BookRepository(filesDir)
    return TtuBookDataConverter(repository, EpubBookParser(), filesDir, ioDispatcher)
}

internal fun remapBooksRestoreEntryName(entryName: String): String {
    val directory = entryName.endsWith('/')
    val segments = entryName.removeSuffix("/").split('/')
    require(segments.none { it.isEmpty() || it == "." || it == ".." || '\\' in it || '\u0000' in it }) { "Unsafe backup entry: $entryName" }
    val bookIndex = if (segments.first() == STATISTICS_ARCHIVE_DIRECTORY) 1 else 0
    return segments.mapIndexed { index, component ->
        when {
            index < bookIndex -> component
            index == bookIndex && (segments.size > index + 1 || directory) -> component.fitBookStorageName()
            else -> component.fitUtf8PathComponent(MAX_PATH_COMPONENT_UTF8_BYTES)
        }
    }.joinToString("/") + if (directory) "/" else ""
}
