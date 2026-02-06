package com.ethora.chat.core.store

import android.util.Log
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.xmpp.XMPPClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Initialize presence for all rooms
 * Sends presence to all rooms before loading message history
 * Required for XMPP MUC (Multi-User Chat) to work properly
 */
object RoomsPresenceInitializer {
    private const val TAG = "RoomsPresenceInitializer"
    
    /**
     * Send presence to all rooms
     * 
     * @param xmppClient XMPP client instance
     * @param rooms List of rooms to send presence to
     */
    suspend fun initRoomsPresence(
        xmppClient: XMPPClient?,
        rooms: List<Room>
    ) {
        if (xmppClient == null) {
            Log.w(TAG, "XMPP client is null, cannot send presence")
            return
        }
        
        if (rooms.isEmpty()) {
            Log.d(TAG, "No rooms to send presence to")
            return
        }
        
        Log.d(TAG, "Sending presence to ${rooms.size} rooms")
        
        try {
            coroutineScope {
                rooms.map { room ->
                    async {
                        try {
                            // Check connection status first
                            if (xmppClient.isFullyConnected()) {
                                val success = xmppClient.sendPresenceInRoom(room.jid)
                                if (!success) {
                                    Log.w(TAG, "Failed to send presence to ${room.jid}")
                                }
                            } else {
                                // Silent return or debug log - avoid spamming errors during initial connection
                                // The presence will be sent when connection is established via XMPPClientDelegate
                            }
                            Unit
                        } catch (e: Exception) {
                            Log.w(TAG, "Error sending presence to ${room.jid}", e)
                        }
                    }
                }.awaitAll()
            }
            
            Log.d(TAG, "Finished sending presence to all rooms")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing rooms presence", e)
        }
    }
}
