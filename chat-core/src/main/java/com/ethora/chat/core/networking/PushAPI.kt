package com.ethora.chat.core.networking

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Url

/**
 * Body for `POST push/subscription/{appId}`. [tokenType] is omitted when null,
 * which is the shape used by the 422 retry.
 */
data class PushSubscriptionPayload(
    val registrationToken: String,
    val deviceType: String = "android",
    val tokenType: String? = null
)

/** Body for `DELETE push/subscription/{appId}`. */
data class PushUnsubscribePayload(val registrationToken: String)

/** Body for the legacy `DELETE users/endpoints` fallback. */
data class PushEndpointPayload(val endpoint: String)

/** Body for `POST {apiUrl}/subscriptions` when a push gateway is configured. */
data class GatewaySubscriptionPayload(
    val projectName: String,
    val registrationToken: String,
    val deviceType: String,
    val jid: String
)

interface PushAPI {
    @POST("push/subscription/{appId}")
    suspend fun subscribeToPush(
        @Path("appId") appId: String,
        @Body payload: PushSubscriptionPayload,
        @Header("Authorization") authorization: String
    ): Response<Any>

    @HTTP(method = "DELETE", path = "push/subscription/{appId}", hasBody = true)
    suspend fun unsubscribeFromPush(
        @Path("appId") appId: String,
        @Body payload: PushUnsubscribePayload,
        @Header("Authorization") authorization: String
    ): Response<Any>

    @HTTP(method = "DELETE", path = "users/endpoints", hasBody = true)
    suspend fun deleteEndpoint(
        @Body payload: PushEndpointPayload,
        @Header("Authorization") authorization: String
    ): Response<Any>

    @POST
    suspend fun subscribeViaGateway(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body payload: GatewaySubscriptionPayload
    ): Response<Any>
}

/**
 * Kept for source compatibility. Delegates to
 * [com.ethora.chat.core.push.PushRegistration.register].
 */
object PushAPIHelper {
    suspend fun subscribeToPush(
        fcmToken: String,
        appId: String = com.ethora.chat.core.store.ChatStore.getEffectiveAppId(),
        baseUrl: String = com.ethora.chat.core.store.ChatStore.getEffectiveBaseUrl()
    ): Boolean = com.ethora.chat.core.push.PushRegistration.register(
        registrationToken = fcmToken,
        appId = appId,
        baseUrl = baseUrl
    )
}
