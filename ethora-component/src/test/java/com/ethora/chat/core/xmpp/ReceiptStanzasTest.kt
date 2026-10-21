package com.ethora.chat.core.xmpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for [ReceiptStanzas]. The server reflects a groupchat stanza
 * with `from` rewritten to `room@conference/nick`; [reflect] simulates that so
 * each builder is parsed the way a receiving client sees it.
 */
class ReceiptStanzasTest {

    private val room = "room1@conference.example.com"

    private fun reflect(stanza: String): String =
        stanza.replaceFirst(Regex("""from="[^"]*""""), """from="$room/alice"""")

    @Test
    fun receivedStanza_roundTripsThroughReflection() {
        val built = ReceiptStanzas.receivedStanza(
            id = "message-received:1700000000000",
            from = "alice@example.com",
            roomJid = room,
            messageId = "archive-42"
        )

        val parsed = ReceiptStanzas.parseReceived(reflect(built))

        assertEquals(
            ReceiptStanzas.Receipt(messageId = "archive-42", fromXmppUsername = "alice", roomJid = room),
            parsed
        )
    }

    @Test
    fun receivedStanza_carriesExpectedNamespaceAndIdPrefix() {
        val built = ReceiptStanzas.receivedStanza(
            id = "${ReceiptStanzas.RECEIVED_ID_PREFIX}1",
            from = "alice@example.com",
            roomJid = room,
            messageId = "m1"
        )

        assertTrue(built.contains("""<received xmlns="urn:xmpp:receipts" id="m1" from="alice@example.com"/>"""))
        assertTrue(built.contains("""id="message-received:1""""))
        assertTrue(built.contains("""<store xmlns="urn:xmpp:hints"/>"""))
    }

    @Test
    fun displayedStanza_roundTripsThroughReflection() {
        val built = ReceiptStanzas.displayedStanza(
            id = "message-displayed:1700000000000",
            from = "bob@example.com",
            roomJid = room,
            messageId = "archive-99"
        )

        val parsed = ReceiptStanzas.parseDisplayed(reflect(built))

        assertEquals(
            ReceiptStanzas.Receipt(messageId = "archive-99", fromXmppUsername = "bob", roomJid = room),
            parsed
        )
    }

    @Test
    fun parsers_areNamespaceAware() {
        val receivedXml = reflect(
            ReceiptStanzas.receivedStanza("x", "alice@example.com", room, "m1")
        )
        val displayedXml = reflect(
            ReceiptStanzas.displayedStanza("y", "alice@example.com", room, "m1")
        )

        assertNull(ReceiptStanzas.parseDisplayed(receivedXml))
        assertNull(ReceiptStanzas.parseReceived(displayedXml))
    }

    @Test
    fun parsers_returnNullForPlainChatMessage() {
        val plain = "<message from='$room/alice' type='groupchat' id='m1'><body>hi</body></message>"

        assertNull(ReceiptStanzas.parseReceived(plain))
        assertNull(ReceiptStanzas.parseDisplayed(plain))
    }

    @Test
    fun receivedStanza_escapesMessageIdAndFrom() {
        val built = ReceiptStanzas.receivedStanza(
            id = "i&1",
            from = "a<b@example.com",
            roomJid = room,
            messageId = "m\"1"
        )

        assertTrue(built.contains("""id="i&amp;1""""))
        assertTrue(built.contains("""from="a&lt;b@example.com""""))
        assertTrue(built.contains("""id="m&quot;1""""))
    }

    @Test
    fun typingStanza_composingAndPaused_useChatStatesAndFullName() {
        val composing = ReceiptStanzas.typingStanza("typing-1", room, "Ann Lee", composing = true)
        val paused = ReceiptStanzas.typingStanza("stop-typing-1", room, "Ann Lee", composing = false)

        assertTrue(composing.contains("""<composing xmlns="http://jabber.org/protocol/chatstates"/>"""))
        assertTrue(composing.contains("""<data fullName="Ann Lee"/>"""))
        assertTrue(composing.contains("""to="$room""""))
        assertTrue(paused.contains("""<paused xmlns="http://jabber.org/protocol/chatstates"/>"""))
    }

    @Test
    fun typingStanza_escapesFullNameSoTheStanzaStaysWellFormed() {
        val stanza = ReceiptStanzas.typingStanza("typing-2", room, "O'Neil & <Co>", composing = true)

        assertTrue(stanza.contains("""<data fullName="O&apos;Neil &amp; &lt;Co&gt;"/>"""))
    }

    @Test
    fun parsedSenderUsesLocalPartOfJid() {
        val xml = "<message from='$room/nick' type='groupchat'>" +
            "<received xmlns='urn:xmpp:receipts' id='m9' from='carol@example.com/phone'/></message>"

        assertEquals("carol", ReceiptStanzas.parseReceived(xml)?.fromXmppUsername)
    }

    @Test
    fun normalizeLocal_stripsDomainResourceUnderscoresAndCase() {
        assertEquals("alice", ReceiptStanzas.normalizeLocal("Al_ice@example.com/res"))
        assertEquals("", ReceiptStanzas.normalizeLocal(null))
    }
}
