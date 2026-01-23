package com.ethora.chat.core.persistence

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Local storage utility using DataStore
 */
class LocalStorage(private val context: Context) {
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "chat_storage")

    companion object {
        private val USER_TOKEN_KEY = stringPreferencesKey("user_token")
        private val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")
        private val USER_ID_KEY = stringPreferencesKey("user_id")
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
     * Clear all data
     */
    suspend fun clear() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }
}
