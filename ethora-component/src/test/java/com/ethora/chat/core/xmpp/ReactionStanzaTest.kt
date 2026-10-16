package com.ethora.chat.core.xmpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionStanzaTest {

    @Test
    fun parsesLiveReflectionAndRoomFromStanzaIdNotTo() {
        val xml = """<message from="room@conference.xmpp.example.com/alice" to="bob@xmpp.example.com/web" type="groupchat">""" +
            """<reactions id="1700000000000001" from="alice@xmpp.example.com/res" xmlns="urn:xmpp:reactions:0">""" +
            """<reaction>heart</reaction><reaction>+1</reaction></reactions>""" +
            """<data senderFirstName="Alice" senderLastName="Smith"/>""" +
            """<stanza-id xmlns="urn:xmpp:sid:0" by="room@conference.xmpp.example.com" id="1700000000999999"/>""" +
            "</message>"

        val parsed = ReactionStanza.parse(xml)!!

        assertEquals("1700000000000001", parsed.targetId)
        assertEquals("alice", parsed.reactorId)
        assertEquals(listOf("heart", "+1"), parsed.emoji)
        assertEquals("Alice", parsed.data["senderFirstName"])
        assertEquals("Smith", parsed.data["senderLastName"])
        assertEquals("room@conference.xmpp.example.com", parsed.roomJid)
        assertEquals(1700000000999999L, parsed.timestamp)
    }

    @Test
    fun parsesMamForwardedReaction() {
        val xml = """<message to="bob@xmpp.example.com" id="m1">""" +
            """<result xmlns="urn:xmpp:mam:2" queryid="q1" id="r1"><forwarded xmlns="urn:xmpp:forward:0">""" +
            """<message from="room@conference.xmpp.example.com/carol" type="groupchat">""" +
            """<reactions id="1700000000000007" from="dave@xmpp.example.com/x" xmlns="urn:xmpp:reactions:0"><reaction>joy</reaction></reactions>""" +
            """<data senderFirstName="Dave" senderLastName="R&amp;D"/>""" +
            """<stanza-id xmlns="urn:xmpp:sid:0" by="room@conference.xmpp.example.com" id="1700000000000100"/>""" +
            "</message></forwarded></result></message>"

        val parsed = ReactionStanza.parse(xml)!!

        assertEquals("dave", parsed.reactorId)
        assertEquals(listOf("joy"), parsed.emoji)
        assertEquals("R&D", parsed.data["senderLastName"])
        assertEquals("room@conference.xmpp.example.com", parsed.roomJid)
        assertEquals(1700000000000100L, parsed.timestamp)
    }

    @Test
    fun emptyReactionListIsRemovalMarker() {
        val selfClosing = """<message><reactions id="5" from="alice@h/r" xmlns="urn:xmpp:reactions:0"/>""" +
            """<stanza-id by="room@conference.h" id="9"/></message>"""
        val withClose = """<message><reactions id="5" from="alice@h/r" xmlns="urn:xmpp:reactions:0"></reactions>""" +
            """<stanza-id by="room@conference.h" id="9"/></message>"""

        assertTrue(ReactionStanza.parse(selfClosing)!!.emoji.isEmpty())
        assertTrue(ReactionStanza.parse(withClose)!!.emoji.isEmpty())
    }

    @Test
    fun stanzaWithoutReactionsOrTargetIsNull() {
        assertNull(ReactionStanza.parse("<message><body>hi</body></message>"))
        assertNull(ReactionStanza.parse("""<message><reactions from="alice@h"/></message>"""))
    }

    @Test
    fun buildThenParseRoundTripsTargetEmojiReactorAndData() {
        val stanza = ReactionStanza.buildSend(
            stanzaId = "message-reaction:42",
            fromJid = "alice@xmpp.example.com",
            roomJid = "room@conference.xmpp.example.com",
            targetId = "1700000000000123",
            emoji = listOf("+1", "heart"),
            firstName = "Ann & Co",
            lastName = "Lee"
        )

        assertTrue(stanza.contains("""<message type="groupchat" id="message-reaction:42""""))
        assertTrue(stanza.contains("""xmlns="urn:xmpp:reactions:0""""))
        val parsed = ReactionStanza.parse(stanza)!!
        assertEquals("1700000000000123", parsed.targetId)
        assertEquals("alice", parsed.reactorId)
        assertEquals(listOf("+1", "heart"), parsed.emoji)
        assertEquals("Ann & Co", parsed.data["senderFirstName"])
        assertEquals("Lee", parsed.data["senderLastName"])
    }

    @Test
    fun buildWithEmptyListSendsRemoveAll() {
        val stanza = ReactionStanza.buildSend(
            stanzaId = "message-reaction:1",
            fromJid = "alice@h",
            roomJid = "room@conference.h",
            targetId = "77",
            emoji = emptyList(),
            firstName = "A",
            lastName = "B"
        )
        assertTrue(ReactionStanza.parse(stanza)!!.emoji.isEmpty())
    }
}
