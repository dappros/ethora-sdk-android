package com.ethora.chat.core.models

/**
 * User model
 */
data class User(
    val id: String,
    val name: String? = null,
    val userJID: String? = null,
    val token: String? = null,
    val refreshToken: String? = null,
    val walletAddress: String? = null,
    val description: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val profileImage: String? = null,
    val username: String? = null,
    val xmppPassword: String? = null,
    val xmppUsername: String? = null,
    val langSource: String? = null,
    val homeScreen: String? = null,
    val registrationChannelType: String? = null,
    val updatedAt: String? = null,
    val authMethod: String? = null,
    val roles: List<String>? = null,
    val tags: List<String>? = null,
    val isProfileOpen: Boolean? = null,
    val isAssetsOpen: Boolean? = null,
    val isAgreeWithTerms: Boolean? = null,
    val isSuperAdmin: Boolean? = null,
    val appId: String? = null
) {
    val fullName: String
        get() = when {
            !firstName.isNullOrBlank() && !lastName.isNullOrBlank() -> "$firstName $lastName"
            !name.isNullOrBlank() -> name
            !username.isNullOrBlank() -> username
            else -> id
        }
}
