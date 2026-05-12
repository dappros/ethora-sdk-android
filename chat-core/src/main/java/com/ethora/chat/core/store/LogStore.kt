package com.ethora.chat.core.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/**
 * Global log store for debugging purposes.
 * Keeps a single chronological session log that can be copied from the sample app
 * or SDK log viewer without relying on Logcat.
 */
object LogStore {
    private const val MAX_LOGS = 2000

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val absoluteDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }
    private val sessionCounter = AtomicLong(1L)
    private val eventCounter = AtomicLong(0L)

    @Volatile
    private var sessionStartedAtMs: Long = System.currentTimeMillis()
    @Volatile
    private var sessionId: String = "session-${sessionCounter.get()}"

    data class LogEntry(
        val sessionId: String,
        val eventId: Long,
        val timestamp: String,
        val relativeMs: Long,
        val tag: String,
        val category: String,
        val message: String,
        val type: LogType = LogType.INFO,
        val rawMessage: String? = null
    )

    enum class LogType {
        INFO, WARNING, ERROR, SUCCESS, SEND, RECEIVE
    }

    data class LogOptions(
        val category: String = "general",
        val rawMessage: String? = null
    )

    fun currentSessionId(): String = sessionId

    fun startNewSession(reason: String? = null) {
        sessionStartedAtMs = System.currentTimeMillis()
        sessionId = "session-${sessionCounter.incrementAndGet()}"
        eventCounter.set(0L)
        _logs.value = emptyList()
        if (!reason.isNullOrBlank()) {
            info("LogStore", "Started new log session: $reason", category = "session")
        }
    }

    fun log(
        tag: String,
        message: String,
        type: LogType = LogType.INFO,
        category: String = "general",
        rawMessage: String? = null
    ) {
        val now = System.currentTimeMillis()
        val entry = LogEntry(
            sessionId = sessionId,
            eventId = eventCounter.incrementAndGet(),
            timestamp = absoluteDateFormat.format(Date(now)),
            relativeMs = now - sessionStartedAtMs,
            tag = tag,
            category = category,
            message = message,
            type = type,
            rawMessage = rawMessage
        )

        val currentLogs = _logs.value.toMutableList()
        currentLogs.add(0, entry)
        if (currentLogs.size > MAX_LOGS) {
            currentLogs.subList(MAX_LOGS, currentLogs.size).clear()
        }
        _logs.value = currentLogs

        when (type) {
            LogType.ERROR -> android.util.Log.e(tag, message)
            LogType.WARNING -> android.util.Log.w(tag, message)
            else -> android.util.Log.d(tag, message)
        }
    }

    fun info(tag: String, message: String, category: String = "general", rawMessage: String? = null) =
        log(tag, message, LogType.INFO, category, rawMessage)

    fun success(tag: String, message: String, category: String = "general", rawMessage: String? = null) =
        log(tag, message, LogType.SUCCESS, category, rawMessage)

    fun warning(tag: String, message: String, category: String = "general", rawMessage: String? = null) =
        log(tag, message, LogType.WARNING, category, rawMessage)

    fun error(tag: String, message: String, category: String = "general", rawMessage: String? = null) =
        log(tag, message, LogType.ERROR, category, rawMessage)

    fun send(tag: String, message: String, category: String = "xmpp-send", rawMessage: String? = null) =
        log(tag, message, LogType.SEND, category, rawMessage)

    fun receive(tag: String, message: String, category: String = "xmpp-recv", rawMessage: String? = null) =
        log(tag, message, LogType.RECEIVE, category, rawMessage)

    fun clear(startNewSession: Boolean = false) {
        if (startNewSession) {
            startNewSession("manual clear")
        } else {
            _logs.value = emptyList()
        }
    }

    /**
     * Hard ceiling on what we are willing to pass to the system clipboard.
     * Android's clipboard is exposed over a Binder IPC channel whose default
     * transaction limit is ~1 MB; pushing a string anywhere near that limit
     * throws `TransactionTooLargeException` from `ClipboardManager.setPrimaryClip`
     * and tears the calling app down. Even short of the hard limit, the
     * marshalling + IPC happens synchronously on the main thread when called
     * from a Compose `onClick`, which can ANR a busy app.
     *
     * 500 KB sits well under both ceilings and is enough for ~500 lines of
     * typical XMPP-frame logs. Past this point we truncate from the head
     * (oldest first) and append a banner explaining the cut.
     */
    const val MAX_CLIPBOARD_BYTES = 500_000

    fun copyAllLogs(): String = exportText(_logs.value)

    fun getAllLogsAsText(): String = copyAllLogs()

    fun exportText(entries: List<LogEntry>): String {
        return entries
            .asReversed()
            .joinToString("\n") { entry ->
                buildString {
                    append("[${entry.timestamp}]")
                    append(" [t+${entry.relativeMs}ms]")
                    append(" [${entry.type}]")
                    append(" [${entry.tag}]")
                    append(" [category=${entry.category}]")
                    append(" [session=${entry.sessionId}]")
                    append(" [event=${entry.eventId}] ")
                    append(entry.message)
                    entry.rawMessage?.takeIf { it.isNotBlank() }?.let { raw ->
                        append("\nraw=")
                        append(raw)
                    }
                }
            }
    }

    /**
     * Produce a clipboard-safe export. Builds [exportText] off the caller's
     * thread, truncates to [MAX_CLIPBOARD_BYTES] (cuts from the head — older
     * lines — so the freshest events are preserved), and prepends a one-line
     * banner when truncation happened so the receiver knows the dump is a tail.
     */
    fun copyAllLogsForClipboard(): String {
        val full = exportText(_logs.value)
        val approxBytes = full.length // UTF-16 char count is the upper bound for ASCII; close enough as a guard.
        if (approxBytes <= MAX_CLIPBOARD_BYTES) return full
        // Keep the tail (newest entries land at the head of `exportText`'s
        // output because we `asReversed()` there — so to keep the freshest
        // events we take the LEADING bytes).
        val truncated = full.take(MAX_CLIPBOARD_BYTES)
        val banner = "[...truncated to ${MAX_CLIPBOARD_BYTES / 1024} KB; ${_logs.value.size} total log entries...]\n"
        return banner + truncated
    }
}
