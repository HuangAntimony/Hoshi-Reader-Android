package moe.antimony.hoshi.features.tts

import java.io.IOException
import kotlinx.serialization.json.*
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.diagnosticMessage
import moe.antimony.hoshi.ui.resolve
import org.junit.Assert.*
import org.junit.Test

class TtsRegexReplacementTest {
    @Test fun editingRegexDraftsKeepsInlineDiagnosticsWithoutRecordingRuntimeErrors() {
        ErrorDiagnosticEvents.buffer.drain()
        try {
            val networkFailure = IOException("Voice provider unavailable", IOException("Connection refused"))
            val networkError = networkFailure.toTtsError(TtsFailure.Network)
            val networkMessage = networkError.message.resolve({ _, _ -> "Network failed" }, { _, _, _ -> "" })
            assertTrue(networkMessage.contains("Voice provider unavailable\nConnection refused"))

            val sentence = TtsSentence(0, 0, 2, "ab")
            val preferences = TtsPreferences()
            val drafts = listOf(
                rule("(", "a") to TtsWordError.RegexPattern,
                rule("[ab", "a") to TtsWordError.RegexPattern,
                rule("(a)", "$") to TtsWordError.RegexReplacement,
                rule("(a)", "\$2") to TtsWordError.RegexReplacement,
            )
            drafts.forEach { (draft, category) ->
                val preview = prepareTtsWordPreview(draft, sentence, null, preferences)
                assertEquals(category, preview.error)
                assertNull(preview.text)
                val original = requireNotNull(TtsRegexRule.failure(draft)).diagnosticMessage()
                val message = requireNotNull(preview.errorMessage).resolve({ _, _ -> "Invalid draft" }, { _, _, _ -> "" })
                assertEquals("Invalid draft\n$original", message)
            }
            val complete = prepareTtsWordPreview(rule("(a)", "\$1"), sentence, null, preferences)
            assertNull(complete.error)
            assertNull(complete.errorMessage)
            assertEquals("ab", complete.text)
            assertEquals(listOf(networkFailure), ErrorDiagnosticEvents.buffer.drain().map { it.error })
        } finally {
            ErrorDiagnosticEvents.buffer.drain()
        }
    }

    @Test fun invalidPatternPreviewKeepsTheOriginalParserDiagnostic() {
        val preview = prepareTtsWordPreview(rule("[", "a"), TtsSentence(0, 0, 1, "a"), null, TtsPreferences())

        assertEquals(TtsWordError.RegexPattern, preview.error)
        assertNotNull(preview.errorMessage)
        val message = preview.errorMessage!!.resolve({ _, _ -> "Invalid pattern" }, { _, _, _ -> "" })
        assertTrue(message.startsWith("Invalid pattern\n"))
        assertTrue(message.contains("missing closing ]"))
    }

    @Test fun invalidReplacementPreviewKeepsTheOriginalGroupValidationDiagnostic() {
        val preview = prepareTtsWordPreview(rule("(a)", "\$2"), TtsSentence(0, 0, 1, "a"), null, TtsPreferences())

        assertEquals(TtsWordError.RegexReplacement, preview.error)
        assertEquals(UiText.Joined(listOf(UiText.Resource(R.string.tts_invalid_regex_replacement), UiText.Literal("Failed requirement."))), preview.errorMessage)
    }

    private val json = Json { ignoreUnknownKeys = true }
    @Test fun emptyLiteralAndRegexReplacementsDeleteWithoutChangingSource() {
        val sentence = TtsSentence(3, 8, 6, "前（注）後々。")
        val words = listOf(TtsWord("literal", "（注）", ""), TtsWord("regex", "々", "", regex = true))
        words.forEach { assertNull(validateTtsWord(it, words)) }
        val preview = prepareTtsWordPreview(words.last(), sentence, "book", TtsPreferences(words = words))
        assertNull(preview.error)
        assertEquals("前後。", preview.text)
        assertEquals("前（注）後々。", preview.sentence.text)
        assertEquals(8, preview.sentence.start)
    }

    private fun rule(pattern: String, replacement: String, id: String = "regex", book: String? = null): TtsWord =
        json.decodeFromJsonElement(buildJsonObject {
            put("id", id); put("surface", pattern); put("reading", replacement); put("regex", true)
            book?.let { put("bookId", it) }
        })
    private fun speak(text: String, vararg rules: TtsWord) = TtsText.spoken(TtsSentence(0, 0, text.length, text), "book", rules.toList())

    @Test fun expandsIterationMarksAndSupplementaryKanjiWithoutChangingSource() {
        val source = TtsSentence(0, 20, 9, "時々、人々、𠮷々😀")
        assertEquals("時時、人人、𠮷𠮷😀", TtsText.spoken(source, "book", listOf(rule("([\\p{Han}])々", "\$1\$1"))))
        assertEquals("時々、人々、𠮷々😀", source.text)
        assertEquals(20, source.start)
    }

    @Test fun literalRulesAndRubyTakePriorityOverBroadRegex() {
        val sentence = TtsSentence(0, 0, 9, "時々人々瑞々しい", listOf(TtsRuby(4, 6, "みずみず")))
        val rules = listOf(rule("([\\p{Han}])々", "\$1\$1"), TtsWord("literal", "人々", "ひとびと"))
        assertEquals("時時ひとびとみずみずしい", TtsText.spoken(sentence, "book", rules))
        assertEquals("時時ひとびと瑞瑞しい", TtsText.spoken(sentence, "book", rules, useRubyReadings = false))
        assertEquals("時ひとびと", speak("時人々", rule("時人", "wrong"), TtsWord("literal", "人々", "ひとびと")))
    }

    @Test fun longestActualMatchWinsThenBookScopeAndReplacementIsSinglePass() {
        assertEquals("long", speak("東京駅", rule("東京", "short", "a"), rule("東京駅", "long", "b")))
        assertEquals("local", speak("時々", rule("時々", "global", "a"), rule("時々", "local", "b", "book")))
        assertEquals("時時", speak("時々", rule("(時)々", "\$1\$1"), rule("時時", "wrong", "b")))
        assertEquals("時々", speak("時々", rule("(時)々", "\$1\$1", book = "other")))
    }

    @Test fun replacementsAcrossRolesKeepFirstNonQuoteOwnership() {
        val sentence = TtsSentence(0, 0, 6, "前「時々」後")
        val result = TtsText.spokenParts(sentence, null, listOf(rule("(前「時々)", "置換")))
        assertEquals(listOf(TtsSpokenPart(TtsTextRole.Narration, "置換"),
            TtsSpokenPart(TtsTextRole.Dialogue, "」"), TtsSpokenPart(TtsTextRole.Narration, "後")), result)
    }

    @Test fun validationRejectsInvalidPatternsAndMissingGroupsButLiteralSyntaxStaysLiteral() {
        assertNotNull(validateTtsWord(rule("[", "a"), emptyList()))
        assertNotNull(validateTtsWord(rule("(a)", "\$2"), emptyList()))
        assertNotNull(validateTtsWord(rule("(?<=a)b", "c"), emptyList()))
        assertNotNull(validateTtsWord(rule("(a)", "\\"), emptyList()))
        assertNull(validateTtsWord(rule("(a)", "\\\$1-\$0-\$1"), emptyList()))
        assertEquals("\$1", speak("[", TtsWord("literal", "[", "\$1")))
        assertEquals("\$1-a-a", speak("a", rule("(a)", "\\\$1-\$0-\$1")))
    }

    @Test fun ignoresZeroWidthMatchesAndHandlesAdversarialInputWithoutBacktracking() {
        assertEquals("ab", speak("ab", rule("^|$", "insert")))
        val text = "a".repeat(20_000) + "!"
        assertEquals(text, speak(text, rule("(a+)+$", "wrong")))
    }
    @Test fun previewShowsAllActiveRulesAndRubyAndKeepsDraftUnpersisted() {
        val sentence = TtsSentence(0, 0, 6, "時々東京", listOf(TtsRuby(2, 4, "とうきょう")))
        val saved = TtsPreferences(words = listOf(TtsWord("old", "時々", "ときどき")))
        val draft = TtsWord("old", "時々", "じじ")
        val preview = prepareTtsWordPreview(draft, sentence, "book", saved)
        assertEquals("時々東京", preview.sentence.text)
        assertEquals("じじとうきょう", preview.text)
        assertEquals("ときどき", saved.words.single().replacement)
        assertEquals("じじ", preview.preferences.words.single().replacement)
        val bad = prepareTtsWordPreview(rule("[", "x"), sentence, "book", saved)
        assertNull(bad.text)
        assertEquals(TtsWordError.RegexPattern, bad.error)
    }

    @Test fun regexPreviewKeepsLiteralConflictAndPreservesWhitespaceInPattern() {
        val sentence = TtsSentence(0, 0, 5, " 時々 時々")
        val draft = rule(" 時々 ", "間")
        val saved = TtsPreferences(words = listOf(TtsWord("literal", " 時々 ", "そのまま")))
        val preview = prepareTtsWordPreview(draft, sentence, "book", saved)
        assertEquals("そのまま時々", preview.text)
        assertEquals(2, preview.preferences.words.size)
        assertEquals("間時々", prepareTtsWordPreview(draft, sentence, "book", TtsPreferences()).text)
    }

    @Test fun skippedOverlappingLiteralAndRubyDoNotReserveUnconsumedText() {
        val sentence = TtsSentence(0, 0, 5, "東京大学生")
        val literal = TtsWord("long", "東京大学", "とうだい")
        val regex = rule("生", "せい")
        assertEquals("とうだいせい", TtsText.spoken(sentence, null,
            listOf(literal, TtsWord("overlap", "大学生", "だいがくせい"), regex)))
        assertEquals("とうだいせい", TtsText.spoken(sentence.copy(ruby = listOf(TtsRuby(2, 5, "だいがくせい"))), null,
            listOf(literal, regex)))
    }

    @Test fun imageRubyAtMatchBoundaryIsPreservedWithoutBlockingTheMatch() {
        val regex = rule("(時)々", "\$1\$1")
        val source = TtsSentence(0, 0, 2, "時々", listOf(TtsRuby(0, 0, "画像"), TtsRuby(2, 2, "末尾")))
        assertEquals("画像時時末尾", TtsText.spoken(source, null, listOf(regex)))
        assertEquals("時画像々", TtsText.spoken(source.copy(ruby = listOf(TtsRuby(1, 1, "画像"))), null, listOf(regex)))
    }

}
