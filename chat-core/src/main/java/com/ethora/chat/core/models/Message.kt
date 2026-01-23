package com.ethora.chat.core.models

import java.util.Date

/**
 * Message model
 */
data class Message(
    val id: String,
    val user: User,
    val date: Date,
    val body: String,
    val roomJid: String,
    val key: String? = null,
    val coinsInMessage: String? = null,
    val numberOfReplies: Int? = null,
    val isSystemMessage: String? = null,
    val isMediafile: String? = null,
    val locationPreview: String? = null,
    val mimetype: String? = null,
    val location: String? = null,
    val pending: Boolean? = null,
    val timestamp: Long? = null,
    val showInChannel: String? = null,
    val activeMessage: Boolean? = null,
    val isReply: Boolean? = null,
    val isDeleted: Boolean? = null,
    val mainMessage: String? = null,
    val reply: List<Reply>? = null,
    val reaction: Map<String, ReactionMessage>? = null,
    val fileName: String? = null,
    val translations: Map<String, MessageTranslation>? = null,
    val langSource: String? = null,
    val originalName: String? = null,
    val size: String? = null,
    val xmppId: String? = null,
    val xmppFrom: String? = null,
    val waveForm: String? = null // Waveform data for audio messages
)

/**
 * Reply is an alias for Message
 */
typealias Reply = Message

/**
 * Reaction message
 */
data class ReactionMessage(
    val emoji: List<String>,
    val data: Map<String, String>
)

/**
 * Last message in a room
 */
data class LastMessage(
    val body: String,
    val date: Date? = null,
    val emoji: String? = null,
    val locationPreview: String? = null,
    val filename: String? = null,
    val mimetype: String? = null,
    val originalName: String? = null
)
