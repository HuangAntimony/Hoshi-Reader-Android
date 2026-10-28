package moe.antimony.hoshi.features.sasayaki

import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.SasayakiMatchData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NakuruTranscriptAlignerTest {
    @Test fun cancelledMatchingDoesNotPublishAResult() = runTest {
        val book = EpubBook(title = "Cancellation", chapters = listOf(
            EpubChapter("one", "one.xhtml", "application/xhtml+xml", "<p>雨の降る静かな朝だった。</p>"),
        ))
        val aligner = NakuruTranscriptAligner(book)
        var cancelled = false
        val task = launch {
            currentCoroutineContext().cancel()
            try {
                aligner.align(listOf(SasayakiToken("雨の降る静かな朝だった", 1.0, 4.0)))
            } catch (_: CancellationException) {
                cancelled = true
            }
        }
        task.join()
        assertTrue("Matching must observe cancellation before returning cues", cancelled)
    }

    @Test fun successivePrefixesMatchFreshAlignmentAcrossFixtureBoundaries() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val cases = javaClass.getResourceAsStream("/nakuru/aligner.json")!!.bufferedReader().use {
            json.decodeFromString<List<Case>>(it.readText())
        }
        for (case in cases) {
            val book = EpubBook(title = case.name, chapters = case.chapters.mapIndexed { index, html ->
                EpubChapter("chapter-$index", "chapter-$index.xhtml", "application/xhtml+xml", html)
            })
            val session = NakuruTranscriptAligner(book)
            for (count in (listOf(0, 1, 2, 3, case.tokens.size / 2, case.tokens.size - 1, case.tokens.size)
                .filter { it in 0..case.tokens.size }.distinct().sorted())) {
                val snapshot = case.tokens.take(count)
                assertEquals("${case.name}: prefix=$count", NakuruTranscriptAligner(book).align(snapshot), session.align(snapshot))
            }
            assertEquals(case.expected.matches.size, session.align(case.tokens).matches.size)
        }
    }

    @Serializable private data class Case(
        val name: String,
        val chapters: List<String>,
        val tokens: List<SasayakiToken>,
        val expected: SasayakiMatchData,
    )

    @Test fun matchesExpectedAlignments() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val cases = javaClass.getResourceAsStream("/nakuru/aligner.json")!!.bufferedReader().use {
            json.decodeFromString<List<Case>>(it.readText())
        }
        cases.forEach { case ->
            val book = EpubBook(title = case.name, chapters = case.chapters.mapIndexed { index, html ->
                EpubChapter("chapter-$index", "chapter-$index.xhtml", "application/xhtml+xml", html)
            })
            val result = NakuruTranscriptAligner(book).align(case.tokens)
            assertEquals(case.name, case.expected.unmatched, result.unmatched)
            assertEquals(case.name, case.expected.matches.size, result.matches.size)
            case.expected.matches.zip(result.matches).forEach { (expected, actual) ->
                assertEquals(case.name, expected.startTime, actual.startTime, 1e-9)
                assertEquals(case.name, expected.endTime, actual.endTime, 1e-9)
                assertEquals(case.name, expected.copy(startTime = actual.startTime, endTime = actual.endTime), actual)
            }
        }
    }

    @Test fun unreadPageBeforeIntroStaysUnmatched() = runTest {
        val random = Random(1)
        val page = sentences(random, 4)
        val story = sentences(random, 20)
        val speech = Speech(random)
        speech.say(kana(random, 40))
        story.forEach { speech.say(it, .03) }
        val result = NakuruTranscriptAligner(book(page, story)).align(speech.tokens)
        assertEquals(0, result.matches.count { it.chapterIndex == 0 })
        assertEquals(20, result.matches.count { it.chapterIndex == 1 })
    }

    @Test fun alignsAudioStartingAtLaterChapter() = runTest {
        val random = Random(2)
        val skipped = List(10) { sentences(random, 40) }
        val first = sentences(random, 120)
        val second = sentences(random, 40)
        val speech = Speech(random)
        speech.say(kana(random, 30))
        speech.say(first[0].replaceRange(4, 5, kana(random, 1)))
        (first.drop(1) + second).forEach { speech.say(it, .03) }
        val result = NakuruTranscriptAligner(book(*skipped.toTypedArray(), first, second)).align(speech.tokens)
        assertEquals(0, result.matches.count { it.chapterIndex < 10 })
        assertEquals(120, result.matches.count { it.chapterIndex == 10 })
        assertEquals(40, result.matches.count { it.chapterIndex == 11 })
    }

    @Test fun laterExactOpeningPreservesEarlierMatchesInLiveAndFreshAlignment() = runTest {
        val random = Random(3)
        val opening = kana(random, 24)
        val story = sentences(random, 60)
        val ending = sentences(random, 10)
        val book = book(listOf(opening), story, listOf(opening) + ending)
        val speech = Speech(random)
        speech.say(opening.replaceRange(5, 5, "あ"))
        story.forEach { speech.say(it) }
        val beforeRepeat = speech.tokens.size
        speech.say(opening)
        ending.forEach { speech.say(it) }
        val repeatTime = speech.tokens[beforeRepeat].start
        val live = NakuruTranscriptAligner(book)

        for (count in listOf(beforeRepeat, beforeRepeat + 3, speech.tokens.size)) {
            val result = live.align(speech.tokens.take(count))
            assertEquals("The earlier story must remain matched at prefix $count",
                60, result.matches.count { it.chapterIndex == 1 })
            assertTrue("The opening must retain its original audio time at prefix $count",
                result.matches.single { it.chapterIndex == 0 }.endTime < repeatTime)
        }

        // Realign after completion starts with no previous live matches to preserve.
        val fresh = NakuruTranscriptAligner(book).align(speech.tokens)
        assertEquals(60, fresh.matches.count { it.chapterIndex == 1 })
        assertTrue(fresh.matches.single { it.chapterIndex == 0 }.endTime < repeatTime)
        assertEquals(11, fresh.matches.count { it.chapterIndex == 2 })
        assertTrue(fresh.matches.first { it.chapterIndex == 2 }.startTime >= repeatTime - .5)
        assertEquals(fresh, live.align(speech.tokens))
    }

    @Test fun openingOverlappingFirstSequentialAnchorSurvivesLongNarrationLeadIn() = runTest {
        val random = Random(5)
        val story = List(11) { kana(random, 24) }
        for (repeatOpening in listOf(false, true)) {
            val paragraphs = if (repeatOpening) story + story.first() else story
            val speech = Speech(random)
            // The opening begins five characters before the next 100-character probe.
            speech.say("ん".repeat(2095))
            val openingTime = speech.tokens.last().end
            paragraphs.forEach { speech.say(it) }

            val result = NakuruTranscriptAligner(book(paragraphs)).align(speech.tokens)

            assertEquals("Repeated opening: $repeatOpening", paragraphs, result.matches.map { it.text })
            assertTrue(result.matches.first().startTime >= openingTime)
        }
    }

    private class Speech(private val random: Random) {
        val tokens = mutableListOf<SasayakiToken>()
        private var time = 0.0

        fun say(text: String, errors: Double = 0.0) {
            text.chunked(6).forEach { word ->
                val spoken = word.map { if (random.nextDouble() < errors) kana(random, 1)[0] else it }.joinToString("")
                tokens += SasayakiToken(spoken, time, time + .15 * spoken.length - .03)
                time += .15 * spoken.length
            }
            time += .4
        }
    }

    private companion object {
        fun kana(random: Random, length: Int) = String(CharArray(length) { (0x3042 + random.nextInt(0x52)).toChar() })

        fun sentences(random: Random, count: Int) = List(count) { kana(random, 12 + random.nextInt(17)) }

        fun book(vararg chapters: List<String>) = EpubBook(title = "", chapters = chapters.mapIndexed { index, sentences ->
            val html = "<body>" + sentences.joinToString("") { "<p>$it。</p>" } + "</body>"
            EpubChapter("chapter-$index", "chapter-$index.xhtml", "application/xhtml+xml", html)
        })
    }
}
