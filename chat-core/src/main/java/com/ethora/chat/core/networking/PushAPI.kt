package com.ethora.chat.core.networking

import com.ethora.chat.core.config.AppConfig
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
    @POST("push/subscriptions/{appId}")
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
        appId: String = AppConfig.defaultAppId,
        baseUrl: String = AppConfig.defaultBaseURL
    ): Boolean {
        return try {
            val userToken = UserStore.currentUser.value?.token
            if (userToken.isNullOrBlank()) {
                android.util.Log.e(TAG, "Cannot subscribe to push: no user token")
                return false
            }

            val api = ApiClient.createService(PushAPI::class.java, baseUrl)
            val payload = PushSubscriptionPayload(
                registrationToken = fcmToken,
                deviceType = "android"
            )

            val authHeader = if (userToken.startsWith("Bearer ") || userToken.startsWith("JWT ")) {
                userToken
            } else {
                "Bearer $userToken"
            }

            android.util.Log.d(TAG, "Subscribing to push: appId=$appId, tokenPrefix=${fcmToken.take(10)}...")
            val response = api.subscribeToPush(appId, payload, authHeader)
            if (response.isSuccessful) {
                android.util.Log.d(TAG, "Successfully subscribed to push notifications")
                true
            } else {
                android.util.Log.e(TAG, "Failed to subscribe to push: ${response.code()} ${response.message()}")
                android.util.Log.e(TAG, "  Response body: ${response.errorBody()?.string()}")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error subscribing to push notifications", e)
            false
        }
    }
}
