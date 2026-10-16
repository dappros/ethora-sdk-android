package com.ethora.chat.core.push

import android.util.Log
import com.ethora.chat.core.config.PushNotificationsConfig
import com.ethora.chat.core.networking.ApiClient
import com.ethora.chat.core.networking.GatewaySubscriptionPayload
import com.ethora.chat.core.networking.PushAPI
import com.ethora.chat.core.networking.PushEndpointPayload
import com.ethora.chat.core.networking.PushSubscriptionPayload
import com.ethora.chat.core.networking.PushUnsubscribePayload
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.UserStore

/** Result of one push REST call: HTTP status plus the raw error or success body. */
internal data class PushHttpResult(val code: Int, val body: String?) {
    val ok: Boolean get() = code in 200..299
}

/**
 * Transport seam for push REST calls. The default implementation goes through
 * Retrofit; tests substitute a fake to exercise the retry and fallback rules.
 */
internal interface PushTransport {
    suspend fun subscribe(baseUrl: String, appId: String, payload: PushSubscriptionPayload, authorization: String): PushHttpResult
    suspend fun unsubscribe(baseUrl: String, appId: String, registrationToken: String, authorization: String): PushHttpResult
    suspend fun deleteEndpoint(baseUrl: String, endpoint: String, authorization: String): PushHttpResult
    suspend fun gatewaySubscribe(gatewayUrl: String, authorization: String, payload: GatewaySubscriptionPayload): PushHttpResult
}

internal object RetrofitPushTransport : PushTransport {
    private fun toPushResult(response: retrofit2.Response<Any>): PushHttpResult =
        PushHttpResult(response.code(), if (response.isSuccessful) null else response.errorBody()?.string())

    override suspend fun subscribe(baseUrl: String, appId: String, payload: PushSubscriptionPayload, authorization: String): PushHttpResult {
        val api = ApiClient.createService(PushAPI::class.java, baseUrl)
        return toPushResult(api.subscribeToPush(appId, payload, authorization))
    }

    override suspend fun unsubscribe(baseUrl: String, appId: String, registrationToken: String, authorization: String): PushHttpResult {
        val api = ApiClient.createService(PushAPI::class.java, baseUrl)
        return toPushResult(api.unsubscribeFromPush(appId, PushUnsubscribePayload(registrationToken), authorization))
    }

    override suspend fun deleteEndpoint(baseUrl: String, endpoint: String, authorization: String): PushHttpResult {
        val api = ApiClient.createService(PushAPI::class.java, baseUrl)
        return toPushResult(api.deleteEndpoint(PushEndpointPayload(endpoint), authorization))
    }

    override suspend fun gatewaySubscribe(gatewayUrl: String, authorization: String, payload: GatewaySubscriptionPayload): PushHttpResult {
        val api = ApiClient.createService(PushAPI::class.java, gatewayUrl)
        return toPushResult(api.subscribeViaGateway(gatewayUrl, authorization, payload))
    }
}

/**
 * Device push registration with the Ethora backend (or a push gateway).
 * Mirrors RN `pushRegistration` and web `push.api.ts`.
 */
object PushRegistration {
    const val TOKEN_TYPE_APNS = "apns"
    const val TOKEN_TYPE_APNS_VOIP = "apns-voip"
    const val TOKEN_TYPE_FCM = "fcm"
    const val TOKEN_TYPE_EXPO = "expo"
    const val DEVICE_TYPE_ANDROID = "android"

    private const val TAG = "PushRegistration"
    private val HEX_TOKEN = Regex("^[0-9a-fA-F]{64,}$")

    internal var transport: PushTransport = RetrofitPushTransport

    /**
     * RN rule: `ExponentPushToken[...]` is expo, 64+ hex characters is apns,
     * anything else is fcm.
     */
    fun detectTokenType(registrationToken: String): String {
        val token = registrationToken.trim()
        return when {
            token.startsWith("ExponentPushToken[") -> TOKEN_TYPE_EXPO
            HEX_TOKEN.matches(token) -> TOKEN_TYPE_APNS
            else -> TOKEN_TYPE_FCM
        }
    }

    /**
     * Register [registrationToken] for push. Direct mode POSTs to
     * `push/subscription/{appId}`; gateway mode is used when `apiUrl` is set.
     * Returns true when the server accepted the subscription.
     */
    suspend fun register(
        registrationToken: String,
        tokenType: String? = detectTokenType(registrationToken),
        config: PushNotificationsConfig? = ChatStore.getConfig()?.pushNotifications,
        appId: String = ChatStore.getEffectiveAppId(),
        baseUrl: String = ChatStore.getEffectiveBaseUrl()
    ): Boolean {
        if (registrationToken.isBlank()) return false
        if (tokenType == TOKEN_TYPE_EXPO) {
            Log.e(TAG, "Expo push tokens are not accepted by the Ethora backend")
            return false
        }
        val user = UserStore.currentUser.value
        val userToken = user?.token
        if (userToken.isNullOrBlank()) {
            Log.e(TAG, "Cannot register push token: no user token")
            return false
        }

        val gatewayUrl = config?.apiUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        if (gatewayUrl != null) {
            return registerViaGateway(registrationToken, gatewayUrl, userToken, config)
        }

        // RN parity: the path appId comes from the authenticated user first, then config.
        val pathAppId = user.appId?.takeIf { it.isNotBlank() } ?: appId
        // Web and RN send the raw session token (no Bearer prefix).
        val first = transport.subscribe(
            baseUrl,
            pathAppId,
            PushSubscriptionPayload(registrationToken, DEVICE_TYPE_ANDROID, tokenType),
            userToken
        )
        if (first.ok) return true

        // The backend sometimes refuses an explicit tokenType with 422 "not allowed";
        // RN retries once with the registration token only.
        val refusedType = tokenType == TOKEN_TYPE_APNS || tokenType == TOKEN_TYPE_FCM
        if (first.code == 422 && refusedType && first.body?.contains("not allowed", ignoreCase = true) == true) {
            val retry = transport.subscribe(
                baseUrl,
                pathAppId,
                PushSubscriptionPayload(registrationToken, DEVICE_TYPE_ANDROID, null),
                userToken
            )
            if (retry.ok) return true
            Log.e(TAG, "Push subscribe retry without tokenType failed: ${retry.code} ${retry.body}")
            return false
        }
        Log.e(TAG, "Push subscribe failed: ${first.code} ${first.body}")
        return false
    }

    private suspend fun registerViaGateway(
        registrationToken: String,
        gatewayUrl: String,
        userToken: String,
        config: PushNotificationsConfig?
    ): Boolean {
        val projectName = config?.projectName?.trim()?.takeIf { it.isNotEmpty() }
        if (projectName == null) {
            Log.e(TAG, "Push gateway mode needs pushNotifications.projectName")
            return false
        }
        val user = UserStore.currentUser.value
        val xmppUser = user?.xmppUsername?.takeIf { it.isNotBlank() } ?: user?.walletAddress
        val xmppHost = ChatStore.getEffectiveXmppSettings().host
        if (xmppUser.isNullOrBlank() || xmppHost.isBlank()) {
            Log.e(TAG, "Push gateway mode needs an XMPP username and xmppSettings.host")
            return false
        }
        val result = transport.gatewaySubscribe(
            "$gatewayUrl/subscriptions",
            "Bearer $userToken",
            GatewaySubscriptionPayload(
                projectName = projectName,
                registrationToken = registrationToken,
                deviceType = DEVICE_TYPE_ANDROID,
                jid = "$xmppUser@$xmppHost"
            )
        )
        if (!result.ok) Log.e(TAG, "Push gateway subscribe failed: ${result.code} ${result.body}")
        return result.ok
    }

    /**
     * Release the registration on logout. DELETE `push/subscription/{appId}`,
     * falling back to DELETE `users/endpoints` when that route answers 404.
     */
    suspend fun unregister(
        registrationToken: String,
        authToken: String,
        appId: String = ChatStore.getEffectiveAppId(),
        baseUrl: String = ChatStore.getEffectiveBaseUrl()
    ): Boolean {
        if (registrationToken.isBlank() || authToken.isBlank()) return false
        val primary = transport.unsubscribe(baseUrl, appId, registrationToken, authToken)
        if (primary.ok) return true
        if (primary.code == 404) {
            val fallback = transport.deleteEndpoint(baseUrl, registrationToken, authToken)
            if (fallback.ok) return true
            Log.w(TAG, "Push endpoint fallback unregister failed: ${fallback.code}")
            return false
        }
        Log.w(TAG, "Push unregister failed: ${primary.code} ${primary.body}")
        return false
    }
}
