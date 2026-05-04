package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSelectionBridgeTest {
    @Test
    fun parsesTextSelectionPayloadLikeIosMessageBody() {
        val payload = """
            {
                "text": "食べる",
                "sentence": "私は食べる。",
                "rect": {
                    "x": 12.5,
                    "y": 24.25,
                    "width": 40.0,
                    "height": 18.0
                },
                "normalizedOffset": 42,
                "futureField": "ignored"
            }
        """.trimIndent()

        assertEquals(
            ReaderSelectionData(
                text = "食べる",
                sentence = "私は食べる。",
                rect = ReaderSelectionRect(
                    x = 12.5,
                    y = 24.25,
                    width = 40.0,
                    height = 18.0,
                ),
                normalizedOffset = 42,
            ),
            ReaderSelectionBridgePayload.fromJson(payload),
        )
    }
}
