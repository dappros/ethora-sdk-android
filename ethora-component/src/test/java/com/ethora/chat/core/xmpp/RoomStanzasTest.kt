package com.ethora.chat.core.xmpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire shapes for room-management stanzas (web parity) and the
 * `<ethora-event>` headline parser. Each builder has a round trip through
 * the matching parser or a literal expected shape.
 */
class RoomStanzasTest {

    private val room = "room1_abc@conference.xmpp.example.com"
    private val appId = "app1"

    // --- builders -------------------------------------------------------

    @Test
    fun leavePresenceIsUnavailableToRoomNick() {
        assertEquals(
            "<presence to='room1_abc@conference.xmpp.example.com/app1_me' type='unavailable'/>",
            RoomStanzas.leavePresence(room, "app1_me")
        )
    }

    @Test
    fun inviteUsesMucUserInviteToUsernameAtHost() {
        assertEquals(
            "<message to='room1_abc@conference.xmpp.example.com' id='invite-rooms:42'>" +
                "<x xmlns='http://jabber.org/protocol/muc#user'>" +
                "<invite to='app1_bob@xmpp.example.com'/>" +
                "</x></message>",
            RoomStanzas.invite(room, "app1_bob", "xmpp.example.com", "invite-rooms:42")
        )
    }

    @Test
    fun membersRefreshCarriesHintAndAffiliationItem() {
        val xml = RoomStanzas.membersRefresh(room, "app1_me", "member", "members-changed-1-2")
        assertTrue(xml.contains("type='groupchat'"))
        assertTrue(xml.contains("<members-refresh xmlns='ethora:chats:members-refresh'/>"))
        assertTrue(xml.contains("<no-store xmlns='urn:xmpp:hints'/>"))
        assertTrue(xml.contains("<item affiliation='member' nick='app1_me'/>"))
    }

    @Test
    fun builderEscapesAttributeValues() {
        val xml = RoomStanzas.leavePresence("r'x@conf", "a<b")
        assertTrue(xml.contains("to='r&apos;x@conf/a&lt;b'"))
    }

    // --- ethora-event parsing ------------------------------------------

    private fun headline(from: String, event: String) =
        "<message from='$from' type='headline' to='me@xmpp.example.com'>$event</message>"

    private val chatMeta =
        "<ethora-event xmlns='urn:ethora:events:1' type='chat-meta-updated' chatName='app1_abc' appId='app1'/>"

    @Test
    fun carrierFindsPlainHeadline() {
        val xml = headline("xmpp.example.com", chatMeta)
        assertNotNull(EthoraEventParser.carrier(xml))
    }

    @Test
    fun carrierIgnoresNonHeadlineMessages() {
        val xml = "<message from='x@xmpp.example.com' type='groupchat'>$chatMeta</message>"
        assertNull(EthoraEventParser.carrier(xml))
    }

    @Test
    fun carrierUnwrapsMucSubPubsubWrapper() {
        val inner = headline("xmpp.example.com", chatMeta)
        val wrapped = "<message from='pubsub.example.com'>" +
            "<event xmlns='http://jabber.org/protocol/pubsub#event'>" +
            "<items node='urn:xmpp:mucsub:nodes:messages'><item>$inner</item></items>" +
            "</event></message>"
        val carrier = EthoraEventParser.carrier(wrapped)
        assertNotNull(carrier)
        assertEquals("xmpp.example.com", EthoraEventParser.from(carrier!!))
    }

    @Test
    fun parseReadsTypeAndAttributes() {
        val carrier = EthoraEventParser.carrier(headline("xmpp.example.com", chatMeta))!!
        val event = EthoraEventParser.parse(carrier)
        assertNotNull(event)
        assertEquals("chat-meta-updated", event!!.type)
        assertEquals("app1_abc", event.chatName)
        assertEquals("app1", event.appId)
        assertNull(event.xmppUsername)
    }

    @Test
    fun parseRejectsWrongNamespace() {
        val carrier = headline(
            "xmpp.example.com",
            "<ethora-event xmlns='urn:other' type='chat-meta-updated' chatName='app1_abc'/>"
        )
        assertNull(EthoraEventParser.parse(carrier))
    }

    @Test
    fun trustedSenderMustBeBareServerJid() {
        val own = "xmpp.example.com"
        // a server headline: bare, on our own domain
        assertTrue(EthoraEventParser.isTrustedSender("admin@xmpp.example.com", own))
        // a user or MUC occupant always carries a resource: never trusted
        assertFalse(EthoraEventParser.isTrustedSender("admin@xmpp.example.com/res", own))
        // another domain (a conference room or a foreign server)
        assertFalse(EthoraEventParser.isTrustedSender("admin@conference.xmpp.example.com", own))
        assertFalse(EthoraEventParser.isTrustedSender("admin@evil.example.org", own))
        // no local part, or two @ signs
        assertFalse(EthoraEventParser.isTrustedSender("xmpp.example.com", own))
        assertFalse(EthoraEventParser.isTrustedSender("@xmpp.example.com", own))
        assertFalse(EthoraEventParser.isTrustedSender("a@b@xmpp.example.com", own))
        // empty input
        assertFalse(EthoraEventParser.isTrustedSender("", own))
    }

    @Test
    fun safeKeyNeedsAppPrefixAndCleanSegments() {
        assertTrue(EthoraEventParser.isSafeKey("app1_abc", appId))
        assertFalse(EthoraEventParser.isSafeKey("other_abc", appId))   // wrong app
        assertFalse(EthoraEventParser.isSafeKey("app1", appId))        // no separator
        assertFalse(EthoraEventParser.isSafeKey("app1__abc", appId))   // empty segment
        assertFalse(EthoraEventParser.isSafeKey("app1_a b", appId))    // charset
        assertFalse(EthoraEventParser.isSafeKey("app1_" + "x".repeat(200), appId)) // length
        assertFalse(EthoraEventParser.isSafeKey(null, appId))
        assertTrue(EthoraEventParser.isSafeKey("app1_abc", ""))         // no session appId: shape only
    }
}
