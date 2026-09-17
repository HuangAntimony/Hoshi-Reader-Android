package moe.antimony.hoshi.epub

import java.security.MessageDigest
import java.text.Normalizer

internal const val MAX_PATH_COMPONENT_UTF8_BYTES = 255
internal const val MAX_BOOK_STORAGE_BASENAME_UTF8_BYTES = MAX_PATH_COMPONENT_UTF8_BYTES - ".epub".length

internal fun String.toImportedBookStorageName(): String =
    sanitizeImportedBookTitle()
        .fitBookStorageName()

internal fun String.normalizedBookFolder(): String = Normalizer.normalize(this, Normalizer.Form.NFC)

internal fun String.fitBookStorageName(): String {
    val normalized = normalizedBookFolder()
    return (if (normalized == STATISTICS_ARCHIVE_DIRECTORY) "$normalized-${normalized.sha256Hex().take(HASH_HEX_LENGTH)}" else normalized)
        .fitUtf8PathComponent(MAX_BOOK_STORAGE_BASENAME_UTF8_BYTES)
}

internal fun String.fitUtf8PathComponent(maxUtf8Bytes: Int): String {
    require(maxUtf8Bytes > HASH_SUFFIX_UTF8_BYTES) { "UTF-8 path component budget is too small." }
    if (toByteArray(Charsets.UTF_8).size <= maxUtf8Bytes) return this

    val suffix = "-${sha256Hex().take(HASH_HEX_LENGTH)}"
    val prefix = takeUtf8Prefix(maxUtf8Bytes - suffix.toByteArray(Charsets.UTF_8).size).trimEnd()
    return prefix + suffix
}

private fun String.sanitizeImportedBookTitle(): String =
    split(Regex("[\\\\/:*?\"<>|\\n\\r\\u0000-\\u001F]"))
        .joinToString("_")
        .trim()

private fun String.takeUtf8Prefix(maxUtf8Bytes: Int): String = buildString {
    var sourceIndex = 0
    var usedBytes = 0
    while (sourceIndex < this@takeUtf8Prefix.length) {
        val codePoint = this@takeUtf8Prefix.codePointAt(sourceIndex)
        val codePointText = String(Character.toChars(codePoint))
        val codePointBytes = codePointText.toByteArray(Charsets.UTF_8).size
        if (usedBytes + codePointBytes > maxUtf8Bytes) break
        append(codePointText)
        usedBytes += codePointBytes
        sourceIndex += Character.charCount(codePoint)
    }
}

private fun String.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private const val HASH_HEX_LENGTH = 16
private const val HASH_SUFFIX_UTF8_BYTES = HASH_HEX_LENGTH + 1

/** A pre-archive Android version could import this exact title as a regular book. */
internal fun migrateReservedStatisticsBook(booksDirectory: java.io.File) = synchronized(reservedBookMigrationLock) {
    val legacy = booksDirectory.resolve(STATISTICS_ARCHIVE_DIRECTORY)
    if (!legacy.resolve("metadata.json").isFile && !legacy.resolve("statistics_archive.epub").isFile && !legacy.resolve("META-INF/container.xml").isFile) return@synchronized
    val destination = booksDirectory.resolve(STATISTICS_ARCHIVE_DIRECTORY.toImportedBookStorageName())
    check(!destination.exists()) { "The reserved book folder cannot be migrated because its destination exists." }
    val metadataFile = legacy.resolve("metadata.json")
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    val metadata = metadataFile.takeIf { it.isFile }?.let { json.decodeFromString(BookMetadata.serializer(), it.readText()) }
    java.nio.file.Files.move(legacy.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    try {
        metadata?.let {
            val temporary = java.io.File.createTempFile(".metadata-", ".tmp", destination)
            try {
                temporary.writeText(json.encodeToString(BookMetadata.serializer(), it.copy(
                    folder = destination.name,
                    cover = it.cover?.replaceFirst("Books/$STATISTICS_ARCHIVE_DIRECTORY/", "Books/${destination.name}/"),
                )))
                java.nio.file.Files.move(temporary.toPath(), destination.resolve("metadata.json").toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        }
    } catch (error: Exception) {
        java.nio.file.Files.move(destination.toPath(), legacy.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        throw error
    }
}

private val reservedBookMigrationLock = Any()
