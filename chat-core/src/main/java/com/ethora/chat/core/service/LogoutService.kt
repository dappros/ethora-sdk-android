package com.ethora.chat.core.service

import android.util.Log
import com.ethora.chat.core.networking.TokenManager
import com.ethora.chat.core.store.*
import com.ethora.chat.core.xmpp.XMPPClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Logout service for the chat component
 * Allows external apps to logout from the chat component
 * Matches web: logoutService from useLogout.tsx
 * 
 * Usage:
 * ```kotlin
 * // Set callback (optional)
 * LogoutService.setOnLogoutCallback {
 *     // Handle logout completion
 * }
 * 
 * // Perform logout
 * LogoutService.performLogout()
 * ```
 */
object LogoutService {
    private const val TAG = "LogoutService"
    
    // XMPP client reference (set by chat component)
    private var xmppClient: XMPPClient? = null
    
    // Logout callback (optional, set by external app)
    private var onLogoutCallback: (() -> Unit)? = null
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    /**
     * Set XMPP client reference
     * Called by chat component during initialization
     * Internal use only - but needs to be public for Chat component
     */
    fun setXMPPClient(client: XMPPClient?) {
        xmppClient = client
        Log.d(TAG, "✅ XMPP client reference set")
    }
    
    /**
     * Set logout callback
     * External apps can set this to be notified when logout completes
     * 
     * @param callback Function to call when logout is complete
     */
    fun setOnLogoutCallback(callback: (() -> Unit)?) {
        onLogoutCallback = callback
        Log.d(TAG, "✅ Logout callback set")
    }
    
    /**
     * Perform logout
     * This is the main function that external apps should call
     * Matches web: logoutService.performLogout()
     * 
     * This will:
     * 1. Disconnect XMPP client
     * 2. Clear all stores (UserStore, RoomStore, MessageStore)
     * 3. Clear all persistence data
     * 4. Call the logout callback if set
     */
    fun performLogout() {
        Log.d(TAG, "🚪 Starting logout process...")
        
        scope.launch {
            try {
                // 1. Disconnect XMPP client
                withContext(Dispatchers.IO) {
                    xmppClient?.let { client ->
                        Log.d(TAG, "🔌 Disconnecting XMPP client...")
                        client.disconnect()
                        Log.d(TAG, "✅ XMPP client disconnected")
                    } ?: Log.w(TAG, "⚠️ XMPP client is null, skipping disconnect")
                }
                
                // 2. Clear all stores
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "🧹 Clearing stores...")
                    
                    // Clear user store
                    UserStore.clear()
                    UserStore.clearSelectedUser()
                    
                    // Clear room store
                    RoomStore.clear()
                    
                    // Clear message store
                    MessageStore.clear()
                    
                    // Clear scroll positions
                    ScrollPositionStore.clearAll()
                    
                    Log.d(TAG, "✅ All stores cleared")
                }
                
                // 3. Clear persistence (background)
                withContext(Dispatchers.IO) {
                    Log.d(TAG, "💾 Clearing persistence...")
                    try {
                        // Clear user persistence
                        val userPersistence = UserStore.getPersistenceManager()
                        userPersistence?.saveUser(null)
                        userPersistence?.saveTokens(null, null)
                        // Clear JWT token
                        userPersistence?.clearJWTToken()
                        
                        // Clear room persistence
                        val roomPersistence = RoomStore.getPersistenceManager()
                        roomPersistence?.saveRooms(emptyList())
                        roomPersistence?.saveCurrentRoomJid(null)
                        
                        // Clear message persistence
                        val messageCache = MessageStore.getMessageCache()
                        messageCache?.let { cache ->
                            kotlinx.coroutines.runBlocking {
                                cache.clearAllMessages()
                            }
                        }
                        
                        Log.d(TAG, "✅ Persistence cleared")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error clearing persistence", e)
                    }
                }
                
                // 4. Clear ApiClient user token (Fixes relogin 400 error)
                com.ethora.chat.core.networking.ApiClient.setUserToken(null)
                
                // 5. Stop token refresh
                TokenManager.stopAutoRefresh()
                
                // 6. Reset XMPP client reference
                xmppClient = null
                
                Log.d(TAG, "✅ Logout completed successfully")
                
                // 6. Call external callback if set
                onLogoutCallback?.invoke()
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error during logout", e)
                // Still call callback even on error
                onLogoutCallback?.invoke()
            }
        }
    }
    
    /**
     * Check if user is logged in
     */
    fun isLoggedIn(): Boolean {
        return UserStore.currentUser.value != null
    }
    
    /**
     * Get current user (if logged in)
     */
    fun getCurrentUser() = UserStore.currentUser.value
}
