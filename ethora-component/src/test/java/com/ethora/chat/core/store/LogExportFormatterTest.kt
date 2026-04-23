package com.ethora.chat.core.store

import org.junit.Assert.assertTrue
import org.junit.Test

class LogExportFormatterTest {
    @Test
    fun `exportText orders entries oldest first and includes metadata`() {
        val text = LogStore.exportText(
            listOf(
                LogStore.LogEntry(
                    sessionId = "session-a",
                    eventId = 2L,
                    timestamp = "2026-04-23 10:00:02.100",
                    relativeMs = 2100L,
                    tag = "XMPP",
                    category = "xmpp-recv",
                    message = "incoming stanza",
                    type = LogStore.LogType.RECEIVE,
                    rawMessage = "<message id='2' />"
                ),
                LogStore.LogEntry(
                    sessionId = "session-a",
                    eventId = 1L,
                    timestamp = "2026-04-23 10:00:01.000",
                    relativeMs = 1000L,
                    tag = "Chat",
                    category = "single-room",
                    message = "bootstrap started",
                    type = LogStore.LogType.INFO,
                    rawMessage = null
                )
            )
        )

        assertTrue(text.indexOf("event=1") < text.indexOf("event=2"))
        assertTrue(text.contains("category=single-room"))
        assertTrue(text.contains("category=xmpp-recv"))
        assertTrue(text.contains("raw=<message id='2' />"))
    }
}
