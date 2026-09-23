package moe.antimony.hoshi.features.sasayaki

import kotlin.math.max
import kotlin.math.min
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiMatchSource

/**
 * Exact, distinctive text establishes positions; edit alignment only repairs bounded gaps.
 * There are deliberately no synthetic anchors at the start/end of the audio or book.
 * This keeps unspoken passages and unrelated introductions out of the saved highlights.
 */
object SasayakiTranscriptAligner {
    private const val seedLength = 8
    private const val maxGap = 384
    private const val minimumSimilarity = 0.55
    private const val maxUncertainRun = 6

    private data class Timing(val start: Double, val end: Double)
    private data class Speech(val text: IntArray, val times: List<Timing>, val lastStart: Double = 0.0)
    private data class Chapter(
        val source: SasayakiSource.Chapter,
        val projections: List<SasayakiSource.Projection>,
        val boundaries: BooleanArray,
        val globalStart: Int,
    )
    private data class Seed(val chapter: Int, val projection: Int, val offset: Int, val sourceStart: Int)
    private data class Anchor(
        val chapter: Int,
        val projection: Int,
        val written: Int,
        val spoken: Int,
        val length: Int,
        val sourceStart: Int,
        val sourceEnd: Int,
        val globalStart: Int,
    ) {
        val speechEnd: Int get() = spoken + length
        val globalEnd: Int get() = globalStart + sourceEnd - sourceStart
    }
    private data class Pairing(val written: Int, val spoken: Int, val exact: Boolean)
    private data class Repair(
        val projection: SasayakiSource.Projection,
        val offset: Int,
        val pairs: List<Pairing>,
        val similarity: Double,
    )

    fun align(book: EpubBook, tokens: List<SasayakiToken>): SasayakiMatchData =
        Session(book).align(tokens)

    /** One append-only transcript. Book normalization and the distinctive-text index are shared by all updates. */
    class Session(book: EpubBook) {
        private val chapters: List<Chapter>
        private val index: Map<Long, Seed?>
        private var processedTokens = 0
        private var speech = Speech(IntArray(0), emptyList())
        private var candidates = emptyList<Anchor>()
        private var gaps = emptyMap<Gap, List<Timing?>>()
        private data class Gap(val chapter: Int, val lower: Int, val upper: Int, val from: Int, val to: Int)

        init {
            var offset = 0
            chapters = SasayakiSource.chapters(book).map { source ->
                Chapter(source, SasayakiSource.projections(source), SasayakiSource.boundaries(source), offset)
                    .also { offset += source.text.size }
            }
            index = buildIndex(chapters)
        }

        fun align(tokens: List<SasayakiToken>, complete: Boolean = false): SasayakiMatchData {
            if (complete) {
                processedTokens = 0
                speech = Speech(IntArray(0), emptyList())
                candidates = emptyList()
                gaps = emptyMap()
            }
            require(tokens.size >= processedTokens) { "A matching session requires an append-only transcript" }
            // Revisit an exact run touching the old tail: new speech may extend it.
            val tail = (speech.text.size - seedLength + 1).coerceAtLeast(0)
            val from = candidates.filter { it.speechEnd >= tail }.minOfOrNull { it.spoken }?.coerceAtMost(tail) ?: tail
            val added = speech(tokens.subList(processedTokens, tokens.size), speech.lastStart)
            speech = Speech(speech.text + added.text, speech.times + added.times, added.lastStart)
            processedTokens = tokens.size
            candidates = (candidates.filter { it.spoken < from && it.speechEnd < tail } +
                findAnchors(chapters, speech.text, index, from)).distinctBy { Triple(it.chapter, it.sourceStart, it.spoken) }
            // Choosing the monotonic chain is cheap and lets stronger new evidence correct an old location.
            val anchors = coherentAnchors(candidates)
            val times = chapters.map { arrayOfNulls<Timing>(it.source.text.size) }
            anchors.forEach { anchor ->
                val projection = chapters[anchor.chapter].projections[anchor.projection]
                repeat(anchor.length) { position ->
                    assign(times[anchor.chapter], projection, anchor.written + position, speech.times[anchor.spoken + position])
                }
            }
            val nextGaps = HashMap<Gap, List<Timing?>>()
            anchors.zipWithNext().forEach { (left, right) ->
                if (left.chapter != right.chapter) return@forEach
                val gap = Gap(left.chapter, left.sourceEnd, right.sourceStart, left.speechEnd, right.spoken)
                if (gap.upper - gap.lower !in 1..maxGap || gap.to - gap.from !in 0..maxGap) return@forEach
                val cached = gaps[gap]
                if (cached != null) {
                    cached.forEachIndexed { position, time -> times[gap.chapter][gap.lower + position] = time }
                    nextGaps[gap] = cached
                } else {
                    repairGap(chapters[gap.chapter], gap.lower, gap.upper, speech, gap.from, gap.to, times[gap.chapter])
                    nextGaps[gap] = times[gap.chapter].slice(gap.lower until gap.upper)
                }
            }
            // A formerly unmatched gap is retried only when its neighboring anchors change.
            gaps = nextGaps
            return cut(chapters, times)
        }
    }

    private fun speech(tokens: List<SasayakiToken>, minimumStart: Double = 0.0): Speech {
        val characters = mutableListOf<Int>()
        val times = mutableListOf<Timing>()
        var previousStart = minimumStart
        tokens.forEach { token ->
            if (!token.start.isFinite() || !token.end.isFinite() || token.start < previousStart ||
                token.end <= token.start) return@forEach
            previousStart = token.start
            val points = SasayakiSource.normalizedText(token.text)
            points.forEachIndexed { index, point ->
                characters += point
                val length = token.end - token.start
                times += Timing(token.start + length * index / points.size, token.start + length * (index + 1) / points.size)
            }
        }
        return Speech(characters.toIntArray(), times, previousStart)
    }

    private fun buildIndex(chapters: List<Chapter>): Map<Long, Seed?> {
        // A null entry is ambiguous. Duplicate base/ruby projections at the same source
        // position count once; repeated phrases elsewhere cannot become independent anchors.
        val index = HashMap<Long, Seed?>()
        chapters.forEachIndexed { chapterIndex, chapter ->
            chapter.projections.forEachIndexed { projectionIndex, projection ->
                for (position in 0..projection.text.size - seedLength) {
                    val key = hash(projection.text, position)
                    val candidate = Seed(chapterIndex, projectionIndex, position, projection.starts[position])
                    if (!index.containsKey(key)) {
                        index[key] = candidate
                    } else {
                        val previous = index[key]
                        if (previous != null && (previous.chapter != chapterIndex || previous.sourceStart != candidate.sourceStart)) {
                            index[key] = null
                        }
                    }
                }
            }
        }
        return index
    }

    private fun findAnchors(chapters: List<Chapter>, speech: IntArray, index: Map<Long, Seed?>, from: Int): List<Anchor> {
        val result = mutableListOf<Anchor>()
        var position = from
        while (position <= speech.size - seedLength) {
            val seed = index[hash(speech, position)]
            if (seed == null) {
                position++
                continue
            }
            val chapter = chapters[seed.chapter]
            val projection = chapter.projections[seed.projection]
            if ((0 until seedLength).any { speech[position + it] != projection.text[seed.offset + it] } ||
                (0 until seedLength).map { speech[position + it] }.distinct().size < 3) {
                position++
                continue
            }
            var before = 0
            while (position > before && seed.offset > before &&
                speech[position - before - 1] == projection.text[seed.offset - before - 1]) before++
            var length = seedLength
            while (position + length < speech.size && seed.offset + length < projection.text.size &&
                speech[position + length] == projection.text[seed.offset + length]) length++
            val written = seed.offset - before
            val sourceStart = projection.starts[written]
            val sourceEnd = projection.ends[seed.offset + length - 1]
            result += Anchor(seed.chapter, seed.projection, written, position - before,
                length + before, sourceStart, sourceEnd, chapter.globalStart + sourceStart)
            position += length - seedLength + 1
        }
        return result.distinctBy { Triple(it.chapter, it.sourceStart, it.spoken) }
    }

    private fun hash(text: IntArray, offset: Int): Long {
        var value = 0L
        repeat(seedLength) { value = value * 1_000_003 + text[offset + it] }
        return value
    }

    private fun coherentAnchors(candidates: List<Anchor>): List<Anchor> {
        val sorted = candidates.sortedWith(compareBy<Anchor> { it.speechEnd }.thenBy { it.globalEnd })
        if (sorted.isEmpty()) return emptyList()
        // Weighted increasing subsequence in O(n log n): long exact runs win over
        // isolated coincidences without quadratic work for a full audiobook.
        val coordinates = sorted.map { it.globalEnd }.distinct().sorted()
        val bestAt = IntArray(coordinates.size + 1) { -1 }
        val scores = IntArray(sorted.size)
        val previous = IntArray(sorted.size) { -1 }
        val eligible = sorted.indices.sortedBy { sorted[it].speechEnd }
        var eligibleIndex = 0
        fun better(a: Int, b: Int): Int = when {
            a < 0 -> b
            b < 0 -> a
            scores[a] >= scores[b] -> a
            else -> b
        }
        val order = sorted.indices.sortedBy { sorted[it].spoken }
        order.forEach { current ->
            val anchor = sorted[current]
            while (eligibleIndex < eligible.size && sorted[eligible[eligibleIndex]].speechEnd <= anchor.spoken) {
                val prior = eligible[eligibleIndex++]
                var treeIndex = coordinates.binarySearch(sorted[prior].globalEnd) + 1
                while (treeIndex < bestAt.size) {
                    bestAt[treeIndex] = better(bestAt[treeIndex], prior)
                    treeIndex += treeIndex and -treeIndex
                }
            }
            var query = coordinates.binarySearch(anchor.globalStart).let { if (it >= 0) it + 1 else -it - 1 }
            var best = -1
            while (query > 0) {
                best = better(best, bestAt[query])
                query -= query and -query
            }
            previous[current] = best
            scores[current] = (if (best >= 0) scores[best] else 0) + anchor.sourceEnd - anchor.sourceStart
        }
        var selected = scores.indices.maxBy { scores[it] }
        val chain = mutableListOf<Anchor>()
        while (selected >= 0) {
            chain += sorted[selected]
            selected = previous[selected]
        }
        return chain.asReversed()
    }

    private fun repairGap(
        chapter: Chapter, lower: Int, upper: Int,
        speech: Speech, speechStart: Int, speechEnd: Int, times: Array<Timing?>,
    ) {
        val writtenCount = upper - lower
        val spokenCount = speechEnd - speechStart
        if (spokenCount == 0) {
            recoverOmittedCharacters(chapter, lower, upper, speech, speechStart, times)
            return
        }
        if (writtenCount !in 1..maxGap || spokenCount !in 1..maxGap) return
        val repair = chapter.projections.mapNotNull { projection ->
            val start = projection.starts.indexOfFirst { it >= lower }
            val end = projection.ends.indexOfLast { it <= upper } + 1
            if (start < 0 || end <= start || end - start > maxGap) return@mapNotNull null
            val count = end - start
            if (count.toDouble() / spokenCount !in 0.45..2.2) return@mapNotNull null
            val (pairs, edits) = editAlignment(projection.text, start, end, speech.text, speechStart, speechEnd)
            Repair(projection, start, pairs, 1.0 - edits.toDouble() / max(count, spokenCount))
        }.maxByOrNull { it.similarity } ?: return
        if (repair.similarity < minimumSimilarity) {
            recoverShortRewrite(chapter, lower, upper, speech, speechStart, speechEnd, repair.similarity, times)
            return
        }
        // A good overall score cannot justify a long unsupported run inside the gap.
        var cursor = 0
        while (cursor < repair.pairs.size) {
            val first = cursor
            val exact = repair.pairs[cursor].exact
            while (cursor < repair.pairs.size && repair.pairs[cursor].exact == exact) cursor++
            if (exact || cursor - first <= maxUncertainRun) {
                for (index in first until cursor) {
                    val pair = repair.pairs[index]
                    assign(times, repair.projection, repair.offset + pair.written, speech.times[speechStart + pair.spoken])
                }
            }
        }
    }

    /** Repair a short missing word fragment, never an entirely unspoken cue. */
    private fun recoverOmittedCharacters(
        chapter: Chapter, lower: Int, upper: Int,
        speech: Speech, speechOffset: Int, times: Array<Timing?>,
    ) {
        val count = upper - lower
        if (count !in 1..maxUncertainRun || lower <= 0 || upper >= times.size) return
        if ((lower until upper - 1).any { chapter.boundaries[it] } ||
            (chapter.boundaries[lower - 1] && chapter.boundaries[upper - 1])) return
        val from = speech.times[speechOffset - 1].end
        val to = speech.times[speechOffset].start
        if (to <= from || to - from > min(2.5, 1.0 + count * 0.7)) return
        repeat(count) { index ->
            times[lower + index] = Timing(from + (to - from) * index / count, from + (to - from) * (index + 1) / count)
        }
    }

    /** Fushi-style proportional recovery, restricted to short gaps with two real anchors. */
    private fun recoverShortRewrite(
        chapter: Chapter, lower: Int, upper: Int,
        speech: Speech, speechStart: Int, speechEnd: Int,
        similarity: Double, times: Array<Timing?>,
    ) {
        val writtenCount = upper - lower
        val spokenCount = speechEnd - speechStart
        if (writtenCount !in 1..24 || spokenCount !in 1..48 ||
            writtenCount.toDouble() / spokenCount !in 0.5..2.0) return
        // A missing reply after a spoken sentence is not a kana rewrite. Proportional
        // assignment may repair one segment, but cannot move its audio across a boundary.
        if ((lower until upper - 1).any { chapter.boundaries[it] }) return
        val duration = speech.times[speechEnd].start - speech.times[speechStart - 1].end
        if (duration <= 0 || duration > 1.0 + writtenCount * 0.7) return
        fun kana(point: Int) = point in 0x3041..0x30FA || point == 0x30FC
        fun kanji(point: Int) = Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN
        val written = chapter.source.text.sliceArray(lower until upper)
        val spoken = speech.text.sliceArray(speechStart until speechEnd)
        val readingRewrite =
            (written.all(::kana) && spoken.any(::kanji) && spoken.all { kana(it) || kanji(it) }) ||
            (spoken.all(::kana) && written.any(::kanji) && written.all { kana(it) || kanji(it) })
        // Entirely unrelated phrases cannot be recovered from duration/length alone.
        if (similarity < 0.25 && !readingRewrite) return
        for (index in 0 until writtenCount) {
            val first = speechStart + index * spokenCount / writtenCount
            val end = speechStart + ((index + 1) * spokenCount + writtenCount - 1) / writtenCount
            times[lower + index] = Timing(speech.times[first].start, speech.times[end - 1].end)
        }
    }

    private fun editAlignment(
        written: IntArray, from: Int, to: Int,
        spoken: IntArray, speechFrom: Int, speechTo: Int,
    ): Pair<List<Pairing>, Int> {
        val rows = to - from
        val cols = speechTo - speechFrom
        val width = cols + 1
        val costs = IntArray((rows + 1) * width)
        for (row in 0..rows) costs[row * width] = row
        for (col in 0..cols) costs[col] = col
        for (row in 1..rows) {
            for (col in 1..cols) {
                val difference = if (written[from + row - 1] == spoken[speechFrom + col - 1]) 0 else 1
                costs[row * width + col] = min(costs[(row - 1) * width + col - 1] + difference,
                    min(costs[(row - 1) * width + col] + 1, costs[row * width + col - 1] + 1))
            }
        }
        var row = rows
        var col = cols
        val pairs = mutableListOf<Pairing>()
        while (row > 0 && col > 0) {
            val exact = written[from + row - 1] == spoken[speechFrom + col - 1]
            if (costs[row * width + col] == costs[(row - 1) * width + col - 1] + if (exact) 0 else 1) {
                pairs += Pairing(row - 1, col - 1, exact)
                row--
                col--
            } else if (costs[row * width + col] == costs[(row - 1) * width + col] + 1) {
                // A missing ASR character gets the neighboring token's time only within
                // a short bounded error run; it never consumes a new audio interval.
                pairs += Pairing(row - 1, (col - 1).coerceAtLeast(0), false)
                row--
            } else {
                col--
            }
        }
        while (row > 0) pairs += Pairing(--row, 0, false)
        return pairs.asReversed() to costs[rows * width + cols]
    }

    private fun assign(times: Array<Timing?>, projection: SasayakiSource.Projection, index: Int, time: Timing) {
        for (point in projection.starts[index] until projection.ends[index]) {
            val existing = times[point]
            times[point] = if (existing == null) time else Timing(min(existing.start, time.start), max(existing.end, time.end))
        }
    }

    private fun cut(chapters: List<Chapter>, times: List<Array<Timing?>>): SasayakiMatchData {
        val matches = mutableListOf<SasayakiMatch>()
        var unmatched = 0
        chapters.forEachIndexed { index, chapter ->
            var start = 0
            chapter.boundaries.forEachIndexed { end, boundary ->
                if (!boundary) return@forEachIndexed
                val timed = (start..end).filter { times[index][it] != null }
                val priorCount = matches.size
                var run = 0
                while (run < timed.size) {
                    val first = run
                    while (run + 1 < timed.size && timed[run + 1] - timed[run] <= maxUncertainRun + 1) run++
                    val lower = timed[first]
                    val upper = timed[run]
                    val from = times[index][lower]!!.start
                    val to = times[index][upper]!!.end
                    // Trim unsupported edges and split long internal holes. A missing passage
                    // must neither acquire invented timing nor discard its neighboring anchors.
                    if ((run - first + 1).toDouble() / (upper - lower + 1) >= 0.7 &&
                        to > from && to - from <= 1.0 + (upper - lower + 1) * 0.7) {
                        matches += SasayakiMatch(
                            id = "${chapter.source.index}-$lower", startTime = from, endTime = to,
                            text = String(chapter.source.text, lower, upper - lower + 1),
                            chapterIndex = chapter.source.index, start = lower, length = upper - lower + 1,
                        )
                    }
                    run++
                }
                if (matches.size == priorCount) unmatched++
                start = end + 1
            }
        }
        return SasayakiMatchData(matches.sortedBy { it.startTime }, unmatched, SasayakiMatchSource.Transcription)
    }
}
