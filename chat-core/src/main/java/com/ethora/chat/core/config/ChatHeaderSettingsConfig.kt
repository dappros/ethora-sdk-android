package com.ethora.chat.core.config

/**
 * Chat header settings configuration
 */
data class ChatHeaderSettingsConfig(
    val hide: Boolean? = null,
    val disableCreate: Boolean? = null,
    val disableMenu: Boolean? = null,
    val hideSearch: Boolean? = null,
    /** Per-room header title overrides keyed by room JID (supports bare or full JID). */
    val roomTitleOverrides: Map<String, String>? = null,
    /** When true, hides the 3-dot chat info button in chat header. */
    val chatInfoButtonDisabled: Boolean? = null,
    /** When true, hides the back button in chat header. */
    val backButtonDisabled: Boolean? = null
)
