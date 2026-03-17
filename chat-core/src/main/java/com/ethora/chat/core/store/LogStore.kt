package com.ethora.chat.core.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

/**
 * Global log store for debugging purposes
 * Collects logs that can be displayed in the UI
 */
object LogStore {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    data class LogEntry(
        val timestamp: String,
        val tag: String,
        val message: String,
        val type: LogType = LogType.INFO
    )

    enum class LogType {
        INFO, WARNING, ERROR, SUCCESS, SEND, RECEIVE
    }

    /**
     * Add a new log entry
     */
    fun log(tag: String, message: String, type: LogType = LogType.INFO) {
        val entry = LogEntry(
            timestamp = dateFormat.format(Date()),
            tag = tag,
            message = message,
            type = type
        )
        
        // Keep last 500 logs
        val currentLogs = _logs.value.toMutableList()
        currentLogs.add(0, entry) // Add to top
        if (currentLogs.size > 500) {
            currentLogs.removeAt(currentLogs.size - 1)
        }
        _logs.value = currentLogs
        
        // Also print to Logcat
        when (type) {
            LogType.ERROR -> android.util.Log.e(tag, message)
            LogType.WARNING -> android.util.Log.w(tag, message)
            else -> android.util.Log.d(tag, message)
        }
    }

    fun info(tag: String, message: String) = log(tag, message, LogType.INFO)
    fun success(tag: String, message: String) = log(tag, message, LogType.SUCCESS)
    fun warning(tag: String, message: String) = log(tag, message, LogType.WARNING)
    fun error(tag: String, message: String) = log(tag, message, LogType.ERROR)
    fun send(tag: String, message: String) = log(tag, message, LogType.SEND)
    fun receive(tag: String, message: String) = log(tag, message, LogType.RECEIVE)

    /**
     * Clear all logs
     */
    fun clear() {
        _logs.value = emptyList()
    }
    
    /**
     * Get all logs as a single string for copying
     */
    fun getAllLogsAsText(): String {
        return _logs.value.reversed().joinToString("\n") { 
            "[${it.timestamp}] [${it.tag}] [${it.type}] ${it.message}"
        }
    }
}
