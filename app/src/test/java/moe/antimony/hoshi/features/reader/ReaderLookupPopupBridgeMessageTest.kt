package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.audio.AudioPlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLookupPopupBridgeMessageTest {
    @Test
    fun parsesReaderPopupBridgeMessages() {
        assertEquals(
            ReaderLookupPopupBridgeMessage.OpenLink(
                popupId = "root",
                messageId = null,
                url = "https://example.com",
            ),
            ReaderLookupPopupBridgeMessage.fromJson(
                """{"name":"openLink","popupId":"root","body":"https://example.com"}""",
            ),
        )

        assertEquals(
            ReaderLookupPopupBridgeMessage.TapOutside(popupId = "root", messageId = null),
            ReaderLookupPopupBridgeMessage.fromJson("""{"name":"tapOutside","popupId":"root"}"""),
        )

        assertEquals(
            ReaderLookupPopupBridgeMessage.SwipeDismiss(popupId = "root", messageId = null),
            ReaderLookupPopupBridgeMessage.fromJson("""{"name":"swipeDismiss","popupId":"root"}"""),
        )

        assertEquals(
            ReaderLookupPopupBridgeMessage.PlayWordAudio(
                popupId = "root",
                messageId = null,
                url = "https://audio.example/word.mp3",
                mode = AudioPlaybackMode.Interrupt,
            ),
            ReaderLookupPopupBridgeMessage.fromJson(
                """
                    {
                      "name":"playWordAudio",
                      "popupId":"root",
                      "body":{"url":"https://audio.example/word.mp3","mode":"interrupt"}
                    }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun parsesSelectionAndAsyncRequestMessages() {
        val textSelected = ReaderLookupPopupBridgeMessage.fromJson(
            """
                {
                  "name":"textSelected",
                  "popupId":"root",
                  "body":{
                    "text":"食べる",
                    "sentence":"食べる。",
                    "rect":{"x":12.0,"y":34.0,"width":56.0,"height":18.0},
                    "normalizedOffset":3,
                    "sentenceOffset":1
                  }
                }
            """.trimIndent(),
        )

        assertTrue(textSelected is ReaderLookupPopupBridgeMessage.TextSelected)
        val selection = (textSelected as ReaderLookupPopupBridgeMessage.TextSelected).selection
        assertEquals("食べる", selection.text)
        assertEquals(12.0, selection.rect.x, 0.0)
        assertEquals(3, selection.normalizedOffset)

        assertEquals(
            ReaderLookupPopupBridgeMessage.DuplicateCheck(
                popupId = "child",
                messageId = "42",
                expression = "猫",
            ),
            ReaderLookupPopupBridgeMessage.fromJson(
                """{"name":"duplicateCheck","id":"42","popupId":"child","body":"猫"}""",
            ),
        )
        assertEquals(
            ReaderLookupPopupBridgeMessage.LookupRedirect(
                popupId = "child",
                messageId = "43",
                query = "犬",
            ),
            ReaderLookupPopupBridgeMessage.fromJson(
                """{"name":"lookupRedirect","id":"43","popupId":"child","body":"犬"}""",
            ),
        )
        assertEquals(
            ReaderLookupPopupBridgeMessage.GetEntry(
                popupId = "child",
                messageId = "44",
                index = 2,
            ),
            ReaderLookupPopupBridgeMessage.fromJson(
                """{"name":"getEntry","id":"44","popupId":"child","body":2}""",
            ),
        )
        assertEquals(
            ReaderLookupPopupBridgeMessage.MineEntry(
                popupId = "child",
                messageId = "45",
                payloadJson = """{"expression":"猫"}""",
            ),
            ReaderLookupPopupBridgeMessage.fromJson(
                """{"name":"mineEntry","id":"45","popupId":"child","body":{"expression":"猫"}}""",
            ),
        )
        assertEquals(
            ReaderLookupPopupBridgeMessage.ScrollState(
                popupId = "root",
                messageId = null,
                atTop = true,
                scrollTop = 0.0,
            ),
            ReaderLookupPopupBridgeMessage.fromJson(
                """{"name":"scrollState","popupId":"root","body":{"atTop":true,"scrollTop":0.0}}""",
            ),
        )
    }

    @Test
    fun rejectsMalformedReaderPopupMessages() {
        assertNull(ReaderLookupPopupBridgeMessage.fromJson("not-json"))
        assertNull(ReaderLookupPopupBridgeMessage.fromJson("""{"name":"unknown","popupId":"root"}"""))
        assertNull(ReaderLookupPopupBridgeMessage.fromJson("""{"name":"openLink","body":"https://example.com"}"""))
        assertNull(ReaderLookupPopupBridgeMessage.fromJson("""{"name":"textSelected","popupId":"root","body":{}}"""))
        assertNull(ReaderLookupPopupBridgeMessage.fromJson("""{"name":"getEntry","id":"1","popupId":"root","body":-1}"""))
    }
}
