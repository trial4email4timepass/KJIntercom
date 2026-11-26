package com.khalsa.intercom.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Same-Wi-Fi (multicast) packet format. Wire-compatible with v2.2. */
object IntercomProtocol {
    const val GROUP = "239.255.42.99"
    const val PORT = 47778
    const val VERSION = 1
    const val HEADER = 12
    const val SAMPLE_RATE = 16000
    const val FRAME_SAMPLES = 320
    const val FRAME_BYTES = 640
    const val PEER_TTL_MS = 7000L
    const val BEACON_MS = 2000L
    const val T_BEACON: Byte = 1
    const val T_AUDIO: Byte = 2
    const val T_BYE: Byte = 3
    const val T_AUDIO_PRIVATE: Byte = 4
    const val T_CHAT: Byte = 5
    const val MAX_CHAT_CHARS = 500

    class Packet(
        val type: Byte,
        val senderId: Int,
        val seq: Int,
        val payload: ByteArray,
        val targetId: Int = 0,
        val text: String = ""
    )

    private fun base(type: Byte, senderId: Int, seq: Int, payloadLen: Int): ByteBuffer {
        val buf = ByteBuffer.allocate(payloadLen + HEADER).order(ByteOrder.BIG_ENDIAN)
        buf.put('K'.code.toByte())
        buf.put('I'.code.toByte())
        buf.put(VERSION.toByte())
        buf.put(type)
        buf.putInt(senderId)
        buf.putInt(seq)
        return buf
    }

    private fun utf8Prefix(s: String, maxBytes: Int): ByteArray {
        val raw = s.toByteArray(Charsets.UTF_8)
        if (raw.size <= maxBytes) return raw
        var end = maxBytes
        while (end > 0 && (raw[end].toInt() and 0xC0) == 0x80) end--
        return raw.copyOf(end)
    }

    fun encodeBeacon(senderId: Int, name: String): ByteArray {
        val nameBytes = utf8Prefix(name, 48)
        return base(T_BEACON, senderId, 0, nameBytes.size).put(nameBytes).array()
    }

    fun encodeAudio(senderId: Int, seq: Int, pcm: ByteArray, len: Int): ByteArray =
        base(T_AUDIO, senderId, seq, len).put(pcm, 0, len).array()

    fun encodeAudioPrivate(senderId: Int, seq: Int, targetId: Int, pcm: ByteArray, len: Int): ByteArray =
        base(T_AUDIO_PRIVATE, senderId, seq, len + 4).putInt(targetId).put(pcm, 0, len).array()

    fun encodeChat(senderId: Int, seq: Int, targetId: Int, text: String): ByteArray {
        val raw = text.take(MAX_CHAT_CHARS).toByteArray(Charsets.UTF_8)
        return base(T_CHAT, senderId, seq, raw.size + 4).putInt(targetId).put(raw).array()
    }

    fun encodeBye(senderId: Int): ByteArray = base(T_BYE, senderId, 0, 0).array()

    fun decode(data: ByteArray, len: Int): Packet? {
        if (len < HEADER || data[0] != 'K'.code.toByte() || data[1] != 'I'.code.toByte() || data[2] != VERSION.toByte()) {
            return null
        }
        val buf = ByteBuffer.wrap(data, 0, len).order(ByteOrder.BIG_ENDIAN)
        val type = buf.get(3)
        val sender = buf.getInt(4)
        val seq = buf.getInt(8)
        if (type == T_AUDIO_PRIVATE || type == T_CHAT) {
            if (len < 16) return null
            val target = buf.getInt(12)
            val rest = data.copyOfRange(16, len)
            return if (type == T_CHAT) {
                Packet(type, sender, seq, rest, target, String(rest, Charsets.UTF_8))
            } else {
                Packet(type, sender, seq, rest, target)
            }
        }
        return Packet(type, sender, seq, data.copyOfRange(HEADER, len))
    }
}

/** Phones seen on the same Wi-Fi. */
class PeerTable {
    data class Peer(val id: Int, var name: String, var lastSeenMs: Long, var lastAudioMs: Long = 0L)

    private val peers = LinkedHashMap<Int, Peer>()

    /** Returns true when the visible list changed. */
    @Synchronized
    fun onBeacon(id: Int, name: String, now: Long): Boolean {
        val p = peers[id]
        if (p == null) {
            peers[id] = Peer(id, name, now)
            return true
        }
        val changed = p.name != name
        p.name = name
        p.lastSeenMs = now
        return changed
    }

    @Synchronized
    fun onAudio(id: Int, now: Long) {
        val p = peers[id]
        if (p == null) {
            peers[id] = Peer(id, "A phone", now, now)
        } else {
            p.lastAudioMs = now
            p.lastSeenMs = now
        }
    }

    @Synchronized
    fun onChat(id: Int, now: Long) {
        val p = peers[id]
        if (p == null) peers[id] = Peer(id, "A phone", now) else p.lastSeenMs = now
    }

    @Synchronized
    fun onBye(id: Int): Boolean = peers.remove(id) != null

    @Synchronized
    fun prune(now: Long): Boolean {
        var changed = false
        val it = peers.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.lastSeenMs > IntercomProtocol.PEER_TTL_MS) {
                it.remove()
                changed = true
            }
        }
        return changed
    }

    @Synchronized
    fun snapshot(): List<Peer> = peers.values.map { it.copy() }
}
