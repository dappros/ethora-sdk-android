package com.ethora.chat.core.networking

import com.ethora.chat.core.config.AppConfig
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.UserStore
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

data class PushSubscriptionPayload(
    val registrationToken: String,
    val deviceType: String = "android"
)

interface PushAPI {
    @POST("push/subscription/{appId}")
    suspend fun subscribeToPush(
        @Path("appId") appId: String,
        @Body payload: PushSubscriptionPayload,
        @Header("Authorization") authorization: String
    ): Response<Any>
}

object PushAPIHelper {
    private val TAG = "PushAPIHelper"

    suspend fun subscribeToPush(
        fcmToken: String,
        appId: String = ChatStore.getEffectiveAppId(),
        baseUrl: String = ChatStore.getEffectiveBaseUrl()
    ): Boolean {
        return try {
            val currentUser = UserStore.currentUser.value
            val userToken = currentUser?.token
            if (userToken.isNullOrBlank()) {
                android.util.Log.e(TAG, "Cannot subscribe to push: no user token")
                return false
            }
            // RN parity: path appId comes from authenticated user first, then config fallback.
            val pathAppId = currentUser?.appId?.takeIf { it.isNotBlank() } ?: appId

            val payload = PushSubscriptionPayload(
                registrationToken = fcmToken,
                deviceType = "android"
            )

            val authHeader = if (userToken.startsWith("Bearer ") || userToken.startsWith("JWT ")) {
                userToken
            } else {
                "Bearer $userToken"
            }

            // Backend environments differ: some expose push under /v1, some under root.
            // Try current base URL first, then fallback stripped from trailing /v1.
            val candidateBaseUrls = linkedSetOf(baseUrl).apply {
                val trimmed = baseUrl.trimEnd('/')
                if (trimmed.endsWith("/v1")) {
                    add(trimmed.removeSuffix("/v1"))
                }
            }
            for (candidate in candidateBaseUrls) {
                val api = ApiClient.createService(PushAPI::class.java, candidate)
                android.util.Log.d(
                    TAG,
                    "Subscribing to push via base=$candidate pathAppId=$pathAppId configAppId=$appId userAppId=${currentUser?.appId} tokenPrefix=${fcmToken.take(10)}..."
                )
                val response = api.subscribeToPush(pathAppId, payload, authHeader)
                if (response.isSuccessful) {
                    android.util.Log.d(TAG, "Successfully subscribed to push notifications via base=$candidate")
                    return true
                }
                android.util.Log.e(TAG, "Failed push subscribe via base=$candidate: ${response.code()} ${response.message()}")
                android.util.Log.e(TAG, "  Response body: ${response.errorBody()?.string()}")
            }
            false
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error subscribing to push notifications", e)
            false
        }
    }
}
