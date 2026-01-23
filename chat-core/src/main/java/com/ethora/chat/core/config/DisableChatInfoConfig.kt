package com.ethora.chat.core.config

/**
 * Disable chat info configuration
 */
data class DisableChatInfoConfig(
    val disableHeader: Boolean? = null,
    val disableDescription: Boolean? = null,
    val disableType: Boolean? = null,
    val disableMembers: Boolean? = null,
    val hideMembers: Boolean? = null,
    val disableChatHeaderMenu: Boolean? = null
)
