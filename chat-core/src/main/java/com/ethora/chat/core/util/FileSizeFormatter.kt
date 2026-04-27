package com.ethora.chat.core.util

import java.util.Locale

object FileSizeFormatter {
    fun format(sizeInBytes: String?): String {
        val bytes = sizeInBytes?.toLongOrNull() ?: return "Unknown size"
        return format(bytes)
    }

    fun format(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.2f KB", bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
