package moe.antimony.hoshi.features.tts

import org.junit.Assert.*
import org.junit.Test

class TtsClauseSegmentationTest {
    private fun split(html: String) = TtsText.chapter(0, html, splitAtClausePunctuation = true)

    @Test fun additionalPunctuationIsOptInAndEveryCueRetainsSourceOffsets() {
        for (mark in "、,，─；;") {
            val text = "前文${mark}後文。"
            assertEquals(listOf(text), TtsText.chapter(0, "<p>$text</p>").map { it.text })
            val cues = split("<p>$text</p>")
            assertEquals(mark.toString(), listOf("前文$mark", "後文。"), cues.map { it.text })
            assertEquals(listOf(0, 2), cues.map { it.start })
            assertEquals(listOf(2, 2), cues.map { it.length })
        }
    }

    @Test fun protectsShortPiecesAndImmediateSentenceEndWithoutSpecialCasingNumbers() {
        val cases = listOf(
            "一、後文。" to listOf("一、後文。"),
            "前文、後。" to listOf("前文、後。"),
            "前文、。後文。" to listOf("前文、。", "後文。"),
            "前文、、；後文。" to listOf("前文、、；", "後文。"),
            "12,345、後文。" to listOf("12,", "345、", "後文。"),
            "1,000。" to listOf("1,000。"),
            "前文、一、後文。" to listOf("前文、", "一、後文。"),
        )
        for ((text, expected) in cases) assertEquals(text, expected, split("<p>$text</p>").map { it.text })
    }

    @Test fun nodesRubySupplementaryCharactersAndSelectionShareTheSameCuts() {
        val source = "𠮷野、東京；後文。"
        val cues = split("<p><span>𠮷</span>野<span>、</span><ruby>東京<rt>とうきょう</rt></ruby>；後文。</p>")
        assertEquals(listOf("𠮷野、", "東京；", "後文。"), cues.map { it.text })
        assertEquals(listOf(0, 2, 4), cues.map { it.start })
        val selection = TtsText.selection(cues, source, 0, 0)
        assertEquals(source, selection.text)
        assertEquals("𠮷野、とうきょう；後文。", TtsText.spoken(selection, null, emptyList()))
        val atomic = split("<p>前文<ruby>東京、大阪<rt>よみ</rt></ruby>、後文。</p>")
        assertEquals(listOf("前文東京、大阪、", "後文。"), atomic.map { it.text })
        assertEquals("前文よみ、", TtsText.spoken(atomic.first(), null, emptyList()))
    }

    @Test fun quotesRemainBoundariesAndClausesRetainDialogueRoles() {
        val cues = split("<p>前文“你好，世界”後文</p>")
        assertEquals(listOf("前文", "“你好，", "世界”", "後文"), cues.map { it.text })
        assertTrue(cues.all { it.length > 0 })
        assertEquals(listOf(TtsTextRole.Narration, TtsTextRole.Dialogue, TtsTextRole.Dialogue, TtsTextRole.Narration),
            cues.map { it.roles.single().role })
    }
}
