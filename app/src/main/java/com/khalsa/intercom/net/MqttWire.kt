package com.khalsa.intercom.net

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/** Minimal MQTT 3.1.1 framing: CONNECT, PUBLISH (QoS 0), SUBSCRIBE, PING, DISCONNECT. */
object MqttWire {
    const val T_CONNACK = 2
    const val T_PUBLISH = 3
    const val T_SUBACK = 9
    const val T_PINGRESP = 13
    private const val MAX_PACKET_BYTES = 1_048_576

    class Packet(
        val type: Int,
        val topic: String? = null,
        val payload: ByteArray = ByteArray(0),
        val connackCode: Int = 0,
        val packetId: Int = 0
    )

    fun encodeRemainingLength(x: Int): ByteArray {
        var v = x
        val out = ByteArrayOutputStream()
        do {
            var b = v % 128
            v /= 128
            if (v > 0) b = b or 0x80
            out.write(b)
        } while (v > 0)
        return out.toByteArray()
    }

    private fun writeUtf8(out: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        out.write((b.size shr 8) and 0xFF)
        out.write(b.size and 0xFF)
        out.write(b)
    }

    private fun frame(headerByte: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size + 5)
        out.write(headerByte)
        out.write(encodeRemainingLength(body.size))
        out.write(body)
        return out.toByteArray()
    }

    fun encodeConnect(clientId: String, keepAliveSec: Int): ByteArray {
        val body = ByteArrayOutputStream()
        writeUtf8(body, "MQTT")
        body.write(4) // protocol level 3.1.1
        body.write(2) // clean session
        body.write((keepAliveSec shr 8) and 0xFF)
        body.write(keepAliveSec and 0xFF)
        writeUtf8(body, clientId)
        return frame(0x10, body.toByteArray())
    }

    fun encodePublish(topic: String, payload: ByteArray): ByteArray {
        val body = ByteArrayOutputStream(payload.size + topic.length + 2)
        writeUtf8(body, topic)
        body.write(payload)
        return frame(0x30, body.toByteArray())
    }

    fun encodeSubscribe(packetId: Int, topics: List<String>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write((packetId shr 8) and 0xFF)
        body.write(packetId and 0xFF)
        for (t in topics) {
            writeUtf8(body, t)
            body.write(0)
        }
        return frame(0x82, body.toByteArray())
    }

    fun encodePingreq(): ByteArray = byteArrayOf(0xC0.toByte(), 0)

    fun encodeDisconnect(): ByteArray = byteArrayOf(0xE0.toByte(), 0)

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException("stream closed")
            off += n
        }
    }

    private fun readRemainingLength(input: InputStream): Int {
        var multiplier = 1
        var value = 0
        var b: Int
        do {
            b = input.read()
            if (b < 0) throw EOFException("stream closed")
            value += (b and 127) * multiplier
            if (multiplier > 128 * 128 * 128) throw IllegalStateException("malformed remaining length")
            multiplier *= 128
        } while ((b and 128) != 0)
        return value
    }

    /** Reads one packet, or returns null at a clean end of stream. */
    fun readPacket(input: InputStream): Packet? {
        val first = input.read()
        if (first < 0) return null
        val type = (first shr 4) and 15
        val flags = first and 15
        val remaining = readRemainingLength(input)
        if (remaining > MAX_PACKET_BYTES) throw IllegalStateException("oversized packet")
        val body = ByteArray(remaining)
        readFully(input, body)
        return when (type) {
            T_CONNACK -> Packet(type, connackCode = if (body.size >= 2) body[1].toInt() and 0xFF else -1)
            T_PUBLISH -> {
                if (body.size < 2) throw IllegalStateException("short publish")
                val topicLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                if (body.size < topicLen + 2) throw IllegalStateException("short publish topic")
                val topic = String(body, 2, topicLen, Charsets.UTF_8)
                var off = topicLen + 2
                var pid = 0
                if (((flags shr 1) and 3) > 0) {
                    if (body.size < off + 2) throw IllegalStateException("short publish id")
                    pid = ((body[off].toInt() and 0xFF) shl 8) or (body[off + 1].toInt() and 0xFF)
                    off += 2
                }
                Packet(type, topic, body.copyOfRange(off, body.size), packetId = pid)
            }
            T_SUBACK -> Packet(
                type,
                payload = if (body.size > 2) body.copyOfRange(2, body.size) else ByteArray(0),
                packetId = if (body.size >= 2) ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF) else 0
            )
            else -> Packet(type, payload = body)
        }
    }
}
