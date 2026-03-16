package com.ethora.chat.app

import android.content.Context
import android.util.Log
import com.ethora.chat.core.networking.ApiClient
import com.ethora.chat.core.networking.AuthAPIHelper
import com.ethora.chat.core.networking.RoomsAPIHelper
import com.ethora.chat.core.persistence.ChatDatabase
import com.ethora.chat.core.persistence.ChatPersistenceManager
import com.ethora.chat.core.persistence.LocalStorage
import com.ethora.chat.core.persistence.MessageCache
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.MessageLoader
import com.ethora.chat.core.store.MessageStore
import com.ethora.chat.core.store.RoomStore
import com.ethora.chat.core.store.ScrollPositionStore
import com.ethora.chat.core.store.UserStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Helper class to manage authentication flow
 */
class AuthManager(private val context: Context) {
    private val TAG = "AuthManager"
    
    /**
     * Initialize persistence and stores
     */
    suspend fun initialize() {
        Log.d(TAG, "💾 Initializing persistence...")
        val persistenceManager = ChatPersistenceManager(context)
        val chatDatabase = ChatDatabase.getDatabase(context)
        val messageCache = MessageCache(chatDatabase)
        
        // Initialize stores with persistence
        RoomStore.initialize(persistenceManager)
        UserStore.initialize(persistenceManager)
        MessageStore.initialize(messageCache)
        ScrollPositionStore.initialize(context)
        
        // Initialize MessageLoader with LocalStorage for sync
        val localStorage = LocalStorage(context)
        MessageLoader.initialize(localStorage)
        
        Log.d(TAG, "✅ Persistence initialized")
    }
    
    /**
     * Load persisted user and rooms
     * Returns true if user was found
     */
    suspend fun loadPersistedData(): Boolean {
        Log.d(TAG, "📂 Loading persisted data...")
        
        return withContext(Dispatchers.IO) {
            // Load user
            val persistedUser = UserStore.loadUserFromPersistence()
            if (persistedUser != null) {
                Log.d(TAG, "📂 Found persisted user")
                withContext(Dispatchers.Main) {
                    UserStore.setUser(persistedUser)
                    ApiClient.setUserToken(persistedUser.token ?: "")
                }
                
                // Load rooms
                val persistedRooms = RoomStore.loadRoomsFromPersistence()
                if (persistedRooms.isNotEmpty()) {
                    Log.d(TAG, "📂 Found ${persistedRooms.size} persisted rooms")
                    
                    // Load messages for each room on IO thread
                    val persistedMessagesByRoom = persistedRooms.associate { room ->
                        room.jid to MessageStore.loadMessagesFromPersistence(room.jid)
                    }
                    
                    withContext(Dispatchers.Main) {
                        RoomStore.setRooms(persistedRooms)
                        
                        persistedMessagesByRoom.forEach { (jid, messages) ->
                            if (messages.isNotEmpty()) {
                                MessageStore.setMessagesForRoom(jid, messages)
                                Log.d(TAG, "📂 Loaded ${messages.size} messages for $jid")
                            }
                        }
                        
                        // Load current room
                        val currentRoomJid = RoomStore.loadCurrentRoomJidFromPersistence()
                        currentRoomJid?.let { jid ->
                            persistedRooms.firstOrNull { it.jid == jid }?.let { room ->
                                RoomStore.setCurrentRoom(room)
                            }
                        }
                    }
                }
                
                true
            } else {
                Log.d(TAG, "📂 No persisted user found")
                false
            }
        }
    }
    
    /**
     * Perform login with email and password
     */
    suspend fun login(email: String, password: String): Result<Unit> {
        return try {
            Log.d(TAG, "🔐 Logging in with email: $email")
            
            withContext(Dispatchers.IO) {
                val loginResponse = AuthAPIHelper.loginWithEmail(
                    email = email,
                    password = password,
                    baseUrl = ChatStore.getEffectiveBaseUrl()
                )
                
                withContext(Dispatchers.Main) {
                    UserStore.setUser(loginResponse)
                    ApiClient.setUserToken(loginResponse.token)
                }
                
                Log.d(TAG, "✅ Login successful!")
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Login failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Load rooms from API
     */
    suspend fun loadRooms(): Result<Int> {
        return try {
            Log.d(TAG, "📋 Loading rooms from API...")
            
            val rooms = withContext(Dispatchers.IO) {
                RoomsAPIHelper.getRooms()
            }
            
            Log.d(TAG, "✅ Loaded ${rooms.size} rooms")
            
            // Load persisted messages for each room on IO thread
            val persistedMessagesByRoom = withContext(Dispatchers.IO) {
                rooms.associate { room ->
                    room.jid to MessageStore.loadMessagesFromPersistence(room.jid)
                }
            }
            
            withContext(Dispatchers.Main) {
                RoomStore.setRooms(rooms)
                
                persistedMessagesByRoom.forEach { (jid, messages) ->
                    if (messages.isNotEmpty()) {
                        MessageStore.setMessagesForRoom(jid, messages)
                    }
                }
            }
            
            Result.success(rooms.size)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load rooms", e)
            Result.failure(e)
        }
    }
}
