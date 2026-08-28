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
        val text: IntArray,
    )

    private data class AnchorScore(
        val longestRun: Int,
        val weightedCharacters: Int,
        val matchedCues: Int,
        val firstMatchedCue: Int,
    )

    fun match(book: EpubBook, cues: List<SasayakiCue>): SasayakiMatchData {
        val chapterTexts = mutableListOf<IntArray>()
        val chapters = mutableListOf<ChapterRange>()
        var sourceLength = 0
        book.chapters.forEachIndexed { index, chapter ->
            if (!chapter.linear) return@forEachIndexed
            if (chapter.properties.hasManifestProperty("nav")) return@forEachIndexed
            if (chapter.isGuideToc) return@forEachIndexed
            val codePoints = chapter.html.filteredReaderText().codePointsArray()
            chapters += ChapterRange(
                chapterIndex = index,
                start = sourceLength,
                length = codePoints.size,
            )
            chapterTexts += codePoints
            sourceLength += codePoints.size
        }
        val source = IntArray(sourceLength)
        var sourceOffset = 0
        chapterTexts.forEach { chapterText ->
            chapterText.copyInto(source, destinationOffset = sourceOffset)
            sourceOffset += chapterText.size
        }

        val matchCues = cues.map { cue ->
            MatchCue(cue = cue, text = cue.text.filteredReaderText().codePointsArray())
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
                findChapter(chapters = chapters, position = position, textLength = chars.size)
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
        source: IntArray,
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
        source: IntArray,
        chapters: List<ChapterRange>,
        cues: List<MatchCue>,
        sourceStart: Int,
        sourceEnd: Int,
        cueStartIndex: Int,
        cueScanLimit: Int,
        validationCueLimit: Int,
        allowShortCoherentRun: Boolean,
    ): Int? {
        val eligible = mutableListOf<IndexedValue<MatchCue>>()
        val cueEnd = minOf(cues.size, cueStartIndex + cueScanLimit)
        for (index in cueStartIndex until cueEnd) {
            val cue = cues[index]
            if (!cue.cue.text.startsWith("＊") && cue.text.size >= anchorMinimumCueLength) {
                eligible += IndexedValue(index = index, value = cue)
            }
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
        source: IntArray,
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

        val cueEnd = minOf(cues.size, cueStartIndex + validationCueLimit)
        for (cuePosition in cueStartIndex until cueEnd) {
            val (cue, text) = cues[cuePosition]
            if (text.isEmpty() || cue.text.startsWith("＊") && text.size < 5) continue
            val match = findText(
                source = source,
                text = text,
                start = cursor,
                end = minOf(source.size, cursor + text.size + localSearchWindow),
            )
            val chapter = match?.let { position ->
                findChapter(chapters = chapters, position = position, textLength = text.size)
            }
            if (match == null || chapter == null) {
                currentRun = 0
                continue
            }

            cursor = match + text.size
            currentRun += 1
            longestRun = maxOf(longestRun, currentRun)
            weightedCharacters += minOf(text.size, 24)
            matchedCues += 1
            firstMatchedCue = minOf(firstMatchedCue, cuePosition - cueStartIndex)
        }

        return AnchorScore(
            longestRun = longestRun,
            weightedCharacters = weightedCharacters,
            matchedCues = matchedCues,
            firstMatchedCue = firstMatchedCue,
        )
    }

    private fun findText(source: IntArray, text: IntArray, start: Int, end: Int): Int? {
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

    private fun findChapter(
        chapters: List<ChapterRange>,
        position: Int,
        textLength: Int,
    ): ChapterRange? {
        var low = 0
        var high = chapters.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val chapter = chapters[middle]
            when {
                position < chapter.start -> high = middle - 1
                position >= chapter.end -> low = middle + 1
                position + textLength <= chapter.end -> return chapter
                else -> return null
            }
        }
        return null
    }
}

internal fun String.codePointsArray(): IntArray =
    codePoints().toArray()

private fun String?.hasManifestProperty(property: String): Boolean =
    this
        ?.trim()
        ?.splitToSequence(Regex("\\s+"))
        ?.any { it == property } == true
