package moe.antimony.hoshi.features.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.features.tts.TtsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderSasayakiCuesTest {
    @Test
    fun ttsChapterCuesUseTheSameSourceOffsetsAndIdsAsPlaybackIncludingRuby() {
        val sentences = TtsText.chapter(3, "<p>前半。<ruby>漢字<rt>かんじ</rt></ruby>の続き。</p><p>最後。</p>")
        val cues = Json.parseToJsonElement(ReaderSasayakiCues.ttsChapterCuesJson(sentences)).jsonArray
        assertEquals(sentences.size, cues.size)
        sentences.zip(cues).forEach { (sentence, value) ->
            val cue = ReaderSasayakiCues.ttsCue(sentence)
            val serialized = value.jsonObject
            assertEquals("tts-3-${sentence.start}", serialized.getValue("id").jsonPrimitive.content)
            assertEquals(cue.start.toString(), serialized.getValue("start").jsonPrimitive.content)
            assertEquals(cue.length.toString(), serialized.getValue("length").jsonPrimitive.content)
        }
        assertEquals("[]", ReaderSasayakiCues.ttsChapterCuesJson(emptyList()))
    }

    @Test
    fun chapterCuesSerializesOnlyCurrentChapterCuesSortedByStart() {
        val matchData = SasayakiMatchData(
            matches = listOf(
                match(id = "later", chapterIndex = 1, start = 20, length = 3),
                match(id = "other", chapterIndex = 0, start = 1, length = 4),
                match(id = "first", chapterIndex = 1, start = 10, length = 2),
            ),
            unmatched = 0,
        )

        val json = ReaderSasayakiCues.chapterCuesJson(matchData, chapterIndex = 1)
        val cues = Json.parseToJsonElement(requireNotNull(json)).jsonArray

        assertEquals(listOf("first", "later"), cues.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(listOf("10", "20"), cues.map { it.jsonObject.getValue("start").jsonPrimitive.content })
        assertEquals(listOf("2", "3"), cues.map { it.jsonObject.getValue("length").jsonPrimitive.content })
    }

    @Test
    fun chapterCuesReturnsNullWhenChapterHasNoMatches() {
        val matchData = SasayakiMatchData(
            matches = listOf(match(id = "other", chapterIndex = 0, start = 1, length = 4)),
            unmatched = 0,
        )

        assertNull(ReaderSasayakiCues.chapterCuesJson(matchData, chapterIndex = 1))
    }

    private fun match(
        id: String,
        chapterIndex: Int,
        start: Int,
        length: Int,
    ): SasayakiMatch =
        SasayakiMatch(
            id = id,
            startTime = start.toDouble(),
            endTime = start.toDouble() + 1,
            text = id,
            chapterIndex = chapterIndex,
            start = start,
            length = length,
        )
}
