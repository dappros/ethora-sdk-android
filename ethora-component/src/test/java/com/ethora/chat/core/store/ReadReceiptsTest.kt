package com.ethora.chat.core.store

import com.ethora.chat.core.store.ReadReceipts
import com.ethora.chat.core.store.ReceiptState
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadReceiptsTest {

    // Ordered room list: m1 (oldest) .. m3 (newest)
    private val index = mapOf("m1" to 0, "m2" to 1, "m3" to 2)
    private val me = "me@example.com"

    @Test
    fun noReceiptsAndNoWatermark_isSent() {
        val info = ReadReceipts.compute("m2", null, index, null, me)

        assertEquals(ReceiptState.SENT, info.state)
        assertEquals(emptyList<String>(), info.receivedBy)
        assertEquals(emptyList<String>(), info.seenBy)
    }

    @Test
    fun deliveryReceipt_isDelivered() {
        val info = ReadReceipts.compute("m2", mapOf("bob" to 10L), index, null, me)

        assertEquals(ReceiptState.DELIVERED, info.state)
        assertEquals(listOf("bob"), info.receivedBy)
    }

    @Test
    fun watermarkAtOrAfterMessage_isSeen() {
        val info = ReadReceipts.compute(
            "m2", null, index, mapOf("bob" to "m3", "ann" to "m2"), me
        )

        assertEquals(ReceiptState.SEEN, info.state)
        assertEquals(setOf("bob", "ann"), info.seenBy.toSet())
    }

    @Test
    fun watermarkBeforeMessage_isNotSeen() {
        val info = ReadReceipts.compute("m2", mapOf("bob" to 1L), index, mapOf("bob" to "m1"), me)

        assertEquals(ReceiptState.DELIVERED, info.state)
        assertEquals(emptyList<String>(), info.seenBy)
    }

    @Test
    fun ownWatermarkIsIgnored() {
        val info = ReadReceipts.compute("m2", null, index, mapOf("me" to "m3"), me)

        assertEquals(ReceiptState.SENT, info.state)
    }

    @Test
    fun unknownWatermarkOrMessage_doesNotCountAsSeen() {
        assertEquals(
            ReceiptState.SENT,
            ReadReceipts.compute("m2", null, index, mapOf("bob" to "gone"), me).state
        )
        assertEquals(
            ReceiptState.SENT,
            ReadReceipts.compute("missing", null, index, mapOf("bob" to "m3"), me).state
        )
    }

    @Test
    fun seenWinsOverDelivered() {
        val info = ReadReceipts.compute(
            "m1", mapOf("bob" to 5L), index, mapOf("ann" to "m3"), me
        )

        assertEquals(ReceiptState.SEEN, info.state)
    }
}
