package com.ethora.chat.core.store

import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.RoomMember

/**
 * Live membership bookkeeping for a room, mirroring web stanzaHandlers.ts
 * (`onPresenceInRoom`, `onRoomMembershipChange`). Pure functions: each takes
 * the current room and returns the room to store, or null when nothing
 * changes.
 *
 * `usersCnt` is the real total. `members` may be a truncated page of a big
 * room, so a leave or join that is not in the loaded page only moves the
 * count, and a join is never guessed when the page is truncated.
 */
internal object RoomMembership {

    /** Count to display: the total, never less than the loaded members. */
    fun userCount(room: Room): Int = maxOf(room.usersCnt, room.members?.size ?: 0)

    /** True when the room has more users than the members loaded. */
    fun isTruncated(room: Room): Boolean = room.usersCnt > (room.members?.size ?: 0)

    private fun adjusted(room: Room, delta: Int, nextMembersSize: Int): Int =
        maxOf(nextMembersSize, userCount(room) + delta, 0)

    /**
     * Presence of another occupant (`nick` is their xmppUsername). Web's
     * onPresenceInRoom: an `unavailable` presence with affiliation none or
     * outcast is a kick or a leave; an available presence with an owner,
     * admin or member affiliation is a join.
     */
    fun afterPresence(room: Room, nick: String, affiliation: String, unavailable: Boolean): Room? {
        if (nick.isEmpty()) return null
        val kicked = unavailable && (affiliation == "none" || affiliation == "outcast")
        val persistent = !unavailable &&
            (affiliation == "owner" || affiliation == "admin" || affiliation == "member")
        if (!kicked && !persistent) return null
        return if (kicked) removeMember(room, nick) else addMember(room, nick, jid = "")
    }

    /**
     * A `<message><x muc#user><item jid affiliation/></x></message>` change
     * (server-driven add or remove). Web onRoomMembershipChange: none or
     * outcast removes, anything else adds, and an add is not guessed on a
     * truncated page.
     */
    fun afterMembershipMessage(
        room: Room,
        xmppUsername: String,
        memberJid: String,
        affiliation: String
    ): Room? {
        if (xmppUsername.isEmpty()) return null
        return if (affiliation == "none" || affiliation == "outcast") {
            removeMember(room, xmppUsername)
        } else {
            val members = room.members.orEmpty()
            if (members.any { it.xmppUsername == xmppUsername }) return null
            val next = members + placeholder(xmppUsername, memberJid)
            room.copy(members = next, usersCnt = adjusted(room, 1, next.size))
        }
    }

    private fun removeMember(room: Room, nick: String): Room? {
        val members = room.members.orEmpty()
        val idx = members.indexOfFirst { it.xmppUsername == nick }
        if (idx < 0) {
            // The leaver may exist only in the directory of a big room.
            if (!isTruncated(room)) return null
            return room.copy(usersCnt = adjusted(room, -1, members.size))
        }
        val next = members.filterIndexed { i, _ -> i != idx }
        return room.copy(members = next, usersCnt = adjusted(room, -1, next.size))
    }

    private fun addMember(room: Room, nick: String, jid: String): Room? {
        val members = room.members.orEmpty()
        if (members.any { it.xmppUsername == nick } || isTruncated(room)) return null
        val next = members + placeholder(nick, jid)
        return room.copy(members = next, usersCnt = adjusted(room, 1, next.size))
    }

    private fun placeholder(xmppUsername: String, jid: String): RoomMember = RoomMember(
        id = "",
        firstName = "",
        lastName = "",
        xmppUsername = xmppUsername,
        jid = jid.takeIf { it.isNotEmpty() }
    )
}
