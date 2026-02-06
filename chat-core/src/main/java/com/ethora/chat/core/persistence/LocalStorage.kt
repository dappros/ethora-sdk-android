package com.ethora.chat.core.persistence

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

/**
 * Local storage utility using DataStore and EncryptedSharedPreferences for sensitive data
 */
class LocalStorage(private val context: Context) {
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "chat_storage")
    
    // Encrypted storage for JWT tokens
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "encrypted_chat_storage",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private val USER_TOKEN_KEY = stringPreferencesKey("user_token")
        private val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")
        private val USER_ID_KEY = stringPreferencesKey("user_id")
        private val JWT_TOKEN_KEY = "jwt_token"
        private val LAST_SYNC_TIMESTAMP_KEY = stringPreferencesKey("last_sync_timestamp")
    }

    /**
     * Save user token
     */
    suspend fun saveUserToken(token: String) {
        context.dataStore.edit { preferences ->
            preferences[USER_TOKEN_KEY] = token
        }
    }

    /**
     * Get user token
     */
    fun getUserToken(): Flow<String?> {
        return context.dataStore.data.map { preferences ->
            preferences[USER_TOKEN_KEY]
        }
    }

    /**
     * Save refresh token
     */
    suspend fun saveRefreshToken(token: String) {
        context.dataStore.edit { preferences ->
            preferences[REFRESH_TOKEN_KEY] = token
        }
    }

    /**
     * Get refresh token
     */
    fun getRefreshToken(): Flow<String?> {
        return context.dataStore.data.map { preferences ->
            preferences[REFRESH_TOKEN_KEY]
        }
    }

    /**
     * Save user ID
     */
    suspend fun saveUserId(userId: String) {
        context.dataStore.edit { preferences ->
            preferences[USER_ID_KEY] = userId
        }
    }

    /**
     * Get user ID
     */
    fun getUserId(): Flow<String?> {
        return context.dataStore.data.map { preferences ->
            preferences[USER_ID_KEY]
        }
    }

    /**
     * Save JWT token (encrypted)
     */
    fun saveJWTToken(token: String) {
        try {
            encryptedPrefs.edit().putString(JWT_TOKEN_KEY, token).apply()
            android.util.Log.d("LocalStorage", "💾 Saved JWT token (encrypted)")
        } catch (e: Exception) {
            android.util.Log.e("LocalStorage", "❌ Error saving JWT token", e)
        }
    }
    
    /**
     * Get JWT token (encrypted)
     */
    fun getJWTToken(): String? {
        return try {
            encryptedPrefs.getString(JWT_TOKEN_KEY, null)
        } catch (e: Exception) {
            android.util.Log.e("LocalStorage", "❌ Error getting JWT token", e)
            null
        }
    }
    
    /**
     * Clear JWT token
     */
    fun clearJWTToken() {
        try {
            encryptedPrefs.edit().remove(JWT_TOKEN_KEY).apply()
            android.util.Log.d("LocalStorage", "🗑️ Cleared JWT token")
        } catch (e: Exception) {
            android.util.Log.e("LocalStorage", "❌ Error clearing JWT token", e)
        }
    }
    
    /**
     * Save last sync timestamp
     */
    suspend fun saveLastSyncTimestamp(timestamp: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_SYNC_TIMESTAMP_KEY] = timestamp.toString()
        }
    }
    
    /**
     * Get last sync timestamp
     */
    suspend fun getLastSyncTimestamp(): Long? {
        return try {
            val timestampStr = context.dataStore.data.first()[LAST_SYNC_TIMESTAMP_KEY]
            timestampStr?.toLongOrNull()
        } catch (e: Exception) {
            android.util.Log.e("LocalStorage", "❌ Error getting last sync timestamp", e)
            null
        }
    }
    
    /**
     * Clear all data
     */
    suspend fun clear() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
        clearJWTToken()
    }
}
