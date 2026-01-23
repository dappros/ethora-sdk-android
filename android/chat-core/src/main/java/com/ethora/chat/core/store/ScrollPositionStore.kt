package com.ethora.chat.core.store

/**
 * Store for saving scroll positions for each room
 */
object ScrollPositionStore {
    private val scrollPositions = mutableMapOf<String, Int>()
    
    /**
     * Save scroll position for a room
     */
    fun saveScrollPosition(roomJid: String, position: Int) {
        scrollPositions[roomJid] = position
        android.util.Log.d("ScrollPositionStore", "💾 Saved scroll position for $roomJid: $position")
    }
    
    /**
     * Get saved scroll position for a room
     */
    fun getScrollPosition(roomJid: String): Int? {
        return scrollPositions[roomJid]
    }
    
    /**
     * Clear scroll position for a room
     */
    fun clearScrollPosition(roomJid: String) {
        scrollPositions.remove(roomJid)
    }
    
    /**
     * Clear all scroll positions
     */
    fun clearAll() {
        scrollPositions.clear()
    }
}
