package com.ethora.chat.core.store

import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.RoomMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Membership and usersCnt rules, web onPresenceInRoom / onRoomMembershipChange. */
class RoomMembershipTest {

    private fun member(nick: String) = RoomMember(id = nick, xmppUsername = nick)

    private fun room(usersCnt: Int, vararg nicks: String) = Room(
        id = "r", jid = "r@conf", name = "r", title = "r",
        usersCnt = usersCnt,
        members = nicks.map { member(it) }
    )

    @Test
    fun joinAddsPlaceholderAndCounts() {
        val next = RoomMembership.afterPresence(room(1, "app_a"), "app_b", "member", unavailable = false)!!
        assertEquals(listOf("app_a", "app_b"), next.members!!.map { it.xmppUsername })
        assertEquals(2, next.usersCnt)
    }

    @Test
    fun joinIsNotGuessedOnTruncatedPage() {
        // 30 loaded of 50 total: a join not in the page must not change anything
        assertNull(RoomMembership.afterPresence(room(50, "app_a"), "app_b", "member", unavailable = false))
    }

    @Test
    fun leaveRemovesMemberAndCounts() {
        val next = RoomMembership.afterPresence(room(2, "app_a", "app_b"), "app_b", "none", unavailable = true)!!
        assertEquals(listOf("app_a"), next.members!!.map { it.xmppUsername })
        assertEquals(1, next.usersCnt)
    }

    @Test
    fun leaveOutsideTruncatedPageOnlyMovesCount() {
        val next = RoomMembership.afterPresence(room(50, "app_a"), "app_z", "none", unavailable = true)!!
        assertEquals(49, next.usersCnt)
        assertEquals(1, next.members!!.size)
    }

    @Test
    fun ownerOrModeratorPresenceIsNotAJoinOrLeave() {
        assertNull(RoomMembership.afterPresence(room(1, "app_a"), "app_b", "admin", unavailable = true))
    }

    @Test
    fun membershipMessageRemovesOnNoneAndAddsOnMember() {
        val added = RoomMembership.afterMembershipMessage(room(1, "app_a"), "app_b", "app_b@conf", "member")!!
        assertEquals(2, added.usersCnt)
        assertEquals("app_b@conf", added.members!!.last().jid)
        val removed = RoomMembership.afterMembershipMessage(added, "app_b", "app_b@conf", "none")!!
        assertEquals(1, removed.usersCnt)
        assertEquals(listOf("app_a"), removed.members!!.map { it.xmppUsername })
    }

    @Test
    fun userCountNeverBelowLoadedMembers() {
        assertEquals(3, RoomMembership.userCount(room(1, "a", "b", "c")))
    }
}
