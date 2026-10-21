package com.ethora.chat.core.e2ee

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** In-memory PEP service shared by two fake devices. Items are kept as XML text. */
private class FakePepServer {
    val nodes = HashMap<String, LinkedHashMap<String, LinkedHashMap<String, String>>>()
}

private class FakeTransport(override val ownJid: String, private val server: FakePepServer) : OmemoTransport {
    val sent = ArrayList<XmlElement>()

    override suspend fun request(iq: XmlElement): XmlElement {
        val id = iq.attr("id") ?: ""
        val to = iq.attr("to") ?: ownJid
        val pubsub = iq.elements.first()
        val reply = XmlElement("iq", linkedMapOf("type" to "result", "id" to id))
        if (pubsub.attr("xmlns") == OmemoManager.NS_PUBSUB_OWNER) {
            val node = pubsub.elements.first().attr("node").orEmpty()
            if (pubsub.child("delete") != null) server.nodes[ownJid]?.remove(node)
            return reply
        }
        val publish = pubsub.child("publish")
        if (publish != null) {
            val node = publish.attr("node")!!
            val item = publish.child("item")!!
            val items = server.nodes.getOrPut(ownJid) { LinkedHashMap() }.getOrPut(node) { LinkedHashMap() }
            items[item.attr("id")!!] = item.elements.first().toXml()
            return reply
        }
        val itemsEl = pubsub.child("items")!!
        val node = itemsEl.attr("node")!!
        val store = server.nodes[to]?.get(node) ?: throw OmemoIqException("item-not-found")
        val wanted = itemsEl.child("item")?.attr("id")
        val selected = store.entries.filter { wanted == null || it.key == wanted }
        if (wanted != null && selected.isEmpty()) throw OmemoIqException("item-not-found")
        val items = selected.map { (itemId, xml) ->
            XmlElement("item", linkedMapOf("id" to itemId)).add(XmlParser.parse(xml))
        }
        reply.add(
            XmlElement("pubsub", linkedMapOf("xmlns" to OmemoManager.NS_PUBSUB))
                .add(XmlElement("items", linkedMapOf("node" to node)).addAll(items))
        )
        return reply
    }

    override fun send(stanza: XmlElement) {
        sent += stanza
    }
}

class OmemoManagerTest {

    private val room = "room@conference.example.com"
    private val members = listOf("alice@example.com", "bob@example.com")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** Serializes and re-parses, as the stanza would travel over the socket. */
    private fun wire(el: XmlElement): XmlElement = XmlParser.parse(el.toXml())

    @Test
    fun devicesRoundTripThroughFakePepAndDecrypt() = runBlocking {
        val server = FakePepServer()
        val aliceT = FakeTransport("alice@example.com", server)
        val bobT = FakeTransport("bob@example.com", server)
        val alice = OmemoManager.create(aliceT, InMemoryOmemoStore(), scope)
        val bob = OmemoManager.create(bobT, InMemoryOmemoStore(), scope)

        // Alice to the room: a text message with cleartext <data> beside <encrypted>
        val data = XmlParser.parse("<data senderJID='alice@example.com' note='a&amp;b'/>")
        val stanza = alice.encryptGroupMessage(
            room, members, listOf(XmlElement("body").add("hello bob & <friends>")), "msg-1", listOf(data)
        )
        val received = wire(stanza)
        assertNotNull(received.child("encrypted", OmemoManager.NS_OMEMO))
        assertNotNull(received.child("data"))
        assertEquals("Encrypted message", received.child("body")!!.text())

        val carrier = OmemoStanzas.encryptedCarrier(received)
        assertNotNull(carrier)
        val opened = bob.decrypt(carrier!!, "alice@example.com", room)
        assertEquals("<body>hello bob &amp; &lt;friends&gt;</body>", opened.content)
        assertEquals(OmemoTrust.BLIND, opened.trust)

        // Bob replies; the first message of his session with Alice carries a key exchange
        val reply = wire(bob.encryptGroupMessage(room, members, listOf(XmlElement("body").add("hi alice")), "msg-2"))
        val replyOpened = alice.decrypt(OmemoStanzas.encryptedCarrier(reply)!!, "bob@example.com", room)
        assertEquals("<body>hi alice</body>", replyOpened.content)

        // Alice's second message travels on the ratchet built from the key exchange
        val second = wire(alice.encryptGroupMessage(room, members, listOf(XmlElement("body").add("again")), "msg-3"))
        assertEquals("<body>again</body>", bob.decrypt(OmemoStanzas.encryptedCarrier(second)!!, "alice@example.com", room).content)

        // Bob accepted Alice's key exchange, so he confirmed the session with an empty message
        assertTrue(bobT.sent.isNotEmpty())
        val empty = wire(bobT.sent.first())
        val emptyOpened = alice.decrypt(OmemoStanzas.encryptedCarrier(empty)!!, "bob@example.com", room)
        assertEquals(null, emptyOpened.content)

        // The device list shows Bob's one device, not yet verified: blind trust
        val devices = alice.devices("bob@example.com")
        assertEquals(1, devices.size)
        assertEquals(bob.fingerprint, devices[0].fingerprint)
        assertEquals(OmemoTrust.BLIND, devices[0].trust)
    }

    @Test
    fun encryptingToNobodyThrowsInsteadOfSendingKeyless() = runBlocking {
        val server = FakePepServer()
        val alice = OmemoManager.create(FakeTransport("alice@example.com", server), InMemoryOmemoStore(), scope)
        try {
            alice.encryptGroupMessage(room, emptyList(), listOf(XmlElement("body").add("x")), "msg-x")
            fail("expected OmemoException")
        } catch (e: OmemoException) {
            assertTrue(e.message!!.startsWith("omemo_no_recipients"))
        }
    }

    @Test
    fun encryptedCarrierIsFoundInsideMamWrapper() {
        val wrapped = XmlParser.parse(
            "<message from='room@conference.example.com/bob' id='m9'>" +
                "<result xmlns='urn:xmpp:mam:2' id='arch-1'><forwarded xmlns='urn:xmpp:forward:0'>" +
                "<message type='groupchat'><encrypted xmlns='urn:xmpp:omemo:2'><payload>AAAA</payload></encrypted></message>" +
                "</forwarded></result></message>"
        )
        val carrier = OmemoStanzas.encryptedCarrier(wrapped)
        assertNotNull(carrier)
        assertEquals("message", carrier!!.name)
        assertEquals(null, OmemoStanzas.encryptedCarrier(XmlParser.parse("<message><body>plain</body></message>")))
    }

    @Test
    fun senderIsResolvedFromRoomNick() {
        assertEquals("bob@example.com", OmemoStanzas.senderJidFromMuc("room@conference.example.com/Bob", "example.com"))
        assertEquals(null, OmemoStanzas.senderJidFromMuc("room@conference.example.com", "example.com"))
    }
}
