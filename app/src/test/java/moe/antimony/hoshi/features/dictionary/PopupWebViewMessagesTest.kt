package moe.antimony.hoshi.features.dictionary

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
        val frames = popupButtonFramesFromJson(
            """
                [
                  {"kind":"audio","entryIndex":0,"x":12.5,"y":20,"width":28,"height":28,"state":"error","enabled":true},
                  {"kind":"mine","entryIndex":1,"x":45,"y":21,"width":28,"height":28,"state":"duplicate","enabled":false},
                  {"kind":"bogus","entryIndex":2,"x":1,"y":1,"width":28,"height":28}
                ]
            """.trimIndent(),
        )

        assertTrue(frames.size == 2)
        assertTrue(frames[0].kind == PopupButtonKind.Audio)
        assertTrue(frames[0].state == PopupButtonState.Error)
        assertTrue(frames[0].enabled)
        assertTrue(frames[1].kind == PopupButtonKind.Mine)
        assertTrue(frames[1].state == PopupButtonState.Duplicate)
        assertTrue(!frames[1].enabled)
    }

    @Test
    fun ignoresInvalidPopupButtonFrames() {
        val frames = popupButtonFramesFromJson(
            """
                [
                  {"kind":"audio","entryIndex":0,"x":12,"y":20,"width":0,"height":28},
                  {"kind":"mine","entryIndex":-1,"x":45,"y":21,"width":28,"height":28},
                  {"kind":"audio","entryIndex":2,"x":45,"y":21,"width":28,"height":28}
                ]
            """.trimIndent(),
        )

        assertTrue(frames.size == 1)
        assertTrue(frames.single().entryIndex == 2)
        assertTrue(frames.single().state == PopupButtonState.Default)
        assertTrue(frames.single().enabled)
    }
}
