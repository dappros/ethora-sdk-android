package com.ethora.chat.core.store

import android.util.Log
import com.ethora.chat.core.xmpp.TimestampUtils
import com.ethora.chat.core.xmpp.XMPPClient

/**
 * Port of web's `initBeforeLoad` sequence (xmppProvider.tsx L283-304) and its
 * helper `updatedChatLastTimestamps`. Runs once post-connect:
 *
 *   1. Waits for XMPP to be online.
 *   2. Reads the server-side chatjson private store (XEP-0049) → a map of
 *      `roomJid → lastViewedTimestamp(ms)`. This is the mechanism that keeps
 *      per-room unread state consistent across devices.
 *   3. Merges the server values into local `RoomStore.lastViewedTimestamp`
 *      while preserving a fresher local value (don't overwrite a recent
 *      in-app read with a stale server blob).
 *
 * The WRITE side — "close room → bump server timestamp" — lives in
 * `writeCurrentTimestamp` and is invoked from `ChatRoomView`'s onDispose.
 */
object InitBeforeLoadFlow {
    private const val TAG = "InitBeforeLoadFlow"

    /** One-shot guard so the sync only runs once per XMPP connection. */
    @Volatile private var lastCompletedForClient: Int = 0

    suspend fun run(xmppClient: XMPPClient) {
        val clientId = System.identityHashCode(xmppClient)
        if (lastCompletedForClient == clientId) return
        // Wait up to 5s for the socket to finish auth.
        if (!xmppClient.ensureConnected(5_000)) {
            Log.w(TAG, "XMPP not online — skipping initBeforeLoad")
            return
        }
        val serverStore = try {
            xmppClient.getChatsPrivateStore()
        } catch (e: Exception) {
            Log.w(TAG, "getChatsPrivateStore failed", e)
            null
        }
        if (serverStore.isNullOrEmpty()) {
            Log.d(TAG, "Private store empty — nothing to merge")
            lastCompletedForClient = clientId
            return
        }
        mergeIntoRoomStore(serverStore)
        lastCompletedForClient = clientId
        Log.d(TAG, "initBeforeLoad merged ${serverStore.size} room timestamps")
    }

    /**
     * Equivalent of web's `updatedChatLastTimestamps`. Writes each server
     * timestamp into the local room store unless we already have a FRESHER
     * local value for that room.
     */
    private fun mergeIntoRoomStore(serverStore: Map<String, String>) {
        serverStore.forEach { (jid, rawTs) ->
            if (jid.isBlank()) return@forEach
            val incoming = TimestampUtils.getTimestampFromUnknown(rawTs)
            if (incoming <= 0L) return@forEach
            val local = RoomStore.getRoomByJid(jid)?.lastViewedTimestamp ?: 0L
            if (local > 0L && incoming < local) return@forEach
            RoomStore.setLastViewedTimestamp(jid, incoming)
        }
    }

    /**
     * Called from `ChatRoomView.onDispose` in parallel with the existing
     * `setLastViewedTimestamp`. Writes the fresh read marker back to the
     * server so other devices pick it up. Best-effort — failures are logged
     * and ignored.
     */
    suspend fun writeCurrentTimestamp(
        xmppClient: XMPPClient?,
        roomJid: String,
        timestamp: Long
    ) {
        val client = xmppClient ?: return
        if (roomJid.isBlank() || timestamp <= 0L) return
        if (!client.isFullyConnected()) return
        try {
            val allRooms = RoomStore.rooms.value.map { it.jid }
            client.setTimestampInPrivateStore(
                chatId = roomJid,
                timestamp = timestamp,
                chats = allRooms
            )
        } catch (e: Exception) {
            Log.w(TAG, "writeCurrentTimestamp failed for $roomJid", e)
        }
    }
}
