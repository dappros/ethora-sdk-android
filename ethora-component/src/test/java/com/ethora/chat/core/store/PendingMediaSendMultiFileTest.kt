package com.ethora.chat.core.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingMediaSendMultiFileTest {

    private fun legacy() = PendingMediaSend(
        id = "pending-media:legacy",
        roomJid = "room@conference.example.com",
        messageId = "send-media-message:legacy",
        localFilePath = "/tmp/a.png",
        fileName = "a.png",
        mimeType = "image/png"
    )

    @Test
    fun `legacy entry describes its single file`() {
        val item = legacy()
        assertNull(item.files)
        assertEquals(listOf(PendingMediaFile("/tmp/a.png", "a.png", "image/png")), item.effectiveFiles)
    }

    @Test
    fun `multi file entry keeps every file and the first file fields in step`() {
        val files = listOf(
            PendingMediaFile("/tmp/a.png", "a.png", "image/png"),
            PendingMediaFile("/tmp/b.pdf", "b.pdf", "application/pdf")
        )
        val item = legacy().copy(files = files)
        assertEquals(files, item.effectiveFiles)

        val uploaded = PendingMediaUploadPayload(filename = "a.png", location = "https://secure-files.example.com/a.png", mimetype = "image/png", originalName = "a.png", size = "3", createdAt = "1")
        val updated = item.withFiles(listOf(files[0].copy(uploaded = uploaded), files[1]))
        assertEquals(uploaded, updated.uploaded)
        assertEquals("a.png", updated.fileName)
        assertEquals(2, updated.effectiveFiles.size)
    }

    @Test
    fun `files list survives the queue codec`() {
        val item = legacy().copy(
            files = listOf(
                PendingMediaFile("/tmp/a.png", "a.png", "image/png"),
                PendingMediaFile("/tmp/b.png", "b.png", "image/png")
            ),
            caption = "two pictures"
        )
        val decoded = PendingMediaSendCodec.decodeList(PendingMediaSendCodec.encodeList(listOf(item)))
        assertEquals(1, decoded.size)
        assertEquals(item.files, decoded[0].files)
        assertEquals("two pictures", decoded[0].caption)
    }
}
