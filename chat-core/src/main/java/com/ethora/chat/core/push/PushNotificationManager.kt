package com.ethora.chat.core.push

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ethora.chat.core.config.AppConfig
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.networking.PushAPIHelper
import com.ethora.chat.core.xmpp.XMPPClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages push notifications: FCM token, API subscription, and MUC-SUB room subscriptions.
 * Mirrors pushSubscriptionService.ts from React Native.
 */
object PushNotificationManager {
    private const val TAG = "PushNotifManager"
    private const val PREFS_NAME = "ethora_push_prefs"
    private const val KEY_FCM_TOKEN = "fcm_token"
    private const val KEY_SUBSCRIBED_ROOMS = "subscribed_rooms"
    private const val KEY_PENDING_NOTIFICATION_JID = "pending_notification_jid"

    private var prefs: SharedPreferences? = null
    private val subscribedRooms = mutableSetOf<String>()

    private val _fcmToken = MutableStateFlow<String?>(null)
    val fcmToken: StateFlow<String?> = _fcmToken

    private val _pendingNotificationJid = MutableStateFlow<String?>(null)
    val pendingNotificationJid: StateFlow<String?> = _pendingNotificationJid

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun initialize(context: Context) {
        Log.d(TAG, "🔔 Initializing PushNotificationManager")
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadSubscribedRooms()
        Log.d(TAG, "🔔 Loaded ${subscribedRooms.size} previously subscribed rooms")

        val storedToken = prefs?.getString(KEY_FCM_TOKEN, null)
        if (storedToken != null) {
            _fcmToken.value = storedToken
            Log.d(TAG, "🔔 Restored FCM token from prefs (${storedToken.take(10)}...)")
        }

        val storedJid = prefs?.getString(KEY_PENDING_NOTIFICATION_JID, null)
        if (storedJid != null) {
            _pendingNotificationJid.value = storedJid
            prefs?.edit()?.remove(KEY_PENDING_NOTIFICATION_JID)?.apply()
            Log.d(TAG, "🔔 Restored pending notification JID: $storedJid")
        }
    }

    fun setFcmToken(token: String) {
        _fcmToken.value = token
        prefs?.edit()?.putString(KEY_FCM_TOKEN, token)?.apply()
        Log.d(TAG, "🔔 FCM token stored (${token.take(10)}...)")
    }

    /**
     * Subscribe device to push notifications via backend API.
     * Matches RN: pushSubscriptionService.subscribeToPush()
     */
    suspend fun subscribeToBackend(
        fcmToken: String,
        appId: String = ChatStore.getEffectiveAppId(),
        baseUrl: String = ChatStore.getEffectiveBaseUrl()
    ): Boolean {
        return PushAPIHelper.subscribeToPush(fcmToken, appId, baseUrl)
    }

    /**
     * Subscribe to a single room via XMPP MUC-SUB.
     * Matches RN: subscribeToRoomMessages.xmpp.ts
     */
    suspend fun subscribeToRoom(
        xmppClient: XMPPClient,
        roomJid: String
    ): Boolean {
        if (subscribedRooms.contains(roomJid)) {
            return true
        }
        return try {
            val success = xmppClient.subscribeToRoomMessages(roomJid)
            if (success) {
                subscribedRooms.add(roomJid)
                saveSubscribedRooms()
                Log.d(TAG, "Subscribed to room: $roomJid")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to subscribe to room $roomJid", e)
            false
        }
    }

    /**
     * Subscribe to all rooms via MUC-SUB.
     * Matches RN: pushSubscriptionService.subscribeToRooms()
     */
    suspend fun subscribeToRooms(
        xmppClient: XMPPClient,
        roomJids: List<String>
    ) {
        var successful = 0
        var failed = 0
        for (jid in roomJids) {
            val result = subscribeToRoom(xmppClient, jid)
            if (result) successful++ else failed++
            delay(100)
        }
        Log.d(TAG, "Room subscription complete: $successful succeeded, $failed failed")
    }

    fun setPendingNotificationJid(jid: String) {
        _pendingNotificationJid.value = jid
        prefs?.edit()?.putString(KEY_PENDING_NOTIFICATION_JID, jid)?.apply()
        Log.d(TAG, "Pending notification JID set: $jid")
    }

    fun clearPendingNotificationJid() {
        _pendingNotificationJid.value = null
        prefs?.edit()?.remove(KEY_PENDING_NOTIFICATION_JID)?.apply()
    }

    fun reset() {
        subscribedRooms.clear()
        _fcmToken.value = null
        _pendingNotificationJid.value = null
        prefs?.edit()?.clear()?.apply()
    }

    private fun loadSubscribedRooms() {
        val stored = prefs?.getStringSet(KEY_SUBSCRIBED_ROOMS, emptySet()) ?: emptySet()
        subscribedRooms.clear()
        subscribedRooms.addAll(stored)
    }

    private fun saveSubscribedRooms() {
        prefs?.edit()?.putStringSet(KEY_SUBSCRIBED_ROOMS, subscribedRooms.toSet())?.apply()
    }
}
