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
    private const val resyncCueScanLimit = 96
    private const val resyncValidationCueLimit = 96
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

    private data class PositionedMatch(
        val cueIndex: Int,
        val sourceIndex: Int,
        val chapter: ChapterRange,
    )

    private data class CoherentAlignment(
        val start: Int,
        val score: AnchorScore,
        val trustedRun: List<PositionedMatch>,
    )

    private data class RecoveryPlan(
        val matches: List<PositionedMatch>,
        val nextCueIndex: Int,
        val cursor: Int,
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
        val startAlignment = selectStartAlignment(
            source = source,
            chapters = chapters,
            cues = matchCues,
        )

        val matches = mutableListOf<SasayakiMatch>()
        var cursor = 0
        var cueIndex = 0
        var unresolvedStartIndex: Int? = null
        var searchableFailureCount = 0
        startAlignment?.trustedRun?.firstOrNull()?.let { firstTrusted ->
            alignAnchoredSegment(
                source = source,
                chapters = chapters,
                cues = matchCues,
                cueStartIndex = 0,
                trustedRun = listOf(firstTrusted),
                sourceStart = 0,
            ).forEach { positioned ->
                matches += positioned.toSasayakiMatch(matchCues[positioned.cueIndex])
            }
            cueIndex = firstTrusted.cueIndex + 1
            cursor = firstTrusted.sourceIndex + matchCues[firstTrusted.cueIndex].text.size
        }

        while (cueIndex < matchCues.size) {
            val (cue, chars) = matchCues[cueIndex]
            if (chars.isEmpty()) {
                unresolvedStartIndex = unresolvedStartIndex ?: cueIndex
                cueIndex += 1
                continue
            }
            if (cue.text.startsWith("＊") && chars.size < 5) {
                unresolvedStartIndex = unresolvedStartIndex ?: cueIndex
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
                unresolvedStartIndex = unresolvedStartIndex ?: cueIndex
                cueIndex += 1
                searchableFailureCount += 1
                if (searchableFailureCount >= resyncFailureCount) {
                    val recovery = selectRecoveryPlan(
                        source = source,
                        chapters = chapters,
                        cues = matchCues,
                        sourceStart = cursor,
                        sourceEnd = minOf(source.size, cursor + resyncSourceWindow),
                        cueStartIndex = unresolvedStartIndex,
                    )
                    if (recovery != null && recovery.cursor > cursor) {
                        recovery.matches.forEach { positioned ->
                            matches += positioned.toSasayakiMatch(matchCues[positioned.cueIndex])
                        }
                        cueIndex = recovery.nextCueIndex
                        cursor = recovery.cursor
                    }
                    unresolvedStartIndex = null
                    searchableFailureCount = 0
                }
                continue
            }

            unresolvedStartIndex = null
            searchableFailureCount = 0
            cursor = index + chars.size
            matches += PositionedMatch(
                cueIndex = cueIndex,
                sourceIndex = index,
                chapter = chapter,
            ).toSasayakiMatch(
                matchCue = matchCues[cueIndex],
            )
            cueIndex += 1
        }

        return SasayakiMatchData(
            matches = matches,
            unmatched = cues.size - matches.size,
        )
    }

    private fun selectStartAlignment(
        source: IntArray,
        chapters: List<ChapterRange>,
        cues: List<MatchCue>,
    ): CoherentAlignment? =
        selectCoherentAlignment(
            source = source,
            chapters = chapters,
            cues = cues,
            sourceStart = 0,
            sourceEnd = source.size,
            cueStartIndex = 0,
            cueScanLimit = anchorCueScanLimit,
            validationCueLimit = anchorValidationCueLimit,
            allowShortCoherentRun = true,
        )

    private fun selectCoherentAlignment(
        source: IntArray,
        chapters: List<ChapterRange>,
        cues: List<MatchCue>,
        sourceStart: Int,
        sourceEnd: Int,
        cueStartIndex: Int,
        cueScanLimit: Int,
        validationCueLimit: Int,
        allowShortCoherentRun: Boolean,
    ): CoherentAlignment? {
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
                val (score, trustedRun) = scoreStart(
                    source = source,
                    chapters = chapters,
                    cues = cues,
                    start = candidate,
                    cueStartIndex = cueStartIndex,
                    validationCueLimit = validationCueLimit,
                )
                CoherentAlignment(
                    start = candidate,
                    score = score,
                    trustedRun = trustedRun,
                )
            }
            .filter { alignment -> alignment.score.longestRun >= requiredRun }
            .maxWithOrNull(
                compareBy<CoherentAlignment>(
                    { it.score.longestRun },
                    { it.score.weightedCharacters },
                    { it.score.matchedCues },
                    { -it.score.firstMatchedCue },
                    { -it.start },
                ),
            )
    }

    private fun scoreStart(
        source: IntArray,
        chapters: List<ChapterRange>,
        cues: List<MatchCue>,
        start: Int,
        cueStartIndex: Int,
        validationCueLimit: Int,
    ): Pair<AnchorScore, List<PositionedMatch>> {
        var cursor = start
        var longestRun = 0
        var currentRun = 0
        var weightedCharacters = 0
        var matchedCues = 0
        var firstMatchedCue = Int.MAX_VALUE
        val currentRunMatches = mutableListOf<PositionedMatch>()
        var longestRunMatches = emptyList<PositionedMatch>()

        val cueEnd = minOf(cues.size, cueStartIndex + validationCueLimit)
        for (cuePosition in cueStartIndex until cueEnd) {
            val (cue, text) = cues[cuePosition]
            if (!isTrustedAnchor(cue = cue, text = text)) continue
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
                currentRunMatches.clear()
                continue
            }

            cursor = match + text.size
            currentRun += 1
            currentRunMatches += PositionedMatch(
                cueIndex = cuePosition,
                sourceIndex = match,
                chapter = chapter,
            )
            if (currentRun > longestRun) {
                longestRun = currentRun
                longestRunMatches = currentRunMatches.toList()
            }
            weightedCharacters += minOf(text.size, 24)
            matchedCues += 1
            firstMatchedCue = minOf(firstMatchedCue, cuePosition - cueStartIndex)
        }

        return AnchorScore(
            longestRun = longestRun,
            weightedCharacters = weightedCharacters,
            matchedCues = matchedCues,
            firstMatchedCue = firstMatchedCue,
        ) to longestRunMatches
    }

    private fun selectRecoveryPlan(
        source: IntArray,
        chapters: List<ChapterRange>,
        cues: List<MatchCue>,
        sourceStart: Int,
        sourceEnd: Int,
        cueStartIndex: Int,
    ): RecoveryPlan? {
        val alignment = selectCoherentAlignment(
            source = source,
            chapters = chapters,
            cues = cues,
            sourceStart = sourceStart,
            sourceEnd = sourceEnd,
            cueStartIndex = cueStartIndex,
            cueScanLimit = resyncCueScanLimit,
            validationCueLimit = resyncValidationCueLimit,
            allowShortCoherentRun = false,
        ) ?: return null
        val trustedRun = alignment.trustedRun
        if (trustedRun.isEmpty()) return null

        val lastTrusted = trustedRun.last()
        val recovered = alignAnchoredSegment(
            source = source,
            chapters = chapters,
            cues = cues,
            cueStartIndex = cueStartIndex,
            trustedRun = trustedRun,
            sourceStart = sourceStart,
        )
        return RecoveryPlan(
            matches = recovered,
            nextCueIndex = lastTrusted.cueIndex + 1,
            cursor = lastTrusted.sourceIndex + cues[lastTrusted.cueIndex].text.size,
        )
    }

    private fun alignAnchoredSegment(
        source: IntArray,
        chapters: List<ChapterRange>,
        cues: List<MatchCue>,
        cueStartIndex: Int,
        trustedRun: List<PositionedMatch>,
        sourceStart: Int,
    ): List<PositionedMatch> {
        val trustedByCue = trustedRun.associateBy { it.cueIndex }
        val lastTrustedCue = trustedRun.last().cueIndex
        var cursor = sourceStart
        val matches = mutableListOf<PositionedMatch>()

        for (cueIndex in cueStartIndex..lastTrustedCue) {
            val trusted = trustedByCue[cueIndex]
            if (trusted != null) {
                matches += trusted
                cursor = trusted.sourceIndex + cues[cueIndex].text.size
                continue
            }

            val matchCue = cues[cueIndex]
            if (!isSearchable(matchCue)) continue
            val nextTrusted = trustedRun.firstOrNull { it.cueIndex > cueIndex } ?: continue
            val unique = findUniqueText(
                source = source,
                text = matchCue.text,
                chapters = chapters,
                start = cursor,
                end = nextTrusted.sourceIndex,
            ) ?: continue
            matches += PositionedMatch(
                cueIndex = cueIndex,
                sourceIndex = unique.first,
                chapter = unique.second,
            )
            cursor = unique.first + matchCue.text.size
        }
        return matches
    }

    private fun findUniqueText(
        source: IntArray,
        text: IntArray,
        chapters: List<ChapterRange>,
        start: Int,
        end: Int,
    ): Pair<Int, ChapterRange>? {
        var searchStart = start
        var found: Pair<Int, ChapterRange>? = null
        while (searchStart < end) {
            val position = findText(source = source, text = text, start = searchStart, end = end) ?: break
            val chapter = findChapter(chapters = chapters, position = position, textLength = text.size)
            if (chapter != null) {
                if (found != null) return null
                found = position to chapter
            }
            searchStart = position + 1
        }
        return found
    }

    private fun isSearchable(matchCue: MatchCue): Boolean =
        matchCue.text.isNotEmpty() && !(matchCue.cue.text.startsWith("＊") && matchCue.text.size < 5)

    private fun isTrustedAnchor(cue: SasayakiCue, text: IntArray): Boolean =
        !cue.text.startsWith("＊") && text.size >= anchorMinimumCueLength

    private fun PositionedMatch.toSasayakiMatch(matchCue: MatchCue): SasayakiMatch =
        SasayakiMatch(
            id = matchCue.cue.id,
            startTime = matchCue.cue.startTime,
            endTime = matchCue.cue.endTime,
            text = matchCue.cue.text,
            chapterIndex = chapter.chapterIndex,
            start = sourceIndex - chapter.start,
            length = matchCue.text.size,
        )

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
