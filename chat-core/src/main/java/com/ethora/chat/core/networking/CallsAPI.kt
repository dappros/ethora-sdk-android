package com.ethora.chat.core.networking

import android.util.Log
import com.ethora.chat.core.call.CallCreateResult
import com.ethora.chat.core.call.CallKind
import com.ethora.chat.core.call.IceServerConfig
import com.ethora.chat.core.call.normalizeIceServers
import com.ethora.chat.core.call.parseCallCreateResponse
import com.ethora.chat.core.store.ChatStore
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
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
    ): Response<JsonObject>

    /** `GET /v1/calls/ice-servers`: fresh STUN/TURN credentials bound to the caller. */
    @GET("calls/ice-servers")
    suspend fun iceServers(): Response<JsonObject>

    /** `POST /v1/chats/call/state/{decline|end|...}/{callId}`: client report for a p2p call. */
    @POST("chats/call/state/{report}/{callId}")
    suspend fun reportCallState(
        @Path("report") report: String,
        @Path("callId") callId: String
    ): Response<Unit>
}

/** Body of the call create request. The server stamps [kind] on the call-token broadcast. */
data class CreateCallRequest(
    val kind: String
)

object CallsAPIHelper {
    /** Reports a client can send for a call it cannot carry itself (web `callState.api.ts`). */
    enum class CallStateReport(val wire: String) {
        /** The incoming UI is on screen. */
        RINGING("ringing"),
        /** The callee rejected the call; the server relays it to the caller. */
        DECLINE("decline"),
        /** Media is flowing; starts the server-side call timer. */
        CONNECTED("connected"),
        /** A ringing or connected call ended for any reason (idempotent on the server). */
        END("end")
    }

    private val reportScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Fire and forget: tells the server what this client did with a p2p call, so the
     * other side is not left ringing. Never throws, a failure is only logged.
     */
    fun reportCallState(
        report: CallStateReport,
        callId: String?,
        baseUrl: String? = null
    ) {
        val id = callId?.trim().orEmpty()
        if (id.isEmpty()) return
        reportScope.launch {
            try {
                val api = ApiClient.createService<CallsAPI>(baseUrl ?: ChatStore.getEffectiveBaseUrl())
                val response = api.reportCallState(report.wire, id)
                if (!response.isSuccessful) {
                    Log.w("CallsAPI", "Call state report ${report.wire} failed: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.w("CallsAPI", "Call state report ${report.wire} failed", e)
            }
        }
    }

    /**
     * Starts a call in [chatName] (the bare room name, i.e. the JID localpart).
     * Throws when the server rejects the request.
     */
    suspend fun createChatCall(
        chatName: String,
        kind: CallKind,
        baseUrl: String = ChatStore.getEffectiveBaseUrl()
    ): CallCreateResult {
        val api = ApiClient.createService<CallsAPI>(baseUrl)
        val response = api.createCall(chatName, CreateCallRequest(kind = kind.wire))
        if (!response.isSuccessful) {
            throw Exception("Error creating chat call: ${response.code()} ${response.message()}")
        }
        // Old servers answer with no `mode`: that is a LiveKit call and the caller waits for its call-token.
        return parseCallCreateResponse(response.body(), kind)
    }

    /**
     * Fresh STUN/TURN servers for a p2p call: used by a callee whose ring carried none, and to
     * refresh the (ephemeral) credentials before an ICE restart. Null on any failure so callers
     * can carry on with the servers they already have.
     */
    suspend fun fetchIceServers(baseUrl: String = ChatStore.getEffectiveBaseUrl()): List<IceServerConfig>? {
        return try {
            val api = ApiClient.createService<CallsAPI>(baseUrl)
            val response = api.iceServers()
            if (!response.isSuccessful) return null
            normalizeIceServers(response.body()?.get("iceServers")).takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("CallsAPI", "ice-servers fetch failed", e)
            null
        }
    }
}
