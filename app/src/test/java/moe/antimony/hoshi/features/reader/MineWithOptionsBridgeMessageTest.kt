package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MineWithOptionsBridgeMessageTest {
    @Test
    fun parsesMineWithOptionsWithPayloadAndIds() {
        val json = """{"name":"mineWithOptions","popupId":"p1","id":"m7","body":{"expression":"勉強"}}"""
        val message = ReaderLookupPopupBridgeMessage.fromJson(json)
        assertTrue(message is ReaderLookupPopupBridgeMessage.MineWithOptions)
        message as ReaderLookupPopupBridgeMessage.MineWithOptions
        assertEquals("p1", message.popupId)
        assertEquals("m7", message.messageId)
        assertEquals("""{"expression":"勉強"}""", message.payloadJson)
    }

    @Test
    fun rejectsMineWithOptionsWithoutMessageId() {
        val json = """{"name":"mineWithOptions","popupId":"p1","body":{"expression":"x"}}"""
        assertNull(ReaderLookupPopupBridgeMessage.fromJson(json))
    }

    @Test
    fun rejectsMineWithOptionsWithoutBody() {
        val json = """{"name":"mineWithOptions","popupId":"p1","id":"m7"}"""
        assertNull(ReaderLookupPopupBridgeMessage.fromJson(json))
    }
}
