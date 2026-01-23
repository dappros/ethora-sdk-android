package com.ethora.chat.core.config

import com.ethora.chat.core.models.Room

/**
 * Custom rooms configuration
 */
data class CustomRoomsConfig(
    val rooms: List<Room>,
    val disableGetRooms: Boolean = false,
    val singleRoom: Boolean = false
)
