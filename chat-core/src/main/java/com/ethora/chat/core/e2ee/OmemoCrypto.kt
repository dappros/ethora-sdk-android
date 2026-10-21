package com.ethora.chat.core.e2ee

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Primitives for OMEMO 2, byte-compatible with web `src/e2ee/crypto.ts`.
 *
 * X25519 and Ed25519 come from BouncyCastle's lightweight API (no security
 * provider registration, works from minSdk 26). HKDF is BouncyCastle's
 * RFC 5869 generator, HMAC and AES-CBC are the JCA ones. The Ed25519 to X25519
 * conversions (XEdDSA-style birational map and scalar clamping) are written out
 * here because BouncyCastle does not expose them; known-answer tests pin them
 * to the web output.
 */
object OmemoCrypto {
    private val random = SecureRandom()
    private val zeroSalt = ByteArray(32)

    /** 32-byte X25519 private scalar and its public point. */
    class KeyPair(val priv: ByteArray, val pub: ByteArray)

    /** Ed25519 identity: `seed` is the 32-byte secret, `pub` the public key. */
    class Identity(val seed: ByteArray, val pub: ByteArray)

    fun randomBytes(length: Int): ByteArray = ByteArray(length).also { random.nextBytes(it) }

    fun generateKeyPair(): KeyPair {
        val priv = X25519PrivateKeyParameters(random)
        return KeyPair(priv.encoded, priv.generatePublicKey().encoded)
    }

    fun keyPairFromPrivate(priv: ByteArray): KeyPair =
        KeyPair(priv.copyOf(), x25519Public(priv))

    fun generateIdentity(): Identity {
        val seed = Ed25519PrivateKeyParameters(random)
        return Identity(seed.encoded, seed.generatePublicKey().encoded)
    }

    fun ed25519Public(seed: ByteArray): ByteArray =
        Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    fun x25519Public(priv: ByteArray): ByteArray =
        X25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray?, info: String, length: Int): ByteArray {
        val out = ByteArray(length)
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, salt ?: zeroSalt, info.toByteArray(Charsets.UTF_8)))
        generator.generateBytes(out, 0, length)
        return out
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** AES-256-CBC with PKCS#7 padding (the JCA name is PKCS5, which is the same for AES). */
    fun aesEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    fun aesDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** X25519 agreement. Throws when the peer key is low order (all-zero output). */
    fun dh(priv: ByteArray, pub: ByteArray): ByteArray {
        val out = ByteArray(32)
        try {
            val agreement = X25519Agreement()
            agreement.init(X25519PrivateKeyParameters(priv, 0))
            agreement.calculateAgreement(X25519PublicKeyParameters(pub, 0), out, 0)
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("X25519 agreement failed: ${e.message}", e)
        }
        if (out.all { it == 0.toByte() }) throw IllegalArgumentException("X25519 low-order point")
        return out
    }

    /** X25519 private scalar of an Ed25519 identity: clamp(SHA-512(seed)[0..32]). */
    fun identityDhPrivate(seed: ByteArray): ByteArray {
        val hashed = MessageDigest.getInstance("SHA-512").digest(seed.copyOf(32))
        val scalar = hashed.copyOf(32)
        scalar[0] = (scalar[0].toInt() and 248).toByte()
        scalar[31] = (scalar[31].toInt() and 127).toByte()
        scalar[31] = (scalar[31].toInt() or 64).toByte()
        return scalar
    }

    private val fieldPrime: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))

    /** X25519 public point of an Ed25519 public key: u = (1 + y) / (1 - y) mod p. */
    fun identityDhPublic(edPub: ByteArray): ByteArray {
        require(edPub.size == 32) { "Ed25519 public key must be 32 bytes" }
        val yBytes = edPub.copyOf()
        yBytes[31] = (yBytes[31].toInt() and 0x7f).toByte()
        val y = littleEndianToBigInteger(yBytes)
        val one = BigInteger.ONE
        val denominator = one.subtract(y).mod(fieldPrime)
        if (denominator.signum() == 0) throw IllegalArgumentException("Ed25519 point at infinity")
        val u = one.add(y).mod(fieldPrime).multiply(denominator.modInverse(fieldPrime)).mod(fieldPrime)
        return bigIntegerToLittleEndian(u)
    }

    fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(signature: ByteArray, message: ByteArray, pub: ByteArray): Boolean = try {
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(pub, 0))
        verifier.update(message, 0, message.size)
        verifier.verifySignature(signature)
    } catch (e: RuntimeException) {
        false
    }

    /** Constant-time comparison. */
    fun bytesEqual(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    fun toBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun fromBase64(text: String): ByteArray = Base64.getMimeDecoder().decode(text)

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Uniform random integer in [min, max] (as the web does, modulo bias included). */
    fun randomInt(min: Int, max: Int): Int {
        val n = random.nextInt() and Int.MAX_VALUE
        return min + (n % (max - min + 1))
    }

    private fun littleEndianToBigInteger(bytes: ByteArray): BigInteger =
        BigInteger(1, bytes.reversedArray())

    private fun bigIntegerToLittleEndian(value: BigInteger): ByteArray {
        val big = value.toByteArray() // big-endian, may carry a sign byte
        val out = ByteArray(32)
        for (i in 0 until minOf(32, big.size)) out[i] = big[big.size - 1 - i]
        return out
    }
}
