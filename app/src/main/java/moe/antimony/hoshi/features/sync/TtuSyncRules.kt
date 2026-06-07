package moe.antimony.hoshi.features.sync

import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.ReadingStatistics
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object TtuSyncRules {
    fun sanitizeTtuFilename(title: String): String {
        var result = title
        if (result.endsWith(" ")) {
            result = result.dropLast(1) + "~ttu-spc~"
        }
        if (result.endsWith(".")) {
            result = result.dropLast(1) + "~ttu-dend~"
        }
        result = result.replace("*", "~ttu-star~")
        return buildString(result.length) {
            result.forEach { character ->
                when (character) {
                    '/', '?', '<', '>', '\\', ':', '*', '|', '%', '"' -> append("%")
                        .append(character.code.toString(16).uppercase().padStart(2, '0'))
                    else -> append(character)
                }
            }
        }
    }

    fun desanitizeTtuFilename(name: String): String {
        var result = name
            .replace("~ttu-star~", "*")
        if (result.endsWith("~ttu-spc~")) {
            result = result.removeSuffix("~ttu-spc~") + " "
        }
        if (result.endsWith("~ttu-dend~")) {
            result = result.removeSuffix("~ttu-dend~") + "."
        }
        return result.replace(Regex("%([0-9A-Fa-f]{2})")) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
    }

    fun appleReferenceSecondsToUnixMillis(appleReferenceSeconds: Double): Long =
        (appleReferenceSeconds * 1_000.0 + AppleReferenceEpochMillis).toLong()

    fun unixMillisToAppleReferenceSeconds(unixMillis: Long): Double =
        (unixMillis - AppleReferenceEpochMillis).toDouble() / 1_000.0

    fun parseProgressTimestampMillis(file: DriveFile?): Long? {
        return parseTtuTimestamp(file, prefix = "progress_", index = 3)
    }

    fun parseProgressValue(file: DriveFile?): Double? {
        val name = file?.name ?: return null
        if (!name.startsWith("progress_")) return null
        return name.removeSuffix(".json").split("_").getOrNull(4)?.toDoubleOrNull()
    }

    fun parseBookDataTimestampMillis(file: DriveFile?): Long? {
        return parseTtuTimestamp(file, prefix = "bookdata_", index = 4)
    }

    fun parseStatisticsTimestampMillis(file: DriveFile?): Long? {
        return parseTtuTimestamp(file, prefix = "statistics_", index = 3)
    }

    fun parseAudioBookTimestampMillis(file: DriveFile?): Long? {
        return parseTtuTimestamp(file, prefix = "audioBook_", index = 3)
    }

    fun determineDirection(local: Bookmark?, remoteProgressFile: DriveFile?): SyncDirection {
        val localModified = local?.lastModified?.let(::appleReferenceSecondsToUnixMillis)
        val remoteModified = parseProgressTimestampMillis(remoteProgressFile)
        return when {
            localModified == null && remoteModified == null -> SyncDirection.Synced
            localModified == null -> SyncDirection.ImportFromTtu
            remoteModified == null -> SyncDirection.ExportToTtu
            localModified > remoteModified -> SyncDirection.ExportToTtu
            remoteModified > localModified -> SyncDirection.ImportFromTtu
            else -> SyncDirection.Synced
        }
    }

    fun progressFileName(progress: TtuProgress): String =
        "progress_1_6_${progress.lastBookmarkModified}_${progress.progress}.json"

    fun audioBookFileName(audioBook: TtuAudioBook): String =
        "audioBook_1_6_${audioBook.lastAudioBookModified}_${audioBook.playbackPosition}.json"

    fun statisticsFileName(stats: List<ReadingStatistics>): String {
        var readingTime = 0.0
        var charactersRead = 0
        var minReadingSpeed = 0
        var altMinReadingSpeed = 0
        var maxReadingSpeed = 0
        var weightedSum = 0L
        var validReadingDays = 0
        var lastStatisticModified = 0L

        stats.forEach { stat ->
            readingTime += stat.readingTime
            charactersRead += stat.charactersRead
            minReadingSpeed = if (minReadingSpeed > 0) {
                min(minReadingSpeed, stat.minReadingSpeed)
            } else {
                stat.minReadingSpeed
            }
            altMinReadingSpeed = if (altMinReadingSpeed > 0) {
                min(altMinReadingSpeed, stat.altMinReadingSpeed)
            } else {
                stat.altMinReadingSpeed
            }
            maxReadingSpeed = max(maxReadingSpeed, stat.lastReadingSpeed)
            weightedSum += stat.readingTime.toInt().toLong() * stat.charactersRead.toLong()
            lastStatisticModified = max(lastStatisticModified, stat.lastStatisticModified)
            if (stat.readingTime > 0) {
                validReadingDays += 1
            }
        }

        val averageReadingTime = if (validReadingDays > 0) ceil(readingTime / validReadingDays.toDouble()) else 0.0
        val averageWeightedReadingTime = if (charactersRead > 0) {
            ceil(weightedSum.toDouble() / charactersRead.toDouble())
        } else {
            0.0
        }
        val averageCharactersRead = if (validReadingDays > 0) {
            ceil(charactersRead.toDouble() / validReadingDays.toDouble())
        } else {
            0.0
        }
        val averageWeightedCharactersRead = if (readingTime > 0) {
            ceil(weightedSum.toDouble() / readingTime)
        } else {
            0.0
        }
        val lastReadingSpeed = if (readingTime > 0) {
            ceil((3_600.0 * charactersRead.toDouble()) / readingTime)
        } else {
            0.0
        }
        val averageReadingSpeed = if (averageReadingTime > 0) {
            ceil((3_600.0 * averageCharactersRead) / averageReadingTime)
        } else {
            0.0
        }
        val averageWeightedReadingSpeed = if (averageWeightedReadingTime > 0) {
            ceil((3_600.0 * averageWeightedCharactersRead) / averageWeightedReadingTime)
        } else {
            0.0
        }
        return "statistics_1_6_${lastStatisticModified}_${charactersRead}_${readingTime}_" +
            "${minReadingSpeed}_${altMinReadingSpeed}_${lastReadingSpeed}_${maxReadingSpeed}_" +
            "${averageReadingTime}_${averageWeightedReadingTime}_${averageCharactersRead}_" +
            "${averageWeightedCharactersRead}_${averageReadingSpeed}_${averageWeightedReadingSpeed}_na.json"
    }

    fun mergeStatistics(
        localStatistics: List<ReadingStatistics>,
        externalStatistics: List<ReadingStatistics>,
        syncMode: StatisticsSyncMode,
    ): List<ReadingStatistics> {
        if (syncMode == StatisticsSyncMode.Replace) {
            return externalStatistics
        }
        val grouped = linkedMapOf<String, ReadingStatistics>()
        localStatistics.forEach { stat ->
            grouped[stat.dateKey] = stat
        }
        externalStatistics.forEach { stat ->
            val existing = grouped[stat.dateKey]
            if (existing == null || stat.lastStatisticModified > existing.lastStatisticModified) {
                grouped[stat.dateKey] = stat
            }
        }
        return grouped.values.toList()
    }

    fun coverMetadata(coverData: ByteArray): CoverMetadata {
        val magic = coverData.take(4)
        return when {
            magic.size >= 4 && magic[0] == 0x89.toByte() && magic[1] == 0x50.toByte() &&
                magic[2] == 0x4E.toByte() && magic[3] == 0x47.toByte() -> CoverMetadata("image/png", "png")
            magic.size >= 4 && magic[0] == 0x47.toByte() && magic[1] == 0x49.toByte() &&
                magic[2] == 0x46.toByte() && magic[3] == 0x38.toByte() -> CoverMetadata("image/gif", "gif")
            magic.size >= 2 && magic[0] == 0x42.toByte() && magic[1] == 0x4D.toByte() ->
                CoverMetadata("image/bmp", "bmp")
            magic.size >= 4 && magic[0] == 0x52.toByte() && magic[1] == 0x49.toByte() &&
                magic[2] == 0x46.toByte() && magic[3] == 0x46.toByte() -> CoverMetadata("image/webp", "webp")
            else -> CoverMetadata("image/jpeg", "jpeg")
        }
    }

    private const val AppleReferenceEpochMillis = 978_307_200_000L

    private fun parseTtuTimestamp(file: DriveFile?, prefix: String, index: Int): Long? {
        val name = file?.name ?: return null
        if (!name.startsWith(prefix)) return null
        return name.split("_").getOrNull(index)?.toLongOrNull()
    }
}

data class CoverMetadata(
    val mimeType: String,
    val extension: String,
)

fun BookInfo.resolveTtuCharacterPosition(characterCount: Int): ResolvedBookPosition? {
    val clamped = max(0, min(characterCount, this.characterCount - 1))
    return chapterInfo.values
        .filter { it.spineIndex != null && it.chapterCount > 0 }
        .sortedBy { it.currentTotal }
        .firstOrNull { chapter ->
            clamped >= chapter.currentTotal && clamped < chapter.currentTotal + chapter.chapterCount
        }
        ?.let { chapter ->
            ResolvedBookPosition(
                spineIndex = chapter.spineIndex ?: 0,
                progress = (clamped - chapter.currentTotal).toDouble() / chapter.chapterCount.toDouble(),
            )
        }
}
