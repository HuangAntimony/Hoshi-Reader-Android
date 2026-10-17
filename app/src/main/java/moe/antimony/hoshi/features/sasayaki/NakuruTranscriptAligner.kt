package moe.antimony.hoshi.features.sasayaki

import java.text.Normalizer
import java.util.Locale
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiMatchSource
import moe.antimony.hoshi.epub.isReaderMatchableCodePoint
import moe.antimony.hoshi.epub.visibleReaderText

internal class NakuruTranscriptAligner(book: EpubBook) {
    private data class Chapter(val index: Int, val start: Int, val length: Int) {
        val end get() = start + length
    }
    private data class Timing(val start: Double, val end: Double)
    private data class Anchor(val speech: Int, val book: Int)
    private data class Span(val from: Double, val to: Double, val lower: Int, val upper: Int)
    private val text = mutableListOf<Int>()
    private val sentenceEnds = mutableListOf<Boolean>()
    private val segmentEnds = mutableListOf<Boolean>()
    private val chapters = SasayakiSource.chapters(book).map { chapter ->
        val body = Regex("(?s)<body.*?</body>").find(chapter.html)?.value ?: chapter.html
        val visible = body.replace(blocks, "\u0001").visibleReaderText().codePointsArray()
        val positions = visible.indices.filter { visible[it].isReaderMatchableCodePoint() }
        val start = text.size
        positions.forEachIndexed { index, position ->
            val end = positions.getOrNull(index + 1) ?: visible.size
            val gap = (position + 1 until end).map { visible[it] }
            val sentence = gap.any { it in sentenceEnders || it == 1 }
            text += visible[position]
            sentenceEnds += sentence
            segmentEnds += !sentence && gap.any { it in segmentBreaks }
        }
        Chapter(chapter.index, start, text.size - start)
    }
    private val normalized = text.map(::normalize)

    fun align(tokens: List<SasayakiToken>): SasayakiMatchData {
        val speech = mutableListOf<Int>()
        val timings = mutableListOf<Timing>()
        tokens.forEach { token ->
            token.text.codePoints().filter { it.isReaderMatchableCodePoint() }.forEach {
                speech += normalize(it)
                timings += Timing(token.start, token.end)
            }
        }
        val times = arrayOfNulls<Timing>(text.size)
        val anchors = mutableListOf(Anchor(0, 0))
        chapters.firstOrNull { it.length >= 12 }?.let { chapter ->
            find(speech, normalized.subList(chapter.start, chapter.start + 12), 0, speech.size)?.let {
                anchors[0] = Anchor(it, chapter.start)
            }
        }
        var cursor = 0
        var hit = 0
        for (index in 0..speech.size - 12 step 100) {
            val probe = speech.subList(index, index + 12)
            val limit = minOf(normalized.size, cursor + 2012 + index - hit)
            val found = find(normalized, probe, cursor, limit) ?: continue
            if (find(normalized, probe, found + 1, limit) != null) continue
            cursor = found + 12
            hit = index
            val previous = anchors.last()
            if (index > previous.speech && found > previous.book) anchors += Anchor(index, found)
        }
        chapters.filter { it.length >= 12 }.forEach { chapter ->
            val probe = normalized.subList(chapter.start, chapter.start + 12)
            val after = anchors.indexOfFirst { it.book > chapter.start }.let { if (it < 0) anchors.size else it }
            if (anchors[after - 1].book == chapter.start) return@forEach
            val low = anchors[after - 1].speech + 1
            val high = anchors.getOrNull(after)?.speech ?: speech.size
            val found = find(speech, probe, low, high) ?: return@forEach
            if (find(speech, probe, found + 1, high) != null) return@forEach
            anchors.add(after, Anchor(found, chapter.start))
        }
        anchors += Anchor(speech.size, text.size)
        anchors.zipWithNext().forEach { (anchor, next) ->
            if (next.speech > anchor.speech && next.book > anchor.book) {
                val spoken = speech.subList(anchor.speech, minOf(next.speech, anchor.speech + 2000))
                val written = normalized.subList(anchor.book, minOf(next.book, anchor.book + 2000))
                val (removed, inserted) = difference(spoken, written)
                var offset = 0
                removed.forEachIndexed { index, deleted ->
                    if (!deleted) while (offset < inserted.size && inserted[offset]) offset++
                    val target = anchor.book + minOf(offset, written.lastIndex)
                    if (times[target] == null) times[target] = timings[anchor.speech + index]
                    if (!deleted || (offset < inserted.size && inserted[offset])) offset++
                }
            }
        }
        return cut(times)
    }

    private fun cut(times: Array<Timing?>): SasayakiMatchData {
        val matches = mutableListOf<SasayakiMatch>()
        var unmatched = 0
        chapters.forEach { chapter ->
            var start = chapter.start
            for (index in chapter.start until chapter.end) {
                if (!sentenceEnds[index] && index != chapter.end - 1 &&
                    !(segmentEnds[index] && index > start && index + 2 < chapter.end && !sentenceEnds[index + 1])) continue
                val span = time(start, index, chapter, times, matches.lastOrNull()?.endTime)
                if (span == null) unmatched++ else matches += SasayakiMatch(
                    "${chapter.index}-${span.lower - chapter.start}", span.from, span.to,
                    String(text.subList(span.lower, span.upper + 1).toIntArray(), 0, span.upper - span.lower + 1),
                    chapter.index, span.lower - chapter.start, span.upper - span.lower + 1,
                )
                start = index + 1
            }
        }
        matches.sortBy { it.startTime }
        return SasayakiMatchData(matches.mapIndexed { index, match ->
            match.copy(endTime = maxOf(match.endTime, minOf(match.endTime + .3, matches.getOrNull(index + 1)?.startTime ?: Double.POSITIVE_INFINITY)))
        }, unmatched, SasayakiMatchSource.Transcription)
    }

    private fun time(start: Int, index: Int, chapter: Chapter, times: Array<Timing?>, previous: Double?): Span? {
        val timed = (start..index).filter { times[it] != null }
        if (timed.isEmpty()) return interpolate(start, index, chapter, times, previous)
        val first = timed.first()
        val last = timed.last()
        val firstTiming = times[first]!!
        val to = times[last]!!.end
        val lower = if (first - start <= 12) start else first
        val upper = if (index - last <= 12) index else last
        val from = (if (timed.size >= 2 && times[timed[1]]!!.start - firstTiming.end > .5) {
            times[timed[1]]!!.start
        } else maxOf(firstTiming.start, firstTiming.end - .15)) - .2
        if (!withinBudget(from, to, upper - lower + 1)) return trim(start, index, timed, times) ?: interpolate(start, index, chapter, times, previous)
        return Span(from, to, lower, upper)
    }

    private fun trim(start: Int, index: Int, timed: List<Int>, times: Array<Timing?>): Span? {
        fun at(position: Int) = times[timed[position]]!!
        var first = 0
        var last = timed.lastIndex
        while (first < last && at(first + 1).start - at(first).end > .5) first++
        while (last > first && at(last).start - at(last - 1).end > .5) last--
        val lower = if (timed[first] - start <= 12) start else timed[first]
        val upper = if (index - timed[last] <= 12) index else timed[last]
        val from = maxOf(at(first).start, at(first).end - .15) - .2
        val to = at(last).end
        return if (withinBudget(from, to, upper - lower + 1)) Span(from, to, lower, upper) else null
    }

    private fun interpolate(start: Int, index: Int, chapter: Chapter, times: Array<Timing?>, previous: Double?): Span? {
        val length = index - start + 1
        if (length > 8 || start <= chapter.start || index + 1 >= chapter.end) return null
        val previousEnd = times[start - 1]?.end ?: return null
        val nextStart = times[index + 1]?.start ?: return null
        if (nextStart <= previousEnd || nextStart - previousEnd > 2.5) return null
        var from = minOf(previousEnd + .3, nextStart)
        if (previous != null) from = maxOf(from, previous)
        return if (withinBudget(from, nextStart, length)) Span(from, nextStart, start, index) else null
    }

    private fun withinBudget(from: Double, to: Double, span: Int) = to > from && to - from <= 1.0 + .7 * span

    private fun normalize(point: Int): Int {
        val scalar = Normalizer.normalize(String(Character.toChars(point)), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).codePointAt(0)
        return if (scalar in 0x30A1..0x30F6) scalar - 0x60 else scalar
    }

    private fun find(source: List<Int>, probe: List<Int>, start: Int, end: Int): Int? =
        (start..end - probe.size).firstOrNull { index -> probe.indices.all { source[index + it] == probe[it] } }

    private fun difference(old: List<Int>, new: List<Int>): Pair<BooleanArray, BooleanArray> {
        fun slot(diagonal: Int) = if (diagonal <= 0) -diagonal else diagonal - 1
        val trace = mutableListOf<IntArray>()
        var frontier = IntArray(2)
        search@ for (distance in 0..old.size + new.size) {
            trace += frontier
            frontier = IntArray(distance + 1)
            val previous = trace.last()
            for (diagonal in -distance..distance step 2) {
                var x = if (diagonal == -distance || (diagonal != distance && previous[slot(diagonal - 1)] < previous[slot(diagonal + 1)])) {
                    previous[slot(diagonal + 1)]
                } else previous[slot(diagonal - 1)] + 1
                var y = x - diagonal
                while (x < old.size && y < new.size && old[x] == new[y]) { x++; y++ }
                frontier[slot(diagonal)] = x
                if (x >= old.size && y >= new.size) break@search
            }
        }
        val removed = BooleanArray(old.size)
        val inserted = BooleanArray(new.size)
        var x = old.size
        var y = new.size
        for (distance in trace.lastIndex downTo 1) {
            val previous = trace[distance]
            val diagonal = x - y
            val preceding = if (diagonal == -distance || (diagonal != distance && previous[slot(diagonal - 1)] < previous[slot(diagonal + 1)])) diagonal + 1 else diagonal - 1
            val fromX = previous[slot(preceding)]
            val fromY = fromX - preceding
            while (x > fromX && y > fromY) { x--; y-- }
            if (y != fromY) inserted[fromY] = true else removed[fromX] = true
            x = fromX
            y = fromY
        }
        return removed to inserted
    }

    companion object {
        private val blocks = Regex("(?i)</?(?:p|div|h[1-6]|li|ul|ol|blockquote|section|article|table|tr|td|th|hr|br|img|image)\\b[^>]*>")
        private val sentenceEnders = "。！？!?…」』「『（".codePointsArray().toSet()
        private val segmentBreaks = "、,，─".codePointsArray().toSet()
    }
}
