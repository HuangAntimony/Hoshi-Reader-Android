package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiMatch

import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.filteredReaderText

object SasayakiMatcher {
    private const val anchorCueScanLimit = 32
    private const val anchorLeadingCueLimit = 4
    private const val anchorLongestCueLimit = 8
    private const val anchorValidationCueLimit = 64
    private const val anchorMinimumCueLength = 6
    private const val maxOccurrencesPerAnchorCue = 16
    private const val maxAnchorCandidates = 128
    private const val localSearchWindow = 128
    private const val resyncFailureCount = 3
    private const val resyncCueScanLimit = 24
    private const val resyncValidationCueLimit = 32
    private const val resyncSourceWindow = 4_096

    private data class ChapterRange(
        val chapterIndex: Int,
        val start: Int,
        val length: Int,
    ) {
        val end: Int get() = start + length
    }

    private data class MatchCue(
        val cue: SasayakiCue,
        val text: List<Int>,
    )

    private data class AnchorScore(
        val longestRun: Int,
        val weightedCharacters: Int,
        val matchedCues: Int,
        val firstMatchedCue: Int,
    )

    fun match(book: EpubBook, cues: List<SasayakiCue>): SasayakiMatchData {
        val source = mutableListOf<Int>()
        val chapters = mutableListOf<ChapterRange>()
        book.chapters.forEachIndexed { index, chapter ->
            if (!chapter.linear) return@forEachIndexed
            if (chapter.properties.hasManifestProperty("nav")) return@forEachIndexed
            if (chapter.isGuideToc) return@forEachIndexed
            val codePoints = chapter.html.filteredReaderText().codePointsList()
            chapters += ChapterRange(
                chapterIndex = index,
                start = source.size,
                length = codePoints.size,
            )
            source += codePoints
        }

        val matchCues = cues.map { cue ->
            MatchCue(cue = cue, text = cue.text.filteredReaderText().codePointsList())
        }
        val start = selectStart(
            source = source,
            chapters = chapters,
            cues = matchCues,
        )

        val matches = mutableListOf<SasayakiMatch>()
        var unmatched = 0
        var cursor = start
        var cueIndex = 0
        val pendingUnmatched = ArrayDeque<Int>()

        while (cueIndex < matchCues.size) {
            val (cue, chars) = matchCues[cueIndex]
            if (chars.isEmpty()) {
                unmatched += 1
                cueIndex += 1
                continue
            }
            if (cue.text.startsWith("＊") && chars.size < 5) {
                unmatched += 1
                cueIndex += 1
                continue
            }
            val index = findText(
                source = source,
                text = chars,
                start = cursor,
                end = minOf(source.size, cursor + chars.size + localSearchWindow),
            )
            val chapter = index?.let { position ->
                chapters.firstOrNull {
                    position >= it.start && position < it.end && position + chars.size <= it.end
                }
            }
            if (index == null || chapter == null) {
                pendingUnmatched.addLast(cueIndex)
                cueIndex += 1
                if (pendingUnmatched.size >= resyncFailureCount) {
                    val pendingStart = pendingUnmatched.first()
                    val resyncStart = selectCoherentStart(
                        source = source,
                        chapters = chapters,
                        cues = matchCues,
                        sourceStart = cursor,
                        sourceEnd = minOf(source.size, cursor + resyncSourceWindow),
                        cueStartIndex = pendingStart,
                        cueScanLimit = resyncCueScanLimit,
                        validationCueLimit = resyncValidationCueLimit,
                        allowShortCoherentRun = false,
                    )
                    if (resyncStart != null && resyncStart > cursor) {
                        cueIndex = pendingStart
                        cursor = resyncStart
                        pendingUnmatched.clear()
                    } else {
                        unmatched += pendingUnmatched.size
                        pendingUnmatched.clear()
                    }
                }
                continue
            }

            unmatched += pendingUnmatched.size
            pendingUnmatched.clear()
            cursor = index + chars.size
            matches += SasayakiMatch(
                id = cue.id,
                startTime = cue.startTime,
                endTime = cue.endTime,
                text = cue.text,
                chapterIndex = chapter.chapterIndex,
                start = index - chapter.start,
                length = chars.size,
            )
            cueIndex += 1
        }

        unmatched += pendingUnmatched.size

        return SasayakiMatchData(matches = matches, unmatched = unmatched)
    }

    private fun selectStart(
        source: List<Int>,
        chapters: List<ChapterRange>,
        cues: List<MatchCue>,
    ): Int =
        selectCoherentStart(
            source = source,
            chapters = chapters,
            cues = cues,
            sourceStart = 0,
            sourceEnd = source.size,
            cueStartIndex = 0,
            cueScanLimit = anchorCueScanLimit,
            validationCueLimit = anchorValidationCueLimit,
            allowShortCoherentRun = true,
        ) ?: 0

    private fun selectCoherentStart(
        source: List<Int>,
        chapters: List<ChapterRange>,
        cues: List<MatchCue>,
        sourceStart: Int,
        sourceEnd: Int,
        cueStartIndex: Int,
        cueScanLimit: Int,
        validationCueLimit: Int,
        allowShortCoherentRun: Boolean,
    ): Int? {
        val eligible = cues
            .indices
            .drop(cueStartIndex)
            .take(cueScanLimit)
            .map { index -> IndexedValue(index, cues[index]) }
            .filter { (_, cue) ->
                !cue.cue.text.startsWith("＊") && cue.text.size >= anchorMinimumCueLength
            }
        if (eligible.isEmpty()) return null

        val anchors = (
            eligible.take(anchorLeadingCueLimit) +
                eligible.sortedByDescending { it.value.text.size }.take(anchorLongestCueLimit)
            ).distinctBy { it.index }
        val candidates = linkedSetOf<Int>()
        anchors.forEach { (_, cue) ->
            var searchStart = sourceStart
            var occurrenceCount = 0
            while (occurrenceCount < maxOccurrencesPerAnchorCue && candidates.size < maxAnchorCandidates) {
                val index = findText(source, cue.text, start = searchStart, end = sourceEnd) ?: break
                candidates += index
                occurrenceCount += 1
                searchStart = index + 1
            }
        }
        if (candidates.isEmpty()) return null

        val requiredRun = if (allowShortCoherentRun) minOf(3, eligible.size) else 3
        return candidates
            .map { candidate ->
                candidate to scoreStart(
                    source = source,
                    chapters = chapters,
                    cues = cues,
                    start = candidate,
                    cueStartIndex = cueStartIndex,
                    validationCueLimit = validationCueLimit,
                )
            }
            .filter { (_, score) -> score.longestRun >= requiredRun }
            .maxWithOrNull(
                compareBy<Pair<Int, AnchorScore>>(
                    { it.second.longestRun },
                    { it.second.weightedCharacters },
                    { it.second.matchedCues },
                    { -it.second.firstMatchedCue },
                    { -it.first },
                ),
            )
            ?.first
    }

    private fun scoreStart(
        source: List<Int>,
        chapters: List<ChapterRange>,
        cues: List<MatchCue>,
        start: Int,
        cueStartIndex: Int,
        validationCueLimit: Int,
    ): AnchorScore {
        var cursor = start
        var longestRun = 0
        var currentRun = 0
        var weightedCharacters = 0
        var matchedCues = 0
        var firstMatchedCue = Int.MAX_VALUE

        cues.drop(cueStartIndex).take(validationCueLimit).forEachIndexed { index, (cue, text) ->
            if (text.isEmpty() || cue.text.startsWith("＊") && text.size < 5) return@forEachIndexed
            val match = findText(
                source = source,
                text = text,
                start = cursor,
                end = minOf(source.size, cursor + text.size + localSearchWindow),
            )
            val chapter = match?.let { position ->
                chapters.firstOrNull { position >= it.start && position < it.end && position + text.size <= it.end }
            }
            if (match == null || chapter == null) {
                currentRun = 0
                return@forEachIndexed
            }

            cursor = match + text.size
            currentRun += 1
            longestRun = maxOf(longestRun, currentRun)
            weightedCharacters += minOf(text.size, 24)
            matchedCues += 1
            firstMatchedCue = minOf(firstMatchedCue, index)
        }

        return AnchorScore(
            longestRun = longestRun,
            weightedCharacters = weightedCharacters,
            matchedCues = matchedCues,
            firstMatchedCue = firstMatchedCue,
        )
    }

    private fun findText(source: List<Int>, text: List<Int>, start: Int, end: Int): Int? {
        if (text.isEmpty()) return null
        var index = start
        val last = end - text.size
        while (index <= last) {
            var matched = true
            for (i in text.indices) {
                if (source[index + i] != text[i]) {
                    matched = false
                    break
                }
            }
            if (matched) return index
            index += 1
        }
        return null
    }
}

internal fun String.codePointsList(): List<Int> =
    codePoints().toArray().toList()

private fun String?.hasManifestProperty(property: String): Boolean =
    this
        ?.trim()
        ?.splitToSequence(Regex("\\s+"))
        ?.any { it == property } == true
