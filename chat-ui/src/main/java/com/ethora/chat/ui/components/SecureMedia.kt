package com.ethora.chat.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import com.ethora.chat.core.networking.SecureFileUrl
import com.ethora.chat.core.store.UserStore
import java.io.File

/**
 * The media URL to load now: secure-files URLs get the viewer's `ft` token,
 * everything else (and local file:// URIs) is returned unchanged. Recomposes
 * when the token is refreshed, so images reload with the new token.
 */
@Composable
internal fun rememberSecureFileUrl(url: String?): String? {
    val token by UserStore.fileToken.collectAsState()
    return remember(url, token) {
        url?.takeIf { it.isNotBlank() }?.let { SecureFileUrl.appendFileToken(it, token) }
    }
}

/** Coil model for a URL: local paths and file:// URIs become a File, the rest stay strings. */
internal fun coilModelFor(url: String?): Any? {
    if (url.isNullOrBlank()) return null
    return when {
        url.startsWith("file://") -> File(url.removePrefix("file://"))
        url.startsWith("/") -> File(url)
        else -> url
    }
}
