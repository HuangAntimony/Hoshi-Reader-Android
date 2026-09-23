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

    private data class Timing(val start: Double, val end: Double, val tokenEnd: Boolean = true, val speechCharacters: Double = 1.0)
    private data class Speech(val text: IntArray, val times: List<Timing>, val lastStart: Double = 0.0)
    private data class Chapter(
        val source: SasayakiSource.Chapter,
        val projections: List<SasayakiSource.Projection>,
        val boundaries: BooleanArray,
        val sentences: BooleanArray,
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
    // A negative coordinate is a deletion/insertion, not a neighboring token's time.
    private data class Pairing(val written: Int, val spoken: Int, val exact: Boolean)
    private data class Repair(
        val projection: SasayakiSource.Projection,
        val offset: Int,
        val pairs: List<Pairing>,
    )

    private data class Window(val lower: Int, val upper: Int, val from: Int, val to: Int)

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
        private var chapterGaps = emptyMap<Pair<Anchor, Anchor>, List<Timing?>>()
        private data class Gap(val chapter: Int, val window: Window, val context: Window)

        init {
            var offset = 0
            chapters = SasayakiSource.chapters(book).map { source ->
                Chapter(source, SasayakiSource.projections(source), SasayakiSource.boundaries(source),
                    SasayakiSource.boundaries(source, splitAtCommas = false), offset)
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
                chapterGaps = emptyMap()
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
            val nextChapterGaps = HashMap<Pair<Anchor, Anchor>, List<Timing?>>()
            anchors.zipWithNext().forEach { (left, right) ->
                if (left.chapter != right.chapter) {
                    val count = right.globalStart - left.globalEnd
                    if (count !in 1..maxGap || right.spoken - left.speechEnd !in 1..maxGap) return@forEach
                    val key = left to right
                    val repaired = chapterGaps[key] ?: repairChapterGap(chapters, left, right, speech)
                    nextChapterGaps[key] = repaired
                    for (index in left.chapter..right.chapter) {
                        val chapter = chapters[index]
                        val start = max(left.globalEnd, chapter.globalStart)
                        val end = min(right.globalStart, chapter.globalStart + chapter.source.text.size)
                        for (point in start until end) times[index][point - chapter.globalStart] = repaired[point - left.globalEnd]
                    }
                    return@forEach
                }
                val window = Window(left.sourceEnd, right.sourceStart, left.speechEnd, right.spoken)
                if (window.upper - window.lower !in 1..maxGap || window.to - window.from !in 0..maxGap) return@forEach
                val gap = Gap(left.chapter, window, sentenceContext(chapters[left.chapter], left, right, window))
                val cached = gaps[gap]
                if (cached != null) {
                    cached.forEachIndexed { position, time -> times[gap.chapter][window.lower + position] = time }
                    nextGaps[gap] = cached
                } else {
                    repairGap(chapters[gap.chapter], window, gap.context, speech, times[gap.chapter])
                    nextGaps[gap] = times[gap.chapter].slice(window.lower until window.upper)
                }
            }
            // A formerly unmatched gap is retried only when its neighboring anchors change.
            gaps = nextGaps
            chapterGaps = nextChapterGaps
            return cut(chapters, times)
        }
    }

    /** EPUB file boundaries do not end the audio. Reuse the bounded repair, retaining cue boundaries. */
    private fun repairChapterGap(chapters: List<Chapter>, left: Anchor, right: Anchor, speech: Speech): List<Timing?> {
        val parts = chapters.subList(left.chapter, right.chapter + 1)
        val base = parts.first().globalStart
        val rightOffset = parts.last().globalStart - base
        val projections = (0 until parts.maxOf { it.projections.size }).map { track ->
            SasayakiSource.Projection(
                parts.flatMap { it.projections[min(track, it.projections.lastIndex)].text.asList() }.toIntArray(),
                parts.flatMap { c -> c.projections[min(track, c.projections.lastIndex)].starts.map { it + c.globalStart - base } }.toIntArray(),
                parts.flatMap { c -> c.projections[min(track, c.projections.lastIndex)].ends.map { it + c.globalStart - base } }.toIntArray(),
            )
        }
        val joined = Chapter(
            SasayakiSource.Chapter(parts.first().source.index, "", parts.flatMap { it.source.text.asList() }.toIntArray()),
            projections, parts.flatMap { it.boundaries.asList() }.toBooleanArray(),
            parts.flatMap { it.sentences.asList() }.toBooleanArray(), base,
        )
        val after = right.copy(
            written = right.written + parts.dropLast(1).sumOf { it.projections[min(right.projection, it.projections.lastIndex)].text.size },
            sourceStart = right.sourceStart + rightOffset, sourceEnd = right.sourceEnd + rightOffset,
        )
        val gap = Window(left.sourceEnd, after.sourceStart, left.speechEnd, right.spoken)
        val times = arrayOfNulls<Timing>(joined.source.text.size)
        repairGap(joined, gap, sentenceContext(joined, left, after, gap), speech, times)
        return times.slice(gap.lower until gap.upper)
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
                times += Timing(token.start + length * index / points.size, token.start + length * (index + 1) / points.size, index == points.lastIndex)
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

    /** Include the neighboring anchors up to the sentence edges, preserving their token mapping. */
    private fun sentenceContext(chapter: Chapter, left: Anchor, right: Anchor, gap: Window): Window {
        var lower = gap.lower
        var upper = gap.upper
        var from = gap.from
        var to = gap.to
        val before = chapter.projections[left.projection]
        while (from > left.spoken && lower > 0 && !chapter.sentences[lower - 1] &&
            upper - lower < maxGap && to - from < maxGap) {
            from--
            lower = before.starts[left.written + from - left.spoken]
        }
        val after = chapter.projections[right.projection]
        while (to < right.speechEnd && upper > 0 && !chapter.sentences[upper - 1] &&
            upper - lower < maxGap && to - from < maxGap) {
            upper = after.ends[right.written + to - right.spoken]
            to++
        }
        return Window(lower, upper, from, to)
    }

    private fun repairGap(chapter: Chapter, gap: Window, context: Window, speech: Speech, times: Array<Timing?>) {
        val (lower, upper, speechStart, speechEnd) = gap
        val writtenCount = upper - lower
        val spokenCount = speechEnd - speechStart
        if (spokenCount == 0) {
            recoverOmittedCharacters(chapter, lower, upper, speech, speechStart, times)
            return
        }
        if (writtenCount !in 1..maxGap || spokenCount !in 1..maxGap) return
        val best = chapter.projections.mapNotNull { projection ->
            val start = projection.starts.indexOfFirst { it >= context.lower }
            val end = projection.ends.indexOfLast { it <= context.upper } + 1
            if (start < 0 || end <= start || end - start > maxGap) return@mapNotNull null
            // Work is bounded by maxGap on both axes. A long omission must not
            // prevent comparing the few recognized words that remain inside it.
            // Pin the known text/audio edges while including their context in the
            // sentence score. A free realignment could move a repeated character
            // out of its anchor and then use its time again inside the gap.
            val writtenEdges = intArrayOf(start,
                projection.starts.indexOfFirst { it >= lower }.coerceIn(start, end),
                (projection.ends.indexOfLast { it <= upper } + 1).coerceIn(start, end), end)
            // A shortened ruby reading may span both pinned edges with one symbol.
            if (writtenEdges[1] > writtenEdges[2]) return@mapNotNull null
            val spokenEdges = intArrayOf(context.from, speechStart, speechEnd, context.to)
            val pairs = (0..2).flatMap { part ->
                boundedAlignment(chapter, projection, writtenEdges[part], writtenEdges[part + 1],
                    speech.text, spokenEdges[part], spokenEdges[part + 1]).map { pair ->
                    Pairing(if (pair.written < 0) -1 else pair.written + writtenEdges[part] - start,
                        if (pair.spoken < 0) -1 else pair.spoken + spokenEdges[part] - context.from, pair.exact)
                }
            }
            val repair = Repair(projection, start, pairs)
            repair to sentenceScores(chapter, context, repair)
        }.maxByOrNull { (_, scores) ->
            // Choose the spelling track for the affected sentences, so a long
            // neighboring sentence cannot force its ruby spelling onto a name.
            (lower until upper).sumOf { scores[it - context.lower].similarity }
        }
        if (best == null) {
            recoverShortRewrite(chapter, lower, upper, speech, speechStart, speechEnd, 0.0, times)
            return
        }
        val (repair, scores) = best
        // A weak, wholly unanchored reply can accidentally share one kana with
        // the reading of the next anchored word (いいわね。私 ← わたし). Keep
        // that continuous reading together instead of splitting off its first kana.
        val lastBoundary = (lower until upper - 1).lastOrNull { chapter.boundaries[it] }
        if (lastBoundary != null && (lower == 0 || chapter.boundaries[lower - 1]) &&
            !chapter.boundaries[upper - 1] && scores[lastBoundary + 1 - context.lower].accepted) {
            val prefixPairs = repair.pairs.filter { it.written >= 0 &&
                repair.projection.starts[repair.offset + it.written] in lower..lastBoundary }
            val word = chapter.source.text.sliceArray(lastBoundary + 1 until upper)
            val spoken = speech.text.sliceArray(speechStart until speechEnd)
            val earlierEdges = listOf(lower) + (lower..lastBoundary).filter { chapter.boundaries[it] }.map { it + 1 }
            if (word.all(::isKanji) && isReadingRewrite(word, spoken) &&
                earlierEdges.zipWithNext().none { (from, to) ->
                    isReadingRewrite(chapter.source.text.sliceArray(from until to), spoken)
                } &&
                prefixPairs.count { it.exact } <= 1 &&
                (lower..lastBoundary).all { !scores[it - context.lower].accepted } &&
                (speechStart until speechEnd - 1).all { speech.times[it + 1].start - speech.times[it].end < .1 }) {
                val score = scores[lastBoundary + 1 - context.lower]
                recoverShortRewrite(chapter, lastBoundary + 1, upper, speech, speechStart, speechEnd,
                    score.similarity, times, score.accepted)
                return
            }
        }
        // Keep exact islands fixed and assign only the tokens inside each error block.
        // In particular a DP deletion must not give an omitted reply a neighbor's time.
        var cursor = 0
        var spoken = context.from
        while (cursor < repair.pairs.size) {
            val first = cursor
            val exact = repair.pairs[cursor].exact
            while (cursor < repair.pairs.size && repair.pairs[cursor].exact == exact) cursor++
            val block = repair.pairs.subList(first, cursor)
            val written = block.filter { it.written >= 0 &&
                repair.projection.starts[repair.offset + it.written] >= lower &&
                repair.projection.ends[repair.offset + it.written] <= upper }
            val speechTo = block.lastOrNull { it.spoken >= 0 }?.let { context.from + it.spoken + 1 } ?: spoken
            if (exact) {
                for (pair in written) {
                    val point = repair.projection.starts[repair.offset + pair.written]
                    val token = context.from + pair.spoken
                    // Even a partly omitted sentence keeps the words actually
                    // recognized here; isolated particles in unrelated speech
                    // still need evidence from their own sentence.
                    if (token in speechStart until speechEnd &&
                        (scores[point - context.lower].similarity >= 0.25 || block.size >= 4 ||
                            block.size >= 3 && block.count { isKanji(speech.text[context.from + it.spoken]) } >= 2)) {
                        assign(times, repair.projection, repair.offset + pair.written, speech.times[token])
                    }
                }
            } else if (written.isNotEmpty()) {
                val from = repair.projection.starts[repair.offset + written.first().written]
                val to = repair.projection.ends[repair.offset + written.last().written]
                val tokenFrom = spoken.coerceAtLeast(speechStart)
                val tokenTo = speechTo.coerceAtMost(speechEnd)
                val score = scores[from - context.lower]
                val hasSentenceEvidence = score.similarity >= 0.25
                if (tokenTo == tokenFrom) {
                    if (hasSentenceEvidence) recoverOmittedCharacters(chapter, from, to, speech, tokenFrom, times)
                } else if (tokenTo > tokenFrom) {
                    // Weak sentence evidence cannot become plausible just by splitting
                    // off a kana/kanji fragment. Keep the original whole-gap reading
                    // fallback, but require sentence evidence for inferred fragments.
                    val wholeGap = from == lower && to == upper && tokenFrom == speechStart && tokenTo == speechEnd
                    val sameSentence = (from until to - 1).none { chapter.sentences[it] }
                    val wholeCue = (from == 0 || chapter.boundaries[from - 1]) && chapter.boundaries[to - 1] &&
                        (from until to - 1).none { chapter.boundaries[it] }
                    // A wholly rewritten reply needs its own separated speech, not
                    // an extra suffix inside an adjacent sentence's token interval.
                    val isolatedSpeech = tokenFrom > 0 && tokenTo < speech.times.size &&
                        speech.times[tokenFrom].start - speech.times[tokenFrom - 1].end >= 0.01 &&
                        speech.times[tokenTo].start - speech.times[tokenTo - 1].end >= 0.01
                    if (hasSentenceEvidence || wholeGap || wholeCue && isolatedSpeech) recoverShortRewrite(chapter, from, to, speech, tokenFrom, tokenTo,
                        if (sameSentence) score.similarity else 0.0, times,
                        supported = sameSentence && score.accepted,
                        evidence = { point -> scores[point - context.lower] })
                }
            }
            spoken = speechTo
        }
    }

    private data class SentenceScore(val similarity: Double, val accepted: Boolean)

    /** Score each sentence with its exact context; an omitted neighbor contributes no penalty. */
    private fun sentenceScores(chapter: Chapter, window: Window, repair: Repair): List<SentenceScore> {
        val sentenceAt = IntArray(window.upper - window.lower)
        var count = 0
        for (point in sentenceAt.indices) {
            sentenceAt[point] = count
            if (chapter.sentences[window.lower + point]) count++
        }
        val written = IntArray(count + 1)
        val spoken = IntArray(count + 1)
        val edits = IntArray(count + 1)
        val longest = IntArray(count + 1)
        var sentence = sentenceAt.last()
        var run = 0
        for (pair in repair.pairs.asReversed()) {
            if (pair.written >= 0) {
                val next = sentenceAt[repair.projection.starts[repair.offset + pair.written] - window.lower]
                if (next != sentence) run = 0
                sentence = next
                written[sentence]++
            }
            if (pair.spoken >= 0) spoken[sentence]++
            if (!pair.exact) edits[sentence]++
            run = if (pair.exact) run + 1 else 0
            longest[sentence] = max(longest[sentence], run)
        }
        val scores = written.indices.map { index ->
            val similarity = 1.0 - edits[index].toDouble() / max(written[index], spoken[index]).coerceAtLeast(1)
            val accepted = similarity >= minimumSimilarity ||
                (written[index] <= 48 && spoken[index] <= 48 && similarity >= 0.45 && longest[index] >= 4)
            SentenceScore(similarity, accepted)
        }
        return sentenceAt.map { scores[it] }
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
        if (to < from || to - from > min(2.5, 1.0 + count * 0.7)) return
        if (to == from) {
            // A contracted word can have no silence between its remaining letters.
            // Borrow within its cue only; the whole-cue/boundary checks still apply.
            val neighbor = if (chapter.boundaries[lower - 1]) speechOffset else speechOffset - 1
            repeat(count) { assign(times, lower + it, speech.times[neighbor]) }
            return
        }
        repeat(count) { index ->
            assign(times, lower + index,
                Timing(from + (to - from) * index / count, from + (to - from) * (index + 1) / count))
        }
    }

    /** Fushi-style proportional recovery, restricted to short gaps with two real anchors. */
    private fun recoverShortRewrite(
        chapter: Chapter, lower: Int, upper: Int,
        speech: Speech, speechStart: Int, speechEnd: Int,
        similarity: Double, times: Array<Timing?>, supported: Boolean = false,
        evidence: (Int) -> SentenceScore = { SentenceScore(similarity, supported) },
    ) {
        val boundaries = (lower until upper - 1).filter { chapter.boundaries[it] }
        val prefix = lower > 0 && !chapter.boundaries[lower - 1]
        val suffix = !chapter.boundaries[upper - 1]
        // A comma can divide two short spelling changes inside one supported
        // sentence. Both cues must already have recognized text, with no whole
        // unspoken cue between them and enough tokens to give each edge its own time.
        // Script compatibility prevents incidental exact particles from making an
        // otherwise unrelated phrase eligible for recovery across a comma.
        val bridgesComma = similarity >= 0.25 && boundaries.size == 1 && !chapter.sentences[boundaries.single()] &&
            prefix && suffix && upper - lower <= maxUncertainRun && speechEnd - speechStart >= upper - lower &&
            isReadingRewrite(chapter.source.text.sliceArray(lower until upper),
                speech.text.sliceArray(speechStart until speechEnd))
        if (boundaries.isNotEmpty() && !bridgesComma) {
            val edges = listOf(lower) + boundaries.map { it + 1 } + upper
            val spoken = speech.text.sliceArray(speechStart until speechEnd)
            // Compare each cue independently. An omitted cue must not poison a
            // neighboring reading, but competing plausible readings remain ambiguous.
            val candidates = edges.zipWithNext().filter { (from, to) ->
                val written = chapter.source.text.sliceArray(from until to)
                val wholeCue = (from == 0 || chapter.boundaries[from - 1]) && chapter.boundaries[to - 1]
                if (wholeCue && max(written.size, spoken.size) < 2) return@filter false
                val edits = editAlignment(written, 0, written.size, spoken, 0, spoken.size).second
                isReadingRewrite(written, spoken) || 1.0 - edits.toDouble() / max(written.size, spoken.size) >= 0.25 ||
                    // A differently transcribed name can also own these kanji.
                    // Do not give its token to an adjacent kana interjection.
                    evidence(from).similarity >= 0.25 && written.all(::isKanji) && spoken.all(::isKanji)
            }
            val candidate = candidates.singleOrNull()
            if (candidate != null) {
                val (from, to) = candidate
                val score = evidence(from)
                val wholeCue = (from == 0 || chapter.boundaries[from - 1]) && chapter.boundaries[to - 1]
                if (wholeCue && (max(to - from, spoken.size) < 2 ||
                        speechStart > 0 && !speech.times[speechStart - 1].tokenEnd || !speech.times[speechEnd - 1].tokenEnd)) return
                val partialReading = !wholeCue && prefix != suffix &&
                    isReadingRewrite(chapter.source.text.sliceArray(from until to), spoken)
                if (score.similarity >= 0.25 || partialReading) recoverShortRewrite(chapter, from, to, speech, speechStart, speechEnd,
                    score.similarity, times, score.accepted)
                return
            }
            // Two already supported sentence edges may have separate spelling
            // changes. Split only at an original token boundary and only when
            // exactly one partition is compatible; never divide a shared token.
            if (edges.size != 3 || !prefix && !suffix) return
            val middle = edges[1]
            val left = chapter.source.text.sliceArray(lower until middle)
            val right = chapter.source.text.sliceArray(middle until upper)
            // With an entirely rewritten cue, script lengths alone cannot split
            // one kana reading between two kanji words (最初、盛大 ← さいしょ).
            if ((!prefix || !suffix) && left.all(::isKanji) && right.all(::isKanji) &&
                spoken.none(::isKanji)) return
            fun compatible(written: IntArray, from: Int, to: Int, score: SentenceScore): Boolean {
                if (score.similarity < 0.25) return false
                val spoken = speech.text.sliceArray(from until to)
                if (max(written.size, spoken.size) < 2) return false
                return isReadingRewrite(written, spoken) || score.accepted &&
                    written.all(::isKanji) && spoken.all(::isKanji) && written.size == spoken.size
            }
            val split = (speechStart + 1 until speechEnd).filter { point ->
                speech.times[point - 1].tokenEnd &&
                    compatible(left, speechStart, point, evidence(lower)) &&
                    compatible(right, point, speechEnd, evidence(middle))
            }.singleOrNull() ?: return
            recoverShortRewrite(chapter, lower, middle, speech, speechStart, split,
                evidence(lower).similarity, times, evidence(lower).accepted)
            recoverShortRewrite(chapter, middle, upper, speech, split, speechEnd,
                evidence(middle).similarity, times, evidence(middle).accepted)
            return
        }
        val writtenCount = upper - lower
        val spokenCount = speechEnd - speechStart
        if (writtenCount !in 1..24 || spokenCount !in 1..48) return
        val duration = speech.times[speechEnd - 1].end - speech.times[speechStart].start
        val written = chapter.source.text.sliceArray(lower until upper)
        val spoken = speech.text.sliceArray(speechStart until speechEnd)
        val readingRewrite = isReadingRewrite(written, spoken)
        val durationCharacters = if (readingRewrite) max(writtenCount, spokenCount) else writtenCount
        if (duration <= 0 || duration > 1.0 + durationCharacters * 0.7) return
        val ratio = writtenCount.toDouble() / spokenCount
        val shortSupportedRewrite = supported && writtenCount <= maxUncertainRun
        if (ratio !in 0.5..2.0 && !((readingRewrite || shortSupportedRewrite) && ratio in 0.25..4.0)) return
        // Entirely unrelated phrases cannot be recovered from duration/length alone.
        if (!supported && similarity < 0.25 && !readingRewrite) return
        for (index in 0 until writtenCount) {
            val first = speechStart + index * spokenCount / writtenCount
            // At a cue edge, round toward the next cue's first token; sharing the
            // fractional token here would make the two cue timestamps overlap.
            val rounding = if (bridgesComma && chapter.boundaries[lower + index]) 0 else writtenCount - 1
            val end = speechStart + ((index + 1) * spokenCount + rounding) / writtenCount
            assign(times, lower + index, Timing(speech.times[first].start, speech.times[end - 1].end,
                speechCharacters = durationCharacters.toDouble() / writtenCount))
        }
    }

    private fun isReadingRewrite(written: IntArray, spoken: IntArray): Boolean {
        fun kana(point: Int) = point in 0x3041..0x30FA || point == 0x30FC
        fun kanji(point: Int) = Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN
        fun compatible(reading: IntArray, mixed: IntArray): Boolean {
            if (!reading.all(::kana) || !mixed.any(::kanji) || !mixed.all { kana(it) || kanji(it) }) return false
            // A truncated stutter or a single kana cannot stand for several kanji.
            if (reading.size < mixed.count(::kanji) || reading.lastOrNull() == 'っ'.code || reading.all { it == 'ー'.code }) return false
            // Kana already present in the written phrase must survive the reading.
            // Otherwise any omitted mixed-script sentence could veto a real prefix.
            var cursor = 0
            for (point in mixed.filter(::kana)) {
                while (cursor < reading.size && reading[cursor] != point) cursor++
                if (cursor == reading.size) return false
                cursor++
            }
            return true
        }
        return compatible(written, spoken) || compatible(spoken, written)
    }

    private fun isKanji(point: Int): Boolean = Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN

    private fun isSingleKanjiCue(chapter: Chapter, projection: SasayakiSource.Projection, point: Int): Boolean {
        val start = projection.starts[point]
        return projection.ends[point] == start + 1 && isKanji(projection.text[point]) &&
            (start == 0 || chapter.boundaries[start - 1]) && chapter.boundaries[start]
    }

    /** Preserve a unique, exact short reply at a pinned edge before aligning a long omission. */
    private fun boundedAlignment(
        chapter: Chapter, projection: SasayakiSource.Projection, from: Int, to: Int,
        spoken: IntArray, speechFrom: Int, speechTo: Int,
    ): List<Pairing> {
        val pins = mutableListOf<Pair<Int, Int>>()
        for (written in from until to) {
            // Only short cue endings immediately beside a real anchor may become
            // local pins. A repeated particle deeper in a missing passage cannot.
            val length = when {
                isSingleKanjiCue(chapter, projection, written) -> 1
                written + 1 < to && chapter.boundaries[projection.ends[written + 1] - 1] -> 2
                else -> continue
            }
            if (written - from > 1 && to - written - length > 1) continue
            fun equal(text: IntArray, position: Int): Boolean =
                (0 until length).all { text[position + it] == projection.text[written + it] }
            if ((from..to - length).count { equal(projection.text, it) } != 1) continue
            val token = (speechFrom..speechTo - length).filter { equal(spoken, it) }.singleOrNull() ?: continue
            if (!(written - from <= 1 && token - speechFrom <= 1 ||
                    to - written - length <= 1 && speechTo - token - length <= 1)) continue
            repeat(length) { pins += written + it to token + it }
        }
        val distinct = pins.distinct().sortedBy { it.first }
        if (distinct.zipWithNext().any { (a, b) -> a.first >= b.first || a.second >= b.second })
            return editAlignment(projection.text, from, to, spoken, speechFrom, speechTo).first
        val result = mutableListOf<Pairing>()
        var lower = from
        var first = speechFrom
        for ((upper, last) in distinct + (to to speechTo)) {
            result += editAlignment(projection.text, lower, upper, spoken, first, last).first.map {
                Pairing(if (it.written < 0) -1 else it.written + lower - from,
                    if (it.spoken < 0) -1 else it.spoken + first - speechFrom, it.exact)
            }
            if (upper < to) result += Pairing(upper - from, last - speechFrom, true)
            lower = upper + 1
            first = last + 1
        }
        return result
    }

    private fun editAlignment(
        written: IntArray, from: Int, to: Int,
        spoken: IntArray, speechFrom: Int, speechTo: Int,
    ): Pair<List<Pairing>, Int> {
        val rows = to - from
        val cols = speechTo - speechFrom
        val width = cols + 1
        val costs = IntArray((rows + 1) * width)
        // Among equal edit distances prefer the path retaining more exact letters.
        // This keeps particles in place across length-changing kana/kanji rewrites.
        val unit = max(rows, cols) + 1
        for (row in 0..rows) costs[row * width] = row * unit
        for (col in 0..cols) costs[col] = col * unit
        for (row in 1..rows) {
            for (col in 1..cols) {
                val difference = if (sameSymbol(written[from + row - 1], spoken[speechFrom + col - 1])) -1 else unit
                costs[row * width + col] = min(costs[(row - 1) * width + col - 1] + difference,
                    min(costs[(row - 1) * width + col] + unit, costs[row * width + col - 1] + unit))
            }
        }
        var row = rows
        var col = cols
        val pairs = mutableListOf<Pairing>()
        while (row > 0 && col > 0) {
            val exact = sameSymbol(written[from + row - 1], spoken[speechFrom + col - 1])
            if (costs[row * width + col] == costs[(row - 1) * width + col - 1] + if (exact) -1 else unit) {
                pairs += Pairing(row - 1, col - 1, exact)
                row--
                col--
            } else if (costs[row * width + col] == costs[(row - 1) * width + col] + unit) {
                pairs += Pairing(row - 1, -1, false)
                row--
            } else {
                pairs += Pairing(-1, col - 1, false)
                col--
            }
        }
        while (row > 0) pairs += Pairing(--row, -1, false)
        while (col > 0) pairs += Pairing(-1, --col, false)
        return pairs.asReversed() to ((costs[rows * width + cols] + max(rows, cols)) / unit)
    }

    // Only compare individual digits here. Keep the original text, comma boundaries,
    // and each token's time; do not parse a spoken number into a new synthetic token.
    private fun sameSymbol(written: Int, spoken: Int): Boolean {
        if (written == spoken) return true
        // Small vowel spellings express the same elongated interjection. Do not
        // fold small tsu/ya/yu/yo, which would erase phonetic distinctions.
        fun vowel(point: Int): Int = when (point) {
            'ぁ'.code, 'ぃ'.code, 'ぅ'.code, 'ぇ'.code, 'ぉ'.code -> point + 1
            else -> point
        }
        if (vowel(written) == vowel(spoken)) return true
        val (digit, kanji) = when {
            written in '0'.code..'9'.code -> written - '0'.code to spoken
            spoken in '0'.code..'9'.code -> spoken - '0'.code to written
            else -> return false
        }
        return kanji == "〇一二三四五六七八九"[digit].code || digit == 0 && kanji == '零'.code
    }

    private fun assign(times: Array<Timing?>, projection: SasayakiSource.Projection, index: Int, time: Timing) {
        for (point in projection.starts[index] until projection.ends[index]) {
            assign(times, point, time)
        }
    }

    private fun assign(times: Array<Timing?>, point: Int, time: Timing) {
        val existing = times[point]
        times[point] = if (existing == null) time else Timing(min(existing.start, time.start), max(existing.end, time.end),
            speechCharacters = max(existing.speechCharacters, time.speechCharacters))
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
                fun emit(first: Int, last: Int) {
                    val lower = timed[first]
                    val upper = timed[last]
                    // Recheck every fragment, including those split around a bad
                    // timestamp: their density can be lower than the original cue.
                    if ((last - first + 1).toDouble() / (upper - lower + 1) < 0.7) {
                        var fragment = first
                        while (fragment <= last) {
                            val begin = fragment
                            while (fragment < last && timed[fragment + 1] == timed[fragment] + 1) fragment++
                            emit(begin, fragment)
                            fragment++
                        }
                        return
                    }
                    val from = times[index][lower]!!.start
                    val to = times[index][upper]!!.end
                    val characters = max((upper - lower + 1).toDouble(),
                        (first..last).sumOf { times[index][timed[it]]!!.speechCharacters })
                    if (to > from && to - from <= 1.0 + characters * 0.7) {
                        matches += SasayakiMatch(
                            id = "${chapter.source.index}-$lower", startTime = from, endTime = to,
                            text = String(chapter.source.text, lower, upper - lower + 1),
                            chapterIndex = chapter.source.index, start = lower, length = upper - lower + 1,
                        )
                    } else {
                        // An abnormal token cannot erase the reliably timed rest
                        // of the cue. Keep complete short runs; never invent a new
                        // endpoint by clipping a long token to the duration limit.
                        var fragment = first
                        for (position in first..last + 1) {
                            val time = if (position <= last) times[index][timed[position]] else null
                            if (time != null && time.end - time.start <= 1.0 + max(1.0, time.speechCharacters) * 0.7) continue
                            if (fragment < position && (fragment > first || position <= last)) emit(fragment, position - 1)
                            fragment = position + 1
                        }
                    }
                }
                var run = 0
                while (run < timed.size) {
                    val first = run
                    while (run + 1 < timed.size && timed[run + 1] - timed[run] <= maxUncertainRun + 1) run++
                    emit(first, run)
                    run++
                }
                if (matches.size == priorCount) unmatched++
                start = end + 1
            }
        }
        return SasayakiMatchData(matches.sortedBy { it.startTime }, unmatched, SasayakiMatchSource.Transcription)
    }
}
