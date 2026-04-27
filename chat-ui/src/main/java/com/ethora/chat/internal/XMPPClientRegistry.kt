package com.ethora.chat.internal

import android.util.Log
import com.ethora.chat.core.config.XMPPSettings
import com.ethora.chat.core.store.LogStore
import com.ethora.chat.core.xmpp.XMPPClient

/**
 * Process-wide cache for the single live XMPPClient.
 *
 * Why this exists:
 *
 * [com.ethora.chat.Chat] is a Composable that creates its XMPPClient
 * inside a `remember(...)` block. When the host app disposes the Chat
 * subtree and re-enters it (e.g. when a BottomNav dispatcher uses
 * `when (selectedTab) { 1 -> ChatTab() }` — a very common pattern)
 * the `remember` slot is torn down and a fresh XMPPClient is allocated
 * on the next mount. Both instances race to SASL / bind against the
 * same JID; ejabberd accepts only one resource per JID at a time and
 * kicks the other with `<stream:error><not-authorized/>`, which tears
 * down the socket, which causes another reconnect attempt, which
 * spawns another client — and so on. In local testing we saw up to
 * four XMPPClient instances created for a single user session.
 *
 * [EthoraChatBootstrap.sharedXmppClient] solves this for hosts that
 * call `EthoraChatBootstrap.initialize(...)` at app startup; the
 * registry covers hosts that don't, so transport churn
 * from compose lifecycle quirks doesn't depend on the host opting in
 * to bootstrap.
 *
 * Thread-safe via synchronization on the object itself.
 */
internal object XMPPClientRegistry {
    private const val TAG = "XMPPClientRegistry"

    @Volatile
    private var current: XMPPClient? = null

    @Volatile
    private var currentKey: String? = null

    /**
     * Return the cached client for [username] if one exists, otherwise
     * create a new one with the supplied [settings] and
     * [dnsFallbackOverrides]. Switching users disconnects the previous
     * client before returning the new one.
     *
     * [settings] must come from host configuration. The SDK does not supply
     * default XMPP endpoints when it is absent.
     */
    @Synchronized
    fun getOrCreate(
        username: String,
        password: String,
        settings: XMPPSettings?,
        dnsFallbackOverrides: Map<String, String>?
    ): XMPPClient {
        val existing = current
        if (existing != null && currentKey == username) {
            Log.d(TAG, "↻ reusing cached XMPPClient for $username")
            LogStore.info(TAG, "↻ reusing cached XMPPClient for $username")
            return existing
        }
        existing?.let { old ->
            Log.w(TAG, "⤫ replacing XMPPClient (was=$currentKey, now=$username) — disconnecting old")
            LogStore.warning(TAG, "⤫ replacing XMPPClient (was=$currentKey, now=$username) — disconnecting old")
            try {
                old.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "  (old client disconnect threw: ${e.message})")
            }
        }
        Log.d(TAG, "＋ creating NEW XMPPClient for $username")
        LogStore.info(TAG, "＋ creating NEW XMPPClient for $username")
        val client = XMPPClient(
            username = username,
            password = password,
            settings = settings,
            dnsFallbackOverrides = dnsFallbackOverrides
        )
        current = client
        currentKey = username
        return client
    }

    /**
     * Disconnect and release the cached client. Intended for logout flows.
     */
    @Synchronized
    fun clear() {
        current?.let {
            try {
                it.disconnect()
            } catch (_: Exception) { /* best effort */ }
        }
        current = null
        currentKey = null
    }

    /** For diagnostics — exposed internally only. */
    @Synchronized
    internal fun peekCurrentKey(): String? = currentKey
}
