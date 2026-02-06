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
        retrofit = null // Force recreation
    }

    /**
     * Get Retrofit instance
     */
    fun getRetrofit(baseUrl: String = storedBaseUrl): Retrofit {
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
                val newRequest = originalRequest.newBuilder()
                    .header("Authorization", appToken)
                    .apply {
                        userToken?.let {
                            header("Authorization", it)
                        }
                    }
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(newRequest)
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(authInterceptor)
                .addInterceptor(loggingInterceptor)
                .dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String): List<java.net.InetAddress> {
                        android.util.Log.d("ApiClient", "🔍 DNS lookup for: $hostname")
                        return try {
                            val addresses = okhttp3.Dns.SYSTEM.lookup(hostname)
                            android.util.Log.d("ApiClient", "   ✅ Resolved $hostname to ${addresses.map { it.hostAddress }}")
                            addresses
                        } catch (e: java.net.UnknownHostException) {
                            android.util.Log.w("ApiClient", "   ⚠️ DNS lookup failed for $hostname, using fallback")
                            when (hostname) {
                                "api.ethoradev.com" -> listOf(java.net.InetAddress.getByName("3.139.111.222")).also {
                                    android.util.Log.d("ApiClient", "   ✅ Fallback resolve for $hostname: 3.139.111.222")
                                }
                                "xmpp.ethoradev.com" -> listOf(java.net.InetAddress.getByName("3.139.111.222")).also {
                                    android.util.Log.d("ApiClient", "   ✅ Fallback resolve for $hostname: 3.139.111.222")
                                }
                                else -> throw e
                            }
                        }
                    }
                })
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
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
    fun <T> createService(serviceClass: Class<T>, baseUrl: String = storedBaseUrl): T {
        return getRetrofit(baseUrl).create(serviceClass)
    }
    
    /**
     * Create API service (inline version for convenience)
     */
    inline fun <reified T> createService(baseUrl: String = AppConfig.defaultBaseURL): T {
        return getRetrofit(baseUrl).create(T::class.java)
    }
}
