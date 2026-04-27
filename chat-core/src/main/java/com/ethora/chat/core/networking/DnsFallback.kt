package com.ethora.chat.core.networking

import com.ethora.chat.core.store.ChatStore

/**
 * DNS fallback when system DNS fails (e.g. on emulator).
 * Uses only config.dnsFallbackOverrides (hostname -> IP).
 */
object DnsFallback {
    fun resolveOverride(hostname: String, overrides: Map<String, String>? = null): String? {
        return overrides?.get(hostname)?.takeIf { it.isNotBlank() }
    }

    fun createDns(overrides: Map<String, String>? = null): okhttp3.Dns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            return try {
                okhttp3.Dns.SYSTEM.lookup(hostname)
            } catch (e: java.net.UnknownHostException) {
                val ip = resolveOverride(hostname, overrides)
                if (ip == null) {
                    android.util.Log.e("DnsFallback", "DNS lookup failed for $hostname and no override was configured")
                    throw e
                }
                android.util.Log.d("DnsFallback", "Resolved $hostname -> $ip")
                listOf(java.net.InetAddress.getByName(ip))
            }
        }
    }

    /** Create Dns using ChatStore config overrides (for use in ApiClient / XMPP). */
    fun createDnsFromConfig(): okhttp3.Dns = createDns(ChatStore.getConfig()?.dnsFallbackOverrides)
}
