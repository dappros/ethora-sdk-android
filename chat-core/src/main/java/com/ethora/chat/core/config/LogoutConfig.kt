package com.ethora.chat.core.config

/**
 * Logout behaviour for the account settings screen. Mirrors the `logout` block of the React
 * Native `ChatConfig`. When `ChatConfig.logout` is unset, logout is shown with these defaults.
 *
 * @param enabled Shows the logout action in the account settings screen.
 * @param label Text of the logout action. Default "Log out".
 * @param confirm Ask before logging out. Default true.
 * @param confirmTitle, confirmMessage, confirmButton, cancelButton Texts of the confirmation dialog.
 * @param onBeforeLogout Runs before the session is torn down. Return false to cancel the logout.
 * @param onAfterLogout Runs once the session is torn down.
 */
data class LogoutConfig(
    val enabled: Boolean,
    val label: String? = null,
    val confirm: Boolean = true,
    val confirmTitle: String? = null,
    val confirmMessage: String? = null,
    val confirmButton: String? = null,
    val cancelButton: String? = null,
    val onBeforeLogout: (suspend () -> Boolean)? = null,
    val onAfterLogout: (suspend () -> Unit)? = null
)
