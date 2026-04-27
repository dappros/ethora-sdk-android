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
            // RN parity: path appId comes from authenticated user first, then config.
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

            val api = ApiClient.createService(PushAPI::class.java, baseUrl)
            android.util.Log.d(
                TAG,
                "Subscribing to push via base=$baseUrl pathAppId=$pathAppId configAppId=$appId userAppId=${currentUser?.appId} tokenPrefix=${fcmToken.take(10)}..."
            )
            val response = api.subscribeToPush(pathAppId, payload, authHeader)
            if (response.isSuccessful) {
                android.util.Log.d(TAG, "Successfully subscribed to push notifications via base=$baseUrl")
                return true
            }
            android.util.Log.e(TAG, "Failed push subscribe via base=$baseUrl: ${response.code()} ${response.message()}")
            android.util.Log.e(TAG, "  Response body: ${response.errorBody()?.string()}")
            return false
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error subscribing to push notifications", e)
            false
        }
    }
}
