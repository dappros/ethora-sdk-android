package com.ethora.chat.core.e2ee

import com.google.gson.JsonParser

/**
 * Attachment sealing for encrypted rooms. Port of web `src/e2ee/fileEnvelope.ts`.
 *
 * The file is sealed before it leaves the device, so the server stores opaque
 * bytes under a random name and never sees the real mimetype or filename. The
 * per-file key material travels in the OMEMO-encrypted <body>.
 *
 * Envelope layout: MAGIC(8) | version(1) | headerLen(4, big-endian) | headerJSON | fileBytes.
 */
object OmemoFileEnvelope {
    private val MAGIC = "ETHOFILE".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val HEADER_OFFSET = 8 + 1 + 4

    class Meta(val mimetype: String, val originalname: String, val size: Long)

    class Sealed(
        /** Ciphertext, ready to upload. */
        val ciphertext: ByteArray,
        /** Base64 of the 48-byte key material. Belongs in the encrypted <body>. */
        val keyMaterial: String,
        /** Random, extension-free name to upload under. */
        val filename: String
    )

    class Opened(val meta: Meta, val bytes: ByteArray)

    /** What a sealed media <body> carries: `{"v":1,"keys":[...],"caption"?:"..."}`. */
    class SealedMedia(val keys: List<String>, val caption: String?)

    fun encode(meta: Meta, bytes: ByteArray): ByteArray {
        val header = jsonHeader(meta).toByteArray(Charsets.UTF_8)
        val out = ByteArray(HEADER_OFFSET + header.size + bytes.size)
        MAGIC.copyInto(out, 0)
        out[MAGIC.size] = VERSION.toByte()
        val length = header.size
        out[9] = (length ushr 24).toByte()
        out[10] = (length ushr 16).toByte()
        out[11] = (length ushr 8).toByte()
        out[12] = length.toByte()
        header.copyInto(out, HEADER_OFFSET)
        bytes.copyInto(out, HEADER_OFFSET + header.size)
        return out
    }

    fun decode(envelope: ByteArray): Opened {
        if (envelope.size < HEADER_OFFSET) throw IllegalArgumentException("file_envelope_truncated")
        for (i in MAGIC.indices) {
            if (envelope[i] != MAGIC[i]) throw IllegalArgumentException("file_envelope_bad_magic")
        }
        val version = envelope[MAGIC.size].toInt() and 0xff
        if (version != VERSION) throw IllegalArgumentException("file_envelope_unsupported_version_$version")
        val headerLength = ((envelope[9].toLong() and 0xff) shl 24) or
            ((envelope[10].toLong() and 0xff) shl 16) or
            ((envelope[11].toLong() and 0xff) shl 8) or
            (envelope[12].toLong() and 0xff)
        // A forged length must not read past the buffer
        val bodyStart = HEADER_OFFSET + headerLength
        if (bodyStart > envelope.size) throw IllegalArgumentException("file_envelope_truncated")
        val json = JsonParser.parseString(
            String(envelope, HEADER_OFFSET, headerLength.toInt(), Charsets.UTF_8)
        ).asJsonObject
        val meta = Meta(
            mimetype = json.get("mimetype")?.asString ?: "application/octet-stream",
            originalname = json.get("originalname")?.asString ?: "blob",
            size = json.get("size")?.asLong ?: 0L
        )
        return Opened(meta, envelope.copyOfRange(bodyStart.toInt(), envelope.size))
    }

    /** Seals [bytes] for upload. Same AES-256-CBC and truncated-HMAC construction as message payloads. */
    fun seal(bytes: ByteArray, mimetype: String?, originalName: String?): Sealed {
        val meta = Meta(
            mimetype = mimetype?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
            originalname = originalName?.takeIf { it.isNotBlank() } ?: "blob",
            size = bytes.size.toLong()
        )
        val payload = OmemoRatchet.encryptPayload(encode(meta, bytes))
        return Sealed(
            ciphertext = payload.payload,
            keyMaterial = OmemoCrypto.toBase64(payload.keyMaterial),
            // No extension: a suffix on opaque bytes would give back what sealing hid
            filename = OmemoCrypto.toHex(OmemoCrypto.randomBytes(16))
        )
    }

    fun open(ciphertext: ByteArray, keyMaterial: String): Opened =
        decode(OmemoRatchet.decryptPayload(OmemoCrypto.fromBase64(keyMaterial), ciphertext))

    /**
     * Keys (and the caption, when present) out of a decrypted media <body>.
     * Returns null for anything else, so an ordinary media message passes through.
     */
    fun parseSealedMedia(body: String?): SealedMedia? {
        val text = body?.trim().orEmpty()
        if (!text.startsWith("{")) return null
        return try {
            val json = JsonParser.parseString(text).asJsonObject
            if (json.get("v")?.asInt != 1) return null
            val array = json.getAsJsonArray("keys") ?: return null
            val keys = array.map { it.asString }
            if (keys.isEmpty() || keys.any { it.isEmpty() }) return null
            SealedMedia(keys, json.get("caption")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() })
        } catch (e: Exception) {
            null
        }
    }

    /** The `<body>` of a sealed media message. Keys are JSON strings; the caption is optional. */
    fun sealedMediaBody(keys: List<String>, caption: String? = null): String {
        val sb = StringBuilder("{\"v\":1,\"keys\":[")
        keys.forEachIndexed { i, key ->
            if (i > 0) sb.append(',')
            sb.append(jsonString(key))
        }
        sb.append(']')
        if (!caption.isNullOrEmpty()) sb.append(",\"caption\":").append(jsonString(caption))
        return sb.append('}').toString()
    }

    private fun jsonHeader(meta: Meta): String =
        "{\"mimetype\":${jsonString(meta.mimetype)},\"originalname\":${jsonString(meta.originalname)},\"size\":${meta.size}}"

    /** JSON string literal, escaped the way JSON.stringify does for the characters that matter here. */
    internal fun jsonString(value: String): String {
        val sb = StringBuilder("\"")
        for (c in value) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000c' -> sb.append("\\f")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
