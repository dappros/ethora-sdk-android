package com.ethora.chat.core.networking

import com.ethora.chat.core.models.ApiRoom
import com.ethora.chat.core.models.RoomMember
import com.ethora.chat.core.store.ChatStore
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PUT
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Room management endpoints, wire-compatible with web
 * networking/api-requests/rooms.api.ts, roomMembers.api.ts and publicChats.api.ts.
 * The base URL is the `/v1` root; v2 routes are called by absolute URL.
 */
interface RoomManagementAPI {

    @PUT("chats/my/{chatName}/mute")
    suspend fun muteRoom(
        @Path("chatName") chatName: String,
        @Body body: EmptyRequest = EmptyRequest()
    ): Response<MuteEnvelope>

    @DELETE("chats/my/{chatName}/mute")
    suspend fun unmuteRoom(
        @Path("chatName") chatName: String
    ): Response<MuteEnvelope>

    @POST("chats/reports/{chatName}")
    suspend fun reportRoom(
        @Path("chatName") chatName: String,
        @Body body: ReportRoomRequest
    ): Response<JsonElement>

    @POST("chats/users-access")
    suspend fun addMembers(@Body body: UsersAccessRequest): Response<JsonElement>

    @HTTP(method = "DELETE", path = "chats/users-access", hasBody = true)
    suspend fun removeMembers(@Body body: UsersAccessRequest): Response<JsonElement>

    @HTTP(method = "DELETE", path = "chats", hasBody = true)
    suspend fun deleteRoom(@Body body: DeleteRoomRequest): Response<JsonElement>

    @GET("chats/public")
    suspend fun getPublicChats(
        @Query("limit") limit: Int,
        @Query("offset") offset: Int
    ): Response<PublicChatsResponse>

    @GET("chats/my/{chatName}")
    suspend fun getRoomByName(@Path("chatName") chatName: String): Response<ApiRoom>

    @GET("apps/users/{id}")
    suspend fun getUserV1(@Path("id") id: String): Response<JsonElement>

    @GET
    suspend fun getUserV2(
        @Url url: String,
        @Query("xmppUsername") xmppUsername: String
    ): Response<JsonElement>
}

class EmptyRequest

data class MuteEnvelope(val result: MuteResult? = null)

data class MuteResult(val chatName: String? = null, val muted: Boolean? = null)

data class ReportRoomRequest(val category: String, val text: String)

data class UsersAccessRequest(val chatName: String, val members: List<String>)

data class DeleteRoomRequest(val name: String)

/** One raw row of GET /v1/chats/public. */
data class PublicChatsResponse(
    val items: List<RawPublicChat>? = null,
    val total: Int? = null,
    val offset: Int? = null,
    val limit: Int? = null
)

data class RawPublicChat(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val picture: String? = null,
    val e2ee: Boolean? = null,
    val reported: Boolean? = null
)

/** One entry of the public chats directory: a chat anyone in the app may join. */
data class PublicChat(
    val name: String,
    val title: String,
    val description: String,
    val picture: String,
    val e2ee: Boolean
)

data class PublicChatsPage(
    val items: List<PublicChat>,
    val total: Int,
    val offset: Int,
    val limit: Int,
    /** Server rows consumed so far, not counting hidden (reported) ones. */
    val nextOffset: Int
)

/** Result of looking up one user through the route chain. */
sealed class UserLookupOutcome {
    data class Ok(val user: RoomMember) : UserLookupOutcome()
    object NotFound : UserLookupOutcome()
    object Forbidden : UserLookupOutcome()
    object Error : UserLookupOutcome()
}

object RoomManagementAPIHelper {

    /** The endpoint rejects anything above 50 with a validation error. */
    const val PUBLIC_CHATS_PAGE_SIZE = 50

    private const val USER_ROUTE_TTL_MS = 10 * 60_000L

    @Volatile
    private var v1UnsupportedUntil = 0L

    private fun api(baseUrl: String = ChatStore.getEffectiveBaseUrl()): RoomManagementAPI =
        ApiClient.createService<RoomManagementAPI>(baseUrl)

    /** Mute the caller's own notifications for a chat. Returns the server's `muted`. */
    suspend fun muteRoom(chatName: String, baseUrl: String = ChatStore.getEffectiveBaseUrl()): Boolean? {
        val response = api(baseUrl).muteRoom(chatName)
        if (!response.isSuccessful) throw Exception("Error muting chat: ${response.code()}")
        return muteFromEnvelope(response.body())
    }

    suspend fun unmuteRoom(chatName: String, baseUrl: String = ChatStore.getEffectiveBaseUrl()): Boolean? {
        val response = api(baseUrl).unmuteRoom(chatName)
        if (!response.isSuccessful) throw Exception("Error unmuting chat: ${response.code()}")
        return muteFromEnvelope(response.body())
    }

    /** Report a room. `category` is the English id sent to the API, `text` is optional detail. */
    suspend fun reportRoom(
        chatName: String,
        category: String,
        text: String = "",
        baseUrl: String = ChatStore.getEffectiveBaseUrl()
    ) {
        val response = api(baseUrl).reportRoom(chatName, ReportRoomRequest(category, text))
        if (!response.isSuccessful) throw Exception("Error reporting chat: ${response.code()}")
    }

    /** Add members to a room. Returns the members the server added (`results`). */
    suspend fun addMembers(
        chatName: String,
        members: List<String>,
        baseUrl: String = ChatStore.getEffectiveBaseUrl()
    ): List<RoomMember> {
        val response = api(baseUrl).addMembers(UsersAccessRequest(chatName, members))
        if (!response.isSuccessful) throw Exception("Error adding members: ${response.code()}")
        return parseMemberList(response.body()?.takeIf { it.isJsonObject }?.asJsonObject?.get("results"))
    }

    /** Remove members from a room (moderators only). */
    suspend fun removeMembers(
        chatName: String,
        members: List<String>,
        baseUrl: String = ChatStore.getEffectiveBaseUrl()
    ) {
        val response = api(baseUrl).removeMembers(UsersAccessRequest(chatName, members))
        if (!response.isSuccessful) throw Exception("Error removing members: ${response.code()}")
    }

    /** Delete a room on the server (moderators only). */
    suspend fun deleteRoom(name: String, baseUrl: String = ChatStore.getEffectiveBaseUrl()) {
        val response = api(baseUrl).deleteRoom(DeleteRoomRequest(name))
        if (!response.isSuccessful) throw Exception("Error deleting room: ${response.code()}")
    }

    /** One page of the public chats directory, reported chats filtered out. */
    suspend fun getPublicChats(
        offset: Int = 0,
        limit: Int = PUBLIC_CHATS_PAGE_SIZE,
        baseUrl: String = ChatStore.getEffectiveBaseUrl()
    ): PublicChatsPage {
        val response = api(baseUrl).getPublicChats(
            limit = minOf(limit, PUBLIC_CHATS_PAGE_SIZE),
            offset = offset
        )
        if (!response.isSuccessful) throw Exception("Error loading public chats: ${response.code()}")
        return toPublicChatsPage(response.body(), offset, minOf(limit, PUBLIC_CHATS_PAGE_SIZE))
    }

    /** One room by its bare name (`{appId}_{id}`). */
    suspend fun getRoomByName(chatName: String, baseUrl: String = ChatStore.getEffectiveBaseUrl()): ApiRoom {
        val response = api(baseUrl).getRoomByName(chatName)
        return response.body()?.takeIf { response.isSuccessful }
            ?: throw Exception("Error loading room $chatName: ${response.code()}")
    }

    /**
     * One user by `appId_userId`. GET /v1/apps/users/{id} first; the backend
     * may refuse a user token on v1 (400, 401 or a missing route), in which
     * case v2 answers and v1 is skipped for [USER_ROUTE_TTL_MS]. A 403 on v1
     * is a per-user answer and never falls back (v2 would leak what v1 refused).
     */
    suspend fun lookupUser(
        xmppUsername: String,
        baseUrl: String = ChatStore.getEffectiveBaseUrl()
    ): UserLookupOutcome {
        if (v1UnsupportedUntil > System.currentTimeMillis()) {
            return callV2(xmppUsername, baseUrl)
        }
        val v1 = callV1(xmppUsername, baseUrl)
        if (v1 is V1Result.RouteUnsupported) {
            v1UnsupportedUntil = System.currentTimeMillis() + USER_ROUTE_TTL_MS
            return callV2(xmppUsername, baseUrl)
        }
        return (v1 as V1Result.Done).outcome
    }

    private sealed class V1Result {
        data class Done(val outcome: UserLookupOutcome) : V1Result()
        object RouteUnsupported : V1Result()
    }

    private suspend fun callV1(id: String, baseUrl: String): V1Result = try {
        val response = api(baseUrl).getUserV1(id)
        when {
            response.isSuccessful -> V1Result.Done(
                parseUserPayload(response.body())?.let { UserLookupOutcome.Ok(it) }
                    ?: UserLookupOutcome.Error
            )
            response.code() == 404 && isUserNotFoundBody(response.errorBody()?.string()) ->
                V1Result.Done(UserLookupOutcome.NotFound)
            response.code() == 404 -> V1Result.RouteUnsupported
            response.code() == 403 -> V1Result.Done(UserLookupOutcome.Forbidden)
            response.code() == 400 || response.code() == 401 -> V1Result.RouteUnsupported
            else -> V1Result.Done(UserLookupOutcome.Error)
        }
    } catch (e: Exception) {
        V1Result.Done(UserLookupOutcome.Error)
    }

    private suspend fun callV2(id: String, baseUrl: String): UserLookupOutcome = try {
        val response = api(baseUrl).getUserV2(v2UsersUrl(baseUrl), id)
        when {
            response.isSuccessful -> parseUserPayload(response.body())
                ?.let { UserLookupOutcome.Ok(it) } ?: UserLookupOutcome.Error
            response.code() == 404 -> UserLookupOutcome.NotFound
            response.code() == 403 -> UserLookupOutcome.Forbidden
            else -> UserLookupOutcome.Error
        }
    } catch (e: Exception) {
        UserLookupOutcome.Error
    }

    /** `https://host/v1` -> `https://host/v2/chats/users`. */
    internal fun v2UsersUrl(baseUrl: String): String {
        val root = baseUrl.trimEnd('/').removeSuffix("/v1")
        return "$root/v2/chats/users"
    }

    /** Test hook: forgets the learned v1 route state. */
    internal fun resetUserRouteState() {
        v1UnsupportedUntil = 0L
    }
}

/** Accepts `{result:{...}}`, `{item}`, `{data}` or a one-element array. */
internal fun pickUserPayload(data: JsonElement?): JsonObject? {
    if (data == null || !data.isJsonObject) return null
    val root = data.asJsonObject
    var candidate: JsonElement? = root.get("result")?.takeUnless { it.isJsonNull }
        ?: root.get("item")?.takeUnless { it.isJsonNull }
        ?: root.get("data")?.takeUnless { it.isJsonNull }
    if (candidate != null && candidate.isJsonArray) {
        candidate = candidate.asJsonArray.firstOrNull()
    }
    if (candidate == null || candidate.isJsonNull) {
        if (root.has("xmppUsername") || root.has("firstName") || root.has("lastName")) {
            candidate = root
        }
    }
    if (candidate != null && candidate.isJsonObject) {
        val obj = candidate.asJsonObject
        if ((obj.has("result") || obj.has("item")) && !obj.has("xmppUsername")) {
            candidate = obj.get("result") ?: obj.get("item")
        }
    }
    return candidate?.takeIf { it.isJsonObject }?.asJsonObject
}

/**
 * Display fields only. v2 also returns email and tags, and those must never
 * be kept. Returns null when the payload has none of the display fields.
 */
internal fun parseUserPayload(data: JsonElement?): RoomMember? {
    val c = pickUserPayload(data) ?: return null
    val id = c.stringOrNull("_id")
    val xmppUsername = c.stringOrNull("xmppUsername")
    val firstName = c.stringOrNull("firstName")
    val lastName = c.stringOrNull("lastName")
    val description = c.stringOrNull("description")
    if (id == null && xmppUsername == null && firstName == null && lastName == null) return null
    return RoomMember(
        id = id.orEmpty(),
        firstName = firstName,
        lastName = lastName,
        xmppUsername = xmppUsername,
        description = description
    )
}

internal fun parseMemberList(results: JsonElement?): List<RoomMember> {
    if (results == null || !results.isJsonArray) return emptyList()
    return results.asJsonArray.mapNotNull { parseUserPayload(it) }
}

/** The `muted` state from a mute/unmute envelope, or null when the server did not send it. */
internal fun muteFromEnvelope(envelope: MuteEnvelope?): Boolean? = envelope?.result?.muted

/**
 * Web getPublicChats: drop reported chats and blank names, trim the text
 * fields, and advance the offset by every server row, hidden or not.
 */
internal fun toPublicChatsPage(raw: PublicChatsResponse?, offset: Int, limit: Int): PublicChatsPage {
    val rows = raw?.items.orEmpty()
    val items = rows
        .filter { !it.name.isNullOrBlank() && it.reported != true }
        .map {
            PublicChat(
                name = it.name.orEmpty(),
                title = it.title.orEmpty().trim(),
                description = it.description.orEmpty().trim(),
                picture = it.picture.orEmpty(),
                e2ee = it.e2ee == true
            )
        }
    return PublicChatsPage(
        items = items,
        total = raw?.total ?: 0,
        offset = raw?.offset ?: offset,
        limit = raw?.limit ?: limit,
        nextOffset = offset + rows.size
    )
}

private fun isUserNotFoundBody(text: String?): Boolean {
    if (text.isNullOrEmpty()) return false
    return Regex("USER_NOT_FOUND", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
        Regex("user[^a-z]{0,3}not[^a-z]{0,3}found", RegexOption.IGNORE_CASE).containsMatchIn(text)
}

private fun JsonObject.stringOrNull(key: String): String? {
    val value = get(key) ?: return null
    if (value.isJsonNull || !value.isJsonPrimitive) return null
    return value.asString.takeIf { it.isNotEmpty() }
}
