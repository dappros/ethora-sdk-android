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
    /** True when an optimistic send did not reach the server within the
     *  pending-timeout window or hit a network/XMPP failure. Used by the UI
     *  to render the persistent "Sending failed. Tap to retry or delete."
     *  state regardless of current connection status. */
    val sendFailed: Boolean? = null,
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
    val waveForm: String? = null, // Waveform data for audio messages
    /** Server-assigned XEP-0359 stanza-id (the value carried inside the
     *  `<stanza-id>` element of the echo / archive). This is what Ethora's
     *  server uses to look up messages for `<replace>` and `<delete>`
     *  stanzas, NOT the original `<message id>` attribute we send with the
     *  send stanza. Populated by `parseAndHandleRealtimeMessage` and
     *  `parseMAMResult`; preserved through reconcile via `.copy()` because
     *  it's not in any override list. Not persisted to the Room DB — after
     *  app restart, messages come back from MAM with `id = archive-id`
     *  directly, so this field is only needed for the in-session window. */
    val archiveId: String? = null
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
