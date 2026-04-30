package com.ethora.chat

import android.content.Context
import com.ethora.chat.core.persistence.ChatDatabase
import com.ethora.chat.core.persistence.ChatPersistenceManager
import com.ethora.chat.core.persistence.LocalStorage
import com.ethora.chat.core.persistence.MessageCache
import com.ethora.chat.core.push.PushNotificationManager
import com.ethora.chat.core.store.MessageLoader
import com.ethora.chat.core.store.MessageStore
import com.ethora.chat.core.store.PendingMediaSendQueue
import com.ethora.chat.core.store.RoomStore
import com.ethora.chat.core.store.ScrollPositionStore
import com.ethora.chat.core.store.UserStore

/**
 * Process-level SDK initializer.
 *
 * Host apps should call this once from Application.onCreate(). The method is
 * idempotent so Activity recreation or defensive repeated calls are safe.
 */
object EthoraChatSdk {
    @Volatile
    private var initialized = false

    @JvmStatic
    @Synchronized
    fun initialize(context: Context) {
        if (initialized) {
            android.util.Log.d("EthoraChatSdk", "↻ EthoraChatSdk already initialized")
            return
        }

        val appContext = context.applicationContext
        val persistenceManager = ChatPersistenceManager(appContext)
        val chatDatabase = ChatDatabase.getDatabase(appContext)
        val messageCache = MessageCache(chatDatabase)

        RoomStore.initialize(persistenceManager)
        UserStore.initialize(persistenceManager)
        MessageStore.initialize(messageCache)
        ScrollPositionStore.initialize(appContext)
        MessageLoader.initialize(LocalStorage(appContext))
        PendingMediaSendQueue.initialize(appContext)
        PushNotificationManager.initialize(appContext)

        initialized = true
        android.util.Log.d("EthoraChatSdk", "✅ EthoraChatSdk initialized")
    }

    @JvmStatic
    fun isInitialized(): Boolean = initialized
}
