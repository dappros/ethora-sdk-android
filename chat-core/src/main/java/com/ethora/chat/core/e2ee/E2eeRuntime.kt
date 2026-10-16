package com.ethora.chat.core.e2ee

import android.util.Log
import com.ethora.chat.core.store.RoomStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Process-wide E2EE state: the config switch, the live OMEMO manager for the
 * signed-in account, and the room lookup. Everything here is inert unless the
 * host turns encryption on (`ChatConfig.e2ee.enabled`) AND the backend marks
 * the room `e2ee`, so with the flag off the stanza builders and the receive path
 * behave exactly as before.
 */
object E2eeRuntime {
    private const val TAG = "E2eeRuntime"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var enabled = false
    @Volatile private var manager: OmemoManager? = null
    @Volatile private var starting: Deferred<OmemoManager?>? = null
    @Volatile private var storeFactory: ((String) -> OmemoStore)? = null

    /** Result of trying to encrypt one outgoing stanza. */
    sealed class Outcome {
        /** The room is not end-to-end encrypted, so nothing was done. */
        object NotEncrypted : Outcome()
        class Encrypted(val stanza: XmlElement) : Outcome()
        class Failed(val reason: String) : Outcome()
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) stop()
    }

    fun isEnabled(): Boolean = enabled

    /** Where each account's keys live. Without a store nothing starts and rooms stay plaintext. */
    fun setStoreFactory(factory: ((accountJid: String) -> OmemoStore)?) {
        storeFactory = factory
    }

    /** Called once the XMPP session is bound. Safe on every reconnect: keys are reused, the bundle republished. */
    fun onOnline(transport: OmemoTransport) {
        if (!enabled) return
        val factory = storeFactory ?: run {
            Log.w(TAG, "E2EE is on but no key store is configured; encrypted rooms send in clear")
            return
        }
        starting = scope.async {
            try {
                OmemoManager.create(transport, factory(transport.ownJid), scope).also { manager = it }
            } catch (e: Exception) {
                Log.e(TAG, "OMEMO could not start", e)
                null
            }
        }
    }

    /** The manager once it is usable, waiting out a start that is in flight. */
    suspend fun ready(): OmemoManager? {
        if (!enabled) return null
        manager?.let { return it }
        return starting?.await()
    }

    /** Drops the in-memory manager. Stored keys survive, so a re-login resumes. */
    fun stop() {
        manager = null
        starting = null
    }

    /** True when the backend marked this room end-to-end encrypted. */
    fun isE2eeRoom(roomJid: String): Boolean {
        if (!enabled) return false
        return RoomStore.getRoomByJid(roomJid.substringBefore('/'))?.e2ee == true
    }

    /** Bare JIDs of every room member on [domain]: the recipients of an encrypted message. */
    fun memberJids(roomJid: String, domain: String): List<String> {
        val members = RoomStore.getRoomByJid(roomJid.substringBefore('/'))?.members ?: return emptyList()
        return members.mapNotNull { member ->
            val local = member.xmppUsername?.substringBefore('@')?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            "$local@$domain".lowercase()
        }
    }

    /**
     * Encrypts a groupchat stanza for an e2ee room. [content] is what OMEMO
     * protects, [cleartext] rides beside <encrypted> (the <data> element).
     */
    suspend fun encryptGroupStanza(
        roomJid: String,
        domain: String,
        id: String,
        content: List<XmlElement>,
        cleartext: List<XmlElement>
    ): Outcome {
        if (!isE2eeRoom(roomJid)) return Outcome.NotEncrypted
        val manager = ready() ?: return Outcome.Failed("omemo_not_ready")
        val room = roomJid.substringBefore('/')
        return try {
            val stanza = manager.encryptGroupMessage(
                room,
                memberJids(room, domain),
                content,
                id,
                cleartext
            )
            Outcome.Encrypted(stanza)
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
