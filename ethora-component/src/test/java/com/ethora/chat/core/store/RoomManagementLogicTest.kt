package com.ethora.chat.core.store

import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.RoomType
import com.ethora.chat.core.networking.PublicChat
import com.ethora.chat.core.networking.PublicChatsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomManagementLogicTest {

    private fun chat(name: String, title: String = name, description: String = "") =
        PublicChat(name, title, description, "", false)

    private fun page(items: List<PublicChat>, total: Int = 10, next: Int = items.size) =
        PublicChatsPage(items, total, 0, 50, next)

    @Test
    fun `report categories match web and Other needs details`() {
        assertEquals(
            listOf("Spam", "Violence", "Child Abuse", "Pornography", "Personal Details", "Illegal Drugs"),
            RoomManagementLogic.REPORT_CATEGORIES.map { it.id }
        )
        val spam = RoomManagementLogic.REPORT_CATEGORIES.first()
        assertTrue(RoomManagementLogic.canSubmitReport(spam, ""))
        assertEquals("", RoomManagementLogic.reportText(spam, "ignored"))
        assertFalse(RoomManagementLogic.canSubmitReport(RoomManagementLogic.OTHER_CATEGORY, "   "))
        assertTrue(RoomManagementLogic.canSubmitReport(RoomManagementLogic.OTHER_CATEGORY, "rude"))
        assertEquals("rude", RoomManagementLogic.reportText(RoomManagementLogic.OTHER_CATEGORY, "  rude "))
        assertFalse(RoomManagementLogic.canSubmitReport(null, "x"))
    }

    @Test
    fun `only a moderator of a group manages members`() {
        fun room(role: String?, type: RoomType) = Room(id = "r", jid = "r@c", name = "r", title = "r", usersCnt = 2, role = role, type = type)
        assertTrue(RoomManagementLogic.canManageMembers(room("moderator", RoomType.GROUP)))
        assertFalse(RoomManagementLogic.canManageMembers(room("participant", RoomType.GROUP)))
        assertFalse(RoomManagementLogic.canManageMembers(room("moderator", RoomType.PRIVATE)))
    }

    @Test
    fun `member input is trimmed and short or spaced ids are refused`() {
        assertEquals("app_user1", RoomManagementLogic.normalizeMemberInput("  app_user1 "))
        assertNull(RoomManagementLogic.normalizeMemberInput("ab"))
        assertNull(RoomManagementLogic.normalizeMemberInput("a b c"))
    }

    @Test
    fun `public chats filter on title and description`() {
        val items = listOf(chat("a", "Kotlin Fans", "coroutines"), chat("b", "Cooking", "Pasta"), chat("c", "Chess"))
        assertEquals(items, RoomManagementLogic.filterPublicChats(items, "  "))
        assertEquals(listOf("a"), RoomManagementLogic.filterPublicChats(items, "kotlin").map { it.name })
        assertEquals(listOf("b"), RoomManagementLogic.filterPublicChats(items, "PASTA").map { it.name })
    }

    @Test
    fun `pages append without duplicates and the first page replaces`() {
        val first = page(listOf(chat("a"), chat("b")))
        val merged = RoomManagementLogic.mergePublicChats(first.items, page(listOf(chat("b"), chat("c"))), offset = 2)
        assertEquals(listOf("a", "b", "c"), merged.map { it.name })
        assertEquals(listOf("x"), RoomManagementLogic.mergePublicChats(merged, page(listOf(chat("x"))), offset = 0).map { it.name })
        assertTrue(RoomManagementLogic.hasMorePublicChats(2, 10))
        assertFalse(RoomManagementLogic.hasMorePublicChats(10, 10))
    }

    @Test
    fun `joined names and initials`() {
        val rooms = listOf(Room(id = "1", jid = "app_one@conf.h", name = "One", title = "One", usersCnt = 1))
        assertEquals(setOf("app_one"), RoomManagementLogic.joinedNames(rooms))
        assertEquals("K", RoomManagementLogic.publicChatInitial(chat("k", "kotlin")))
        assertEquals("#", RoomManagementLogic.publicChatInitial(chat("", "")))
    }
}
