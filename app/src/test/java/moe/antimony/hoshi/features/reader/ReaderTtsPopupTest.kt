package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupState
import org.junit.Assert.*
import org.junit.Test

class ReaderTtsPopupTest {
    private val root = LookupPopupItem(readerChapterIndex = 2, state = LookupPopupState(
        selection = ReaderSelectionData("東京", "東京です。", ReaderSelectionRect(10.0, 10.0, 20.0, 20.0), 4),
        results = emptyList(),
    ))
    private fun frame(popup: LookupPopupItem = root, available: Boolean = true) =
        ReaderLookupPopupFramePayload.fromPopup(popup, 0, ReaderLookupPopupViewport(400.0, 800.0),
            ttsAvailable = available, ttsWasPaused = true)

    @Test fun sourcePopupShowsTtsControlsWithoutAnAudiobookCue() {
        val value = frame()
        assertTrue(value.ttsVisible)
        assertTrue(value.ttsWasPaused)
        assertFalse(value.sasayakiVisible)
    }
    @Test fun childPopupsUnavailableSourceAndAudiobooksDoNotExposeTtsControls() {
        assertFalse(frame(root.copy(readerChapterIndex = null)).ttsVisible)
        assertFalse(frame(available = false).ttsVisible)
        assertFalse(frame(root.copy(state = root.state.copy(selection = root.state.selection.copy(normalizedOffset = null)))).ttsVisible)
        val audiobook = frame(root.copy(sasayakiCue = SasayakiMatch("cue", 0.0, 1.0, "東京です。", 2, 4, 5)))
        assertTrue(audiobook.sasayakiVisible)
        assertFalse(audiobook.ttsVisible)
    }
    @Test fun ttsMessagesKeepTheirOwnSourceIdentity() {
        assertEquals(ReaderLookupPopupBridgeMessage.CreatePronunciation("root", null, "食べる", "たべる"),
            ReaderLookupPopupBridgeMessage.fromJson("""{"name":"createPronunciation","popupId":"root","body":{"expression":"食べる","reading":"たべる"}}"""))
        assertNull(ReaderLookupPopupBridgeMessage.fromJson("""{"name":"createPronunciation","popupId":"root","body":{"expression":"","reading":""}}"""))
        assertEquals(ReaderLookupPopupBridgeMessage.TtsReplaySentence("root", null), ReaderLookupPopupBridgeMessage.fromJson("""{"name":"ttsReplaySentence","popupId":"root"}"""))
        assertEquals(ReaderLookupPopupBridgeMessage.TtsTogglePlayback("root", null), ReaderLookupPopupBridgeMessage.fromJson("""{"name":"ttsTogglePlayback","popupId":"root"}"""))
        assertEquals(ReaderLookupPopupBridgeMessage.TtsPlayForward("root", null), ReaderLookupPopupBridgeMessage.fromJson("""{"name":"ttsPlayForward","popupId":"root"}"""))
    }
}
