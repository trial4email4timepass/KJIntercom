package com.khalsa.intercom.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Family code, key derivation, encryption and small message formats for AWAY mode.
 * Wire-compatible with v2.2 for presence and chat.
 */
object AwayProtocol {
    const val TOPIC_PREFIX = "khalsa-intercom/v2/"
    const val CODE_LENGTH = 10
    const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val PRESENCE_MS = 10_000L
    const val ONLINE_TTL_MS = 25_000L
    const val MAX_CHAT_CHARS = 500
    const val REPLAY_WINDOW_MS = 120_000L

    private const val PBKDF2_ITERATIONS = 20_000
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val MAX_NAME_BYTES = 48
    private const val MSG_ID_BYTES = 16
    private const val ENVELOPE_BYTES = 8 + MSG_ID_BYTES
    private val SALT = "khalsa-intercom/v2/family-salt".toByteArray(Charsets.UTF_8)

    private fun utf8Prefix(s: String, maxBytes: Int): ByteArray {
        val raw = s.toByteArray(Charsets.UTF_8)
        if (raw.size <= maxBytes) return raw
        var end = maxBytes
        while (end > 0 && (raw[end].toInt() and 0xC0) == 0x80) end--
        return raw.copyOf(end)
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format(Locale.ROOT, "%02x", b.toInt() and 0xFF))
        return sb.toString()
    }

    fun normalizeCode(raw: String): String =
        raw.uppercase(Locale.ROOT).replace(" ", "").replace("-", "")

    fun isValidCode(code: String): Boolean =
        code.length == CODE_LENGTH && code.all { CODE_ALPHABET.indexOf(it) >= 0 }

    fun generateCode(random: SecureRandom): String {
        val sb = StringBuilder(CODE_LENGTH)
        repeat(CODE_LENGTH) { sb.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) }
        return sb.toString()
    }

    fun displayCode(code: String): String =
        if (code.length != CODE_LENGTH) code else code.substring(0, 5) + " " + code.substring(5)

    /** Topic prefix for a family. Hashed so the relay never sees the code itself. */
    fun topicRoot(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(code.toByteArray(Charsets.UTF_8))
        return TOPIC_PREFIX + hex(digest).substring(0, 32)
    }

    fun deriveKey(code: String): SecretKeySpec {
        val spec = PBEKeySpec(code.toCharArray(), SALT, PBKDF2_ITERATIONS, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    fun encrypt(key: SecretKeySpec, plain: ByteArray, random: SecureRandom): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(plain)
    }

    fun decrypt(key: SecretKeySpec, sealed: ByteArray): ByteArray? {
        if (sealed.size <= GCM_IV_BYTES) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, sealed.copyOfRange(0, GCM_IV_BYTES)))
            cipher.doFinal(sealed, GCM_IV_BYTES, sealed.size - GCM_IV_BYTES)
        } catch (e: Exception) {
            null
        }
    }

    class FreshPayload(val timestampMs: Long, val messageId: ByteArray, val body: ByteArray)

    /** Prepends a timestamp and random message id so receivers can reject replays. */
    fun wrapFresh(body: ByteArray, random: SecureRandom, nowMs: Long): ByteArray {
        val id = ByteArray(MSG_ID_BYTES)
        random.nextBytes(id)
        val buf = ByteBuffer.allocate(body.size + ENVELOPE_BYTES).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(nowMs)
        buf.put(id)
        buf.put(body)
        return buf.array()
    }

    fun unwrapFresh(plain: ByteArray, nowMs: Long): FreshPayload? {
        if (plain.size < ENVELOPE_BYTES) return null
        val ts = ByteBuffer.wrap(plain).order(ByteOrder.BIG_ENDIAN).getLong(0)
        if (Math.abs(nowMs - ts) > REPLAY_WINDOW_MS) return null
        return FreshPayload(ts, plain.copyOfRange(8, ENVELOPE_BYTES), plain.copyOfRange(ENVELOPE_BYTES, plain.size))
    }

    /** Remembers recent message ids per sender; each id is accepted once. */
    class ReplayGuard {
        private val seen = HashMap<Int, LinkedHashSet<String>>()

        @Synchronized
        fun accept(senderId: Int, messageId: ByteArray): Boolean {
            val set = seen.getOrPut(senderId) { LinkedHashSet() }
            if (!set.add(hex(messageId))) return false
            while (set.size > MAX_IDS_PER_SENDER) {
                val it = set.iterator()
                it.next()
                it.remove()
            }
            return true
        }

        companion object {
            const val MAX_IDS_PER_SENDER = 256
        }
    }

    fun encodePresence(deviceId: Int, name: String): ByteArray {
        val nameBytes = utf8Prefix(name, MAX_NAME_BYTES)
        val buf = ByteBuffer.allocate(nameBytes.size + 4).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(deviceId)
        buf.put(nameBytes)
        return buf.array()
    }

    fun decodePresence(bytes: ByteArray): Pair<Int, String>? {
        if (bytes.size < 4) return null
        val id = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(0)
        return id to String(bytes, 4, bytes.size - 4, Charsets.UTF_8)
    }

    class ChatPayload(val senderId: Int, val targetId: Int, val senderName: String, val text: String)

    fun encodeChat(senderId: Int, targetId: Int, senderName: String, text: String): ByteArray {
        val nameBytes = utf8Prefix(senderName, MAX_NAME_BYTES)
        val textBytes = text.take(MAX_CHAT_CHARS).toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(9 + nameBytes.size + textBytes.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(senderId)
        buf.putInt(targetId)
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        buf.put(textBytes)
        return buf.array()
    }

    fun decodeChat(bytes: ByteArray): ChatPayload? {
        if (bytes.size < 9) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val senderId = buf.getInt(0)
        val targetId = buf.getInt(4)
        val nameLen = bytes[8].toInt() and 0xFF
        if (bytes.size < 9 + nameLen) return null
        val name = String(bytes, 9, nameLen, Charsets.UTF_8)
        val text = String(bytes, 9 + nameLen, bytes.size - 9 - nameLen, Charsets.UTF_8)
        return ChatPayload(senderId, targetId, name, text)
    }
}
