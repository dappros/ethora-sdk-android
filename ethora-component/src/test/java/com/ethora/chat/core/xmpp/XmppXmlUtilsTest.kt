package com.ethora.chat.core.xmpp

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

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
