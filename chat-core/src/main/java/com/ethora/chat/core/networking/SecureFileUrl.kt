package com.ethora.chat.core.networking

import com.ethora.chat.core.store.UserStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Secure media URLs. Files uploaded through /v2/files/secure are served from
 * the secure-files.* host, and every download is gated by chat membership:
 * the server needs `?ft=<fileToken>` to identify the requester. The token is
 * personal to the viewer, so it is appended at render or download time and is
 * never written into the XMPP stanza.
 *
 * Mirrors web helpers/secureFileUrl.ts (isSecureFileUrl, appendFileToken,
 * withFileToken).
 */
object SecureFileUrl {
    private const val SECURE_HOST_PREFIX = "secure-files."
    const val FILE_TOKEN_PARAM = "ft"

    fun isSecureFileUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.toHttpUrlOrNull()?.host?.startsWith(SECURE_HOST_PREFIX) == true
    }

    /** Same URL with `ft` set to [fileToken], for secure URLs only. Other URLs pass through unchanged. */
    fun appendFileToken(url: String?, fileToken: String?): String {
        if (url.isNullOrBlank()) return ""
        if (fileToken.isNullOrBlank() || !isSecureFileUrl(url)) return url
        val parsed = url.toHttpUrlOrNull() ?: return url
        return parsed.newBuilder()
            .setQueryParameter(FILE_TOKEN_PARAM, fileToken)
            .build()
            .toString()
    }

    /** Imperative variant for click-time work (download, open): reads the current token. */
    fun withFileToken(url: String?): String = appendFileToken(url, UserStore.fileToken.value)
}
