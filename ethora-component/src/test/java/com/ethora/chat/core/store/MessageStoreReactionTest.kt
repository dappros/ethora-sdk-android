package com.ethora.chat.core.store

import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.User
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Date

class MessageStoreReactionTest {

    private val room = "room-r@conference.xmpp.example.com"

    @Before
    fun reset() {
        MessageStore.clear()
        RoomStore.clear()
    }

    @After
    fun tearDown() {
        MessageStore.clear()
        RoomStore.clear()
    }

    private fun message(id: String, archiveId: String? = null) = Message(
        id = id,
        user = User(id = "u-1", firstName = "A"),
        date = Date(1_700_000_000_000L),
        body = "text",
        roomJid = room,
        archiveId = archiveId
    )

    private fun reactionsOf(id: String): Map<String, String> =
        MessageStore.getMessagesForRoom(room).first { it.id == id }
            .reaction.orEmpty().mapValues { it.value.emoji.joinToString(",") }

    @Test
    fun reactionAppliesToLoadedMessageByArchiveId() {
        MessageStore.addMessage(room, message("1700000000000001", archiveId = "1700000000000001"))

        MessageStore.setReaction(room, "1700000000000001", "bob", listOf("heart"), mapOf("senderFirstName" to "Bob"), 10)

        assertEquals(mapOf("bob" to "heart"), reactionsOf("1700000000000001"))
    }

    @Test
    fun olderReactionNeverOverwritesNewer() {
        MessageStore.addMessage(room, message("m1"))
        MessageStore.setReaction(room, "m1", "bob", listOf("heart"), emptyMap(), 200)

        MessageStore.setReaction(room, "m1", "bob", listOf("joy"), emptyMap(), 100)

        assertEquals(mapOf("bob" to "heart"), reactionsOf("m1"))
    }

    @Test
    fun emptyListRemovesReactorAndStaysRemovedAgainstOlderReplay() {
        MessageStore.addMessage(room, message("m2"))
        MessageStore.setReaction(room, "m2", "bob", listOf("heart"), emptyMap(), 100)
        MessageStore.setReaction(room, "m2", "bob", emptyList(), emptyMap(), 300)
        MessageStore.setReaction(room, "m2", "bob", listOf("heart"), emptyMap(), 150)

        assertEquals(emptyMap<String, String>(), reactionsOf("m2"))
    }

    @Test
    fun reactionArrivingBeforeTargetIsAppliedWhenTargetLoads() {
        MessageStore.setReaction(room, "1700000000000050", "carol", listOf("+1"), mapOf("senderFirstName" to "C"), 5)
        assertNull(MessageStore.getMessagesForRoom(room).firstOrNull())

        MessageStore.addMessage(room, message("1700000000000050", archiveId = "1700000000000050"))

        assertEquals(mapOf("carol" to "+1"), reactionsOf("1700000000000050"))
    }

    @Test
    fun editMarksMessageAsEdited() {
        MessageStore.addMessage(room, message("m3"))
        MessageStore.editMessage(room, "m3", "changed")

        val edited = MessageStore.getMessagesForRoom(room).first { it.id == "m3" }
        assertEquals(true, edited.isEdited)
        assertEquals("changed", edited.body)
    }
}
