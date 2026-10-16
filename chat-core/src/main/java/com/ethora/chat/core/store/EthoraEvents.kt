package com.ethora.chat.core.store

import com.ethora.chat.core.models.ApiRoom
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.RoomMember
import com.ethora.chat.core.models.createRoomFromApi
import com.ethora.chat.core.networking.RoomManagementAPIHelper
import com.ethora.chat.core.networking.UserLookupOutcome
import com.ethora.chat.core.xmpp.ETHORA_EVENT_CHAT_META
import com.ethora.chat.core.xmpp.ETHORA_EVENT_USER_PROFILE
import com.ethora.chat.core.xmpp.EthoraEventParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Server push `<ethora-event xmlns="urn:ethora:events:1"/>` headlines,
 * web parity with networking/ethoraEvents.ts.
 *
 * The payload is a pointer only: the client re-reads the user or the room
 * through the normal API, so nothing in the stanza is shown or stored.
 * Every attribute is untrusted input until it passes the checks below.
 *
 *  - user-profile-updated: re-read the user, patch its display fields in
 *    every loaded room's members.
 *  - chat-meta-updated: re-read the room (metadata only). Debounced per room
 *    (500 ms), one request in flight, backoff 5 s / 30 s / 120 s on failure.
 */
object EthoraEvents {
    private const val TAG = "EthoraEvents"
    private const val ROOM_DEBOUNCE_MS = 500L
    private val ROOM_BACKOFF_MS = longArrayOf(5_000L, 30_000L, 120_000L)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val roomJobs = ConcurrentHashMap<String, Job>()
    private val roomInflight = ConcurrentHashMap.newKeySet<String>()
    private val roomAgain = ConcurrentHashMap.newKeySet<String>()
    private val roomFailures = ConcurrentHashMap<String, Pair<Int, Long>>()
    private val userInflight = ConcurrentHashMap.newKeySet<String>()

    /**
     * Handles the headline when [xml] carries an ethora-event. Returns true
     * when it did (trusted or not), so the caller stops routing the stanza.
     * [ownDomain] is this session's XMPP domain, [appId] the configured app.
     */
    fun handle(xml: String, ownDomain: String, appId: String): Boolean {
        val carrier = EthoraEventParser.carrier(xml) ?: return false
        val from = EthoraEventParser.from(carrier)
        if (!EthoraEventParser.isTrustedSender(from, ownDomain)) {
            android.util.Log.d(TAG, "ignored: untrusted sender")
            return true
        }
        val event = EthoraEventParser.parse(carrier) ?: return true
        if (event.appId != null && appId.isNotEmpty() && event.appId != appId) return true

        when (event.type) {
            ETHORA_EVENT_USER_PROFILE -> {
                val id = event.xmppUsername
                if (EthoraEventParser.isSafeKey(id, appId)) refreshUser(id!!)
            }
            ETHORA_EVENT_CHAT_META -> {
                val chatName = event.chatName
                // A room we do not hold is the invite flow's job, not this one.
                if (EthoraEventParser.isSafeKey(chatName, appId) && findRoomJid(chatName!!) != null) {
                    scheduleRoomRefresh(chatName)
                }
            }
        }
        return true
    }

    /**
     * A groupchat `<members-refresh>` signal: some peer changed this room's
     * membership on the server, so re-read the room's members from the API.
     */
    fun refreshRoomMembers(chatName: String) {
        if (findRoomJid(chatName) == null) return
        scope.launch {
            try {
                val apiRoom = RoomManagementAPIHelper.getRoomByName(chatName)
                val jid = findRoomJid(chatName) ?: return@launch
                val current = RoomStore.getRoomByJid(jid) ?: return@launch
                val members = apiRoom.members ?: return@launch
                RoomStore.updateRoom(
                    current.copy(
                        members = members,
                        usersCnt = maxOf(apiRoom.usersCnt ?: 0, members.size)
                    )
                )
            } catch (e: Exception) {
                android.util.Log.d(TAG, "members refresh failed for $chatName", e)
            }
        }
    }

    /** Drops timers and per-room state (logout, tests). */
    fun reset() {
        roomJobs.values.forEach { it.cancel() }
        roomJobs.clear()
        roomInflight.clear()
        roomAgain.clear()
        roomFailures.clear()
        userInflight.clear()
    }

    private fun findRoomJid(chatName: String?): String? {
        if (chatName.isNullOrEmpty()) return null
        return RoomStore.rooms.value.firstOrNull { it.jid.substringBefore('@') == chatName }?.jid
    }

    // --- user-profile-updated -------------------------------------------

    private fun refreshUser(id: String) {
        if (!userInflight.add(id)) return
        scope.launch {
            try {
                val outcome = RoomManagementAPIHelper.lookupUser(id)
                val user = (outcome as? UserLookupOutcome.Ok)?.user ?: return@launch
                applyUserToRooms(id, user)
            } catch (e: Exception) {
                android.util.Log.d(TAG, "user refresh failed for $id", e)
            } finally {
                userInflight.remove(id)
            }
        }
    }

    /** Patches display fields of [user] wherever it is a loaded member. */
    internal fun applyUserToRooms(id: String, user: RoomMember) {
        RoomStore.rooms.value.forEach { room ->
            val members = room.members ?: return@forEach
            var changed = false
            val next = members.map { member ->
                if (member.xmppUsername != id) return@map member
                changed = true
                member.copy(
                    id = user.id.ifEmpty { member.id },
                    firstName = user.firstName ?: member.firstName,
                    lastName = user.lastName ?: member.lastName,
                    description = user.description ?: member.description
                )
            }
            if (changed) RoomStore.updateRoom(room.copy(members = next))
        }
    }

    // --- chat-meta-updated ----------------------------------------------

    private fun scheduleRoomRefresh(chatName: String) {
        roomJobs.remove(chatName)?.cancel()
        roomJobs[chatName] = scope.launch {
            delay(ROOM_DEBOUNCE_MS)
            roomJobs.remove(chatName)
            runRoomRefresh(chatName)
        }
    }

    private suspend fun runRoomRefresh(chatName: String) {
        if (!roomInflight.add(chatName)) {
            roomAgain.add(chatName)
            return
        }
        try {
            val now = System.currentTimeMillis()
            val failure = roomFailures[chatName]
            if (failure != null && failure.second > now) return
            val jid = findRoomJid(chatName) ?: return
            val apiRoom = RoomManagementAPIHelper.getRoomByName(chatName)
            roomFailures.remove(chatName)
            val current = RoomStore.getRoomByJid(jid) ?: return
            RoomStore.updateRoom(mergeRoomMetadata(current, apiRoom, jid))
        } catch (e: Exception) {
            val count = (roomFailures[chatName]?.first ?: 0) + 1
            val backoff = ROOM_BACKOFF_MS[minOf(count - 1, ROOM_BACKOFF_MS.size - 1)]
            roomFailures[chatName] = count to (System.currentTimeMillis() + backoff)
            android.util.Log.d(TAG, "room refresh failed for $chatName", e)
        } finally {
            roomInflight.remove(chatName)
            if (roomAgain.remove(chatName)) scheduleRoomRefresh(chatName)
        }
    }

    /**
     * Metadata only: title, description, picture, type and the total count.
     * Members, messages and the unread fields stay as they are, because the
     * answer may carry a truncated member list.
     */
    internal fun mergeRoomMetadata(current: Room, apiRoom: ApiRoom, jid: String): Room {
        val fresh = createRoomFromApi(
            apiRoom.copy(name = apiRoom.name.ifEmpty { jid.substringBefore('@') }),
            jid.substringAfter('@', ""),
            RoomMembership.userCount(current)
        )
        return current.copy(
            title = fresh.title,
            name = fresh.name,
            description = fresh.description ?: current.description,
            picture = fresh.picture ?: current.picture,
            icon = fresh.icon ?: current.icon,
            type = apiRoom.type ?: current.type,
            // never lower the known total
            usersCnt = maxOf(fresh.usersCnt, RoomMembership.userCount(current)),
            muted = apiRoom.muted ?: current.muted
        )
    }
}
