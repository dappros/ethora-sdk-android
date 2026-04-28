package com.ethora.chat.core.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ethora.chat.core.networking.FileUploadResult
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import kotlin.math.min

enum class PendingMediaSendStatus {
    QUEUED,
    UPLOADING,
    READY_TO_SEND,
    FAILED_WAITING_RETRY,
    PERMANENTLY_FAILED,
    SENT
}

data class PendingMediaUploadPayload(
    val filename: String,
    val location: String,
    val locationPreview: String? = null,
    val mimetype: String,
    val originalName: String,
    val size: String,
    val createdAt: String,
    val expiresAt: String? = null,
    val isVisible: Boolean? = null,
    val ownerKey: String? = null,
    val updatedAt: String? = null,
    val userId: String? = null,
    val duration: String? = null,
    val waveForm: String? = null,
    val attachmentId: String? = null
) {
    fun toFileUploadResult(): FileUploadResult = FileUploadResult(
        filename = filename,
        location = location,
        locationPreview = locationPreview,
        mimetype = mimetype,
        originalName = originalName,
        size = size,
        createdAt = createdAt,
        expiresAt = expiresAt,
        isVisible = isVisible,
        ownerKey = ownerKey,
        updatedAt = updatedAt,
        userId = userId,
        duration = duration,
        waveForm = waveForm,
        attachmentId = attachmentId
    )

    companion object {
        fun from(result: FileUploadResult): PendingMediaUploadPayload = PendingMediaUploadPayload(
            filename = result.filename,
            location = result.location,
            locationPreview = result.locationPreview,
            mimetype = result.mimetype,
            originalName = result.originalName,
            size = result.size,
            createdAt = result.createdAt,
            expiresAt = result.expiresAt,
            isVisible = result.isVisible,
            ownerKey = result.ownerKey,
            updatedAt = result.updatedAt,
            userId = result.userId,
            duration = result.duration,
            waveForm = result.waveForm,
            attachmentId = result.attachmentId
        )
    }
}

data class PendingMediaSend(
    val id: String = "pending-media:${UUID.randomUUID()}",
    val roomJid: String,
    val messageId: String,
    val localFilePath: String,
    val fileName: String,
    val mimeType: String,
    val createdAt: Long = System.currentTimeMillis(),
    val attemptCount: Int = 0,
    val nextRetryAt: Long = 0L,
    val status: PendingMediaSendStatus = PendingMediaSendStatus.QUEUED,
    val uploaded: PendingMediaUploadPayload? = null
) {
    companion object {
        const val MAX_RETRY_ATTEMPTS = 3
    }

    /**
     * @param autoRetry honors `RetryConfig.autoRetry`. When `false`, the item
     *   goes straight to [PendingMediaSendStatus.PERMANENTLY_FAILED] so the UI
     *   stays in the failed state until the user explicitly retries.
     * @param maxAttempts honors `RetryConfig.maxAttempts`. Only consulted when
     *   `autoRetry == true`. Falls back to [MAX_RETRY_ATTEMPTS] for callers
     *   that don't pass an explicit value.
     */
    fun failedForRetry(
        now: Long = System.currentTimeMillis(),
        autoRetry: Boolean = true,
        maxAttempts: Int = MAX_RETRY_ATTEMPTS
    ): PendingMediaSend {
        val nextAttempt = attemptCount + 1
        if (!autoRetry || nextAttempt >= maxAttempts) {
            return copy(
                attemptCount = nextAttempt,
                nextRetryAt = 0L,
                status = PendingMediaSendStatus.PERMANENTLY_FAILED
            )
        }
        val retryDelayMs = min(60_000L, 2_000L * nextAttempt)
        return copy(
            attemptCount = nextAttempt,
            nextRetryAt = now + retryDelayMs,
            status = PendingMediaSendStatus.FAILED_WAITING_RETRY
        )
    }

    fun isValid(): Boolean {
        return id.isNotBlank() &&
            roomJid.isNotBlank() &&
            messageId.isNotBlank() &&
            localFilePath.isNotBlank() &&
            fileName.isNotBlank() &&
            mimeType.isNotBlank()
    }
}

object PendingMediaSendCodec {
    private val gson = Gson()

    fun encodeList(items: List<PendingMediaSend>): String = gson.toJson(items)

    fun decodeList(json: String?): List<PendingMediaSend> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val root = JsonParser.parseString(json)
            if (!root.isJsonArray) return emptyList()
            root.asJsonArray.mapNotNull { element ->
                runCatching {
                    gson.fromJson(element, PendingMediaSend::class.java)
                        ?.takeIf { it.isValid() }
                }.getOrNull()
            }
        } catch (_: JsonSyntaxException) {
            emptyList()
        } catch (_: IllegalStateException) {
            emptyList()
        }
    }
}

object PendingMediaSendQueue {
    private const val TAG = "PendingMediaSendQueue"
    private const val PREFS_NAME = "ethora_pending_media_sends"
    private const val QUEUE_KEY = "queue"
    private const val PENDING_UPLOADS_DIR = "pending_uploads"

    private val lock = Any()
    private var prefs: SharedPreferences? = null

    private val _items = MutableStateFlow<List<PendingMediaSend>>(emptyList())
    val items: StateFlow<List<PendingMediaSend>> = _items.asStateFlow()

    fun initialize(context: Context) {
        synchronized(lock) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            pendingUploadsDir(context).mkdirs()
            val restored = PendingMediaSendCodec.decodeList(prefs?.getString(QUEUE_KEY, null))
                .map { item ->
                    if (item.status == PendingMediaSendStatus.UPLOADING) {
                        item.copy(status = PendingMediaSendStatus.FAILED_WAITING_RETRY, nextRetryAt = 0L)
                    } else {
                        item
                    }
                }
            replaceAllLocked(restored)
        }
    }

    fun pendingUploadsDir(context: Context): File {
        return File(context.applicationContext.filesDir, PENDING_UPLOADS_DIR)
    }

    fun sanitizeFileName(fileName: String): String {
        val base = File(fileName).name.ifBlank { "upload" }
        val sanitized = base
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_', '.', '-')
            .ifBlank { "upload" }
        return sanitized.take(120)
    }

    fun enqueue(item: PendingMediaSend): PendingMediaSend {
        synchronized(lock) {
            val current = _items.value.toMutableList()
            val index = current.indexOfFirst { it.id == item.id || it.messageId == item.messageId }
            if (index >= 0) {
                current[index] = item
            } else {
                current.add(item)
            }
            replaceAllLocked(current)
        }
        return item
    }

    fun upsert(item: PendingMediaSend) {
        enqueue(item)
    }

    fun update(id: String, transform: (PendingMediaSend) -> PendingMediaSend): PendingMediaSend? {
        synchronized(lock) {
            val current = _items.value.toMutableList()
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) return null
            val updated = transform(current[index])
            current[index] = updated
            replaceAllLocked(current)
            return updated
        }
    }

    fun remove(id: String, deleteLocalFile: Boolean = false) {
        synchronized(lock) {
            val current = _items.value.toMutableList()
            val removed = current.firstOrNull { it.id == id }
            val next = current.filterNot { it.id == id }
            replaceAllLocked(next)
            if (deleteLocalFile) {
                removed?.localFilePath?.let { runCatching { File(it).delete() } }
            }
        }
    }

    fun dueItems(now: Long = System.currentTimeMillis()): List<PendingMediaSend> {
        return _items.value.filter { item ->
            when (item.status) {
                PendingMediaSendStatus.QUEUED,
                PendingMediaSendStatus.READY_TO_SEND -> true
                PendingMediaSendStatus.FAILED_WAITING_RETRY -> item.nextRetryAt <= now
                PendingMediaSendStatus.PERMANENTLY_FAILED,
                PendingMediaSendStatus.UPLOADING,
                PendingMediaSendStatus.SENT -> false
            }
        }
    }

    fun retryNow(messageId: String): PendingMediaSend? {
        synchronized(lock) {
            val current = _items.value.toMutableList()
            val index = current.indexOfFirst { it.messageId == messageId || it.id == messageId }
            if (index < 0) return null
            val updated = current[index].copy(
                status = PendingMediaSendStatus.QUEUED,
                nextRetryAt = 0L
            )
            current[index] = updated
            replaceAllLocked(current)
            return updated
        }
    }

    fun getByMessageId(messageId: String): PendingMediaSend? {
        return _items.value.firstOrNull { it.messageId == messageId }
    }

    fun clear(deleteLocalFiles: Boolean = true) {
        synchronized(lock) {
            val current = _items.value
            if (deleteLocalFiles) {
                current.forEach { item ->
                    runCatching { File(item.localFilePath).delete() }
                }
            }
            replaceAllLocked(emptyList())
        }
    }

    fun clearPendingUploads(context: Context) {
        runCatching {
            pendingUploadsDir(context).deleteRecursively()
            pendingUploadsDir(context).mkdirs()
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear pending upload files", error)
        }
    }

    private fun replaceAllLocked(items: List<PendingMediaSend>) {
        val validItems = items.filter { it.isValid() }
        _items.value = validItems
        prefs?.edit()?.putString(QUEUE_KEY, PendingMediaSendCodec.encodeList(validItems))?.apply()
    }
}
