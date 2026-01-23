package com.ethora.chat.core.networking

import com.ethora.chat.core.config.AppConfig
import com.ethora.chat.core.models.User
import retrofit2.Response
import retrofit2.http.*

/**
 * Authentication API interface
 */
interface AuthAPI {
    @POST("users/login-with-email")
    suspend fun loginWithEmail(
        @Body body: LoginRequest
    ): Response<LoginResponse>

    @POST("users/login-with-jwt")
    suspend fun loginViaJWT(
        @Header("Authorization") token: String
    ): Response<LoginResponse>

    @POST("users/refresh-token")
    suspend fun refreshToken(
        @Body body: RefreshTokenRequest
    ): Response<RefreshTokenResponse>
}

/**
 * Login request
 */
data class LoginRequest(
    val email: String,
    val password: String
)

/**
 * Login response
 */
data class LoginResponse(
    val success: Boolean,
    val token: String,
    val refreshToken: String,
    val user: UserResponse,
    val app: AppResponse? = null,
    val isAllowedNewAppCreate: Boolean? = null
)

/**
 * User response
 */
data class UserResponse(
    val _id: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val username: String? = null,
    val tags: List<String>? = null,
    val profileImage: String? = null,
    val appId: String? = null,
    val xmppPassword: String? = null,
    val xmppUsername: String? = null,
    val roles: List<String>? = null,
    val isProfileOpen: Boolean? = null,
    val isAssetsOpen: Boolean? = null,
    val isAgreeWithTerms: Boolean? = null,
    val homeScreen: String? = null,
    val registrationChannelType: String? = null,
    val updatedAt: String? = null,
    val authMethod: String? = null,
    val description: String? = null,
    val defaultWallet: WalletResponse? = null
) {
    fun toUser(): User {
        return User(
            id = _id,
            firstName = firstName,
            lastName = lastName,
            email = email,
            username = username,
            tags = tags,
            profileImage = profileImage,
            appId = appId,
            xmppPassword = xmppPassword,
            xmppUsername = xmppUsername,
            roles = roles,
            isProfileOpen = isProfileOpen,
            isAssetsOpen = isAssetsOpen,
            isAgreeWithTerms = isAgreeWithTerms,
            homeScreen = homeScreen,
            registrationChannelType = registrationChannelType,
            updatedAt = updatedAt,
            authMethod = authMethod,
            description = description,
            walletAddress = defaultWallet?.walletAddress
        )
    }
}

/**
 * Wallet response
 */
data class WalletResponse(
    val walletAddress: String
)

/**
 * App response
 */
data class AppResponse(
    val isUserDataEncrypted: Boolean? = null
)

/**
 * Refresh token request
 */
data class RefreshTokenRequest(
    val refreshToken: String
)

/**
 * Refresh token response
 */
data class RefreshTokenResponse(
    val token: String,
    val refreshToken: String? = null
)

/**
 * Auth API helper functions
 */
object AuthAPIHelper {
    /**
     * Login with email and password
     */
    suspend fun loginWithEmail(
        email: String,
        password: String,
        baseUrl: String = AppConfig.defaultBaseURL
    ): LoginResponse {
        val api = ApiClient.createService<AuthAPI>(baseUrl)
        val response = api.loginWithEmail(LoginRequest(email, password))
        if (response.isSuccessful) {
            return response.body()!!
        } else {
            throw Exception("Login failed: ${response.code()} ${response.message()}")
        }
    }

    /**
     * Refresh token
     */
    suspend fun refreshToken(
        refreshToken: String,
        baseUrl: String = AppConfig.defaultBaseURL
    ): RefreshTokenResponse {
        val api = ApiClient.createService<AuthAPI>(baseUrl)
        val response = api.refreshToken(RefreshTokenRequest(refreshToken))
        if (response.isSuccessful) {
            return response.body()!!
        } else {
            throw Exception("Token refresh failed: ${response.code()} ${response.message()}")
        }
    }

    /**
     * Login with JWT token
     */
    suspend fun loginViaJWT(
        token: String,
        baseUrl: String = AppConfig.defaultBaseURL
    ): LoginResponse? {
        return try {
            val api = ApiClient.createService<AuthAPI>(baseUrl)
            val response = api.loginViaJWT(token)
            if (response.isSuccessful && response.body() != null) {
                response.body()
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("AuthAPIHelper", "JWT login failed", e)
            null
        }
    }
}
