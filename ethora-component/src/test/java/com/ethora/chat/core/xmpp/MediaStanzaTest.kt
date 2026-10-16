package com.ethora.chat.core.xmpp

import com.ethora.chat.core.models.MessageAttachment
import com.ethora.chat.core.models.MessageAttachments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStanzaTest {

    private val attachments = listOf(
        MessageAttachment(location = "https://secure-files.example.com/1.png", mimetype = "image/png", originalName = "a's & \"b\".png"),
        MessageAttachment(location = "https://secure-files.example.com/2.pdf", mimetype = "application/pdf", originalName = "2.pdf")
    )

    @Test
    fun `media stanza round-trips attachments and caption through data attributes`() {
        val caption = "look at <this> & \"that\" isn't it"
        val mediaData: Map<String, Any> = mapOf(
            "location" to attachments[0].location,
            "mimetype" to "image/png",
            "isMediafile" to "true",
            "attachments" to MessageAttachments.serialize(attachments),
            "caption" to caption
        )
        val xml = buildMediaMessageXml(
            fixedRoomJid = "room@conference.example.com",
            fromAttribute = " from=\"me@xmpp.example.com\"",
            mediaData = mediaData,
            safeId = "send-media-message:abc"
        )

        assertTrue(xml.startsWith("<message id=\"send-media-message:abc\" type=\"groupchat\""))
        assertTrue(xml.contains("<body>media</body>"))
        assertTrue(xml.contains("<origin-id xmlns=\"urn:xmpp:sid:0\" id=\"send-media-message:abc\"/>"))

        val dataXml = extractDataElement(xml)
        assertEquals(caption, extractAttribute(dataXml, "caption"))
        assertEquals(attachments, MessageAttachments.parse(extractAttribute(dataXml, "attachments")))
        assertEquals("https://secure-files.example.com/1.png", extractAttribute(dataXml, "location"))
    }

    @Test
    fun `single file stanza carries no attachments attribute`() {
        val xml = buildMediaMessageXml(
            fixedRoomJid = "room@conference.example.com",
            fromAttribute = "",
            mediaData = mapOf("location" to "https://secure-files.example.com/1.png", "isMediafile" to "true"),
            safeId = "id-1"
        )
        assertEquals(null, extractAttribute(extractDataElement(xml), "attachments"))
    }
}
