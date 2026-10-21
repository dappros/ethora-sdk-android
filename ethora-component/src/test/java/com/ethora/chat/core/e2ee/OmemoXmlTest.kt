package com.ethora.chat.core.e2ee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OmemoXmlTest {

    @Test
    fun roundTripsEscapedTextAndAttributes() {
        val xml = "<message id='a1' type=\"groupchat\"><body>1 &lt; 2 &amp; &quot;x&quot;</body>" +
            "<data senderJID=\"bob@x.org\" note=\"a&amp;b\"/></message>"
        val parsed = XmlParser.parse(xml)
        assertEquals("a1", parsed.attr("id"))
        assertEquals("1 < 2 & \"x\"", parsed.child("body")!!.text())
        assertEquals("a&b", parsed.child("data")!!.attr("note"))
        val again = XmlParser.parse(parsed.toXml())
        assertEquals(parsed.toXml(), again.toXml())
    }

    @Test
    fun namespaceIsInheritedFromAncestors() {
        val parsed = XmlParser.parse("<message xmlns='jabber:client'><encrypted xmlns='urn:xmpp:omemo:2'/></message>")
        assertEquals("urn:xmpp:omemo:2", parsed.child("encrypted")!!.namespace)
        assertEquals("jabber:client", parsed.namespace)
        assertNotNull(parsed.child("encrypted", "urn:xmpp:omemo:2"))
        assertNull(parsed.child("encrypted", "urn:other"))
    }

    @Test
    fun movingAnElementDetachesItFromItsOldParent() {
        val source = XmlParser.parse("<wrap><body>hi</body></wrap>")
        val body = source.child("body")!!
        val target = XmlElement("message")
        target.add(body)
        assertEquals(0, source.elements.size)
        assertEquals("<message><body>hi</body></message>", target.toXml())
    }

    @Test
    fun parsesFragmentsAndSkipsDeclarations() {
        val fragments = XmlParser.parseFragments("<data a='1'/><store xmlns='urn:xmpp:hints'/>")
        assertEquals(2, fragments.size)
        val withProlog = XmlParser.parse("<?xml version='1.0'?><!-- c --><iq type='result'/>")
        assertEquals("iq", withProlog.name)
    }

    @Test
    fun removeChildrenDropsMatchingElements() {
        val root = XmlParser.parse("<message><body>a</body><encrypted xmlns='urn:xmpp:omemo:2'/><body>b</body></message>")
        root.removeChildren("body")
        assertEquals("<message><encrypted xmlns=\"urn:xmpp:omemo:2\"/></message>", root.toXml())
    }
}
