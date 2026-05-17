package moe.antimony.hoshi.features.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupWebViewMessagesTest {

    @Test
    fun dictionaryImageMimeTypeMatchesIosImageHandler() {
        assertTrue(dictionaryImageMimeType("icons/arrow.svg") == "image/svg+xml")
        assertTrue(dictionaryImageMimeType("photo.PNG") == "image/png")
        assertTrue(dictionaryImageMimeType("image.jpeg") == "image/jpeg")
        assertTrue(dictionaryImageMimeType("unknown.bin") == "application/octet-stream")
    }

    @Test
    fun parsesPopupButtonFramesFromBridgeJson() {
        val frames = popupButtonFramesFromMessageJson(
            """
                {
                  "name": "buttonFrames",
                  "body": [
                    {"kind":"audio","entryIndex":0,"x":12.5,"y":20,"width":28,"height":28,"state":"error","enabled":true},
                    {"kind":"mine","entryIndex":1,"x":45,"y":21,"width":28,"height":28,"state":"duplicate","enabled":false},
                    {"kind":"bogus","entryIndex":2,"x":1,"y":1,"width":28,"height":28}
                  ]
                }
            """.trimIndent(),
        )

        assertEquals(2, frames.size)
        assertEquals(PopupButtonKind.Audio, frames[0].kind)
        assertEquals(PopupButtonState.Error, frames[0].state)
        assertTrue(frames[0].enabled)
        assertEquals(PopupButtonKind.Mine, frames[1].kind)
        assertEquals(PopupButtonState.Duplicate, frames[1].state)
        assertFalse(frames[1].enabled)
    }

    @Test
    fun ignoresInvalidPopupButtonFrames() {
        val frames = popupButtonFramesFromMessageJson(
            """
                {
                  "name": "buttonFrames",
                  "body": [
                    {"kind":"audio","entryIndex":0,"x":12,"y":20,"width":0,"height":28},
                    {"kind":"mine","entryIndex":-1,"x":45,"y":21,"width":28,"height":28},
                    {"kind":"audio","entryIndex":2,"x":45,"y":21,"width":28,"height":28}
                  ]
                }
            """.trimIndent(),
        )

        assertEquals(1, frames.size)
        assertEquals(PopupButtonKind.Audio, frames.single().kind)
        assertEquals(2, frames.single().entryIndex)
        assertEquals(PopupButtonState.Default, frames.single().state)
        assertTrue(frames.single().enabled)
    }

    @Test
    fun popupButtonActionsCallIosStyleJavascriptEntryPoints() {
        assertEquals("playEntryAudio(3)", PopupButtonKind.Audio.actionScript(3))
        assertEquals("mineEntryAtIndex(4)", PopupButtonKind.Mine.actionScript(4))
    }
}
