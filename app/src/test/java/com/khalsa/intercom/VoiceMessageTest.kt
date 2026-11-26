package com.khalsa.intercom

import com.khalsa.intercom.core.AwayProtocol
import com.khalsa.intercom.core.ClipProtocol
import com.khalsa.intercom.core.ImaAdpcm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Random
import kotlin.math.log10
import kotlin.math.sin

class VoiceMessageTest {

    private fun speechLikePcm(seconds: Double, rate: Int = 16000): ByteArray {
        val n = (rate * seconds).toInt()
        val r = Random(1)
        val pcm = ByteArray(n * 2)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val env = 0.5 + 0.5 * sin(2 * Math.PI * 3 * t)
            val s = env * (9000 * sin(2 * Math.PI * 220 * t) + 4000 * sin(2 * Math.PI * 1300 * t) +
                3000 * sin(2 * Math.PI * 3100 * t)) + r.nextGaussian() * 300
            val v = s.toInt().coerceIn(-32768, 32767)
            pcm[2 * i] = v.toByte()
            pcm[2 * i + 1] = (v shr 8).toByte()
        }
        return pcm
    }

    private fun sample(b: ByteArray, i: Int) = ((b[2 * i].toInt() and 0xFF) or (b[2 * i + 1].toInt() shl 8)).toShort().toInt()

    @Test
    fun adpcmRoundTripIsClear() {
        val pcm = speechLikePcm(3.5)
        val enc = ImaAdpcm.encode(pcm, pcm.size)
        assertEquals(ImaAdpcm.HEADER_BYTES + pcm.size / 4, enc.size)
        val dec = ImaAdpcm.decode(enc)
        assertEquals(pcm.size, dec.size)
        var sig = 0.0
        var err = 0.0
        for (i in 0 until pcm.size / 2) {
            val x = sample(pcm, i).toDouble()
            val y = sample(dec, i).toDouble()
            sig += x * x
            err += (x - y) * (x - y)
        }
        assertTrue("SNR too low", 10 * log10(sig / maxOf(err, 1.0)) > 20)
    }

    @Test
    fun adpcmRejectsGarbage() {
        assertEquals(0, ImaAdpcm.decode(byteArrayOf(0, 0, 200.toByte(), 0, 1)).size)
        assertEquals(0, ImaAdpcm.decode(ByteArray(2)).size)
    }

    @Test
    fun clipSurvivesDuplicatesAndReordering() {
        val audio = ByteArray(30 * 8000 + 4).also { Random(2).nextBytes(it) }
        val parts = ClipProtocol.encodeParts(11, 22, 333, 30000, 16000, audio)
        assertEquals(10, parts.size)
        val asm = ClipProtocol.Assembler(90_000)
        val delivered = (parts + parts).shuffled(Random(5)) // two relays, any order
        var completions = 0
        var clip: ClipProtocol.Clip? = null
        for (b in delivered) {
            val c = asm.offer(ClipProtocol.decodePart(b)!!, 1000)
            if (c != null) {
                completions++
                clip = c
            }
        }
        assertEquals(1, completions)
        assertArrayEquals(audio, clip!!.encoded)
        assertEquals(30000, clip.durationMs)
        assertNull(asm.offer(ClipProtocol.decodePart(parts[0])!!, 2000))
    }

    @Test
    fun ackRoundTrip() {
        val ack = ClipProtocol.decodeAck(ClipProtocol.encodeAck(22, 11, 333, ClipProtocol.ACK_PLAYED))!!
        assertEquals(22, ack.senderId)
        assertEquals(11, ack.targetId)
        assertEquals(333, ack.clipId)
        assertEquals(ClipProtocol.ACK_PLAYED, ack.status)
        assertNull(ClipProtocol.decodePart(ClipProtocol.encodeAck(1, 2, 3, 1)))
    }

    @Test
    fun sealedPartsDecryptOnlyWithFamilyKey() {
        val random = SecureRandom()
        val key = AwayProtocol.deriveKey(AwayProtocol.generateCode(random))
        val other = AwayProtocol.deriveKey(AwayProtocol.generateCode(random))
        val body = ClipProtocol.encodeParts(7, 9, 42, 1500, 16000, ByteArray(30000))[0]
        val now = System.currentTimeMillis()
        val sealed = AwayProtocol.encrypt(key, AwayProtocol.wrapFresh(body, random, now), random)
        assertTrue(sealed.size < 64 * 1024)
        val fresh = AwayProtocol.unwrapFresh(AwayProtocol.decrypt(key, sealed)!!, now + 500)
        assertNotNull(fresh)
        assertArrayEquals(body, fresh!!.body)
        assertNull(AwayProtocol.decrypt(other, sealed))
        assertNull(AwayProtocol.unwrapFresh(AwayProtocol.decrypt(key, sealed)!!, now + 10 * 60_000))
    }

    @Test
    fun replayGuardAcceptsOnce() {
        val g = AwayProtocol.ReplayGuard()
        val id = ByteArray(16) { it.toByte() }
        assertTrue(g.accept(5, id))
        assertTrue(!g.accept(5, id))
        assertTrue(g.accept(6, id))
    }
}
