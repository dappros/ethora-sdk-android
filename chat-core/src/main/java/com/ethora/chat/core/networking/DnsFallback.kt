package com.ethora.chat.core.networking

import com.ethora.chat.core.store.ChatStore

/**
 * DNS fallback when system DNS fails (e.g. on emulator).
 * Uses config.dnsFallbackOverrides (hostname -> IP) and hardcoded ethoradev IPs.
 */
object DnsFallback {
    private const val ETHORADEV_IP = "3.139.111.222"

    fun createDns(overrides: Map<String, String>? = null): okhttp3.Dns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            return try {
                okhttp3.Dns.SYSTEM.lookup(hostname)
            } catch (e: java.net.UnknownHostException) {
                android.util.Log.w("DnsFallback", "DNS lookup failed for $hostname, using fallback (overrides=${overrides?.size ?: 0})")
                val ip = overrides?.get(hostname) ?: when (hostname) {
                    "api.chat.ethora.com", "xmpp.chat.ethora.com", "conference.xmpp.chat.ethora.com" -> ETHORADEV_IP
                    else -> {
                        android.util.Log.e("DnsFallback", "No fallback for hostname=$hostname; overrides keys=${overrides?.keys?.joinToString() ?: "null"}")
                        throw e
                    }
                }
                android.util.Log.d("DnsFallback", "Resolved $hostname -> $ip")
                listOf(java.net.InetAddress.getByName(ip))
            }
        }
    }

    /** Create Dns using ChatStore config overrides (for use in ApiClient / XMPP). */
    fun createDnsFromConfig(): okhttp3.Dns = createDns(ChatStore.getConfig()?.dnsFallbackOverrides)
}
