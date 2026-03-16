package com.ethora.chat.core.networking

import com.ethora.chat.core.config.AppConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
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
                level = HttpLoggingInterceptor.Level.BODY
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
                
                // Don't add user token for login or sign-up endpoints
                val isAuthEndpoint = requestUrl.contains("users/login-with-email") || 
                                   requestUrl.contains("users/sign-up-with-email")

                // Respect per-request Authorization (needed for upload and other custom headers).
                if (explicitAuthHeader.isNullOrBlank()) {
                    builder.header("Authorization", appToken)
                    if (!isAuthEndpoint) {
                        userToken?.let {
                            builder.header("Authorization", it)
                        }
                    }
                }
                
                chain.proceed(builder.build())
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(authInterceptor)
                .addInterceptor(loggingInterceptor)
                .dns(DnsFallback.createDnsFromConfig())
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
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
}
