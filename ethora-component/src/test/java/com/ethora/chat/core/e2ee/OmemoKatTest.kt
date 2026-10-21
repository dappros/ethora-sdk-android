package com.ethora.chat.core.e2ee

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Known-answer tests: every expected value below was produced by the web
 * implementation (src/e2ee) from the same fixed inputs, see
 * src/test/resources/e2ee/omemo_vectors.json. A failure here means the wire
 * format or a key derivation diverged from web.
 */
class OmemoKatTest {

    private val vectors: JsonObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("e2ee/omemo_vectors.json")
        JsonParser.parseReader(stream.reader(Charsets.UTF_8)).asJsonObject
    }

    private fun hex(name: String): ByteArray = OmemoCrypto.fromHex(name)

    /** Same construction as the generator: byte i = (i * 7 + n * 13 + 1) & 0xff. */
    private fun seed(n: Int): ByteArray = ByteArray(32) { ((it * 7 + n * 13 + 1) and 0xff).toByte() }

    private fun JsonObject.str(key: String) = get(key).asString

    @Test
    fun hkdfMatchesWeb() {
        for (entry in vectors.getAsJsonArray("hkdf")) {
            val v = entry.asJsonObject
            val salt = if (v.get("salt").isJsonNull) null else hex(v.str("salt"))
            val out = OmemoCrypto.hkdfSha256(hex(v.str("ikm")), salt, v.str("info"), v.get("length").asInt)
            assertEquals(v.str("out"), OmemoCrypto.toHex(out))
        }
    }

    @Test
    fun identityConversionsAndSignaturesMatchWeb() {
        val id = vectors.getAsJsonObject("identity")
        val aliceSeed = seed(1)
        val bobSeed = seed(2)
        assertEquals(id.str("alicePub"), OmemoCrypto.toHex(OmemoCrypto.ed25519Public(aliceSeed)))
        assertEquals(id.str("bobPub"), OmemoCrypto.toHex(OmemoCrypto.ed25519Public(bobSeed)))
        assertEquals(id.str("aliceDhPriv"), OmemoCrypto.toHex(OmemoCrypto.identityDhPrivate(aliceSeed)))
        assertEquals(id.str("aliceDhPub"), OmemoCrypto.toHex(OmemoCrypto.identityDhPublic(hex(id.str("alicePub")))))
        assertEquals(id.str("bobDhPub"), OmemoCrypto.toHex(OmemoCrypto.identityDhPublic(hex(id.str("bobPub")))))

        val spkPub = OmemoCrypto.x25519Public(seed(10))
        assertEquals(id.str("spkSignature"), OmemoCrypto.toHex(OmemoCrypto.ed25519Sign(bobSeed, spkPub)))
        assertTrue(OmemoCrypto.ed25519Verify(hex(id.str("spkSignature")), spkPub, hex(id.str("bobPub"))))
        assertEquals(
            id.str("aliceSignsMsg"),
            OmemoCrypto.toHex(OmemoCrypto.ed25519Sign(aliceSeed, id.str("msg").toByteArray()))
        )
    }

    @Test
    fun x25519MatchesWeb() {
        val x = vectors.getAsJsonObject("x25519")
        assertEquals(x.str("pubPeer"), OmemoCrypto.toHex(OmemoCrypto.x25519Public(seed(6))))
        assertEquals(x.str("shared"), OmemoCrypto.toHex(OmemoCrypto.dh(seed(5), hex(x.str("pubPeer")))))
    }

    @Test
    fun responderX3dhMatchesWeb() {
        val a = vectors.getAsJsonObject("acceptSession")
        val identity = identityOf(seed(2))
        val spk = OmemoCrypto.keyPairFromPrivate(seed(10))
        val pk = OmemoCrypto.keyPairFromPrivate(seed(11))
        val alicePub = hex(vectors.getAsJsonObject("identity").str("alicePub"))
        val ekPub = OmemoCrypto.x25519Public(seed(12))
        val session = OmemoRatchet.acceptSession(identity, spk, pk, alicePub, ekPub)
        assertEquals(a.str("rk"), OmemoCrypto.toHex(session.rk))
        assertEquals(a.str("ad"), OmemoCrypto.toHex(session.ad))
        assertEquals(a.str("kexEk"), OmemoCrypto.toHex(session.kexEk!!))
    }

    /** Initiator built step by step, exactly as the generator did (initiateSession with fixed keys). */
    private fun initiatorSession(): Pair<OmemoRatchet.Session, ByteArray> {
        val aliceSeed = seed(1)
        val alicePub = OmemoCrypto.ed25519Public(aliceSeed)
        val bobPub = OmemoCrypto.ed25519Public(seed(2))
        val spkPub = OmemoCrypto.x25519Public(seed(10))
        val pkPub = OmemoCrypto.x25519Public(seed(11))
        val ekPriv = seed(12)
        val dhsPriv = seed(13)
        val dhOut = OmemoCrypto.concat(
            OmemoCrypto.dh(OmemoCrypto.identityDhPrivate(aliceSeed), spkPub),
            OmemoCrypto.dh(ekPriv, OmemoCrypto.identityDhPublic(bobPub)),
            OmemoCrypto.dh(ekPriv, spkPub),
            OmemoCrypto.dh(ekPriv, pkPub)
        )
        val prefix = ByteArray(32) { 0xff.toByte() }
        val sk = OmemoCrypto.hkdfSha256(OmemoCrypto.concat(prefix, dhOut), null, "OMEMO X3DH", 32)
        val rootOut = OmemoCrypto.hkdfSha256(OmemoCrypto.dh(dhsPriv, spkPub), sk, "OMEMO Root Chain", 64)
        val session = OmemoRatchet.Session(
            rk = rootOut.copyOfRange(0, 32),
            dhs = OmemoCrypto.keyPairFromPrivate(dhsPriv),
            dhr = spkPub,
            cks = rootOut.copyOfRange(32, 64),
            ad = OmemoCrypto.concat(alicePub, bobPub),
            peerIk = bobPub
        )
        return session to sk
    }

    @Test
    fun ratchetEncryptProducesWebBytes() {
        val r = vectors.getAsJsonObject("ratchet")
        var (session, _) = initiatorSession()
        val plaintexts = r.getAsJsonArray("plaintexts").map { it.asString }
        val wire = r.getAsJsonArray("wire").map { it.asString }
        assertEquals(r.getAsJsonObject("initiator").str("rk"), OmemoCrypto.toHex(session.rk))
        for (i in plaintexts.indices) {
            val (next, data) = OmemoRatchet.ratchetEncrypt(session, plaintexts[i].toByteArray(Charsets.UTF_8))
            assertEquals(wire[i], OmemoCrypto.toHex(data))
            session = next
        }
    }

    @Test
    fun ratchetDecryptsWebMessagesOutOfOrder() {
        val r = vectors.getAsJsonObject("ratchet")
        val wire = r.getAsJsonArray("wire").map { it.asString }
        val plaintexts = r.getAsJsonArray("plaintexts").map { it.asString }
        val order = r.getAsJsonArray("decryptOrder").map { it.asInt }
        val bob = identityOf(seed(2))
        val accepted = OmemoRatchet.acceptSession(
            bob,
            OmemoCrypto.keyPairFromPrivate(seed(10)),
            OmemoCrypto.keyPairFromPrivate(seed(11)),
            OmemoCrypto.ed25519Public(seed(1)),
            OmemoCrypto.x25519Public(seed(12))
        )
        var session = accepted
        for (i in order) {
            val (next, plain) = OmemoRatchet.ratchetDecrypt(session, OmemoCrypto.fromHex(wire[i]))
            assertEquals(plaintexts[i], String(plain, Charsets.UTF_8))
            session = next
        }
    }

    @Test
    fun payloadEncryptionMatchesWebWithFixedKey() {
        val p = vectors.getAsJsonObject("payload")
        val encrypted = OmemoRatchet.encryptPayloadWithKey(hex(p.str("key")), p.str("plaintext").toByteArray())
        assertEquals(p.str("ciphertext"), OmemoCrypto.toHex(encrypted.payload))
        assertEquals(p.str("keyMaterial"), OmemoCrypto.toHex(encrypted.keyMaterial))
        assertArrayEquals(
            p.str("plaintext").toByteArray(),
            OmemoRatchet.decryptPayload(hex(p.str("keyMaterial")), hex(p.str("ciphertext")))
        )
    }

    @Test
    fun protobufMatchesWeb() {
        val pb = vectors.getAsJsonObject("protobuf")
        val spkPub = OmemoCrypto.x25519Public(seed(10))
        val ciphertext = ByteArray(40) { (it + 1).toByte() }
        val message = OmemoProtobuf.encodeMessage(OmemoProtobuf.Message(5, 300, spkPub, ciphertext))
        assertEquals(pb.str("message"), OmemoCrypto.toHex(message))
        val authenticated = OmemoProtobuf.encodeAuthenticated(
            OmemoProtobuf.Authenticated(seed(31).copyOf(16), message)
        )
        assertEquals(pb.str("authenticated"), OmemoCrypto.toHex(authenticated))
        val kex = OmemoProtobuf.encodeKeyExchange(
            OmemoProtobuf.KeyExchange(
                7, 1, OmemoCrypto.ed25519Public(seed(1)), OmemoCrypto.x25519Public(seed(12)), authenticated
            )
        )
        assertEquals(pb.str("keyExchange"), OmemoCrypto.toHex(kex))

        // Decoding web's bytes
        val decoded = OmemoProtobuf.decodeMessage(hex(pb.str("message")))
        assertEquals(pb.get("decodedMessageN").asLong, decoded.n)
        assertEquals(pb.get("decodedMessagePn").asLong, decoded.pn)
        assertEquals(pb.get("decodedKexPkId").asLong, OmemoProtobuf.decodeKeyExchange(hex(pb.str("keyExchange"))).pkId)
    }

    @Test
    fun fileEnvelopeMatchesWeb() {
        val f = vectors.getAsJsonObject("fileEnvelope")
        val meta = f.getAsJsonObject("meta")
        val encoded = OmemoFileEnvelope.encode(
            OmemoFileEnvelope.Meta(meta.str("mimetype"), meta.str("originalname"), meta.get("size").asLong),
            hex(f.str("bytes"))
        )
        assertEquals(f.str("envelope"), OmemoCrypto.toHex(encoded))
        val decoded = OmemoFileEnvelope.decode(hex(f.str("envelope")))
        assertEquals(meta.str("originalname"), decoded.meta.originalname)
        assertArrayEquals(hex(f.str("bytes")), decoded.bytes)
    }

    @Test
    fun sealedFileOpensWebCiphertext() {
        val s = vectors.getAsJsonObject("sealedFile")
        val opened = OmemoFileEnvelope.open(hex(s.str("ciphertext")), s.str("keyMaterialB64"))
        assertEquals(s.str("openedName"), opened.meta.originalname)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), opened.bytes)
    }

    @Test
    fun sealedMediaBodyRoundTrips() {
        val body = OmemoFileEnvelope.sealedMediaBody(listOf("AAAA+/==", "BBBB"))
        val parsed = OmemoFileEnvelope.parseSealedMedia(body)!!
        assertEquals(listOf("AAAA+/==", "BBBB"), parsed.keys)
        assertNull(OmemoFileEnvelope.parseSealedMedia("media"))
        assertNull(OmemoFileEnvelope.parseSealedMedia("{\"v\":2,\"keys\":[\"a\"]}"))
    }

    @Test
    fun sealAndOpenRoundTrip() {
        val bytes = "hello attachment".toByteArray()
        val sealed = OmemoFileEnvelope.seal(bytes, "text/plain", "notes.txt")
        val opened = OmemoFileEnvelope.open(sealed.ciphertext, sealed.keyMaterial)
        assertEquals("text/plain", opened.meta.mimetype)
        assertEquals("notes.txt", opened.meta.originalname)
        assertArrayEquals(bytes, opened.bytes)
        assertEquals(32, sealed.filename.length)
    }

    private fun identityOf(seed: ByteArray) = OmemoRatchet.Identity(seed, OmemoCrypto.ed25519Public(seed))
}
