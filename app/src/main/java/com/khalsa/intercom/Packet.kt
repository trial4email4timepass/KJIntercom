package com.khalsa.intercom

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Everything is end-to-end encrypted with a key derived from the pair code, so the
 * relay server (or anyone else listening on it) only sees random bytes.
 */
class PairCrypto(pairCode: String) {
    val topic: String
    private val key: SecretKeySpec
    private val random = SecureRandom()

    init {
        val code = Prefs.normalizeCode(pairCode)
        val digest = MessageDigest.getInstance("SHA-256").digest("kjintercom-topic:$code".toByteArray())
        topic = "kjintercom/v1/" + digest.joinToString("") { "%02x".format(it) }.take(40)

        val spec = PBEKeySpec(code.toCharArray(), "kjintercom-v1-key".toByteArray(), 20000, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        key = SecretKeySpec(raw, "AES")
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    /** Returns null if the data was not encrypted with our pair code (or was tampered with). */
    fun decrypt(data: ByteArray): ByteArray? = try {
        if (data.size < 12 + 16) null else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
            c.doFinal(data, 12, data.size - 12)
        }
    } catch (e: Exception) {
        null
    }
}

/** Plain-text message format (before encryption). */
data class Packet(
    val type: Int,
    val msgId: Long,
    val senderId: String,
    val senderName: String,
    val timestamp: Long,
    val ackOf: Long = 0L,
    val audio: ByteArray = ByteArray(0),
) {
    companion object {
        const val TYPE_AUDIO = 1
        const val TYPE_ACK = 2   // "your message was played"
        const val TYPE_PING = 3  // presence / connection test
        private const val MAGIC = 0x4B4A // "KJ"

        fun parse(bytes: ByteArray): Packet? = try {
            DataInputStream(ByteArrayInputStream(bytes)).use { i ->
                if (i.readUnsignedShort() != MAGIC) return null
                val type = i.readUnsignedByte()
                val msgId = i.readLong()
                val sender = i.readUTF()
                val name = i.readUTF()
                val ts = i.readLong()
                val ackOf = i.readLong()
                val audio = i.readBytes()
                Packet(type, msgId, sender, name, ts, ackOf, audio)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun toBytes(): ByteArray {
        val bos = ByteArrayOutputStream(audio.size + 128)
        DataOutputStream(bos).use { o ->
            o.writeShort(MAGIC)
            o.writeByte(type)
            o.writeLong(msgId)
            o.writeUTF(senderId)
            o.writeUTF(senderName.take(40))
            o.writeLong(timestamp)
            o.writeLong(ackOf)
            o.write(audio)
        }
        return bos.toByteArray()
    }
}
