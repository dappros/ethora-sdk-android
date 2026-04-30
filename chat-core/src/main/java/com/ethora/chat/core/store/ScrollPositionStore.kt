package com.ethora.chat.core.store

import android.content.Context
import android.content.SharedPreferences

/**
 * Store for saving scroll positions for each room
 * Persists to SharedPreferences (matches web: scrollPositions in localStorage)
 */
object ScrollPositionStore {
    private var sharedPreferences: SharedPreferences? = null
    private val scrollPositions = mutableMapOf<String, Int>()
    
    /**
     * Initialize with context
     */
    @Synchronized
    fun initialize(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("chat_scroll_positions", Context.MODE_PRIVATE)
        if (sharedPreferences === prefs) {
            android.util.Log.d("ScrollPositionStore", "↻ ScrollPositionStore already initialized")
            return
        }
        sharedPreferences = prefs
        // Load existing scroll positions
        loadScrollPositions()
        android.util.Log.d("ScrollPositionStore", "✅ ScrollPositionStore initialized")
    }
    
    /**
     * Load scroll positions from SharedPreferences
     */
    private fun loadScrollPositions() {
        val prefs = sharedPreferences ?: return
        try {
            val all = prefs.all
            all.forEach { (key, value) ->
                if (value is Int) {
                    scrollPositions[key] = value
                }
            }
            android.util.Log.d("ScrollPositionStore", "📂 Loaded ${scrollPositions.size} scroll positions from persistence")
        } catch (e: Exception) {
            android.util.Log.e("ScrollPositionStore", "❌ Error loading scroll positions", e)
        }
    }
    
    /**
     * Save scroll position for a room
     */
    fun saveScrollPosition(roomJid: String, position: Int) {
        scrollPositions[roomJid] = position
        // Persist to SharedPreferences
        sharedPreferences?.edit()?.putInt(roomJid, position)?.apply()
        android.util.Log.d("ScrollPositionStore", "💾 Saved scroll position for $roomJid: $position")
    }
    
    /**
     * Get saved scroll position for a room
     */
    fun getScrollPosition(roomJid: String): Int? {
        // First check in-memory cache
        val cached = scrollPositions[roomJid]
        if (cached != null) {
            return cached
        }
        // Fallback to SharedPreferences
        val persisted = sharedPreferences?.getInt(roomJid, -1)
        return if (persisted != null && persisted >= 0) {
            scrollPositions[roomJid] = persisted
            persisted
        } else {
            null
        }
    }
    
    /**
     * Clear scroll position for a room
     */
    fun clearScrollPosition(roomJid: String) {
        scrollPositions.remove(roomJid)
        sharedPreferences?.edit()?.remove(roomJid)?.apply()
    }
    
    /**
     * Clear all scroll positions
     */
    fun clearAll() {
        scrollPositions.clear()
        sharedPreferences?.edit()?.clear()?.apply()
    }
}
