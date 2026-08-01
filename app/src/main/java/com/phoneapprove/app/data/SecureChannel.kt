package com.phoneapprove.app.data

import java.io.BufferedReader
import java.io.OutputStream
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class HandshakeException(message: String) : Exception(message)

/**
 * Kotlin mirror of daemon/secure_channel.py - has to match it byte-for-byte
 * (curve, public-key point encoding, HKDF construction, nonce scheme) or
 * the two sides derive different keys and every message fails to decrypt.
 * See that file for the full rationale: TCP has no equivalent of
 * Bluetooth's physical-proximity barrier, so this link needs its own
 * encryption now that anyone on the LAN can otherwise sniff it in the
 * clear. Built entirely on java.security/javax.crypto - no extra
 * dependency needed here, unlike the daemon's `cryptography` package.
 */
class SecureChannel private constructor(
    private val reader: BufferedReader,
    private val output: OutputStream,
    private val sendKey: ByteArray,
    private val recvKey: ByteArray,
) {
    private var sendCounter = 0L
    private var recvCounter = 0L
    private val sendLock = Any()

    fun sendLine(data: ByteArray) {
        // Locked end-to-end: concurrent callers interleaving encrypt+write
        // would both scramble the line framing and use nonces out of order.
        synchronized(sendLock) {
            val nonce = counterNonce(sendCounter)
            sendCounter++
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(128, nonce))
            val ciphertext = cipher.doFinal(data)
            output.write((bytesToHex(ciphertext) + "\n").toByteArray(Charsets.US_ASCII))
            output.flush()
        }
    }

    /** Returns decrypted bytes, or null on clean EOF. IOExceptions (including
     * a read timeout) propagate rather than collapsing to null, matching
     * the daemon side's recv_line contract. */
    fun recvLine(): ByteArray? {
        val line = reader.readLine() ?: return null
        val nonce = counterNonce(recvCounter)
        recvCounter++
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        return try {
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(recvKey, "AES"), GCMParameterSpec(128, nonce))
            cipher.doFinal(hexToBytes(line))
        } catch (e: Exception) {
            throw HandshakeException("decrypt failed (wrong token, or tampering): $e")
        }
    }

    companion object {
        private const val CURVE_NAME = "secp256r1"

        /** Client speaks first: send our public key, then read the peer's. */
        fun clientHandshake(reader: BufferedReader, output: OutputStream, token: String): SecureChannel {
            val (priv, pub) = generateKeyPair()
            writeKexLine(output, pub)
            val peerPub = readKexLine(reader)
            val shared = computeSharedSecret(priv, peerPub)
            val (c2s, s2c) = deriveKeys(shared, token)
            return SecureChannel(reader, output, sendKey = c2s, recvKey = s2c)
        }

        /** Server speaks second: read the peer's public key, then reply. */
        fun serverHandshake(reader: BufferedReader, output: OutputStream, token: String): SecureChannel {
            val (priv, pub) = generateKeyPair()
            val peerPub = readKexLine(reader)
            writeKexLine(output, pub)
            val shared = computeSharedSecret(priv, peerPub)
            val (c2s, s2c) = deriveKeys(shared, token)
            return SecureChannel(reader, output, sendKey = s2c, recvKey = c2s)
        }

        private fun generateKeyPair(): Pair<PrivateKey, PublicKey> {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec(CURVE_NAME))
            val kp = kpg.generateKeyPair()
            return kp.private to kp.public
        }

        private fun ecParams(): ECParameterSpec {
            val params = AlgorithmParameters.getInstance("EC")
            params.init(ECGenParameterSpec(CURVE_NAME))
            return params.getParameterSpec(ECParameterSpec::class.java)
        }

        /** Raw uncompressed SEC1 point: 0x04 || X(32 bytes) || Y(32 bytes) -
         * matches what Python's cryptography library produces/expects via
         * Encoding.X962 + PublicFormat.UncompressedPoint. */
        private fun encodePoint(pub: PublicKey): ByteArray {
            val ecPub = pub as ECPublicKey
            val x = fixedLength(ecPub.w.affineX, 32)
            val y = fixedLength(ecPub.w.affineY, 32)
            return byteArrayOf(0x04) + x + y
        }

        private fun decodePoint(bytes: ByteArray): PublicKey {
            if (bytes.size != 65 || bytes[0] != 0x04.toByte()) {
                throw HandshakeException("bad EC point encoding (${bytes.size} bytes)")
            }
            val x = BigInteger(1, bytes.copyOfRange(1, 33))
            val y = BigInteger(1, bytes.copyOfRange(33, 65))
            val spec = ECPublicKeySpec(ECPoint(x, y), ecParams())
            return KeyFactory.getInstance("EC").generatePublic(spec)
        }

        /** ECDH output is the raw X-coordinate of the shared point, per SEC1/
         * JCA convention - matches Python's exchange() output exactly. */
        private fun computeSharedSecret(priv: PrivateKey, peerPub: PublicKey): ByteArray {
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(priv)
            ka.doPhase(peerPub, true)
            return fixedLength(BigInteger(1, ka.generateSecret()), 32)
        }

        /** RFC 5869 HKDF, collapsed to the one-block case (our output is
         * exactly 32 bytes = SHA-256's size, so Expand is a single HMAC
         * call): PRK = HMAC-SHA256(key=token, msg=shared_secret); each key
         * is HMAC-SHA256(PRK, info || 0x01). Must match secure_channel.py's
         * _derive_keys exactly. */
        private fun deriveKeys(sharedSecret: ByteArray, token: String): Pair<ByteArray, ByteArray> {
            val prk = hmacSha256(token.toByteArray(Charsets.UTF_8), sharedSecret)
            val c2s = hmacSha256(prk, "c2s".toByteArray(Charsets.UTF_8) + byteArrayOf(1))
            val s2c = hmacSha256(prk, "s2c".toByteArray(Charsets.UTF_8) + byteArrayOf(1))
            return c2s to s2c
        }

        private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        /** Unsigned big-endian encoding of `value`, exactly `length` bytes -
         * BigInteger.toByteArray() is two's-complement and can carry a
         * leading zero sign byte (or, for a value with fewer significant
         * bytes, be shorter), so strip/pad accordingly. */
        private fun fixedLength(value: BigInteger, length: Int): ByteArray {
            val raw = value.toByteArray()
            val trimmed = if (raw.size > length) raw.copyOfRange(raw.size - length, raw.size) else raw
            return if (trimmed.size < length) ByteArray(length - trimmed.size) + trimmed else trimmed
        }

        private fun writeKexLine(output: OutputStream, pub: PublicKey) {
            val json = "{\"type\":\"kex\",\"pub\":\"${bytesToHex(encodePoint(pub))}\"}\n"
            output.write(json.toByteArray(Charsets.UTF_8))
            output.flush()
        }

        private fun readKexLine(reader: BufferedReader): PublicKey {
            val line = reader.readLine() ?: throw HandshakeException("connection closed during key exchange")
            val hex = Regex("\"pub\"\\s*:\\s*\"([0-9a-fA-F]+)\"").find(line)?.groupValues?.get(1)
                ?: throw HandshakeException("expected kex message, got: $line")
            return decodePoint(hexToBytes(hex))
        }

        private fun counterNonce(counter: Long): ByteArray {
            val nonce = ByteArray(12)
            for (i in 0 until 8) {
                nonce[11 - i] = ((counter shr (8 * i)) and 0xFF).toByte()
            }
            return nonce
        }

        private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }
}
