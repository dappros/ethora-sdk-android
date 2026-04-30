package com.ethora.chat.core.persistence

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.User
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

/**
 * Persistence manager for chat data
 * Matches web: redux-persist with localStorage
 */
private val Context.chatPersistenceDataStore: DataStore<Preferences> by preferencesDataStore(name = "chat_persistence")

class ChatPersistenceManager(private val context: Context) {
    private val appContext = context.applicationContext
    private val gson = Gson()
    
    companion object {
        private val ROOMS_KEY = stringPreferencesKey("rooms")
        private val CURRENT_ROOM_JID_KEY = stringPreferencesKey("current_room_jid")
        private val USER_KEY = stringPreferencesKey("user")
        private val TOKEN_KEY = stringPreferencesKey("token")
        private val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")
        
        // SharedPreferences for scroll positions (simple key-value)
        private const val SCROLL_POSITIONS_PREFS = "chat_scroll_positions"
    }
    
    /**
     * Save rooms (without messages - messages are stored separately in Room Database)
     * Matches web: roomsPersistConfig (blacklist messages, but we store them separately)
     */
    suspend fun saveRooms(rooms: List<Room>) {
        try {
            // Store rooms without messages (messages are in Room Database)
            val roomsWithoutMessages = rooms.map { room ->
                room.copy(messages = emptyList()) // Don't persist messages here
            }
            
            val json = gson.toJson(roomsWithoutMessages)
            appContext.chatPersistenceDataStore.edit { preferences ->
                preferences[ROOMS_KEY] = json
            }
            android.util.Log.d("ChatPersistenceManager", "💾 Saved ${roomsWithoutMessages.size} rooms (without messages) to persistence")
        } catch (e: Exception) {
            android.util.Log.e("ChatPersistenceManager", "❌ Error saving rooms", e)
        }
    }
    
    /**
     * Load rooms. Substitutes `lastViewedTimestamp = now` when the persisted
     * value is 0/null — a stale "open room, app killed" marker that would
     * otherwise count all restored history as unread on cold boot.
     */
    suspend fun loadRooms(): List<Room> {
        return try {
            val json = appContext.chatPersistenceDataStore.data.first()[ROOMS_KEY] ?: return emptyList()
            val type = object : TypeToken<List<Room>>() {}.type
            val rooms = gson.fromJson<List<Room>>(json, type) ?: emptyList()
            val now = System.currentTimeMillis()
            val sanitized = rooms.map { room ->
                if ((room.lastViewedTimestamp ?: 0L) <= 0L) {
                    room.copy(lastViewedTimestamp = now)
                } else room
            }
            android.util.Log.d("ChatPersistenceManager", "📂 Loaded ${sanitized.size} rooms from persistence")
            sanitized
        } catch (e: Exception) {
            android.util.Log.e("ChatPersistenceManager", "❌ Error loading rooms", e)
            emptyList()
        }
    }
    
    /**
     * Save current room JID
     */
    suspend fun saveCurrentRoomJid(roomJid: String?) {
        try {
            appContext.chatPersistenceDataStore.edit { preferences ->
                if (roomJid != null) {
                    preferences[CURRENT_ROOM_JID_KEY] = roomJid
                } else {
                    preferences.remove(CURRENT_ROOM_JID_KEY)
                }
            }
            android.util.Log.d("ChatPersistenceManager", "💾 Saved current room JID: $roomJid")
        } catch (e: Exception) {
            android.util.Log.e("ChatPersistenceManager", "❌ Error saving current room JID", e)
        }
    }
    
    /**
     * Load current room JID
     */
    suspend fun loadCurrentRoomJid(): String? {
        return try {
            val jid = appContext.chatPersistenceDataStore.data.first()[CURRENT_ROOM_JID_KEY]
            android.util.Log.d("ChatPersistenceManager", "📂 Loaded current room JID: $jid")
            jid
        } catch (e: Exception) {
            android.util.Log.e("ChatPersistenceManager", "❌ Error loading current room JID", e)
            null
        }
    }
    
    /**
     * Save user
     */
    suspend fun saveUser(user: User?) {
        try {
            appContext.chatPersistenceDataStore.edit { preferences ->
                if (user != null) {
                    val json = gson.toJson(user)
                    preferences[USER_KEY] = json
                } else {
                    preferences.remove(USER_KEY)
                }
            }
            android.util.Log.d("ChatPersistenceManager", "💾 Saved user to persistence")
        } catch (e: Exception) {
            android.util.Log.e("ChatPersistenceManager", "❌ Error saving user", e)
        }
    }
    
    /**
     * Load user
     */
    suspend fun loadUser(): User? {
        return try {
            val json = appContext.chatPersistenceDataStore.data.first()[USER_KEY] ?: return null
            val user = gson.fromJson<User>(json, User::class.java)
            android.util.Log.d("ChatPersistenceManager", "📂 Loaded user from persistence")
            user
        } catch (e: Exception) {
            android.util.Log.e("ChatPersistenceManager", "❌ Error loading user", e)
            null
        }
    }
    
    /**
     * Save tokens
     */
    suspend fun saveTokens(token: String?, refreshToken: String?) {
        try {
            appContext.chatPersistenceDataStore.edit { preferences ->
                if (token != null) {
                    preferences[TOKEN_KEY] = token
                } else {
                    preferences.remove(TOKEN_KEY)
                }
                if (refreshToken != null) {
                    preferences[REFRESH_TOKEN_KEY] = refreshToken
                } else {
                    preferences.remove(REFRESH_TOKEN_KEY)
                }
            }
            android.util.Log.d("ChatPersistenceManager", "💾 Saved tokens to persistence")
        } catch (e: Exception) {
            android.util.Log.e("ChatPersistenceManager", "❌ Error saving tokens", e)
        }
    }
    
    /**
     * Load tokens
     */
    suspend fun loadTokens(): Pair<String?, String?> {
        return try {
            val prefs = appContext.chatPersistenceDataStore.data.first()
            val token = prefs[TOKEN_KEY]
            val refreshToken = prefs[REFRESH_TOKEN_KEY]
            android.util.Log.d("ChatPersistenceManager", "📂 Loaded tokens from persistence")
            Pair(token, refreshToken)
        } catch (e: Exception) {
            android.util.Log.e("ChatPersistenceManager", "❌ Error loading tokens", e)
            Pair(null, null)
        }
    }
    
    /**
     * Save scroll position for a room
     */
    fun saveScrollPosition(roomJid: String, position: Int) {
        try {
            val prefs = context.getSharedPreferences(SCROLL_POSITIONS_PREFS, Context.MODE_PRIVATE)
            prefs.edit().putInt(roomJid, position).apply()
            android.util.Log.d("ChatPersistenceManager", "💾 Saved scroll position for $roomJid: $position")
        } catch (e: Exception) {
            android.util.Log.e("ChatPersistenceManager", "❌ Error saving scroll position", e)
        }
    }
    
    /**
     * Load scroll position for a room
     */
    fun loadScrollPosition(roomJid: String): Int? {
        return try {
            val prefs = context.getSharedPreferences(SCROLL_POSITIONS_PREFS, Context.MODE_PRIVATE)
            val position = prefs.getInt(roomJid, -1)
            if (position >= 0) {
                android.util.Log.d("ChatPersistenceManager", "📂 Loaded scroll position for $roomJid: $position")
                position
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatPersistenceManager", "❌ Error loading scroll position", e)
            null
        }
    }
    
    /**
     * Clear JWT token (encrypted storage)
     */
    fun clearJWTToken() {
        try {
            val localStorage = LocalStorage(appContext)
            localStorage.clearJWTToken()
            android.util.Log.d("ChatPersistenceManager", "🗑️ Cleared JWT token")
        } catch (e: Exception) {
            android.util.Log.e("ChatPersistenceManager", "❌ Error clearing JWT token", e)
        }
    }
    
    /**
     * Save JWT token (encrypted storage)
     */
    fun saveJWTToken(token: String) {
        try {
            val localStorage = LocalStorage(appContext)
            localStorage.saveJWTToken(token)
            android.util.Log.d("ChatPersistenceManager", "💾 Saved JWT token")
        } catch (e: Exception) {
            android.util.Log.e("ChatPersistenceManager", "❌ Error saving JWT token", e)
        }
    }
    
    /**
     * Get JWT token (encrypted storage)
     */
    fun getJWTToken(): String? {
        return try {
            val localStorage = LocalStorage(appContext)
            localStorage.getJWTToken()
        } catch (e: Exception) {
            android.util.Log.e("ChatPersistenceManager", "❌ Error getting JWT token", e)
            null
        }
    }
    
    /**
     * Clear all persisted data
     */
    suspend fun clearAll() {
        try {
            appContext.chatPersistenceDataStore.edit { preferences ->
                preferences.clear()
            }
            val prefs = appContext.getSharedPreferences(SCROLL_POSITIONS_PREFS, Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            // Clear JWT token
            clearJWTToken()
            android.util.Log.d("ChatPersistenceManager", "🗑️ Cleared all persisted data")
        } catch (e: Exception) {
            android.util.Log.e("ChatPersistenceManager", "❌ Error clearing persisted data", e)
        }
    }
}
