package com.ethora.chat.core.networking

import com.ethora.chat.core.call.CallKind
import com.ethora.chat.core.store.ChatStore
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Call API. `POST /v1/chats/call/create/{chatName}` starts a call in a 1:1 room.
 * The user token is attached by the shared [ApiClient] interceptor.
 */
interface CallsAPI {
    @POST("chats/call/create/{chatName}")
    suspend fun createCall(
        @Path("chatName") chatName: String,
        @Body body: CreateCallRequest
    ): Response<Unit>
}

/** Body of the call create request. The server stamps [kind] on the call-token broadcast. */
data class CreateCallRequest(
    val kind: String
)

object CallsAPIHelper {
    /**
     * Starts a call in [chatName] (the bare room name, i.e. the JID localpart).
     * Throws when the server rejects the request.
     */
    suspend fun createChatCall(
        chatName: String,
        kind: CallKind,
        baseUrl: String = ChatStore.getEffectiveBaseUrl()
    ) {
        val api = ApiClient.createService<CallsAPI>(baseUrl)
        val response = api.createCall(chatName, CreateCallRequest(kind = kind.wire))
        if (!response.isSuccessful) {
            throw Exception("Error creating chat call: ${response.code()} ${response.message()}")
        }
    }
}
