package com.ethora.chat.core.call.p2p

/** User-facing texts of the p2p call (web `describeMediaError` / `remoteEndNotice`). Pure, so JVM-testable. */

/** What to show when the camera or microphone could not be opened. */
fun describeMediaError(error: Throwable?): String {
    val text = error?.message.orEmpty().lowercase()
    return when {
        error is SecurityException || text.contains("permission") || text.contains("denied") ->
            "Camera or microphone permission denied"
        text.contains("no camera") || text.contains("not found") ->
            "No camera or microphone found on this device"
        text.contains("in use") || text.contains("busy") ->
            "Your camera or microphone is already in use by another app"
        else -> "Could not access your camera or microphone"
    }
}

/**
 * What to show when the PEER ended the call for a reason that is a failure rather than a
 * normal hang up / decline. Null means "just close the call".
 */
fun remoteEndNotice(reason: String): String? = when (reason) {
    "media-error" -> "The other person could not access their camera or microphone"
    "connect-timeout", "connection-failed", "negotiation-error", "setup-error" -> "The call could not be connected"
    else -> null
}
