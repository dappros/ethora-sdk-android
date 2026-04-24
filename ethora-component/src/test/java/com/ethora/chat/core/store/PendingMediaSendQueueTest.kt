package com.ethora.chat.core.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingMediaSendQueueTest {
    @Test
    fun `encode and decode preserves pending media send fields`() {
        val item = PendingMediaSend(
            id = "queue-1",
            roomJid = "room@conference.example.com",
            messageId = "send-media-message:1",
            localFilePath = "/private/pending/report.pdf",
            fileName = "report.pdf",
            mimeType = "application/pdf",
            createdAt = 1000L,
            attemptCount = 2,
            nextRetryAt = 1500L,
            status = PendingMediaSendStatus.READY_TO_SEND,
            uploaded = PendingMediaUploadPayload(
                filename = "server-report.pdf",
                location = "https://cdn.example.com/report.pdf",
                locationPreview = "",
                mimetype = "application/pdf",
                originalName = "report.pdf",
                size = "42",
                createdAt = "2026-04-24T00:00:00.000Z",
                expiresAt = "",
                isVisible = true,
                ownerKey = "",
                updatedAt = "",
                userId = "user-1",
                duration = "",
                waveForm = "",
                attachmentId = "attachment-1"
            )
        )

        val decoded = PendingMediaSendCodec.decodeList(PendingMediaSendCodec.encodeList(listOf(item)))

        assertEquals(listOf(item), decoded)
    }

    @Test
    fun `decode ignores corrupt stored queue records`() {
        val json = """
            [
              {"id":"queue-1","roomJid":"room","messageId":"msg","localFilePath":"/file.pdf","fileName":"file.pdf","mimeType":"application/pdf","createdAt":1,"attemptCount":0,"nextRetryAt":0,"status":"QUEUED"},
              {"id":null,"roomJid":"room","messageId":"broken","localFilePath":"","fileName":"","mimeType":"","createdAt":1,"attemptCount":0,"nextRetryAt":0,"status":"QUEUED"}
            ]
        """.trimIndent()

        val decoded = PendingMediaSendCodec.decodeList(json)

        assertEquals(1, decoded.size)
        assertEquals("queue-1", decoded.first().id)
    }

    @Test
    fun `markFailedForRetry increments attempts and schedules retry`() {
        val item = PendingMediaSend(
            id = "queue-1",
            roomJid = "room",
            messageId = "msg",
            localFilePath = "/file.pdf",
            fileName = "file.pdf",
            mimeType = "application/pdf",
            createdAt = 1L
        )

        val failed = item.failedForRetry(now = 10_000L)

        assertEquals(PendingMediaSendStatus.FAILED_WAITING_RETRY, failed.status)
        assertEquals(1, failed.attemptCount)
        assertTrue(failed.nextRetryAt > 10_000L)
    }

    @Test
    fun `sanitizeFileName preserves pdf extension and removes path characters`() {
        val sanitized = PendingMediaSendQueue.sanitizeFileName("../Quarterly Report.pdf")

        assertEquals("Quarterly_Report.pdf", sanitized)
    }
}
