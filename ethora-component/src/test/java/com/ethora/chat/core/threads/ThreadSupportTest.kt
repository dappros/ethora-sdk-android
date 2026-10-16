package com.ethora.chat.core.threads

import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ThreadSupportTest {

    private val room = "room@conference.xmpp.example.com"

    private fun msg(
        id: String,
        body: String = "hello",
        archiveId: String? = null,
        isReply: Boolean? = null,
        showInChannel: String? = null,
        mainMessage: String? = null,
        isSystemMessage: String? = null
    ) = Message(
        id = id,
        user = User(id = "alice", name = "Alice Smith"),
        date = Date(1_700_000_000_000L),
        body = body,
        roomJid = room,
        archiveId = archiveId,
        isReply = isReply,
        showInChannel = showInChannel,
        mainMessage = mainMessage,
        isSystemMessage = isSystemMessage
    )

    @Test
    fun mainMessageJsonRoundTripsWithQuotesAndNewlines() {
        val parent = msg("uuid-1", body = "say \"hi\"\nline2 \\ end", archiveId = "1700000000000001")
        val json = ThreadSupport.createMainMessageForThread(parent)

        val ref = ThreadSupport.parseMessageReference(json)!!

        assertEquals("1700000000000001", ref.id)
        assertEquals("say \"hi\"\nline2 \\ end", ref.text)
        assertEquals("Alice Smith", ref.userName)
        assertEquals(room, ref.roomJid)
        assertTrue(json.startsWith("{\"text\":"))
        assertTrue(json.contains("\"nftId\":\"\""))
    }

    @Test
    fun parseMessageReferenceRejectsNonJson() {
        assertNull(ThreadSupport.parseMessageReference(null))
        assertNull(ThreadSupport.parseMessageReference(""))
        assertNull(ThreadSupport.parseMessageReference("not json"))
        assertNull(ThreadSupport.parseMessageReference("{\"id\": "))
    }

    @Test
    fun parseMessageReferenceReadsNumericAndNullValues() {
        val ref = ThreadSupport.parseMessageReference("""{"id":1700000000000002,"text":null,"roomJid":"r"}""")!!
        assertEquals("1700000000000002", ref.id)
        assertNull(ref.text)
        assertEquals("r", ref.roomJid)
    }

    @Test
    fun threadOnlyReplyHasParentAndIsHiddenFromMainList() {
        val parentJson = ThreadSupport.createMainMessageForThread(msg("p", archiveId = "100"))
        val threadReply = msg("r1", isReply = true, showInChannel = "false", mainMessage = parentJson)

        assertEquals("100", ThreadSupport.replyParentId(threadReply))
        assertFalse(ThreadSupport.isMainListMessage(threadReply))
        assertTrue(ThreadSupport.isThreadReplyOf(threadReply, "100"))
        assertFalse(ThreadSupport.isThreadReplyOf(threadReply, "999"))
    }

    @Test
    fun alsoSendToRoomReplyIsInMainListAndStillInThread() {
        val parentJson = ThreadSupport.createMainMessageForThread(msg("p", archiveId = "100"))
        val shared = msg("r2", isReply = true, showInChannel = "true", mainMessage = parentJson)

        assertNull(ThreadSupport.replyParentId(shared))
        assertTrue(ThreadSupport.isMainListMessage(shared))
        assertTrue(ThreadSupport.isThreadReplyOf(shared, "100"))
        assertEquals("100", ThreadSupport.threadParentFor(shared))
    }

    @Test
    fun rootAndPlainMessagesAreMainList() {
        val root = msg("p", archiveId = "100")
        assertTrue(ThreadSupport.isMainListMessage(root))
        assertNull(ThreadSupport.threadParentFor(root))
        assertTrue(ThreadSupport.isMainListMessage(msg("s", isSystemMessage = "true")))
    }

    @Test
    fun threadKeyPrefersArchiveIdOverOptimisticId() {
        val own = msg("send-text-message:uuid", archiveId = "1700000000000009")
        assertEquals("1700000000000009", ThreadSupport.threadKey(own))
        assertEquals(1700000000000009L, ThreadSupport.numericKey(own))
        assertNull(ThreadSupport.numericKey(msg("send-text-message:uuid")))
    }
}
