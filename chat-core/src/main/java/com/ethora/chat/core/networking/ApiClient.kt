package com.ethora.chat.core.networking

import com.ethora.chat.core.config.AppConfig
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.LogStore
import com.ethora.chat.core.store.UserStore
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * API client wrapper with Retrofit
 */
object ApiClient {
    private var retrofit: Retrofit? = null
    private var appToken: String = AppConfig.defaultAppToken
    private var userToken: String? = null
    internal var storedBaseUrl: String = AppConfig.defaultBaseURL

    // Single-flight refresh guard (OkHttp Authenticator can be called concurrently).
    private val refreshLock = Any()

    /** Public getter for use in inline function defaults (avoids internal visibility in inline) */
    fun getStoredBaseUrl(): String = storedBaseUrl

    /**
     * Set app token
     */
    fun setAppToken(token: String) {
        appToken = token
        retrofit = null // Force recreation
    }

    /**
     * Set user token
     */
    fun setUserToken(token: String?) {
        userToken = token
    }

    /**
     * Set base URL and app token
     */
    fun setBaseUrl(baseUrl: String, appToken: String? = null) {
        storedBaseUrl = baseUrl
        appToken?.let { setAppToken(it) }
        retrofit = null
    }

    /**
     * Get Retrofit instance
     */
    fun getRetrofit(baseUrl: String = getStoredBaseUrl()): Retrofit {
        // Ensure baseUrl ends with a trailing slash (required by Retrofit)
        val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        
        val currentBaseUrl = retrofit?.baseUrl()?.toString()
        if (retrofit == null || currentBaseUrl != normalizedBaseUrl) {
            android.util.Log.d("ApiClient", "🚀 Initializing Retrofit for $normalizedBaseUrl (old: $currentBaseUrl)")
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.NONE
            }

            fun isAuthEndpoint(url: String): Boolean {
                // Auth/bootstrap endpoints must not trigger refresh loops.
                return url.contains("users/login-with-email") ||
                    url.contains("users/sign-up-with-email") ||
                    url.contains("users/client") ||
                    url.contains("users/refresh-token")
            }

            val authInterceptor = Interceptor { chain ->
                val originalRequest = chain.request()
                val requestUrl = originalRequest.url.toString()
                val explicitAuthHeader = originalRequest.header("Authorization")
                val builder = originalRequest.newBuilder()
                val bodyContentType = originalRequest.body?.contentType()
                val isMultipart = bodyContentType?.type == "multipart"
                if (originalRequest.header("Content-Type").isNullOrBlank() && !isMultipart) {
                    builder.header("Content-Type", "application/json")
                }
                if (originalRequest.header("Accept").isNullOrBlank()) {
                    builder.header("Accept", "application/json")
                }
                
                // Respect per-request Authorization (needed for upload and other custom headers).
                if (explicitAuthHeader.isNullOrBlank()) {
                    // Prefer the latest token from UserStore if available.
                    val latestUserToken = UserStore.token.value ?: userToken
                    if (!isAuthEndpoint(requestUrl) && !latestUserToken.isNullOrBlank()) {
                        builder.header("Authorization", latestUserToken)
                    } else {
                        builder.header("Authorization", appToken)
                    }
                }
                
                chain.proceed(builder.build())
            }

            val tokenRefreshAuthenticator = Authenticator { _: Route?, response: Response ->
                // Only react to 401 for non-auth endpoints.
                val url = response.request.url.toString()
                if (response.code != 401 || isAuthEndpoint(url)) return@Authenticator null

                // Prevent infinite loops: if we've already attempted with a new token and still got 401.
                if (responseCount(response) >= 2) return@Authenticator null

                val currentAccessToken = UserStore.token.value ?: userToken
                val currentRefreshToken = UserStore.refreshToken.value
                if (currentAccessToken.isNullOrBlank() || currentRefreshToken.isNullOrBlank()) return@Authenticator null

                synchronized(refreshLock) {
                    LogStore.error("API", "🔄 401 received, attempting refresh for $url")
                    // If another thread already refreshed and UserStore.token changed, just retry with it.
                    val latestAfterLock = UserStore.token.value ?: userToken
                    val reqAuth = response.request.header("Authorization")
                    if (!latestAfterLock.isNullOrBlank() && !reqAuth.isNullOrBlank() && reqAuth != latestAfterLock) {
                        LogStore.error("API", "🔁 Using already-refreshed token, retrying $url")
                        return@synchronized response.request.newBuilder()
                            .header("Authorization", latestAfterLock)
                            .build()
                    }

                    val refreshed = runBlocking {
                        try {
                            AuthAPIHelper.refreshToken(
                                refreshToken = currentRefreshToken,
                                baseUrl = ChatStore.getEffectiveBaseUrl()
                            )
                        } catch (_: Exception) {
                            null
                        }
                    } ?: return@synchronized null

                    // Persist in UserStore + update ApiClient cache.
                    UserStore.updateTokens(refreshed.token, refreshed.refreshToken)
                    setUserToken(refreshed.token)

                    LogStore.error("API", "✅ Refreshed token, retrying $url")
                    response.request.newBuilder()
                        .header("Authorization", refreshed.token)
                        .build()
                }
            }

            val apiDebugInterceptor = Interceptor { chain ->
                val request = chain.request()
                val method = request.method
                val url = request.url.toString()
                val requestBody = request.body
                var requestToSend = request
                val requestBodyPreview =
                    if (requestBody != null && requestBody.contentType()?.type != "multipart") {
                        try {
                            // Logging must not consume a one-shot request body.
                            // We read it into bytes, recreate the RequestBody, and only then proceed.
                            val buffer = Buffer()
                            requestBody.writeTo(buffer)
                            val bytes = buffer.readByteArray()
                            val mediaType = requestBody.contentType()
                            requestToSend = request.newBuilder()
                                .method(method, okhttp3.RequestBody.create(mediaType, bytes))
                                .build()
                            bytes.toString(Charsets.UTF_8).take(1500)
                        } catch (_: Exception) {
                            "<unavailable>"
                        }
                    } else ""

                val requestLog = buildString {
                    append("➡️ $method $url")
                    if (requestBodyPreview.isNotBlank()) {
                        append("\n\n$requestBodyPreview")
                    }
                }
                LogStore.send("API", requestLog)

                val startedAt = System.nanoTime()
                try {
                    val response = chain.proceed(requestToSend)
                    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                    val responseBodyPreview = try {
                        response.peekBody(1024 * 1024).string().take(4000)
                    } catch (_: Exception) {
                        "<unavailable>"
                    }
                    val responseHeadersPreview = buildString {
                        val wwwAuth = response.header("WWW-Authenticate")
                        val contentType = response.header("Content-Type")
                        if (!wwwAuth.isNullOrBlank()) append("WWW-Authenticate: $wwwAuth\n")
                        if (!contentType.isNullOrBlank()) append("Content-Type: $contentType\n")
                    }.trim()
                    val responseLog = buildString {
                        append("⬅️ ${response.code} $method $url (${elapsedMs}ms)")
                        if (responseHeadersPreview.isNotBlank()) append("\n$responseHeadersPreview")
                        append("\n\n")
                        append(if (responseBodyPreview.isNotBlank()) responseBodyPreview else "<empty body>")
                    }
                    if (response.isSuccessful) {
                        LogStore.receive("API", responseLog)
                    } else {
                        // Keep all error responses (including 401) highly visible in the in-app logs.
                        LogStore.error("API", responseLog)
                    }
                    response
                } catch (e: Exception) {
                    LogStore.error("API", "❌ $method $url failed: ${e.message}")
                    throw e
                }
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(authInterceptor)
                .authenticator(tokenRefreshAuthenticator)
                .addInterceptor(apiDebugInterceptor)
                .addInterceptor(loggingInterceptor)
                .dns(DnsFallback.createDnsFromConfig())
                .connectTimeout(45, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .build()

            retrofit = Retrofit.Builder()
                .baseUrl(normalizedBaseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
        return retrofit!!
    }

    /**
     * Create API service
     */
    fun <T> createService(serviceClass: Class<T>, baseUrl: String = getStoredBaseUrl()): T {
        return getRetrofit(baseUrl).create(serviceClass)
    }
    
    /**
     * Create API service (inline version for convenience)
     */
    inline fun <reified T> createService(baseUrl: String = getStoredBaseUrl()): T {
        return getRetrofit(baseUrl).create(T::class.java)
    }

    private fun responseCount(response: Response): Int {
        var res: Response? = response
        var count = 1
        while (res?.priorResponse != null) {
            count++
            res = res.priorResponse
        }
        return count
    }
}
