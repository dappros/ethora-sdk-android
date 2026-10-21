package com.ethora.chat.core.e2ee

import java.io.ByteArrayOutputStream

/**
 * Hand-written codec for the three OMEMO 2 messages (XEP-0384 section 7),
 * byte-compatible with web `src/e2ee/protobuf.ts`:
 *
 *   message OMEMOMessage { required uint32 n = 1; required uint32 pn = 2;
 *     required bytes dh_pub = 3; optional bytes ciphertext = 4; }
 *   message OMEMOAuthenticatedMessage { required bytes mac = 1; required bytes message = 2; }
 *   message OMEMOKeyExchange { required uint32 pk_id = 1; required uint32 spk_id = 2;
 *     required bytes ik = 3; required bytes ek = 4; required OMEMOAuthenticatedMessage message = 5; }
 *
 * uint32 values are carried as Long so that the full unsigned range survives.
 */
object OmemoProtobuf {
    private const val VARINT = 0
    private const val FIXED64 = 1
    private const val BYTES = 2
    private const val FIXED32 = 5
    private const val MAX_UINT32 = 0xffffffffL

    class Message(val n: Long, val pn: Long, val dhPub: ByteArray, val ciphertext: ByteArray?)

    class Authenticated(val mac: ByteArray, val message: ByteArray)

    class KeyExchange(
        val pkId: Long,
        val spkId: Long,
        val ik: ByteArray,
        val ek: ByteArray,
        /** Encoded [Authenticated]. */
        val message: ByteArray
    )

    private class Writer {
        private val out = ByteArrayOutputStream()

        fun uint(field: Int, value: Long): Writer {
            require(value in 0..MAX_UINT32) { "Invalid uint32: $value" }
            writeVarint(((field shl 3) or VARINT).toLong())
            writeVarint(value)
            return this
        }

        fun bytes(field: Int, value: ByteArray?): Writer {
            if (value == null) return this
            writeVarint(((field shl 3) or BYTES).toLong())
            writeVarint(value.size.toLong())
            out.write(value)
            return this
        }

        private fun writeVarint(value: Long) {
            var v = value
            while (v > 0x7f) {
                out.write(((v % 0x80) or 0x80).toInt())
                v /= 0x80
            }
            out.write(v.toInt())
        }

        fun finish(): ByteArray = out.toByteArray()
    }

    /** Last value of each field wins; unknown fields are skipped. */
    private class Reader(private val buf: ByteArray) {
        private var pos = 0
        val fields = HashMap<Int, Any>()

        init {
            while (pos < buf.size) {
                val tag = readVarint()
                val field = (tag / 8).toInt()
                when ((tag % 8).toInt()) {
                    VARINT -> fields[field] = readVarint()
                    BYTES -> {
                        val length = readVarint()
                        val start = pos
                        skip(length)
                        fields[field] = buf.copyOfRange(start, pos)
                    }
                    FIXED64 -> skip(8)
                    FIXED32 -> skip(4)
                    else -> throw IllegalArgumentException("Unsupported wire type")
                }
            }
        }

        private fun readVarint(): Long {
            var result = 0L
            var scale = 1L
            for (i in 0 until 10) {
                if (pos >= buf.size) throw IllegalArgumentException("Truncated varint")
                val b = buf[pos++].toInt() and 0xff
                result += (b and 0x7f).toLong() * scale
                if (b and 0x80 == 0) return result
                scale *= 0x80
            }
            throw IllegalArgumentException("Varint too long")
        }

        private fun skip(length: Long) {
            if (length < 0 || pos + length > buf.size) throw IllegalArgumentException("Truncated field")
            pos += length.toInt()
        }

        fun uint(field: Int): Long {
            val value = fields[field]
            if (value !is Long || value > MAX_UINT32) throw IllegalArgumentException("Missing field $field")
            return value
        }

        fun bytes(field: Int): ByteArray {
            val value = fields[field]
            if (value !is ByteArray) throw IllegalArgumentException("Missing field $field")
            return value.copyOf()
        }

        fun optionalBytes(field: Int): ByteArray? = (fields[field] as? ByteArray)?.copyOf()
    }

    fun encodeMessage(m: Message): ByteArray = Writer()
        .uint(1, m.n)
        .uint(2, m.pn)
        .bytes(3, m.dhPub)
        .bytes(4, m.ciphertext)
        .finish()

    fun decodeMessage(buf: ByteArray): Message {
        val r = Reader(buf)
        return Message(r.uint(1), r.uint(2), r.bytes(3), r.optionalBytes(4))
    }

    fun encodeAuthenticated(m: Authenticated): ByteArray =
        Writer().bytes(1, m.mac).bytes(2, m.message).finish()

    fun decodeAuthenticated(buf: ByteArray): Authenticated {
        val r = Reader(buf)
        return Authenticated(r.bytes(1), r.bytes(2))
    }

    fun encodeKeyExchange(m: KeyExchange): ByteArray = Writer()
        .uint(1, m.pkId)
        .uint(2, m.spkId)
        .bytes(3, m.ik)
        .bytes(4, m.ek)
        .bytes(5, m.message)
        .finish()

    fun decodeKeyExchange(buf: ByteArray): KeyExchange {
        val r = Reader(buf)
        return KeyExchange(r.uint(1), r.uint(2), r.bytes(3), r.bytes(4), r.bytes(5))
    }
}
