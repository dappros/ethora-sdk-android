package com.ethora.chat.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class MessageAttachmentsTest {

    private fun message(
        location: String? = null,
        locationPreview: String? = null,
        mimetype: String? = null,
        originalName: String? = null,
        attachments: List<MessageAttachment>? = null
    ) = Message(
        id = "m1",
        user = User(id = "u1"),
        date = Date(0),
        body = "media",
        roomJid = "room@conference.example.com",
        isMediafile = "true",
        location = location,
        locationPreview = locationPreview,
        mimetype = mimetype,
        originalName = originalName,
        attachments = attachments
    )

    @Test
    fun `serialize then parse round-trips quotes, apostrophes and ampersands`() {
        val items = listOf(
            MessageAttachment(
                attachmentId = "a1",
                location = "https://secure-files.example.com/x.png?ft=1&v=2",
                locationPreview = "https://secure-files.example.com/x_p.png",
                mimetype = "image/png",
                originalName = "Tom's \"best\" photo & more.png",
                fileName = "x.png",
                size = "1024"
            ),
            MessageAttachment(location = "https://secure-files.example.com/y.pdf", mimetype = "application/pdf", originalName = "y.pdf")
        )
        val parsed = MessageAttachments.parse(MessageAttachments.serialize(items))
        assertEquals(items, parsed)
    }

    @Test
    fun `junk input parses to empty`() {
        assertTrue(MessageAttachments.parse(null).isEmpty())
        assertTrue(MessageAttachments.parse("not json").isEmpty())
        assertTrue(MessageAttachments.parse("{\"a\":1}").isEmpty())
    }

    @Test
    fun `parse keeps at most ten attachments`() {
        val many = (1..12).map { MessageAttachment(location = "https://secure-files.example.com/$it.png", mimetype = "image/png") }
        assertEquals(MessageAttachments.MAX_PER_MESSAGE, MessageAttachments.parse(MessageAttachments.serialize(many)).size)
    }

    @Test
    fun `attachments list wins over the flat fields`() {
        val list = listOf(
            MessageAttachment(location = "https://a/1.png", mimetype = "image/png"),
            MessageAttachment(location = "https://a/2.png", mimetype = "image/png")
        )
        val result = MessageAttachments.forMessage(message(location = "https://a/1.png", mimetype = "image/png", attachments = list))
        assertEquals(list, result)
    }

    @Test
    fun `flat single file becomes one attachment`() {
        val result = MessageAttachments.forMessage(message(location = "https://a/1.pdf", mimetype = "application/pdf", originalName = "1.pdf"))
        assertEquals(1, result.size)
        assertEquals("https://a/1.pdf", result[0].location)
        assertEquals("application/pdf", result[0].mimetype)
    }

    @Test
    fun `legacy comma separated url list is split`() {
        val result = MessageAttachments.forMessage(
            message(location = "https://a/1.png, https://a/2.jpg", mimetype = "image/png, image/jpeg")
        )
        assertEquals(listOf("https://a/1.png", "https://a/2.jpg"), result.map { it.location })
        assertEquals(listOf("image/png", "image/jpeg"), result.map { it.mimetype })
    }

    @Test
    fun `no location means no attachments`() {
        assertTrue(MessageAttachments.forMessage(message()).isEmpty())
    }

    @Test
    fun `partition makes album tiles only from two or more visual files`() {
        val img1 = MessageAttachment(location = "a", mimetype = "image/png")
        val img2 = MessageAttachment(location = "b", mimetype = "video/mp4")
        val pdf = MessageAttachment(location = "c", mimetype = "application/pdf")

        val album = MessageAttachments.partition(listOf(img1, pdf, img2))
        assertEquals(listOf(0, 2), album.tiles.map { it.index })
        assertEquals(listOf(1), album.rows.map { it.index })

        val solo = MessageAttachments.partition(listOf(img1, pdf))
        assertTrue(solo.tiles.isEmpty())
        assertEquals(listOf(0, 1), solo.rows.map { it.index })
    }

    @Test
    fun `withAttachment describes only that file`() {
        val source = message(location = "x", attachments = listOf(MessageAttachment(location = "p")))
        val picked = MessageAttachment(location = "https://a/2.png", mimetype = "image/png", originalName = "2.png")
        val copy = source.withAttachment(picked)
        assertEquals("https://a/2.png", copy.location)
        assertEquals("image/png", copy.mimetype)
        assertEquals("2.png", copy.originalName)
        assertNull(copy.attachments)
    }
}
