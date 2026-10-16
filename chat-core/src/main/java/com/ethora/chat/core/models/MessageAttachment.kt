package com.ethora.chat.core.models

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * One file of a media message. A message carries several of these when the
 * sender attached more than one file (web `attachments` attribute on <data>).
 */
data class MessageAttachment(
    val attachmentId: String? = null,
    val location: String = "",
    val locationPreview: String? = null,
    val mimetype: String? = null,
    val originalName: String? = null,
    val fileName: String? = null,
    val size: String? = null,
    val duration: String? = null
) {
    val displayName: String
        get() = originalName ?: fileName ?: location.substringBefore('?').substringAfterLast('/').ifBlank { "MediaFile" }

    val isImage: Boolean get() = mimetype?.startsWith("image/") == true
    val isVideo: Boolean get() = mimetype?.startsWith("video/") == true

    /** Images and videos form the album grid; everything else is a file row. */
    val isVisual: Boolean get() = isImage || isVideo
}

/** One attachment together with its index in the message's list. */
data class IndexedAttachment(val index: Int, val attachment: MessageAttachment)

data class AttachmentPartition(
    /** Visual items drawn as album tiles (only when there are at least two). */
    val tiles: List<IndexedAttachment>,
    /** Everything drawn as a row below the album. */
    val rows: List<IndexedAttachment>
)

object MessageAttachments {
    /** Hard ceiling per message, matching web MAX_ATTACHMENTS_PER_MESSAGE. */
    const val MAX_PER_MESSAGE = 10

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    /** Parses the JSON array carried by the `attachments` attribute. Never throws. */
    fun parse(raw: String?): List<MessageAttachment> {
        if (raw.isNullOrBlank()) return emptyList()
        val root: JsonElement = try {
            JsonParser.parseString(raw)
        } catch (_: Exception) {
            return emptyList()
        }
        if (!root.isJsonArray) return emptyList()
        return root.asJsonArray
            .asSequence()
            .take(MAX_PER_MESSAGE)
            .mapNotNull { element -> (element as? JsonObject)?.toAttachment() }
            .filter { it.location.isNotBlank() }
            .toList()
    }

    /** The wire form: a JSON array of the fields a receiver renders. */
    fun serialize(attachments: List<MessageAttachment>): String {
        val array = JsonArray()
        attachments.take(MAX_PER_MESSAGE).forEach { item ->
            val obj = JsonObject()
            item.attachmentId?.let { obj.addProperty("attachmentId", it) }
            obj.addProperty("location", item.location)
            item.locationPreview?.let { obj.addProperty("locationPreview", it) }
            item.mimetype?.let { obj.addProperty("mimetype", it) }
            item.originalName?.let { obj.addProperty("originalName", it) }
            item.fileName?.let { obj.addProperty("fileName", it) }
            item.size?.let { obj.addProperty("size", it) }
            item.duration?.let { obj.addProperty("duration", it) }
            array.add(obj)
        }
        return gson.toJson(array)
    }

    /**
     * The files of [message]: the `attachments` list when present, otherwise
     * the flat fields (one file, or a legacy comma-separated URL list).
     */
    fun forMessage(message: Message): List<MessageAttachment> {
        message.attachments?.takeIf { it.isNotEmpty() }?.let { return it.take(MAX_PER_MESSAGE) }
        val location = message.location?.takeIf { it.isNotBlank() } ?: return emptyList()
        val urls = splitUrlList(location)
        if (urls.size <= 1) {
            return listOf(
                MessageAttachment(
                    location = location,
                    locationPreview = message.locationPreview?.takeIf { it.isNotBlank() },
                    mimetype = message.mimetype,
                    originalName = message.originalName,
                    fileName = message.fileName,
                    size = message.size
                )
            )
        }
        val previews = splitUrlList(message.locationPreview)
        val mimes = splitList(message.mimetype)
        val names = splitList(message.originalName)
        return urls.take(MAX_PER_MESSAGE).mapIndexed { index, url ->
            MessageAttachment(
                location = url,
                locationPreview = if (previews.size == urls.size) previews[index] else null,
                mimetype = pickNth(mimes, index, urls.size),
                originalName = pickNth(names, index, urls.size)
            )
        }
    }

    /** Splits the list into album tiles (visual, when at least two) and rows. */
    fun partition(attachments: List<MessageAttachment>): AttachmentPartition {
        val indexed = attachments.mapIndexed { index, item -> IndexedAttachment(index, item) }
        val media = indexed.filter { it.attachment.isVisual }
        val others = indexed.filterNot { it.attachment.isVisual }
        return if (media.size < 2) {
            AttachmentPartition(tiles = emptyList(), rows = indexed)
        } else {
            AttachmentPartition(tiles = media, rows = others)
        }
    }

    private fun JsonObject.toAttachment(): MessageAttachment = MessageAttachment(
        attachmentId = str("attachmentId") ?: str("_id"),
        location = str("location") ?: "",
        locationPreview = str("locationPreview"),
        mimetype = str("mimetype"),
        originalName = str("originalName") ?: str("originalname"),
        fileName = str("fileName") ?: str("filename"),
        size = str("size"),
        duration = str("duration")
    )

    private fun JsonObject.str(name: String): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive) return null
        return element.asString.takeIf { it.isNotEmpty() }
    }

    private fun splitUrlList(value: String?): List<String> = when {
        value.isNullOrBlank() -> emptyList()
        Regex(",\\s*https?://", RegexOption.IGNORE_CASE).containsMatchIn(value) ->
            value.split(Regex(",\\s*(?=https?://)", RegexOption.IGNORE_CASE))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        else -> listOf(value)
    }

    private fun splitList(value: String?): List<String> =
        if (value.isNullOrBlank()) emptyList() else value.split(',').map { it.trim() }

    private fun pickNth(list: List<String>, index: Int, total: Int): String? = when {
        list.size == total -> list[index]
        list.size == 1 -> list[0]
        else -> null
    }
}

/** A copy of [this] that describes only [attachment], used for solo bubbles and the viewer. */
fun Message.withAttachment(attachment: MessageAttachment): Message = copy(
    location = attachment.location,
    locationPreview = attachment.locationPreview,
    mimetype = attachment.mimetype,
    originalName = attachment.originalName,
    fileName = attachment.fileName ?: attachment.originalName,
    size = attachment.size,
    attachments = null
)
