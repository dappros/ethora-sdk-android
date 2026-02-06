package com.ethora.chat.core.networking

import com.ethora.chat.core.config.AppConfig
import com.ethora.chat.core.models.User
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
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
    
    @Multipart
    @POST("files/")
    suspend fun uploadFile(
        @Header("Authorization") token: String,
        @Part file: MultipartBody.Part
    ): Response<FileUploadResponse>
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
 * File upload response
 */
data class FileUploadResponse(
    val data: FileUploadData?
)

data class FileUploadData(
    val results: List<FileUploadResult>?
)

data class FileUploadResult(
    val filename: String,
    val location: String,
    val locationPreview: String? = null,
    val mimetype: String,
    val originalName: String,
    val size: String,
    val createdAt: String,
    val expiresAt: String? = null,
    val isVisible: Boolean? = null,
    val ownerKey: String? = null,
    val updatedAt: String? = null,
    val userId: String? = null,
    val duration: String? = null,
    val waveForm: String? = null,
    val attachmentId: String? = null
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
        android.util.Log.d("AuthAPIHelper", "🌐 loginWithEmail: $baseUrl/users/login-with-email")
        val api = ApiClient.createService<AuthAPI>(baseUrl)
        try {
            val response = api.loginWithEmail(LoginRequest(email, password))
            if (response.isSuccessful) {
                val body = response.body()!!
                android.util.Log.d("AuthAPIHelper", "✅ loginWithEmail success: token=${body.token.take(10)}...")
                return body
            } else {
                val errorBody = response.errorBody()?.string()
                android.util.Log.e("AuthAPIHelper", "❌ loginWithEmail failed: ${response.code()} ${response.message()}")
                android.util.Log.e("AuthAPIHelper", "   Error body: $errorBody")
                throw Exception("Login failed: ${response.code()} ${response.message()}")
            }
        } catch (e: Exception) {
            android.util.Log.e("AuthAPIHelper", "❌ loginWithEmail exception: ${e.message}", e)
            throw e
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
    
    /**
     * Upload file
     */
    suspend fun uploadFile(
        file: java.io.File,
        mimeType: String,
        token: String,
        baseUrl: String = AppConfig.defaultBaseURL
    ): FileUploadResult? {
        return try {
            val api = ApiClient.createService<AuthAPI>(baseUrl)
            
            val requestFile = RequestBody.create(
                mimeType.toMediaType(),
                file
            )
            val multipartBody = MultipartBody.Part.createFormData(
                "files",
                file.name,
                requestFile
            )
            
            val response = api.uploadFile("Bearer $token", multipartBody)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val result = body.data?.results?.firstOrNull()
                if (result == null) {
                   android.util.Log.e("AuthAPIHelper", "File upload succeeded but no results found or data is null.")
                }
                result
            } else {
                android.util.Log.e("AuthAPIHelper", "File upload failed: ${response.code()} ${response.message()}")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("AuthAPIHelper", "File upload exception", e)
            null
        }
    }
}
