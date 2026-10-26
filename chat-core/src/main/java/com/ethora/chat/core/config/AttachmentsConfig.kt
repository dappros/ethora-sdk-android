package com.ethora.chat.core.config

import com.ethora.chat.core.models.MessageAttachments

/**
 * Composer limits for one media message (web `attachments` config key).
 *
 * @property maxFiles files one message may carry; clamped to 1..10.
 * @property maxFileSizeMb per-file ceiling. Null keeps the SDK default of 50 MB.
 */
data class AttachmentsConfig(
    val maxFiles: Int = MessageAttachments.MAX_PER_MESSAGE,
    val maxFileSizeMb: Double? = null
) {
    val effectiveMaxFiles: Int
        get() = maxFiles.coerceIn(1, MessageAttachments.MAX_PER_MESSAGE)

    val effectiveMaxFileBytes: Long
        get() = ((maxFileSizeMb ?: DEFAULT_MAX_FILE_MB) * BYTES_PER_MB).toLong()

    companion object {
        const val DEFAULT_MAX_FILE_MB = 50.0
        private const val BYTES_PER_MB = 1024.0 * 1024.0
    }
}
