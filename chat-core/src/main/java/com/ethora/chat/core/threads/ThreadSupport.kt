package com.ethora.chat.core.threads

import com.ethora.chat.core.models.Message
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Reply and thread helpers, ported from web:
 *  - helpers/createMainMessageForThread.ts (the `mainMessage` JSON a reply carries)
 *  - helpers/parseMessageReference.ts
 *  - helpers/jumpThread.ts (`replyParentId`)
 *  - components/MainComponents/MessageList.tsx (main list and thread list filters)
 *
 * A reply is a groupchat message whose `<data>` has `isReply="true"` and a
 * `mainMessage` attribute holding the parent as JSON. The parent is identified
 * by its ARCHIVE id (`<stanza-id>`), not by the optimistic UUID. Replies with
 * `showInChannel="true"` ("Also send to room") are shown in the main list too.
 *
 * Pure Kotlin on purpose (no org.json, no Android types) so the JVM unit tests
 * see the real behaviour.
 */
object ThreadSupport {

    /** The parent reference parsed out of a reply's `mainMessage` JSON. */
    data class MessageReference(
        val id: String?,
        val text: String?,
        val roomJid: String?,
        val userName: String?,
        val createdAt: String?
    )

    /** Key the parent is matched by: the archive id when known, else the row id. */
    fun threadKey(message: Message): String =
        message.archiveId?.takeIf { it.isNotBlank() } ?: message.id

    /** Archive id as a number (ids embed microseconds), or null when not numeric. */
    fun numericKey(message: Message): Long? =
        threadKey(message).takeIf { it.all(Char::isDigit) && it.isNotEmpty() }?.toLongOrNull()

    /**
     * Web `createMainMessageForThread`: the JSON a reply carries in its
     * `mainMessage` attribute. Key order and empty fields match web, so
     * web clients read it back the same way.
     */
    fun createMainMessageForThread(message: Message): String {
        val fields = linkedMapOf(
            "text" to message.body,
            "id" to threadKey(message),
            "userName" to (message.user.name ?: message.user.fullName),
            "createdAt" to isoDate(message.date),
            "imageLocation" to (message.location ?: ""),
            "imagePreview" to (message.locationPreview ?: ""),
            "mimeType" to (message.mimetype ?: ""),
            "size" to "",
            "duration" to "",
            "waveForm" to "",
            "attachmentId" to "",
            "wrappable" to "",
            "nftActionType" to "",
            "contractAddress" to "",
            "roomJid" to message.roomJid,
            "nftId" to ""
        )
        return fields.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
            "\"${jsonEscape(key)}\":\"${jsonEscape(value)}\""
        }
    }

    /** Parses a reply's `mainMessage` JSON. Null when it is blank or not a JSON object. */
    fun parseMessageReference(raw: String?): MessageReference? {
        if (raw.isNullOrBlank()) return null
        val fields = parseFlatJsonObject(raw) ?: return null
        if (fields.isEmpty()) return null
        return MessageReference(
            id = fields["id"],
            text = fields["text"],
            roomJid = fields["roomJid"],
            userName = fields["userName"],
            createdAt = fields["createdAt"]
        )
    }

    /** Web `replyParentId`: the parent key of a thread-only reply, else null. */
    fun replyParentId(message: Message): String? {
        if (message.isReply != true) return null
        if (message.showInChannel == "true") return null
        return parseMessageReference(message.mainMessage)?.id?.takeIf { it.isNotBlank() }
    }

    /** Web main list filter: channel messages, system banners, and anything that is not a thread reply. */
    fun isMainListMessage(message: Message): Boolean =
        message.isSystemMessage == "true" ||
            message.showInChannel == "true" ||
            ((message.isReply != true) && message.mainMessage.isNullOrBlank())

    /** Web thread list filter: a reply (channel-shared or not) whose parent is [parentKey]. */
    fun isThreadReplyOf(message: Message, parentKey: String): Boolean =
        message.isReply == true &&
            parseMessageReference(message.mainMessage)?.id == parentKey

    /** Visible parent key for a main-list message that opens a thread on tap. */
    fun threadParentFor(message: Message): String? =
        replyParentId(message) ?: parseMessageReference(message.mainMessage)?.id?.takeIf { it.isNotBlank() }

    private fun isoDate(date: Date): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(date)
    }

    private fun jsonEscape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch < ' ') out.append(String.format(Locale.US, "\\u%04x", ch.code)) else out.append(ch)
            }
        }
        return out.toString()
    }

    /**
     * Flat JSON object: string, number, boolean or null values only, which is
     * all a `mainMessage` ever holds. Returns null on anything malformed.
     */
    internal fun parseFlatJsonObject(raw: String): Map<String, String?>? {
        val s = raw.trim()
        if (!s.startsWith("{") || !s.endsWith("}")) return null
        return try {
            val out = LinkedHashMap<String, String?>()
            var i = 1
            fun skipWs() {
                while (i < s.length && s[i].isWhitespace()) i++
            }
            fun readString(): String? {
                if (i >= s.length || s[i] != '"') return null
                i++
                val sb = StringBuilder()
                while (i < s.length) {
                    val c = s[i]
                    when {
                        c == '"' -> {
                            i++
                            return sb.toString()
                        }
                        c == '\\' -> {
                            if (i + 1 >= s.length) return null
                            when (s[i + 1]) {
                                '"' -> sb.append('"')
                                '\\' -> sb.append('\\')
                                '/' -> sb.append('/')
                                'b' -> sb.append('\b')
                                'f' -> sb.append('\u000C')
                                'n' -> sb.append('\n')
                                'r' -> sb.append('\r')
                                't' -> sb.append('\t')
                                'u' -> {
                                    if (i + 6 > s.length) return null
                                    sb.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                                    i += 4
                                }
                                else -> return null
                            }
                            i += 2
                        }
                        else -> {
                            sb.append(c)
                            i++
                        }
                    }
                }
                return null
            }
            skipWs()
            while (i < s.length && s[i] != '}') {
                val key = readString() ?: return null
                skipWs()
                if (i >= s.length || s[i] != ':') return null
                i++
                skipWs()
                val value: String? = if (i < s.length && s[i] == '"') {
                    readString() ?: return null
                } else {
                    val start = i
                    while (i < s.length && s[i] != ',' && s[i] != '}') i++
                    val literal = s.substring(start, i).trim()
                    if (literal == "null") null else literal
                }
                out[key] = value
                skipWs()
                if (i < s.length && s[i] == ',') {
                    i++
                    skipWs()
                } else if (i >= s.length || s[i] != '}') {
                    return null
                }
            }
            if (i >= s.length || s[i] != '}') return null
            out
        } catch (e: RuntimeException) {
            null
        }
    }
}
