package moe.antimony.hoshi.features.dictionary

import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.TermResult
import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.reader.ReaderLookupPopupViewport
import moe.antimony.hoshi.features.reader.ReaderPopupHistoryCounts
import moe.antimony.hoshi.features.reader.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessTextLookupStateTest {
    @Test
    fun emptyInitialLookupKeepsOriginalSourceAndMiningContext() {
        val popup = root("。😀猫\n猫")

        assertEquals("。😀猫\n猫", popup.state.selection.text)
        assertEquals("。😀猫\n猫", popup.state.selection.sentence)
        assertEquals(0, popup.state.selection.sentenceOffset)
        assertEquals("。😀猫\n猫", popup.state.ankiContext.sentence)
        assertEquals(0, popup.state.ankiContext.sentenceOffset)
        assertTrue(popup.state.results.isEmpty())
    }

    @Test
    fun successfulSuffixRedirectKeepsOriginalSentenceAndUsesUtf16Offset() {
        val original = root("😀猫と猫です", listOf(result("😀")))
        val results = listOf(result("猫"))

        val redirected = processTextLookupRedirect(original, "猫です", results, isRoot = true)

        assertEquals(original.id, redirected.id)
        assertEquals(results, redirected.state.results)
        assertEquals("猫です", redirected.state.selection.text)
        assertEquals("😀猫と猫です", redirected.state.selection.sentence)
        assertEquals(4, redirected.state.selection.sentenceOffset)
        assertEquals("😀猫と猫です", redirected.state.ankiContext.sentence)
        assertEquals(4, redirected.state.ankiContext.sentenceOffset)
    }

    @Test
    fun nonSuffixRedirectHasNoInferredOffset() {
        val redirected = processTextLookupRedirect(
            root("猫と犬", listOf(result("猫"))), "猫", listOf(result("猫")), isRoot = true,
        )

        assertEquals("猫", redirected.state.selection.text)
        assertEquals("猫と犬", redirected.state.ankiContext.sentence)
        assertNull(redirected.state.selection.sentenceOffset)
        assertNull(redirected.state.ankiContext.sentenceOffset)
    }

    @Test
    fun failedRedirectReturnsExactPriorPopup() {
        val prior = root("😀猫と猫です", listOf(result("猫")))

        assertSame(prior, processTextLookupRedirect(prior, "です", emptyList(), isRoot = true))
    }

    @Test
    fun childRedirectOnlyReplacesResults() {
        val child = root("猫と犬", listOf(result("猫")))
        val results = listOf(result("犬"))

        val redirected = processTextLookupRedirect(child, "犬", results, isRoot = false)

        assertEquals(child.copy(state = child.state.copy(results = results)), redirected)
        assertSame(child, processTextLookupRedirect(child, "未知", emptyList(), isRoot = false))
    }

    @Test
    fun onlyRootPayloadReceivesCompleteSourceText() {
        val root = root("。😀猫\n猫", listOf(result("猫")))
        val child = root("猫", listOf(result("猫")))
        val payloads = processTextLookupFramePayloads(
            query = "。😀猫\n猫",
            popups = listOf(root, child),
            histories = mapOf(root.id to ReaderPopupHistoryCounts(backCount = 2, forwardCount = 1)),
            viewport = ReaderLookupPopupViewport(width = 400.0, height = 800.0),
            iframeUrl = "https://appassets.androidplatform.net/popup.html",
        )

        assertEquals("。😀猫\n猫", payloads[0].sourceText)
        assertEquals(1, payloads[0].entriesCount)
        assertEquals(2, payloads[0].backCount)
        assertEquals(1, payloads[0].forwardCount)
        assertNull(payloads[1].sourceText)
    }

    private fun root(query: String, results: List<LookupResult> = emptyList()): LookupPopupItem =
        requireNotNull(processTextLookupRoot(
            query = query,
            results = results,
            dictionaryStyles = emptyMap(),
            dictionarySettings = DictionarySettings(),
            audioSettings = AudioSettings(),
            readerSettings = ReaderSettings(),
            darkMode = false,
            contentLanguageProfile = ContentLanguageProfile.Default,
        ))

    private fun result(matched: String) = LookupResult(
        matched = matched,
        term = TermResult(
            expression = matched,
            reading = matched,
            rules = "",
            glossaries = emptyArray(),
            frequencies = emptyArray(),
            pitches = emptyArray(),
        ),
        traceCandidates = emptyArray(),
    )
}
