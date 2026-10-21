package com.ethora.chat.core.networking

import com.ethora.chat.core.config.AppConfig
import com.ethora.chat.core.models.User
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.UserStore
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Authentication API interface
 */
interface AuthAPI {
    @POST("users/login-with-email")
    suspend fun loginWithEmail(
        @Header("x-app-id") appId: String,
        @Body body: LoginRequest
    ): Response<LoginResponse>

    /** POST users/client with x-custom-token */
    @POST("users/client")
    suspend fun loginViaJWT(
        @Header("x-app-id") appId: String,
        @Header("x-custom-token") token: String,
        @Body body: ClientTypeRequest
    ): Response<LoginResponse>

    /**
     * Refresh-token rotation, as in web `authRefresh.ts` and RN: the presented
     * refresh token travels in the Authorization header, the body is empty.
     */
    @POST("users/login/refresh")
    suspend fun loginRefresh(
        @Header("Authorization") authorization: String
    ): Response<RefreshTokenResponse>

    /** Legacy endpoint, kept for source compatibility. Use [loginRefresh]. */
    @POST("users/refresh-token")
    suspend fun refreshToken(
        @Body body: RefreshTokenRequest
    ): Response<RefreshTokenResponse>
    
    @Multipart
    @POST("files/")
    suspend fun uploadFile(
        @Header("Authorization") token: String,
        @Header("Accept") accept: String = "*/*",
        @Part file: MultipartBody.Part
    ): Response<JsonObject>

    /**
     * Secure upload (web `/v2/files/secure`). The absolute [url] is passed in
     * because the v2 path does not live under the SDK's /v1 base URL.
     */
    @Multipart
    @POST
    suspend fun uploadFileSecure(
        @Url url: String,
        @Header("Authorization") token: String,
        @Header("Accept") accept: String = "*/*",
        @Part file: MultipartBody.Part,
        @Part("chatName") chatName: RequestBody
    ): Response<JsonObject>
}

/**
 * Some backend deployments expect a `type` field (e.g. "client") in the
 * login request to distinguish the caller.
 */
data class ClientTypeRequest(
    val type: String = "client"
)

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
    val isAllowedNewAppCreate: Boolean? = null,
    /** Per-viewer token for secure media URLs (web top-level `fileToken`). */
    val fileToken: String? = null
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
    val defaultWallet: WalletResponse? = null,
    val appLanguage: String? = null,
    val chatLanguage: String? = null
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
            walletAddress = defaultWallet?.walletAddress,
            appLanguage = appLanguage,
            chatLanguage = chatLanguage
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
    val refreshToken: String? = null,
    val fileToken: String? = null,
    val xmppPassword: String? = null
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
        baseUrl: String = ChatStore.getEffectiveBaseUrl()
    ): LoginResponse {
        val appId = ChatStore.getEffectiveAppId()
        android.util.Log.d("AuthAPIHelper", "🌐 loginWithEmail: baseUrl=$baseUrl appId=$appId")
        val api = ApiClient.createService<AuthAPI>(baseUrl)
        try {
            val response = api.loginWithEmail(appId, LoginRequest(email, password))
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
        baseUrl: String = ChatStore.getEffectiveBaseUrl()
    ): RefreshTokenResponse {
        val api = ApiClient.createService<AuthAPI>(baseUrl)
        val response = api.loginRefresh(refreshToken)
        if (response.isSuccessful) {
            return response.body()!!
        } else {
            throw Exception("Token refresh failed: ${response.code()} ${response.message()}")
        }
    }

    /** Login with JWT via POST users/client + x-custom-token */
    suspend fun loginViaJWT(
        token: String,
        baseUrl: String = ChatStore.getEffectiveBaseUrl()
    ): LoginResponse? {
        return try {
            val appId = ChatStore.getEffectiveAppId()
            val api = ApiClient.createService<AuthAPI>(baseUrl)
            val response = api.loginViaJWT(appId, token, ClientTypeRequest())
            if (response.isSuccessful && response.body() != null) response.body() else null
        } catch (e: Exception) {
            android.util.Log.e("AuthAPIHelper", "JWT login failed", e)
            null
        }
    }
    
    /** Set after the secure route rejects a request for a reason other than auth or size. */
    @Volatile
    private var secureUploadUnavailable = false

    private sealed class UploadAttempt {
        class Done(val result: FileUploadResult?) : UploadAttempt()
        class Rejected(val code: Int) : UploadAttempt()
        object Failed : UploadAttempt()
    }

    /**
     * Upload one file. With [chatName] set, tries the secure route
     * (`/v2/files/secure`, web parity) and falls back to `files/` when that route
     * is unavailable on the backend. 401 is retried once with a refreshed token,
     * 413 is final, and a transport failure is not retried on the legacy route.
     */
    suspend fun uploadFile(
        file: java.io.File,
        mimeType: String,
        token: String,
        baseUrl: String = ChatStore.getEffectiveBaseUrl(),
        chatName: String? = null
    ): FileUploadResult? {
        if (!chatName.isNullOrBlank() && !secureUploadUnavailable) {
            val secureUrl = secureUploadUrl(baseUrl)
            val chatNameBody = chatName.toRequestBody("text/plain".toMediaType())
            when (val attempt = attemptUpload(file, mimeType, token, baseUrl) { api, authHeader, part ->
                api.uploadFileSecure(secureUrl, authHeader, "*/*", part, chatNameBody)
            }) {
                is UploadAttempt.Done -> return attempt.result
                is UploadAttempt.Failed -> return null
                is UploadAttempt.Rejected -> {
                    if (attempt.code == 401 || attempt.code == 413) return null
                    secureUploadUnavailable = true
                    android.util.Log.w(
                        "AuthAPIHelper",
                        "$SECURE_UPLOAD_PATH unavailable (${attempt.code}), using files/ for this session"
                    )
                }
            }
        }
        return when (val attempt = attemptUpload(file, mimeType, token, baseUrl) { api, authHeader, part ->
            api.uploadFile(authHeader, "*/*", part)
        }) {
            is UploadAttempt.Done -> attempt.result
            else -> null
        }
    }

    private const val SECURE_UPLOAD_PATH = "v2/files/secure"

    /** Absolute secure-upload URL: the SDK base URL without its /v1 or /v2 suffix. */
    internal fun secureUploadUrl(baseUrl: String): String {
        val root = baseUrl.trimEnd('/').replace(Regex("/v[12]$", RegexOption.IGNORE_CASE), "")
        return "$root/$SECURE_UPLOAD_PATH"
    }

    private suspend fun attemptUpload(
        file: java.io.File,
        mimeType: String,
        token: String,
        baseUrl: String,
        send: suspend (AuthAPI, String, MultipartBody.Part) -> Response<JsonObject>
    ): UploadAttempt {
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

            fun buildAuthHeaders(rawToken: String): LinkedHashSet<String> {
                return linkedSetOf<String>().apply {
                    add(rawToken)
                    if (!rawToken.startsWith("Bearer ", ignoreCase = true)) {
                        add("Bearer $rawToken")
                    }
                }
            }

            var currentToken = token
            var hasRefreshedToken = false
            var lastCode = -1
            while (true) {
                var shouldRetryWithRefreshedToken = false
                for (authHeader in buildAuthHeaders(currentToken)) {
                    val response = send(api, authHeader, multipartBody)
                    if (response.isSuccessful) {
                        val body = response.body()
                        if (body == null) return UploadAttempt.Done(null)
                        val result = parseUploadResult(body)
                        if (result == null) {
                            android.util.Log.e("AuthAPIHelper", "File upload succeeded but no parsable result. body=$body")
                        }
                        return UploadAttempt.Done(result)
                    }

                    val code = response.code()
                    lastCode = code
                    val errorBody = response.errorBody()?.string()
                    android.util.Log.w(
                        "AuthAPIHelper",
                        "File upload failed with auth=${if (authHeader.startsWith("Bearer ")) "bearer" else "raw"}: $code ${response.message()} body=${errorBody ?: "<empty>"}"
                    )

                    if (code == 401 && !hasRefreshedToken) {
                        val refreshTokenValue = UserStore.refreshToken.value
                        if (!refreshTokenValue.isNullOrBlank()) {
                            try {
                                val refreshed = AuthRefresh.refreshAuthTokens()
                                currentToken = refreshed.token
                                hasRefreshedToken = true
                                shouldRetryWithRefreshedToken = true
                                android.util.Log.i("AuthAPIHelper", "Token refreshed after upload 401, retrying upload")
                                break
                            } catch (e: Exception) {
                                android.util.Log.e("AuthAPIHelper", "Token refresh after upload 401 failed", e)
                            }
                        }
                    }
                }

                if (shouldRetryWithRefreshedToken) {
                    continue
                }
                break
            }

            android.util.Log.e("AuthAPIHelper", "File upload failed for all authorization header variants (baseUrl=$baseUrl)")
            UploadAttempt.Rejected(lastCode)
        } catch (e: Exception) {
            android.util.Log.e("AuthAPIHelper", "File upload exception", e)
            UploadAttempt.Failed
        }
    }

    private fun parseUploadResult(root: JsonObject): FileUploadResult? {
        return try {
            fun JsonObject.safeObj(key: String): JsonObject? =
                get(key)?.takeIf { it.isJsonObject }?.asJsonObject
            fun JsonObject.safeArr(key: String): JsonArray? =
                get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            fun firstObjectFromArray(arr: JsonArray?): JsonObject? {
                if (arr == null || arr.size() == 0) return null
                return arr.asSequence().firstOrNull { it.isJsonObject }?.asJsonObject
            }
            val dataObj = root.safeObj("data")
            val candidate: JsonObject? =
                firstObjectFromArray(dataObj?.safeArr("results"))
                    ?: firstObjectFromArray(root.safeArr("results"))
                    ?: dataObj?.safeObj("result")
                    ?: root.safeObj("result")
                    ?: dataObj?.safeObj("file")
                    ?: root.safeObj("file")
                    ?: dataObj?.takeIf { it.has("filename") || it.has("location") }
                    ?: root.takeIf { it.has("filename") || it.has("location") }

            candidate?.toFileUploadResult()
        } catch (e: Exception) {
            android.util.Log.e("AuthAPIHelper", "parseUploadResult failed", e)
            null
        }
    }

    private fun JsonObject.toFileUploadResult(): FileUploadResult? {
        return try {
            fun string(name: String): String? =
                get(name)?.takeIf { !it.isJsonNull }?.asString
            fun bool(name: String): Boolean? =
                get(name)?.takeIf { !it.isJsonNull }?.asBoolean

            val filename = string("filename") ?: string("fileName") ?: return null
            val location = string("location") ?: return null
            val mimetype = string("mimetype") ?: string("mimeType") ?: "application/octet-stream"
            val originalName = string("originalName") ?: string("originalname") ?: filename
            val size = string("size") ?: "0"
            val createdAt = string("createdAt") ?: string("created_at") ?: System.currentTimeMillis().toString()

            FileUploadResult(
            filename = filename,
            location = location,
            locationPreview = string("locationPreview"),
            mimetype = mimetype,
            originalName = originalName,
            size = size,
            createdAt = createdAt,
            expiresAt = string("expiresAt"),
            isVisible = bool("isVisible"),
            ownerKey = string("ownerKey"),
            updatedAt = string("updatedAt"),
            userId = string("userId"),
            duration = string("duration"),
            waveForm = string("waveForm"),
            attachmentId = string("attachmentId") ?: string("_id") ?: string("id")
            )
        } catch (e: Exception) {
            android.util.Log.e("AuthAPIHelper", "toFileUploadResult failed", e)
            null
        }
    }
}
