package com.ethora.chat.core.networking

import com.ethora.chat.core.config.AppConfig
import com.ethora.chat.core.models.ApiRoom
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.RoomType
import com.ethora.chat.core.models.createRoomFromApi
import retrofit2.Response
import retrofit2.http.*

/**
 * Rooms API interface
 */
interface RoomsAPI {
    @GET("chats/my")
    @Headers("x-app-id: ${AppConfig.defaultAppId}")
    suspend fun getRooms(
        @Header("x-app-id") appId: String = AppConfig.defaultAppId
    ): Response<RoomsResponse>

    @POST("chats")
    @Headers("x-app-id: ${AppConfig.defaultAppId}")
    suspend fun createRoom(
        @Header("x-app-id") appId: String = AppConfig.defaultAppId,
        @Body body: CreateRoomRequest
    ): Response<CreateRoomResponse>

    @POST("chats/{chatName}/report-message")
    suspend fun reportMessage(
        @Path("chatName") chatName: String,
        @Body body: ReportMessageRequest
    ): Response<Unit>

    @POST("chats/private")
    @Headers("x-app-id: ${AppConfig.defaultAppId}")
    suspend fun createPrivateRoom(
        @Header("x-app-id") appId: String = AppConfig.defaultAppId,
        @Body body: CreatePrivateRoomRequest
    ): Response<CreateRoomResponse>
}

/**
 * POST /chats and POST /chats/private responses are wrapped in a
 * `{"result": {...}}` envelope by the Ethora backend. GET /chats/my
 * is NOT wrapped (returns `{items: [...]}` directly) — so the
 * wrapper is specific to the create endpoints. This matches the
 * React SDK which reads `response.data.result`.
 */
data class CreateRoomResponse(
    val result: ApiRoom?
)

/**
 * Rooms response
 */
data class RoomsResponse(
    val items: List<ApiRoom>
)

/**
 * Create room request
 */
data class CreateRoomRequest(
    val title: String,
    val type: String,
    val description: String? = null,
    val picture: String? = null,
    val members: List<String>? = null
)

/**
 * Report message request
 */
data class ReportMessageRequest(
    val messageId: String,
    val category: String,
    val text: String? = null
)

/**
 * Create private room request
 */
data class CreatePrivateRoomRequest(
    val username: String
)

/**
 * Rooms API helper functions
 */
object RoomsAPIHelper {
    /**
     * Get user rooms
     */
    suspend fun getRooms(
        baseUrl: String = ChatStore.getEffectiveBaseUrl(),
        appId: String = ChatStore.getEffectiveAppId(),
        conferenceDomain: String = ChatStore.getEffectiveConference()
    ): List<Room> {
        val api = ApiClient.createService<RoomsAPI>(baseUrl)
        val response = api.getRooms(appId)
        if (response.isSuccessful) {
            val roomsResponse = response.body()!!
            return roomsResponse.items.map { apiRoom ->
                createRoomFromApi(apiRoom, conferenceDomain)
            }
        } else {
            throw Exception("Failed to get rooms: ${response.code()} ${response.message()}")
        }
    }

    /**
     * Create room
     */
    suspend fun createRoom(
        title: String,
        type: RoomType,
        description: String? = null,
        picture: String? = null,
        members: List<String>? = null,
        baseUrl: String = ChatStore.getEffectiveBaseUrl(),
        appId: String = ChatStore.getEffectiveAppId()
    ): ApiRoom {
        val api = ApiClient.createService<RoomsAPI>(baseUrl)
        val response = api.createRoom(
            appId,
            CreateRoomRequest(
                title = title,
                type = type.name.lowercase(),
                description = description,
                picture = picture,
                members = members
            )
        )
        if (response.isSuccessful) {
            val wrapper = response.body()
                ?: throw Exception("Failed to create room: empty response body")
            return wrapper.result
                ?: throw Exception("Failed to create room: response missing 'result' field")
        } else {
            throw Exception("Failed to create room: ${response.code()} ${response.message()}")
        }
    }

    /**
     * Report message
     */
    suspend fun reportMessage(
        chatName: String,
        messageId: String,
        category: String,
        text: String? = null,
        baseUrl: String = ChatStore.getEffectiveBaseUrl()
    ) {
        val api = ApiClient.createService<RoomsAPI>(baseUrl)
        val response = api.reportMessage(
            chatName,
            ReportMessageRequest(messageId, category, text)
        )
        if (!response.isSuccessful) {
            throw Exception("Failed to report message: ${response.code()} ${response.message()}")
        }
    }

    /**
     * Create private room (1v1 chat)
     */
    suspend fun createPrivateRoom(
        username: String,
        baseUrl: String = ChatStore.getEffectiveBaseUrl(),
        appId: String = ChatStore.getEffectiveAppId()
    ): ApiRoom {
        val api = ApiClient.createService<RoomsAPI>(baseUrl)
        val response = api.createPrivateRoom(
            appId,
            CreatePrivateRoomRequest(username = username)
        )
        if (response.isSuccessful) {
            val wrapper = response.body()
                ?: throw Exception("Failed to create private room: empty response body")
            return wrapper.result
                ?: throw Exception("Failed to create private room: response missing 'result' field")
        } else {
            throw Exception("Failed to create private room: ${response.code()} ${response.message()}")
        }
    }
}
