package com.ethora.chat.core.networking

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Image cache utility using LruCache
 */
object ImageCache {
    private val cacheSize = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    private val memoryCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    /**
     * Get bitmap from cache or load from URL
     */
    suspend fun getBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        // Check cache first
        memoryCache.get(url)?.let { return@withContext it }

        // Load from URL
        try {
            val bitmap = BitmapFactory.decodeStream(URL(url).openStream())
            bitmap?.let { memoryCache.put(url, it) }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clear cache
     */
    fun clearCache() {
        memoryCache.evictAll()
    }

    /**
     * Remove specific entry from cache
     */
    fun removeFromCache(url: String) {
        memoryCache.remove(url)
    }
}
