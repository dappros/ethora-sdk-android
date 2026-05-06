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

    /**
     * Tear down runtime SDK state without touching persisted data.
     *
     * What this does:
     *  • disconnects the shared bootstrap XMPP client and clears it from
     *    [EthoraChatBootstrap], so the next [EthoraChatBootstrap.initializeAsync]
     *    starts a fresh socket;
     *  • resets internal sync flags (`InitBeforeLoadFlow`, `MessageLoader`)
     *    so the next session re-runs the first-pass history preload instead
     *    of degrading to a delta catchup;
     *  • clears the cached fallback XMPP client in [XMPPClientRegistry];
     *  • flips this object's `initialized` flag so a subsequent
     *    [initialize] re-runs the real setup instead of being a no-op.
     *
     * What this does NOT do:
     *  • does **not** clear DataStore, Room database, encrypted token storage,
     *    pending-media files, scroll positions, or any other on-disk state —
     *    persisted messages and pending-send queues survive shutdown so the
     *    user does not lose unsent work;
     *  • does **not** unregister callbacks the host installed via
     *    [EthoraChatBootstrap.addUnreadListener] — those are owned by the
     *    host and must be released by the host (the listener registration
     *    is an [AutoCloseable]).
     *
     * Calling [shutdown] when the SDK was never initialized is a safe no-op.
     * After [shutdown], [initialize] can be called again to reuse the same
     * persisted data with a fresh runtime — useful for logout → login flows
     * and for tests that need to reset between cases.
     *
     * This method blocks only briefly (the underlying disconnect is launched
     * on a background scope). For a fully-awaited teardown use
     * [EthoraChatBootstrap.shutdownBlocking] from a coroutine.
     */
    @JvmStatic
    @Synchronized
    fun shutdown() {
        if (!initialized) {
            android.util.Log.d("EthoraChatSdk", "↻ EthoraChatSdk shutdown called but not initialized")
            return
        }
        EthoraChatBootstrap.shutdown()
        initialized = false
        android.util.Log.d("EthoraChatSdk", "🧹 EthoraChatSdk shutdown — runtime state cleared, persisted data preserved")
    }
}
