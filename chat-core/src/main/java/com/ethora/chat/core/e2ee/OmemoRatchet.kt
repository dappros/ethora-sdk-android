package com.ethora.chat.core.e2ee

import com.ethora.chat.core.e2ee.OmemoCrypto.bytesEqual
import com.ethora.chat.core.e2ee.OmemoCrypto.concat

/**
 * X3DH and the Double Ratchet as profiled by OMEMO 2 (XEP-0384 section 6-7),
 * plus the message payload layer (section 4.4). Port of web `src/e2ee/ratchet.ts`.
 */
object OmemoRatchet {
    /** Max message keys skipped in one chain, and kept in total. */
    const val MAX_SKIP = 1000
    private val X3DH_PREFIX = ByteArray(32) { 0xff.toByte() }

    /** Identity key of our own device: the Ed25519 seed plus its public key. */
    class Identity(val seed: ByteArray, val pub: ByteArray)

    /** A peer device's published bundle. */
    class Bundle(
        val ik: ByteArray,
        val spkId: Long,
        val spk: ByteArray,
        val spkSignature: ByteArray,
        val pkId: Long,
        val pk: ByteArray
    )

    /** Key exchange we still owe the peer: repeated on every message until it answers. */
    class PendingKex(val pkId: Long, val spkId: Long, val ek: ByteArray)

    /**
     * Double Ratchet state. Mutable so it can be deep-copied, persisted and
     * restored; the ratchet functions below always work on a copy and return it.
     */
    class Session(
        var rk: ByteArray,
        var dhs: OmemoCrypto.KeyPair,
        var dhr: ByteArray? = null,
        var cks: ByteArray? = null,
        var ckr: ByteArray? = null,
        var ns: Int = 0,
        var nr: Int = 0,
        var pn: Int = 0,
        /** Message keys of skipped messages, keyed `base64(dhPub):n`, oldest first. */
        var skipped: LinkedHashMap<String, ByteArray> = LinkedHashMap(),
        /** X3DH associated data: initiator IK || responder IK. */
        var ad: ByteArray,
        /** The peer device's Ed25519 identity key. */
        var peerIk: ByteArray,
        var pendingKex: PendingKex? = null,
        /** Ephemeral key of the key exchange that created this session (responder side). */
        var kexEk: ByteArray? = null
    ) {
        fun copy(): Session = Session(
            rk = rk.copyOf(),
            dhs = OmemoCrypto.KeyPair(dhs.priv.copyOf(), dhs.pub.copyOf()),
            dhr = dhr?.copyOf(),
            cks = cks?.copyOf(),
            ckr = ckr?.copyOf(),
            ns = ns,
            nr = nr,
            pn = pn,
            skipped = LinkedHashMap(skipped.mapValues { it.value.copyOf() }),
            ad = ad.copyOf(),
            peerIk = peerIk.copyOf(),
            pendingKex = pendingKex?.let { PendingKex(it.pkId, it.spkId, it.ek.copyOf()) },
            kexEk = kexEk?.copyOf()
        )
    }

    private fun kdfRk(rk: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
        val out = OmemoCrypto.hkdfSha256(dhOut, rk, "OMEMO Root Chain", 64)
        return out.copyOfRange(0, 32) to out.copyOfRange(32, 64)
    }

    /** Returns (next chain key, message key). */
    private fun kdfCk(ck: ByteArray): Pair<ByteArray, ByteArray> =
        OmemoCrypto.hmacSha256(ck, byteArrayOf(0x02)) to OmemoCrypto.hmacSha256(ck, byteArrayOf(0x01))

    private class MessageKeys(val enc: ByteArray, val auth: ByteArray, val iv: ByteArray)

    private fun messageKeys(mk: ByteArray): MessageKeys {
        val km = OmemoCrypto.hkdfSha256(mk, null, "OMEMO Message Key Material", 80)
        return MessageKeys(km.copyOfRange(0, 32), km.copyOfRange(32, 64), km.copyOfRange(64, 80))
    }

    private fun x3dhSecret(vararg dhs: ByteArray): ByteArray =
        OmemoCrypto.hkdfSha256(concat(X3DH_PREFIX, *dhs), null, "OMEMO X3DH", 32)

    /** Starts a session with a device from its published bundle (X3DH, active side). */
    fun initiateSession(own: Identity, bundle: Bundle): Session {
        if (!OmemoCrypto.ed25519Verify(bundle.spkSignature, bundle.spk, bundle.ik)) {
            throw IllegalArgumentException("Invalid signed prekey signature")
        }
        val ek = OmemoCrypto.generateKeyPair()
        val sk = x3dhSecret(
            OmemoCrypto.dh(OmemoCrypto.identityDhPrivate(own.seed), bundle.spk),
            OmemoCrypto.dh(ek.priv, OmemoCrypto.identityDhPublic(bundle.ik)),
            OmemoCrypto.dh(ek.priv, bundle.spk),
            OmemoCrypto.dh(ek.priv, bundle.pk)
        )
        val dhs = OmemoCrypto.generateKeyPair()
        val (rk, cks) = kdfRk(sk, OmemoCrypto.dh(dhs.priv, bundle.spk))
        return Session(
            rk = rk,
            dhs = dhs,
            dhr = bundle.spk,
            cks = cks,
            ad = concat(own.pub, bundle.ik),
            peerIk = bundle.ik,
            pendingKex = PendingKex(bundle.pkId, bundle.spkId, ek.pub)
        )
    }

    /** Builds the session from a received key exchange (X3DH, passive side). */
    fun acceptSession(
        own: Identity,
        spk: OmemoCrypto.KeyPair,
        pk: OmemoCrypto.KeyPair,
        peerIk: ByteArray,
        ek: ByteArray
    ): Session {
        val sk = x3dhSecret(
            OmemoCrypto.dh(spk.priv, OmemoCrypto.identityDhPublic(peerIk)),
            OmemoCrypto.dh(OmemoCrypto.identityDhPrivate(own.seed), ek),
            OmemoCrypto.dh(spk.priv, ek),
            OmemoCrypto.dh(pk.priv, ek)
        )
        return Session(
            rk = sk,
            dhs = spk,
            ad = concat(peerIk, own.pub),
            peerIk = peerIk,
            kexEk = ek
        )
    }

    /** Encrypts with the sending chain; returns the encoded OMEMOAuthenticatedMessage. */
    fun ratchetEncrypt(session: Session, plaintext: ByteArray): Pair<Session, ByteArray> {
        val cks = session.cks ?: throw IllegalStateException("Session cannot send yet")
        val s = session.copy()
        val (nextCk, mk) = kdfCk(cks)
        s.cks = nextCk
        val keys = messageKeys(mk)
        val message = OmemoProtobuf.encodeMessage(
            OmemoProtobuf.Message(
                n = s.ns.toLong(),
                pn = s.pn.toLong(),
                dhPub = s.dhs.pub,
                ciphertext = OmemoCrypto.aesEncrypt(keys.enc, keys.iv, plaintext)
            )
        )
        s.ns++
        val mac = OmemoCrypto.hmacSha256(keys.auth, concat(s.ad, message)).copyOfRange(0, 16)
        return s to OmemoProtobuf.encodeAuthenticated(OmemoProtobuf.Authenticated(mac, message))
    }

    /**
     * Decrypts an encoded OMEMOAuthenticatedMessage. The input session is left
     * untouched; the returned one must be stored only after the whole message
     * was processed successfully.
     */
    fun ratchetDecrypt(session: Session, data: ByteArray): Pair<Session, ByteArray> {
        val authenticated = OmemoProtobuf.decodeAuthenticated(data)
        val header = OmemoProtobuf.decodeMessage(authenticated.message)
        val ciphertext = header.ciphertext ?: throw IllegalArgumentException("Message without ciphertext")
        val s = session.copy()

        fun decrypt(mk: ByteArray): ByteArray {
            val keys = messageKeys(mk)
            val expected = OmemoCrypto.hmacSha256(keys.auth, concat(s.ad, authenticated.message)).copyOfRange(0, 16)
            if (!bytesEqual(expected, authenticated.mac)) throw IllegalArgumentException("Message authentication failed")
            return OmemoCrypto.aesDecrypt(keys.enc, keys.iv, ciphertext)
        }

        val skippedId = "${OmemoCrypto.toBase64(header.dhPub)}:${header.n}"
        s.skipped.remove(skippedId)?.let { return s to decrypt(it) }

        val dhr = s.dhr
        if (dhr == null || !bytesEqual(header.dhPub, dhr)) {
            skipMessageKeys(s, header.pn)
            s.pn = s.ns
            s.ns = 0
            s.nr = 0
            s.dhr = header.dhPub
            val (rkA, ckr) = kdfRk(s.rk, OmemoCrypto.dh(s.dhs.priv, header.dhPub))
            s.rk = rkA
            s.ckr = ckr
            s.dhs = OmemoCrypto.generateKeyPair()
            val (rkB, cks) = kdfRk(s.rk, OmemoCrypto.dh(s.dhs.priv, header.dhPub))
            s.rk = rkB
            s.cks = cks
        }

        skipMessageKeys(s, header.n)
        val ckr = s.ckr ?: throw IllegalStateException("No receiving chain")
        val (nextCkr, mk) = kdfCk(ckr)
        s.ckr = nextCkr
        s.nr++
        return s to decrypt(mk)
    }

    private fun skipMessageKeys(s: Session, until: Long) {
        val ckr = s.ckr ?: return
        val dhr = s.dhr ?: return
        if (until - s.nr > MAX_SKIP) throw IllegalArgumentException("Too many skipped messages")
        val dhrB64 = OmemoCrypto.toBase64(dhr)
        var chain = ckr
        while (s.nr < until) {
            val (next, mk) = kdfCk(chain)
            chain = next
            s.skipped["$dhrB64:${s.nr}"] = mk
            s.nr++
        }
        s.ckr = chain
        val keys = s.skipped.keys.toList()
        for (id in keys.take(maxOf(0, keys.size - MAX_SKIP))) s.skipped.remove(id)
    }

    // -- Message payload (XEP-0384 section 4.4) ----------------------------

    private class PayloadKeys(val enc: ByteArray, val auth: ByteArray, val iv: ByteArray)

    private fun payloadKeys(key: ByteArray): PayloadKeys {
        val km = OmemoCrypto.hkdfSha256(key, null, "OMEMO Payload", 80)
        return PayloadKeys(km.copyOfRange(0, 32), km.copyOfRange(32, 64), km.copyOfRange(64, 80))
    }

    /** A sealed payload: `keyMaterial` (key || truncated HMAC, 48 bytes) goes to every recipient device. */
    class Payload(val keyMaterial: ByteArray, val payload: ByteArray)

    fun encryptPayload(plaintext: ByteArray): Payload = encryptPayloadWithKey(OmemoCrypto.randomBytes(32), plaintext)

    /** Same as [encryptPayload] with a caller-chosen key; exists so known-answer tests can pin the output. */
    internal fun encryptPayloadWithKey(key: ByteArray, plaintext: ByteArray): Payload {
        val keys = payloadKeys(key)
        val payload = OmemoCrypto.aesEncrypt(keys.enc, keys.iv, plaintext)
        val mac = OmemoCrypto.hmacSha256(keys.auth, payload).copyOfRange(0, 16)
        return Payload(concat(key, mac), payload)
    }

    fun decryptPayload(keyMaterial: ByteArray, payload: ByteArray): ByteArray {
        if (keyMaterial.size != 48) throw IllegalArgumentException("Invalid key material")
        val keys = payloadKeys(keyMaterial.copyOfRange(0, 32))
        val expected = OmemoCrypto.hmacSha256(keys.auth, payload).copyOfRange(0, 16)
        if (!bytesEqual(expected, keyMaterial.copyOfRange(32, 48))) {
            throw IllegalArgumentException("Payload authentication failed")
        }
        return OmemoCrypto.aesDecrypt(keys.enc, keys.iv, payload)
    }
}
