package com.ethora.chat.core.models

import com.google.gson.annotations.SerializedName

/**
 * Room member
 */
data class RoomMember(
    val id: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val xmppUsername: String? = null,
    val banStatus: String? = null,
    val jid: String? = null,
    val name: String? = null,
    val role: String? = null,
    val lastActive: Long? = null,
    val description: String? = null
)

/**
 * Room type. Backend JSON uses lowercase ("public", "group", "private") —
 * @SerializedName lets Gson deserialize those into the enum. Without this
 * annotation, parsing ApiRoom silently leaves `type` null (Gson is
 * case-sensitive on enum constant names) and rooms fail to render.
 */
enum class RoomType {
    @SerializedName("public")
    PUBLIC,

    @SerializedName("group")
    GROUP,

    @SerializedName("private")
    PRIVATE
}

enum class HistoryPreloadState {
    IDLE,
    LOADING,
    DONE,
    ERROR
}

/**
 * Message statistics
 */
data class MessageStats(
    val lastMessageTimestamp: Long? = null,
    val firstMessageTimestamp: Long? = null
)

/**
 * Room model
 */
data class Room(
    val id: String,
    val jid: String,
    val name: String,
    val title: String,
    val usersCnt: Int = 0,
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val roomBg: String? = null,
    val members: List<RoomMember>? = null,
    val type: RoomType? = null,
    val createdAt: String? = null,
    val appId: String? = null,
    val createdBy: String? = null,
    val description: String? = null,
    val isAppChat: Boolean? = null,
    val picture: String? = null,
    val updatedAt: String? = null,
    val lastMessage: LastMessage? = null,
    val lastMessageTimestamp: Long? = null,
    val icon: String? = null,
    val composing: Boolean? = null,
    val composingList: List<String>? = null,
    val lastViewedTimestamp: Long? = null,
    val unreadMessages: Int = 0,
    val pendingMessages: Int = 0,
    val unreadCapped: Boolean = false,
    val noMessages: Boolean? = null,
    val role: String? = null,
    val messageStats: MessageStats? = null,
    val historyComplete: Boolean? = null,
    val historyPreloadState: HistoryPreloadState = HistoryPreloadState.IDLE
)

/**
 * API-level room representation (matches TypeScript ApiRoom)
 */
data class ApiRoom(
    val name: String,
    val type: RoomType,
    val title: String? = null,
    val description: String? = null,
    val picture: String? = null,
    val members: List<RoomMember>? = null,
    val createdBy: String? = null,
    val appId: String? = null,
    val _id: String? = null,
    val isAppChat: Boolean? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val __v: Int? = null,
    val reported: Boolean? = null
)

/**
 * Convenience function to create Room from ApiRoom
 */
fun createRoomFromApi(apiRoom: ApiRoom, conferenceDomain: String, usersArrayLength: Int = 0): Room {
    val jid = "${apiRoom.name}@$conferenceDomain"
    return Room(
        id = apiRoom._id ?: apiRoom.name,
        jid = jid,
        name = apiRoom.title ?: apiRoom.name,
        title = apiRoom.title ?: apiRoom.name,
        usersCnt = apiRoom.members?.size ?: (usersArrayLength + 1),
        messages = emptyList(),
        isLoading = false,
        roomBg = null,
        members = apiRoom.members,
        type = apiRoom.type,
        createdAt = apiRoom.createdAt,
        appId = apiRoom.appId,
        createdBy = apiRoom.createdBy,
        description = apiRoom.description,
        isAppChat = apiRoom.isAppChat,
        picture = apiRoom.picture,
        updatedAt = apiRoom.updatedAt,
        lastMessage = null,
        lastMessageTimestamp = null,
        icon = if (apiRoom.picture != null && apiRoom.picture != "none") apiRoom.picture else null,
        composing = null,
        composingList = null,
        lastViewedTimestamp = 0,
        unreadMessages = 0,
        pendingMessages = 0,
        unreadCapped = false,
        noMessages = null,
        role = null,
        messageStats = null,
        historyComplete = null,
        historyPreloadState = HistoryPreloadState.IDLE
    )
}
