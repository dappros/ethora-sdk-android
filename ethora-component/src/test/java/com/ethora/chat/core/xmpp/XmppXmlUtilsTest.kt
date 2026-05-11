package com.ethora.chat.core.xmpp

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * Unit tests for the shared XMPP XML parsing helpers in
 * [XmppXmlUtils]. These functions are `internal` to `chat-core`;
 * `:chat-core:test` runs in-module and sees them directly.
 *
 * Regression-risk context (from the source comment):
 *   "Centralised because the two paths had drifted apart on <data>
 *    handling and attribute decoding, which made file messages loaded
 *    from history render as plain text and broke signed-URL thumbnails."
 *
 * Every assertion below maps to a real failure mode the live / MAM
 * parser combo has hit at some point.
 */
class XmppXmlUtilsTest {

    // --- extractDataElement -------------------------------------------

    @Test
    fun `extractDataElement returns empty string when no data tag present`() {
        assertEquals("", extractDataElement("<message><body>hi</body></message>"))
    }

    @Test
    fun `extractDataElement returns empty string for empty input`() {
        assertEquals("", extractDataElement(""))
    }

    @Test
    fun `extractDataElement extracts a self-closing data tag`() {
        val xml = "<message><data url=\"https://x\"/></message>"
        // Self-closing case: substring from <data to and including />.
        assertEquals("<data url=\"https://x\"/>", extractDataElement(xml))
    }

    @Test
    fun `extractDataElement extracts open-close data tag with body`() {
        val xml = "<message><data url=\"https://x\">inner</data></message>"
        assertEquals(
            "<data url=\"https://x\">inner</data>",
            extractDataElement(xml)
        )
    }

    @Test
    fun `extractDataElement returns empty when opening tag has no closing bracket`() {
        // Malformed input — opening "<data" never closes. The function
        // should not throw, just return "".
        assertEquals("", extractDataElement("<message><data url=\"x"))
    }

    @Test
    fun `extractDataElement returns empty when closing data tag is missing`() {
        val xml = "<message><data url=\"x\">inner without close"
        assertEquals("", extractDataElement(xml))
    }

    // --- extractAttribute ---------------------------------------------

    @Test
    fun `extractAttribute returns null for empty xml`() {
        assertNull(extractAttribute("", "url"))
    }

    @Test
    fun `extractAttribute returns null when attribute is absent`() {
        assertNull(extractAttribute("<data id=\"x\"/>", "url"))
    }

    @Test
    fun `extractAttribute reads double-quoted value`() {
        val xml = "<data url=\"https://example.com/file.png\"/>"
        assertEquals(
            "https://example.com/file.png",
            extractAttribute(xml, "url")
        )
    }

    @Test
    fun `extractAttribute reads single-quoted value`() {
        // Some XMPP servers emit single-quoted attributes on stanzas
        // forwarded through MAM. Both quoting styles must be accepted.
        val xml = "<data url='https://example.com/file.png'/>"
        assertEquals(
            "https://example.com/file.png",
            extractAttribute(xml, "url")
        )
    }

    @Test
    fun `extractAttribute reads unquoted value up to first whitespace`() {
        val xml = "<data url=https://example.com/file.png mimetype=image/png/>"
        assertEquals(
            "https://example.com/file.png",
            extractAttribute(xml, "url")
        )
        // And the second attribute still resolves correctly with the
        // unquoted-stop-on-whitespace rule.
        assertEquals(
            "image/png/",
            extractAttribute(xml, "mimetype")
        )
    }

    @Test
    fun `extractAttribute decodes XML entities in value`() {
        // Signed-URL thumbnails contain & in the query string, which on
        // the wire is encoded as &amp;. The history parser must decode
        // back so the URL is fetch-able — that exact regression is
        // what motivated centralising this helper.
        val xml = "<data url=\"https://example.com/file.png?sig=abc&amp;exp=123\"/>"
        assertEquals(
            "https://example.com/file.png?sig=abc&exp=123",
            extractAttribute(xml, "url")
        )
    }

    @Test
    fun `extractAttribute decodes all common entity replacements`() {
        val xml = "<data caption=\"&lt;b&gt; &quot;hi&quot; &apos;you&apos; &amp;me\"/>"
        assertEquals(
            "<b> \"hi\" 'you' &me",
            extractAttribute(xml, "caption")
        )
    }

    // --- extractBody --------------------------------------------------

    @Test
    fun `extractBody returns null when no body tag present`() {
        assertNull(extractBody("<message><data url=\"x\"/></message>"))
    }

    @Test
    fun `extractBody returns null for empty input`() {
        assertNull(extractBody(""))
    }

    @Test
    fun `extractBody reads simple body`() {
        assertEquals("hello", extractBody("<message><body>hello</body></message>"))
    }

    @Test
    fun `extractBody reads body with xml lang attribute`() {
        // ejabberd's MAM re-serialisation regularly tacks xml:lang onto
        // archived bodies. The old `indexOf("<body>")` parser silently
        // dropped these stanzas — the root cause of "single chat history
        // omits some messages".
        val xml = "<message><body xml:lang=\"en\">hi there</body></message>"
        assertEquals("hi there", extractBody(xml))
    }

    @Test
    fun `extractBody reads body with jabber-client namespace attribute`() {
        // Some servers re-emit archived stanzas with the full
        // `xmlns="jabber:client"` namespace declaration on every element
        // inside <forwarded>. Must still parse.
        val xml = "<forwarded><message><body xmlns=\"jabber:client\">media</body></message></forwarded>"
        assertEquals("media", extractBody(xml))
    }

    @Test
    fun `extractBody decodes xml entities inside body`() {
        // A user message like `<a> & "b"` round-trips through the wire as
        // entities; the parser must decode so the chat shows the
        // original characters.
        val xml = "<message><body>&lt;a&gt; &amp; &quot;b&quot;</body></message>"
        assertEquals("<a> & \"b\"", extractBody(xml))
    }

    @Test
    fun `extractBody is non-greedy across two messages in one buffer`() {
        // MAM result wraps a <forwarded><message><body>...</body></message></forwarded>
        // inside an outer <message>. The regex must stop at the first
        // </body>, not consume across both.
        val xml = "<message><body>first</body></message><message><body>second</body></message>"
        assertEquals("first", extractBody(xml))
    }

    @Test
    fun `extractBody returns empty string for explicitly empty body`() {
        // Empty body is distinct from "no body at all" — caller decides
        // whether to drop it (parseMAMResult drops `body.isBlank()` so
        // this still routes to the skip path).
        assertEquals("", extractBody("<message><body></body></message>"))
    }

    // --- unwrapMucSubInnerMessage --------------------------------------

    @Test
    fun `unwrapMucSubInnerMessage returns input unchanged when no pubsub wrapper`() {
        val xml = "<message id='send-text-message:abc' type='groupchat' from='r@conf/u'><body>hi</body></message>"
        assertEquals(xml, unwrapMucSubInnerMessage(xml))
    }

    @Test
    fun `unwrapMucSubInnerMessage returns input unchanged for empty string`() {
        assertEquals("", unwrapMucSubInnerMessage(""))
    }

    @Test
    fun `unwrapMucSubInnerMessage extracts inner message from MUC-SUB pubsub wrapper`() {
        // Verbatim shape from production logs: server delivers each room
        // chat message wrapped in a pubsub event whose own id is a
        // numeric sequence. The inner stanza carries the original
        // `send-text-message:UUID` id our SDK sent — that's the one
        // optimistic-row dedup needs.
        val outer = "<message xmlns='jabber:client' id='1778505884432425' " +
            "from='room@conf/u' to='me@host'>" +
            "<event xmlns='http://jabber.org/protocol/pubsub#event'>" +
            "<items node='urn:xmpp:mucsub:nodes:messages'>" +
            "<item id='1778505884432425'>" +
            "<message xml:lang='en' id='send-text-message:dba9ab35' type='groupchat' " +
            "from='room@conf/u' xmlns='jabber:client'>" +
            "<body>hello world</body>" +
            "<stanza-id by='room@conf' id='1778505884432425' xmlns='urn:xmpp:sid:0'/>" +
            "</message></item></items></event></message>"

        val inner = unwrapMucSubInnerMessage(outer)
        // The inner stanza is what we want — verify it starts with the
        // inner <message> open tag and ends with the matching close.
        assertTrue(
            "unwrapped XML must begin with the inner <message>, was: ${inner.take(80)}",
            inner.startsWith("<message xml:lang='en' id='send-text-message:dba9ab35'")
        )
        assertTrue(
            "unwrapped XML must end with </message>, was: …${inner.takeLast(40)}",
            inner.endsWith("</message>")
        )
        // Crucially: reading `id` off the unwrapped XML must yield the
        // optimistic UUID, NOT the outer pubsub sequence number. This is
        // the exact dedup signal that was broken before the unwrap.
        assertEquals("send-text-message:dba9ab35", extractAttribute(inner, "id"))
    }

    @Test
    fun `unwrapMucSubInnerMessage handles nested message elements inside the forwarded stanza`() {
        // Guard rail: some servers nest <message> inside <reactions>,
        // <reply>, or other extensions. The depth tracker must close on
        // the outermost </message>, not the first one.
        val outer = "<message id='outer-1'>" +
            "<event xmlns='http://jabber.org/protocol/pubsub#event'>" +
            "<items node='urn:xmpp:mucsub:nodes:messages'>" +
            "<item id='outer-1'>" +
            "<message id='real-1' type='groupchat'>" +
            "<reactions xmlns='urn:xmpp:reactions:0'>" +
            "<message id='nested-noise'>filler</message>" +
            "</reactions>" +
            "<body>hi</body>" +
            "</message>" +
            "</item></items></event></message>"

        val inner = unwrapMucSubInnerMessage(outer)
        // The unwrapped stanza must include the body and end at the
        // matching close, not at the nested </message>.
        assertEquals("real-1", extractAttribute(inner, "id"))
        assertTrue(
            "nested filler must be retained inside the unwrapped stanza: $inner",
            inner.contains("nested-noise")
        )
        assertTrue(inner.contains("<body>hi</body>"))
    }

    @Test
    fun `unwrapMucSubInnerMessage returns input unchanged for non-mucsub pubsub events`() {
        // Defensive: only the `mucsub:nodes:messages` node carries chat
        // messages we should unwrap. Other pubsub-event traffic (e.g.
        // PEP notifications) must pass through untouched so the realtime
        // parser still classifies it correctly.
        val xml = "<message id='outer'>" +
            "<event xmlns='http://jabber.org/protocol/pubsub#event'>" +
            "<items node='some:other:node'><item><payload/></item></items>" +
            "</event></message>"
        assertEquals(xml, unwrapMucSubInnerMessage(xml))
    }

    @Test
    fun `extractAttribute handles attribute appearing later in tag`() {
        // Some stanzas put structural attrs (id, from, to) first and the
        // data-bearing ones (url, mimetype) at the end. Regex is greedy
        // enough to find any position.
        val xml = "<data id=\"abc\" type=\"image\" url=\"https://x.example/img.png\"/>"
        assertEquals(
            "https://x.example/img.png",
            extractAttribute(xml, "url")
        )
    }
}
