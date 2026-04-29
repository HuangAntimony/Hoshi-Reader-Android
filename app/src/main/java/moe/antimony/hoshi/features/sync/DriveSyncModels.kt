package moe.antimony.hoshi.features.sync

import kotlinx.serialization.Serializable
import moe.antimony.hoshi.epub.BookInfo
import kotlin.math.roundToLong

@Serializable
data class DriveFile(
    val id: String,
    val name: String,
)

@Serializable
data class DriveFileList(
    val files: List<DriveFile> = emptyList(),
)

data class DriveSyncFiles(
    val progress: DriveFile?,
    val statistics: DriveFile?,
    val audioBook: DriveFile?,
)

@Serializable
data class TtuProgress(
    val dataId: Int,
    val exploredCharCount: Int,
    val progress: Double,
    val lastBookmarkModified: Long,
)

data class ResolvedBookPosition(
    val chapterIndex: Int,
    val progress: Double,
)

enum class SyncDirection {
    ImportFromTtu,
    ExportToTtu,
    Synced,
}

sealed interface SyncResult {
    data class Synced(val title: String) : SyncResult
    data class Imported(val title: String, val characterCount: Int) : SyncResult
    data class Exported(val title: String, val characterCount: Int) : SyncResult
    data class Skipped(val title: String?, val reason: String) : SyncResult
    data class Failed(val title: String?, val message: String) : SyncResult
}

private const val AppleReferenceEpochSeconds = 978_307_200.0

fun sanitizeTtuFilename(title: String): String {
    var result = title
    if (result.endsWith(" ")) {
        result = result.dropLast(1) + "~ttu-spc~"
    }
    if (result.endsWith(".")) {
        result = result.dropLast(1) + "~ttu-dend~"
    }
    result = result.replace("*", "~ttu-star~")

    val escaped = buildString {
        result.forEach { char ->
            if (char in setOf('/', '?', '<', '>', '\\', ':', '|', '%', '"')) {
                append("%")
                append(char.code.toString(16).uppercase().padStart(2, '0'))
            } else {
                append(char)
            }
        }
    }
    return escaped
}

fun parseProgressTimestampMillis(file: DriveFile): Long? {
    if (!file.name.startsWith("progress_")) return null
    val parts = file.name.split("_")
    if (parts.size <= 3) return null
    return parts[3].toLongOrNull()
}

fun appleReferenceSecondsToUnixMillis(seconds: Double): Long =
    ((seconds + AppleReferenceEpochSeconds) * 1000.0).roundToLong()

fun unixMillisToAppleReferenceSeconds(millis: Long): Double =
    (millis.toDouble() / 1000.0) - AppleReferenceEpochSeconds

fun BookInfo.resolveCharacterPosition(characterCount: Int): ResolvedBookPosition? {
    val chapters = chapterInfo.values
        .filter { it.spineIndex != null && it.chapterCount > 0 }
        .sortedWith(compareBy<BookInfo.ChapterInfo> { it.currentTotal }.thenBy { it.spineIndex })
    if (chapters.isEmpty()) return null

    val clamped = characterCount.coerceIn(0, this.characterCount)
    val chapter = chapters.lastOrNull { clamped >= it.currentTotal } ?: chapters.first()
    val chapterStart = chapter.currentTotal
    val offset = (clamped - chapterStart).coerceIn(0, chapter.chapterCount)
    return ResolvedBookPosition(
        chapterIndex = requireNotNull(chapter.spineIndex),
        progress = offset.toDouble().div(chapter.chapterCount.toDouble()).coerceIn(0.0, 1.0),
    )
}
