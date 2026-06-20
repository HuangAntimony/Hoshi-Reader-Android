package moe.antimony.hoshi.features.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import moe.antimony.hoshi.content.ContentLanguageProfile

enum class ReaderProgressDisplayUnit {
    Characters,
    Words,
}

data class ReaderProgressDisplay(
    val unit: ReaderProgressDisplayUnit,
) {
    val usesWords: Boolean get() = unit == ReaderProgressDisplayUnit.Words

    fun displayCount(rawCount: Int): Int =
        when (unit) {
            ReaderProgressDisplayUnit.Characters -> rawCount.coerceAtLeast(0)
            ReaderProgressDisplayUnit.Words -> ceil(max(rawCount, 0).toDouble() / CharactersPerDisplayedWord).toInt()
        }

    fun rawTargetFromDisplayCount(displayCount: Int, totalCharacters: Int): Int {
        val safeTotal = totalCharacters.coerceAtLeast(0)
        val rawTarget = when (unit) {
            ReaderProgressDisplayUnit.Characters -> displayCount.coerceAtLeast(0).toLong()
            ReaderProgressDisplayUnit.Words -> displayCount.coerceAtLeast(0).toLong() * CharactersPerDisplayedWord.toLong()
        }
        return rawTarget.coerceIn(0L, safeTotal.toLong()).toInt()
    }

    fun rangeText(currentRawCount: Int, totalRawCount: Int): String =
        "${formatDisplayNumber(displayCount(currentRawCount))} / ${formatDisplayNumber(displayCount(totalRawCount))}"

    fun countText(rawCount: Int): String =
        formatDisplayNumber(displayCount(rawCount))

    fun countWithPercentText(rawCount: Int, totalRawCount: Int): String =
        "${countText(rawCount)} (${rawProgressPercentText(rawCount, totalRawCount)})"

    fun speedText(rawSpeed: Int): String =
        "${formatDisplayNumber(displayCount(rawSpeed))} / h"

    fun jumpTargetText(rawCount: Int): String =
        formatDisplayNumber(displayCount(rawCount))

    private fun formatDisplayNumber(value: Int): String =
        if (unit == ReaderProgressDisplayUnit.Words) {
            NumberFormat.getIntegerInstance(Locale.US).format(value.toLong())
        } else {
            value.toString()
        }

    private fun rawProgressPercentText(rawCount: Int, totalRawCount: Int): String {
        val percent = if (totalRawCount > 0) {
            rawCount.coerceIn(0, totalRawCount).toDouble() / totalRawCount.toDouble() * 100.0
        } else {
            0.0
        }
        return String.format(Locale.US, "%.2f%%", percent)
    }

    companion object {
        private const val CharactersPerDisplayedWord = 5
        private val CharacterDisplay = ReaderProgressDisplay(ReaderProgressDisplayUnit.Characters)

        fun characters(): ReaderProgressDisplay = CharacterDisplay

        fun word(): ReaderProgressDisplay =
            ReaderProgressDisplay(unit = ReaderProgressDisplayUnit.Words)

        fun forContentLanguageProfile(contentLanguageProfile: ContentLanguageProfile): ReaderProgressDisplay =
            if (contentLanguageProfile.id == ContentLanguageProfile.EnglishLanguageId) {
                word()
            } else {
                characters()
            }
    }
}

@Composable
internal fun readerProgressDisplay(contentLanguageProfile: ContentLanguageProfile): ReaderProgressDisplay =
    remember(contentLanguageProfile.id) {
        ReaderProgressDisplay.forContentLanguageProfile(contentLanguageProfile)
    }
