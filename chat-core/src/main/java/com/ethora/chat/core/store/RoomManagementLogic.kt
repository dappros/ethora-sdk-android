package com.ethora.chat.core.store

import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.RoomType
import com.ethora.chat.core.networking.PublicChat
import com.ethora.chat.core.networking.PublicChatsPage

/**
 * Pure rules behind the room management screens (public directory, report sheet, members),
 * ported from the web modals so they can be tested without a UI.
 */
object RoomManagementLogic {

    /** A report category: [id] is the English value sent to the API, [labelKey] the UI string key. */
    data class ReportCategory(val id: String, val labelKey: String)

    /** Web `ModalReportChat`: the six fixed categories, then `Other` (which asks for details). */
    val REPORT_CATEGORIES: List<ReportCategory> = listOf(
        ReportCategory("Spam", "report.category.spam"),
        ReportCategory("Violence", "report.category.violence"),
        ReportCategory("Child Abuse", "report.category.childAbuse"),
        ReportCategory("Pornography", "report.category.pornography"),
        ReportCategory("Personal Details", "report.category.personalDetails"),
        ReportCategory("Illegal Drugs", "report.category.illegalDrugs")
    )
    val OTHER_CATEGORY = ReportCategory("Other", "report.category.other")

    /** `Other` needs the reporter's own words; the fixed categories send an empty text. */
    fun canSubmitReport(category: ReportCategory?, text: String): Boolean = when {
        category == null -> false
        category.id == OTHER_CATEGORY.id -> text.isNotBlank()
        else -> true
    }

    fun reportText(category: ReportCategory, text: String): String =
        if (category.id == OTHER_CATEGORY.id) text.trim() else ""

    /** Only a moderator of a group chat adds or removes members (web ChatProfileModal). */
    fun canManageMembers(room: Room): Boolean =
        room.role == "moderator" && room.type == RoomType.GROUP

    /** The xmppUsername to invite, or null when the input is not a usable user id. */
    fun normalizeMemberInput(raw: String): String? =
        raw.trim().takeIf { it.length >= 3 && it.none { c -> c.isWhitespace() } }

    /** Bare names (JID localparts) of the rooms the user is already in. */
    fun joinedNames(rooms: List<Room>): Set<String> = rooms.map { it.jid.substringBefore("@") }.toSet()

    /** Filter on the title and description of the chats loaded so far (case-insensitive). */
    fun filterPublicChats(items: List<PublicChat>, filter: String): List<PublicChat> {
        val needle = filter.trim().lowercase()
        if (needle.isEmpty()) return items
        return items.filter { it.title.lowercase().contains(needle) || it.description.lowercase().contains(needle) }
    }

    /** Appends a page, skipping chats already listed; the first page replaces the list. */
    fun mergePublicChats(existing: List<PublicChat>, page: PublicChatsPage, offset: Int): List<PublicChat> {
        if (offset == 0) return page.items
        val seen = existing.mapTo(HashSet()) { it.name }
        return existing + page.items.filter { it.name !in seen }
    }

    fun hasMorePublicChats(nextOffset: Int, total: Int): Boolean = nextOffset < total

    fun publicChatInitial(chat: PublicChat): String =
        (chat.title.ifBlank { chat.name }).trim().take(1).uppercase().ifEmpty { "#" }
}
