package com.khalsa.intercom

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.khalsa.intercom.core.AwayProtocol
import com.khalsa.intercom.core.ClipProtocol
import com.khalsa.intercom.core.ImaAdpcm
import com.khalsa.intercom.net.MqttClient
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * AWAY mode: talk to family phones anywhere over the internet (mobile data or Wi-Fi).
 *
 * v2.2 streamed live audio in 100 ms pieces through one public relay and tried to play
 * each piece the moment it arrived. On mobile data the pieces arrived, so the app showed
 * the other person talking, but nothing audible came out. This version sends each push-to-talk
 * press as one complete voice message:
 *
 *  1. Record the whole message (up to 30 s), boost quiet audio, compress (IMA ADPCM, 8 KB/s).
 *  2. Encrypt it, split it into 24 KB parts and send it on two independent relays at once.
 *  3. The other phone plays it only after every part has arrived, so mobile-data delays
 *     cannot chop or silence it. It waits if its owner is talking, instead of dropping it.
 *  4. The other phone replies "received" and then "played"; the sender sees ✓ / ✓✓ and
 *     resends once if no receipt comes back.
 */
class AwayEngine(
    private val context: Context,
    private val myId: Int,
    private val myName: () -> String,
    private val isTransmitting: () -> Boolean,
    private val onChat: (senderId: Int, senderName: String, targetId: Int, text: String) -> Unit,
    private val onMicError: (String) -> Unit,
    private val onState: () -> Unit,
    private val onIdCollision: () -> Unit
) {
    data class Member(val id: Int, val name: String, val lastSeenMs: Long, val lastAudioMs: Long)

    companion object {
        /** Two independent public relays. Both phones use both, so they always share one. */
        val RELAYS = listOf("broker.emqx.io" to 8883, "test.mosquitto.org" to 8886)
        const val SAMPLE_RATE = 16000
        const val MAX_CLIP_MS = 30_000
        const val MIN_CLIP_MS = 300
        const val MEMBER_EVICT_MS = 600_000L
        const val MAX_MEMBERS = 64
        const val ACK_WAIT_MS = 15_000L
        const val CLIP_EXPIRE_MS = 120_000L
        private const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1000
        private const val FRAME_BYTES = 640
        private const val SILENCE_SWITCH_MS = 1500
    }

    private class Outgoing(
        val clipId: Int,
        val targetId: Int,
        val durationMs: Int,
        val sealedParts: List<ByteArray>,
        @Volatile var received: Boolean = false,
        @Volatile var resent: Boolean = false
    )

    private val random = SecureRandom()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val members = ConcurrentHashMap<Int, Member>()
    private val replayGuard = AwayProtocol.ReplayGuard()
    private val assembler = ClipProtocol.Assembler(CLIP_EXPIRE_MS)
    private val outgoing = ConcurrentHashMap<Int, Outgoing>()
    private val playQueue = LinkedBlockingQueue<ClipProtocol.Clip>(16)
    private var net: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "away-net").apply { isDaemon = true }
    }

    @Volatile private var running = false
    @Volatile private var code = ""
    @Volatile private var key: SecretKeySpec? = null
    @Volatile private var root = ""
    @Volatile private var clients: List<MqttClient> = emptyList()
    @Volatile private var collisionReported = false
    @Volatile private var recording = false
    @Volatile private var clipGen = 0
    @Volatile private var recordStartMs = 0L
    @Volatile private var playingSender = 0
    @Volatile private var playingUntilMs = 0L
    @Volatile private var status: String? = null
    @Volatile private var statusAtMs = 0L
    @Volatile private var oldVersionNoticeMs = 0L
    private var playThread: Thread? = null
    private var presenceThread: Thread? = null

    val connected: Boolean get() = clients.any { it.connected }
    val relaysUp: Int get() = clients.count { it.connected }
    val ready: Boolean get() = connected && key != null
    val lastError: String? get() = clients.firstNotNullOfOrNull { it.lastError }
    val isRecording: Boolean get() = recording

    /** Latest delivery/playback note for the status line, shown for 20 s. */
    fun statusLine(now: Long): String? {
        if (recording) {
            val secs = ((now - recordStartMs) / 1000).toInt()
            return "Recording message… ${secs}s (release to send, max ${MAX_CLIP_MS / 1000}s)"
        }
        val s = status ?: return null
        return if (now - statusAtMs < 20_000) s else null
    }

    private fun setStatus(s: String) {
        status = s
        statusAtMs = System.currentTimeMillis()
        onState()
    }

    // ---------------------------------------------------------------- lifecycle

    fun start(familyCode: String) {
        running = true
        applyCode(familyCode)
        playThread = Thread({ playRun() }, "away-play").apply { isDaemon = true; start() }
        presenceThread = Thread({ presenceRun() }, "away-presence").apply { isDaemon = true; start() }
        connect()
    }

    fun restart(familyCode: String) {
        endClip()
        val old = clients
        clients = emptyList()
        net.execute { old.forEach { it.stop() } }
        members.clear()
        outgoing.clear()
        playQueue.clear()
        applyCode(familyCode)
        connect()
        onState()
    }

    fun stop() {
        running = false
        recording = false
        clipGen++
        val old = clients
        clients = emptyList()
        Thread({ old.forEach { it.stop() } }, "away-stop").apply { isDaemon = true; start() }
        presenceThread?.interrupt()
        playThread?.interrupt()
        net.shutdownNow()
    }

    private fun applyCode(familyCode: String) {
        code = familyCode
        root = AwayProtocol.topicRoot(familyCode)
        key = null
        collisionReported = false
        Thread({
            try {
                val derived = AwayProtocol.deriveKey(familyCode)
                if (code == familyCode) key = derived
            } catch (e: Exception) {
            }
            onState()
        }, "away-key").apply { isDaemon = true; start() }
    }

    private fun connect() {
        val r = root
        clients = RELAYS.map { (host, port) ->
            val id = "khalsa3-" + Integer.toHexString(myId) + "-" + Integer.toHexString(random.nextInt(65536))
            MqttClient(host, port, id, relayListener).apply {
                subscribe("$r/presence")
                subscribe("$r/chat")
                subscribe("$r/clip/$myId")
                subscribe("$r/ack/$myId")
                subscribe("$r/voice/$myId") // only to warn about phones still on v2.2
                start()
            }
        }
    }

    /** Runs a network write off the main thread. Android forbids sockets on the main thread. */
    private fun onNet(block: () -> Unit) {
        try {
            net.execute {
                try {
                    block()
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
            // executor shut down: engine stopped
        }
    }

    /** Publishes on every connected relay. Returns how many took it. Network thread only. */
    private fun publishAll(topic: String, payload: ByteArray): Int = clients.count { it.publish(topic, payload) }

    private fun seal(body: ByteArray): ByteArray? {
        val k = key ?: return null
        return AwayProtocol.encrypt(k, AwayProtocol.wrapFresh(body, random, System.currentTimeMillis()), random)
    }

    // ---------------------------------------------------------------- presence and members

    private fun presenceRun() {
        var tick = 0
        while (running) {
            try {
                Thread.sleep(1000L)
            } catch (e: InterruptedException) {
                return
            }
            tick++
            if (tick % (AwayProtocol.PRESENCE_MS / 1000).toInt() == 0) publishPresence()
            if (tick % 5 == 0) {
                pruneMembers(System.currentTimeMillis())
                onState()
            }
        }
    }

    private fun publishPresence() {
        if (!running || key == null) return
        onNet {
            val sealed = seal(AwayProtocol.encodePresence(myId, myName()))
            if (sealed != null) publishAll("$root/presence", sealed)
        }
    }

    private fun touchMember(id: Int, name: String?, now: Long) {
        members.compute(id) { _, old ->
            if (old == null) {
                Member(id, name ?: "Family phone", now, 0L)
            } else {
                old.copy(name = name ?: old.name, lastSeenMs = maxOf(old.lastSeenMs, now))
            }
        }
        if (members.size > MAX_MEMBERS) pruneMembers(now)
    }

    private fun pruneMembers(now: Long) {
        members.entries.removeIf { now - it.value.lastSeenMs > MEMBER_EVICT_MS }
        if (members.size > MAX_MEMBERS) {
            members.values.sortedBy { it.lastSeenMs }.take(members.size - MAX_MEMBERS).forEach { members.remove(it.id) }
        }
    }

    /** Members, with lastAudioMs = now while their message is playing (drives the "talking" dot). */
    fun snapshot(now: Long): List<Member> =
        members.values
            .map { if (it.id == playingSender && now < playingUntilMs) it.copy(lastAudioMs = now) else it }
            .sortedWith(compareBy({ !isOnline(it, now) }, { it.name.lowercase() }))

    fun isOnline(m: Member, now: Long): Boolean = now - m.lastSeenMs <= AwayProtocol.ONLINE_TTL_MS
    fun isOnline(id: Int, now: Long): Boolean = members[id]?.let { isOnline(it, now) } ?: false
    fun memberName(id: Int): String? = members[id]?.name

    private fun reportCollision() {
        if (collisionReported) return
        collisionReported = true
        onIdCollision()
    }

    // ---------------------------------------------------------------- incoming

    private val relayListener = object : MqttClient.Listener {
        override fun onConnected(client: MqttClient) {
            publishPresence()
            onState()
        }

        override fun onDisconnected(client: MqttClient) {
            onState()
        }

        override fun onMessage(client: MqttClient, topic: String, payload: ByteArray) {
            handleMessage(topic, payload)
        }
    }

    private fun handleMessage(topic: String, payload: ByteArray) {
        val k = key ?: return
        val plain = AwayProtocol.decrypt(k, payload) ?: return
        val now = System.currentTimeMillis()
        val fresh = AwayProtocol.unwrapFresh(plain, now) ?: return
        val r = root
        when (topic) {
            "$r/presence" -> {
                val (id, name) = AwayProtocol.decodePresence(fresh.body) ?: return
                if (id == myId) {
                    if (name != myName()) reportCollision()
                    return
                }
                if (replayGuard.accept(id, fresh.messageId)) {
                    val wasOnline = isOnline(id, now)
                    touchMember(id, name, now)
                    if (!wasOnline) onState()
                }
            }
            "$r/chat" -> {
                val chat = AwayProtocol.decodeChat(fresh.body) ?: return
                if (chat.senderId == myId) {
                    if (chat.senderName != myName()) reportCollision()
                    return
                }
                if (chat.targetId != 0 && chat.targetId != myId) return
                if (!replayGuard.accept(chat.senderId, fresh.messageId)) return
                touchMember(chat.senderId, chat.senderName, now)
                onChat(chat.senderId, chat.senderName, chat.targetId, chat.text)
                onState()
            }
            "$r/clip/$myId" -> {
                val part = ClipProtocol.decodePart(fresh.body) ?: return
                if (part.senderId == myId || part.targetId != myId) return
                touchMember(part.senderId, null, now)
                val clip = synchronized(assembler) { assembler.offer(part, now) } ?: return
                sendAck(clip.senderId, clip.clipId, ClipProtocol.ACK_RECEIVED)
                val secs = (clip.durationMs + 500) / 1000
                if (!playQueue.offer(clip)) {
                    playQueue.poll()
                    playQueue.offer(clip)
                }
                setStatus("Message from ${memberName(clip.senderId) ?: "family phone"} (${secs}s) arrived")
            }
            "$r/ack/$myId" -> {
                val ack = ClipProtocol.decodeAck(fresh.body) ?: return
                if (ack.targetId != myId || !replayGuard.accept(ack.senderId, fresh.messageId)) return
                val out = outgoing[ack.clipId] ?: return
                if (out.targetId != ack.senderId) return
                touchMember(ack.senderId, null, now)
                val who = memberName(ack.senderId) ?: "the other phone"
                if (ack.status == ClipProtocol.ACK_PLAYED) {
                    outgoing.remove(ack.clipId)
                    setStatus("Played on $who ✓✓")
                } else if (!out.received) {
                    out.received = true
                    setStatus("Delivered to $who ✓ — playing there now")
                }
            }
            "$r/voice/$myId" -> {
                if (now - oldVersionNoticeMs > 30_000) {
                    oldVersionNoticeMs = now
                    setStatus("The other phone still runs the old app (v2.2). Install the new version on BOTH phones.")
                }
            }
        }
    }

    private fun sendAck(toId: Int, clipId: Int, ackStatus: Byte) {
        onNet {
            val sealed = seal(ClipProtocol.encodeAck(myId, toId, clipId, ackStatus))
            if (sealed != null) publishAll("$root/ack/$toId", sealed)
        }
    }

    fun sendChat(targetId: Int, text: String): Boolean {
        if (!ready) return false
        val body = AwayProtocol.encodeChat(myId, targetId, myName(), text)
        onNet {
            val sealed = seal(body)
            if (sealed != null && publishAll("$root/chat", sealed) == 0) {
                setStatus("Chat not sent — the internet connection dropped")
            }
        }
        return true
    }

    // ---------------------------------------------------------------- outgoing voice messages

    /** Starts recording a message for [targetId]. Returns false if not ready. */
    fun beginClip(targetId: Int): Boolean {
        if (recording || !ready) return false
        recording = true
        recordStartMs = System.currentTimeMillis()
        val gen = ++clipGen
        Thread({ recordRun(targetId, gen) }, "away-rec").apply { isDaemon = true; start() }
        onState()
        return true
    }

    /** Stops recording; the message is then sent. */
    fun endClip() {
        recording = false
    }

    private fun newRecorder(source: Int): AudioRecord? {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return null
            val rec = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, 8192))
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return null
            }
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                rec.release()
                return null
            }
            rec
        } catch (e: Exception) {
            null
        }
    }

    private fun release(rec: AudioRecord?) {
        try {
            rec?.stop()
        } catch (e: Exception) {
        }
        try {
            rec?.release()
        } catch (e: Exception) {
        }
    }

    private fun recordRun(targetId: Int, gen: Int) {
        val sources = intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        var sourceIndex = 0
        var rec: AudioRecord? = null
        while (rec == null && sourceIndex < sources.size) {
            rec = newRecorder(sources[sourceIndex])
            if (rec == null) sourceIndex++
        }
        if (rec == null) {
            recording = false
            onMicError("AWAY: the microphone could not start — another app may be using it")
            return
        }
        val pcm = ByteArrayOutputStream(MAX_CLIP_MS * BYTES_PER_MS)
        val frame = ByteArray(FRAME_BYTES)
        var peak = 0
        var hitLimit = false
        try {
            while (recording && gen == clipGen) {
                val n = try {
                    rec!!.read(frame, 0, frame.size)
                } catch (e: Exception) {
                    -1
                }
                if (n <= 0) break
                pcm.write(frame, 0, n)
                var i = 0
                while (i + 1 < n) {
                    val s = abs(((frame[i].toInt() and 0xFF) or (frame[i + 1].toInt() shl 8)).toShort().toInt())
                    if (s > peak) peak = s
                    i += 2
                }
                // Some phones hand one source pure digital silence. Switch to the other once.
                if (peak == 0 && pcm.size() >= SILENCE_SWITCH_MS * BYTES_PER_MS && sourceIndex + 1 < sources.size) {
                    release(rec)
                    sourceIndex++
                    rec = newRecorder(sources[sourceIndex])
                    pcm.reset()
                    if (rec == null) break
                }
                if (pcm.size() >= MAX_CLIP_MS * BYTES_PER_MS) {
                    hitLimit = true
                    break
                }
            }
        } finally {
            release(rec)
            recording = false
        }
        if (gen != clipGen) return
        val audio = pcm.toByteArray()
        val durationMs = audio.size / BYTES_PER_MS
        if (durationMs < MIN_CLIP_MS) {
            setStatus("Too short — hold TALK while you speak")
            return
        }
        if (peak == 0) {
            onMicError("AWAY: the microphone recorded only silence — check mic permission or Bluetooth")
            return
        }
        boost(audio, peak)
        val encoded = ImaAdpcm.encode(audio, audio.size)
        val clipId = random.nextInt()
        val bodies = try {
            ClipProtocol.encodeParts(myId, targetId, clipId, durationMs, SAMPLE_RATE, encoded)
        } catch (e: Exception) {
            setStatus("Message too long")
            return
        }
        val who = memberName(targetId) ?: "the other phone"
        val secs = (durationMs + 500) / 1000
        setStatus((if (hitLimit) "30 s limit reached. " else "") + "Sending ${secs}s message to $who…")
        onNet {
            val sealedParts = bodies.mapNotNull { seal(it) }
            if (sealedParts.size != bodies.size) {
                setStatus("Not sent — the family key is not ready")
                return@onNet
            }
            val out = Outgoing(clipId, targetId, durationMs, sealedParts)
            outgoing[clipId] = out
            trimOutgoing()
            val ok = sendParts(out)
            setStatus(if (ok) "Sent ${secs}s message — waiting for $who…" else "Not sent — no internet connection to the relay")
            scheduleAckCheck(out, who)
        }
    }

    /** Raises quiet recordings toward a healthy level (at most 4x) so they are clearly audible. */
    private fun boost(audio: ByteArray, peak: Int) {
        if (peak <= 0 || peak >= 16000) return
        val gain = min(4.0f, 20000f / peak)
        if (gain <= 1.05f) return
        var i = 0
        while (i + 1 < audio.size) {
            val s = ((audio[i].toInt() and 0xFF) or (audio[i + 1].toInt() shl 8)).toShort().toInt()
            val v = (s * gain).toInt().coerceIn(-32768, 32767)
            audio[i] = v.toByte()
            audio[i + 1] = (v shr 8).toByte()
            i += 2
        }
    }

    private fun sendParts(out: Outgoing): Boolean {
        val topic = "$root/clip/${out.targetId}"
        var allSent = true
        for (part in out.sealedParts) {
            if (publishAll(topic, part) == 0) allSent = false
        }
        return allSent
    }

    private fun scheduleAckCheck(out: Outgoing, who: String) {
        try {
            net.schedule({
                if (!running || outgoing[out.clipId] !== out || out.received) return@schedule
                if (!out.resent) {
                    out.resent = true
                    val ok = sendParts(out)
                    setStatus(if (ok) "No answer yet from $who — sent again…" else "Still no connection — message not delivered")
                    scheduleAckCheck(out, who)
                } else {
                    outgoing.remove(out.clipId)
                    setStatus("Not confirmed by $who. Check that phone has internet and the app open in the background.")
                }
            }, ACK_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
        }
    }

    private fun trimOutgoing() {
        while (outgoing.size > 8) {
            val oldest = outgoing.keys.firstOrNull() ?: return
            outgoing.remove(oldest)
        }
    }

    // ---------------------------------------------------------------- playback

    private fun playRun() {
        while (running) {
            val clip = try {
                playQueue.poll(500, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                return
            } ?: continue
            // Hold the message while this phone's owner is talking; play it right after.
            while (running && (isTransmitting() || recording)) {
                try {
                    Thread.sleep(150)
                } catch (e: InterruptedException) {
                    return
                }
            }
            if (!running) return
            playClip(clip)
        }
    }

    private fun chime(): ByteArray {
        val toneMs = 110
        val samples = SAMPLE_RATE * toneMs / 1000
        val out = ByteArray((samples * 2 + SAMPLE_RATE / 10) * 2)
        var w = 0
        for (f in intArrayOf(880, 660)) {
            for (i in 0 until samples) {
                val fade = min(1.0, min(i, samples - i) / 160.0)
                val v = (sin(2 * Math.PI * f * i / SAMPLE_RATE) * 9000 * fade).toInt()
                out[w++] = v.toByte()
                out[w++] = (v shr 8).toByte()
            }
        }
        return out // trailing zeros give a short pause before the voice
    }

    private fun playClip(clip: ClipProtocol.Clip) {
        val who = memberName(clip.senderId) ?: "family phone"
        val voice = ImaAdpcm.decode(clip.encoded)
        if (voice.isEmpty() || clip.sampleRate != SAMPLE_RATE) {
            setStatus("A message from $who could not be decoded")
            return
        }
        val pcm = chime() + voice
        val frames = pcm.size / 2
        val durationMs = frames * 1000L / SAMPLE_RATE

        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            setStatus("Message from $who is playing but MEDIA VOLUME IS OFF — turn the volume up")
        }

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        try {
            audioManager.requestAudioFocus(focus)
        } catch (e: Exception) {
        }

        var track: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf * 4, 16_000))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                setStatus("Speaker could not start for the message from $who")
                return
            }
            playingSender = clip.senderId
            playingUntilMs = System.currentTimeMillis() + durationMs + 300
            onState()
            track.play()
            var off = 0
            while (off < pcm.size && running) {
                val n = track.write(pcm, off, min(8192, pcm.size - off))
                if (n < 0) break
                off += n
            }
            // Let the last buffered audio play out before releasing the track.
            val deadline = System.currentTimeMillis() + durationMs + 2000
            while (running && track.playbackHeadPosition < frames && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            if (off >= pcm.size) {
                sendAck(clip.senderId, clip.clipId, ClipProtocol.ACK_PLAYED)
            }
        } catch (e: InterruptedException) {
            return
        } catch (e: Exception) {
            setStatus("Speaker problem while playing the message from $who: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            playingSender = 0
            playingUntilMs = 0
            try {
                track?.stop()
            } catch (e: Exception) {
            }
            try {
                track?.release()
            } catch (e: Exception) {
            }
            try {
                audioManager.abandonAudioFocusRequest(focus)
            } catch (e: Exception) {
            }
            onState()
        }
    }
}
