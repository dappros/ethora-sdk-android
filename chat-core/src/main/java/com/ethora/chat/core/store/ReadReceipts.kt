package com.ethora.chat.core.store

import com.ethora.chat.core.xmpp.ReceiptStanzas

/** Delivery/read state of one of the current user's messages (web MessageBubble tickInfo). */
enum class ReceiptState { SENT, DELIVERED, SEEN }

/**
 * @property receivedBy local parts of users who sent a delivery receipt.
 * @property seenBy local parts of other users whose read marker is at or after this message.
 */
data class ReceiptInfo(
    val state: ReceiptState,
    val receivedBy: List<String>,
    val seenBy: List<String>
)

/**
 * Pure receipt logic, mirroring web Message.tsx (tickInfo / seenBy selector):
 * "seen" wins over "delivered" wins over plain "sent".
 * `lastDisplayedByUser` is a room-level watermark, so a message is seen by a
 * user when that user's watermark sits at or after the message in the list.
 */
object ReadReceipts {

    /**
     * @param messageIndexById message id -> position in the room's ordered message list.
     *        Callers build it once per list so bubbles do not each scan the list.
     */
    fun compute(
        messageId: String,
        receivedBy: Map<String, Long>?,
        messageIndexById: Map<String, Int>,
        lastDisplayedByUser: Map<String, String>?,
        currentXmppUsername: String?
    ): ReceiptInfo {
        val selfLocal = ReceiptStanzas.normalizeLocal(currentXmppUsername)
        val thisIndex = messageIndexById[messageId] ?: -1
        val seenBy = if (thisIndex < 0 || lastDisplayedByUser.isNullOrEmpty()) {
            emptyList()
        } else {
            lastDisplayedByUser.entries
                .filter { (user, _) -> user.isNotBlank() && ReceiptStanzas.normalizeLocal(user) != selfLocal }
                .filter { (_, watermarkId) -> (messageIndexById[watermarkId] ?: -1) >= thisIndex }
                .map { it.key }
        }
        val received = receivedBy?.keys?.toList().orEmpty()
        val state = when {
            seenBy.isNotEmpty() -> ReceiptState.SEEN
            received.isNotEmpty() -> ReceiptState.DELIVERED
            else -> ReceiptState.SENT
        }
        return ReceiptInfo(state = state, receivedBy = received, seenBy = seenBy)
    }
}
