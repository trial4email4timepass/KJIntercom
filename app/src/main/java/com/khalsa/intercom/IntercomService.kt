package com.khalsa.intercom

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.khalsa.intercom.core.AwayProtocol
import com.khalsa.intercom.core.IntercomProtocol
import com.khalsa.intercom.core.PeerTable
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Always-on intercom service.
 *  - PUBLIC / PRIVATE: live push-to-talk to phones on the same Wi-Fi (UDP multicast).
 *  - AWAY: voice messages and chat to family phones anywhere, through [AwayEngine].
 *  - BROADCAST: handled by the activity (records a note to share in other apps).
 */
class IntercomService : Service() {

    enum class Mode { PUBLIC, PRIVATE, BROADCAST, AWAY }

    data class PeerUi(val id: Int, val name: String, val speaking: Boolean)
    data class AwayPeerUi(val id: Int, val name: String, val online: Boolean, val speaking: Boolean, val selected: Boolean)
    data class ChatUi(
        val senderId: Int,
        val senderName: String,
        val text: String,
        val isPrivate: Boolean,
        val mine: Boolean,
        val timeMs: Long,
        val away: Boolean
    )

    data class UiState(
        val myName: String,
        val peers: List<PeerUi>,
        val transmitting: Boolean,
        val speakingNow: String?,
        val detail: String,
        val mode: Mode,
        val selectedPeerId: Int,
        val chat: List<ChatUi>,
        val awayPeers: List<AwayPeerUi>,
        val selectedAwayId: Int,
        val awayConnected: Boolean,
        val familyCode: String
    )

    fun interface StateListener {
        fun onState(state: UiState)
    }

    inner class LocalBinder : Binder() {
        val service: IntercomService get() = this@IntercomService
    }

    private class ChatEntry(
        val senderId: Int,
        val text: String,
        val isPrivate: Boolean,
        val mine: Boolean,
        val timeMs: Long,
        val away: Boolean,
        val senderName: String = ""
    )

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<StateListener>()
    private val peers = PeerTable()
    private val playQueue = LinkedBlockingQueue<ByteArray>(40)
    private val netExec = Executors.newSingleThreadExecutor { r -> Thread(r, "lan-net").apply { isDaemon = true } }
    private val chatLock = Any()
    private val chatHistory = ArrayList<ChatEntry>()

    @Volatile private var myId = 0
    @Volatile var myName = ""
        private set
    @Volatile var mode = Mode.PUBLIC
        private set
    @Volatile var selectedPeerId = 0
        private set
    @Volatile private var running = false
    @Volatile private var transmitting = false
    @Volatile private var txGen = 0
    @Volatile private var txPackets = 0
    @Volatile private var silentFrames = 0
    @Volatile private var silenceWarn = false
    // What this phone is hearing on Wi-Fi, shown in the status line so failures are visible.
    @Volatile private var rxFromId = 0
    @Volatile private var rxPackets = 0
    @Volatile private var rxPeak = 0
    @Volatile private var rxFirstMs = 0L
    @Volatile private var rxLastMs = 0L
    @Volatile private var micError: String? = null
    @Volatile private var engineError: String? = null
    @Volatile private var lastPush = 0L
    @Volatile private var lastCollisionMs = 0L
    @Volatile private var familyCode = ""
    @Volatile private var selectedAwayMemberId = 0
    @Volatile private var wifiIpText = ""
    @Volatile private var wifiIpSnapshot = ""
    @Volatile private var rxSocket: MulticastSocket? = null
    @Volatile private var txSocket: MulticastSocket? = null
    @Volatile private var groupAddr: InetAddress? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var away: AwayEngine? = null
    private var txThread: Thread? = null
    private var player: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectivity: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var chatSeq = 0

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        myId = Identity.id(this)
        myName = Identity.name(this)
        familyCode = FamilyCodeStore.code(this)
        selectedAwayMemberId = FamilyCodeStore.selectedMember(this)
        mode = FamilyCodeStore.modeName(this)?.let { name -> Mode.values().firstOrNull { it.name == name } } ?: Mode.PUBLIC
        selectedPeerId = FamilyCodeStore.selectedPeer(this)
        Notify.ensureChannel(this)
        if (startInForeground()) startEngine() else stopSelf()
    }

    private fun startInForeground(): Boolean {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = Notification.Builder(this, Notify.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("KHALSA Intercom")
            .setContentText("On — same Wi-Fi and internet (AWAY) messages play automatically")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        val attempts = ArrayList<() -> Unit>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            attempts.add {
                startForeground(
                    Notify.ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            }
            attempts.add { startForeground(Notify.ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }
        }
        attempts.add { startForeground(Notify.ID, notif) }
        for (attempt in attempts) {
            try {
                attempt()
                return true
            } catch (e: Exception) {
            }
        }
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // ---------------------------------------------------------------- engine

    private fun startEngine() {
        if (running) return
        running = true
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("khalsa-intercom").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
        }
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "khalsa:intercom").apply { acquire() }
        } catch (e: Exception) {
        }
        // Note: v2.2 forced the whole phone into "in call" audio mode for as long as the app
        // ran. That re-routes and mutes media audio on many phones. Push-to-talk is half
        // duplex (we never play while recording), so normal mode is correct.
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        openLanSockets()
        watchWifiChanges()
        startAwayEngine()
        Thread({ rxLoop() }, "intercom-rx").apply { isDaemon = true; start() }
        Thread({ playLoop() }, "intercom-play").apply { isDaemon = true; start() }
        Thread({ beaconLoop() }, "intercom-beacon").apply { isDaemon = true; start() }
        pushState()
    }

    private fun startAwayEngine() {
        val eng = AwayEngine(
            context = applicationContext,
            myId = myId,
            myName = { myName },
            isTransmitting = { transmitting },
            onChat = { senderId, senderName, targetId, text ->
                appendChat(ChatEntry(senderId, text, targetId != 0, false, System.currentTimeMillis(), true, senderName))
                pushState()
            },
            onMicError = { msg ->
                micError = msg
                if (mode == Mode.AWAY) transmitting = false
                pushState()
            },
            onState = { pushStateThrottled() },
            onIdCollision = { main.post { onIdentityCollision() } }
        )
        away = eng
        eng.start(familyCode)
    }

    private fun onIdentityCollision() {
        val now = System.currentTimeMillis()
        if (now - lastCollisionMs < 30_000) return
        lastCollisionMs = now
        myId = Identity.regenerate(this)
        try {
            away?.stop()
        } catch (e: Exception) {
        }
        startAwayEngine()
        sendBeacon()
        pushState()
    }

    // ---------------------------------------------------------------- same-Wi-Fi (LAN)

    private fun wifiInterface(): NetworkInterface? {
        return try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wifi.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                val addr = InetAddress.getByAddress(
                    byteArrayOf(
                        (ip and 0xFF).toByte(), ((ip shr 8) and 0xFF).toByte(),
                        ((ip shr 16) and 0xFF).toByte(), ((ip shr 24) and 0xFF).toByte()
                    )
                )
                wifiIpText = addr.hostAddress ?: ""
                NetworkInterface.getByInetAddress(addr)?.let { return it }
            } else {
                wifiIpText = ""
            }
            NetworkInterface.getNetworkInterfaces()?.toList()?.firstOrNull {
                it.isUp && !it.isLoopback && it.supportsMulticast() && it.name.startsWith("wlan")
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun openLanSockets(): Boolean {
        return try {
            val group = InetAddress.getByName(IntercomProtocol.GROUP)
            groupAddr = group
            val iface = wifiInterface()
            val newTx = MulticastSocket().apply {
                timeToLive = 2
                if (iface != null) networkInterface = iface
            }
            val newRx = MulticastSocket(null as java.net.SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(IntercomProtocol.PORT))
                soTimeout = 1000
            }
            try {
                newRx.joinGroup(InetSocketAddress(group, IntercomProtocol.PORT), iface)
            } catch (e: Exception) {
                @Suppress("DEPRECATION")
                newRx.joinGroup(group)
            }
            val oldRx = rxSocket
            val oldTx = txSocket
            rxSocket = newRx
            txSocket = newTx
            try {
                oldRx?.close()
            } catch (e: Exception) {
            }
            try {
                oldTx?.close()
            } catch (e: Exception) {
            }
            wifiIpSnapshot = wifiIpText
            engineError = null
            true
        } catch (e: Exception) {
            // No Wi-Fi is normal on mobile data; AWAY mode still works.
            engineError = if (mode == Mode.AWAY) null else "Same-Wi-Fi link not available: ${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    private fun watchWifiChanges() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivity = cm
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                    if (!running) return
                    val ip = lp.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                        ?.address?.hostAddress ?: return
                    if (ip == wifiIpSnapshot) return
                    if (openLanSockets()) {
                        sendBeacon()
                        pushState()
                    }
                }

                override fun onLost(network: Network) {
                    if (running) {
                        wifiIpSnapshot = ""
                        pushState()
                    }
                }
            }
            netCallback = cb
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb
            )
        } catch (e: Exception) {
        }
    }

    private fun rxLoop() {
        val buf = ByteArray(2048)
        while (running) {
            val sock = rxSocket
            if (sock == null || sock.isClosed) {
                // v2.2 exited here forever when started without Wi-Fi. Wait instead.
                try {
                    Thread.sleep(500)
                } catch (e: InterruptedException) {
                    return
                }
                continue
            }
            val dp = DatagramPacket(buf, buf.size)
            try {
                sock.receive(dp)
            } catch (e: java.net.SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (!running) return
                try {
                    Thread.sleep(200)
                } catch (ie: InterruptedException) {
                    return
                }
                continue
            }
            val pkt = IntercomProtocol.decode(dp.data, dp.length) ?: continue
            handleLanPacket(pkt, dp.address)
        }
    }

    private fun handleLanPacket(pkt: IntercomProtocol.Packet, from: InetAddress?) {
        if (pkt.senderId == myId) {
            if (pkt.type == IntercomProtocol.T_BEACON) {
                val n = String(pkt.payload, Charsets.UTF_8).trim()
                if (n.isNotEmpty() && n != myName) main.post { onIdentityCollision() }
            }
            return
        }
        val now = System.currentTimeMillis()
        when (pkt.type) {
            IntercomProtocol.T_BEACON -> {
                val name = String(pkt.payload, Charsets.UTF_8).trim().ifEmpty { "A phone" }
                if (peers.onBeacon(pkt.senderId, name, now, from)) pushState()
            }
            IntercomProtocol.T_AUDIO -> {
                // Hear everyone in every mode; the mode only decides who YOU talk to.
                peers.onAudio(pkt.senderId, now, from)
                onHeardFrame(pkt.senderId, pkt.payload, now)
                if (!transmitting) queueFrame(pkt.payload)
                pushStateThrottled()
            }
            IntercomProtocol.T_AUDIO_PRIVATE -> {
                if (pkt.targetId == myId) {
                    peers.onAudio(pkt.senderId, now, from)
                    onHeardFrame(pkt.senderId, pkt.payload, now)
                    if (!transmitting) queueFrame(pkt.payload)
                    pushStateThrottled()
                }
            }
            IntercomProtocol.T_CHAT -> {
                if (pkt.targetId == 0 || pkt.targetId == myId) {
                    peers.onChat(pkt.senderId, now, from)
                    appendChat(ChatEntry(pkt.senderId, pkt.text, pkt.targetId != 0, false, now, false))
                    pushState()
                }
            }
            IntercomProtocol.T_BYE -> if (peers.onBye(pkt.senderId)) pushState()
        }
    }

    private fun onHeardFrame(sender: Int, pcm: ByteArray, now: Long) {
        if (sender != rxFromId || now - rxLastMs > 1500) {
            rxFromId = sender
            rxPackets = 0
            rxPeak = 0
            rxFirstMs = now
        }
        rxPackets++
        rxLastMs = now
        var i = 0
        var peak = rxPeak
        while (i + 1 < pcm.size) {
            val v = Math.abs(((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toInt())
            if (v > peak) peak = v
            i += 2
        }
        rxPeak = peak
    }

    private fun queueFrame(frame: ByteArray) {
        if (!playQueue.offer(frame)) {
            playQueue.poll()
            playQueue.offer(frame)
        }
    }

    private fun playLoop() {
        val minBuf = AudioTrack.getMinBufferSize(IntercomProtocol.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(IntercomProtocol.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, 5120))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            null
        }
        player = track
        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            engineError = "Speaker problem: the audio output did not start on this phone"
        } else {
            try {
                track.play()
            } catch (e: Exception) {
                engineError = "Speaker could not start on this phone: ${e.message ?: "audio refused"}"
            }
        }
        while (running) {
            try {
                val frame = playQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (!transmitting) player?.write(frame, 0, frame.size)
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
            }
        }
    }

    private fun beaconLoop() {
        while (running) {
            try {
                sendBeacon()
                if (peers.prune(System.currentTimeMillis())) pushState()
                Thread.sleep(IntercomProtocol.BEACON_MS)
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
            }
        }
    }

    /** Sends a UDP packet. Hops off the main thread, where Android forbids network calls. */
    private fun sendRaw(bytes: ByteArray) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                netExec.execute { sendRawNow(bytes) }
            } catch (e: Exception) {
            }
        } else {
            sendRawNow(bytes)
        }
    }

    private fun sendRawNow(bytes: ByteArray) {
        try {
            val sock = txSocket ?: return
            val addr = groupAddr ?: return
            sock.send(DatagramPacket(bytes, bytes.size, addr, IntercomProtocol.PORT))
        } catch (e: Exception) {
        }
    }

    private fun sendBeacon() = sendRaw(IntercomProtocol.encodeBeacon(myId, myName))

    /**
     * Sends a voice frame straight to each listening phone (unicast). v2.2 multicast every
     * frame to the whole network; many routers throttle or drop a steady multicast stream
     * (or deliver it only in bursts to phones in power save) even though the odd chat
     * packet gets through, which is exactly "text arrives, voice doesn't". Unicast Wi-Fi
     * frames are retried and sent at full speed. Falls back to multicast if no address is known.
     */
    private fun sendVoice(packet: ByteArray, txMode: Mode, txTarget: Int) {
        val sock = txSocket ?: return
        val targets = peers.snapshot()
            .filter { if (txMode == Mode.PRIVATE) it.id == txTarget else true }
            .mapNotNull { it.addr }
            .distinct()
        if (targets.isEmpty()) {
            sendRawNow(packet)
            return
        }
        for (addr in targets) {
            try {
                sock.send(DatagramPacket(packet, packet.size, addr, IntercomProtocol.PORT))
            } catch (e: Exception) {
            }
        }
    }

    // ---------------------------------------------------------------- user actions

    private fun selectedAwayMember(): AwayEngine.Member? {
        val eng = away ?: return null
        val id = selectedAwayMemberId
        if (id == 0) return null
        return eng.snapshot(System.currentTimeMillis()).firstOrNull { it.id == id }
    }

    fun selectAwayMember(id: Int) {
        selectedAwayMemberId = if (selectedAwayMemberId == id) 0 else id
        FamilyCodeStore.setSelectedMember(this, selectedAwayMemberId)
        pushState()
    }

    fun joinFamily(newCode: String) {
        familyCode = newCode
        FamilyCodeStore.setCode(this, newCode)
        selectedAwayMemberId = 0
        FamilyCodeStore.setSelectedMember(this, 0)
        away?.restart(newCode)
        pushState()
    }

    fun rename(name: String) {
        val clean = name.trim().take(24)
        if (clean.isEmpty()) return
        myName = clean
        Identity.setName(this, clean)
        sendBeacon()
        pushState()
    }

    fun setMode(m: Mode) {
        if (mode == m) return
        if (transmitting) stopTalk()
        mode = m
        micError = null
        FamilyCodeStore.setModeName(this, m.name)
        pushState()
    }

    fun toggleSelectPeer(id: Int) {
        selectedPeerId = if (selectedPeerId == id) 0 else id
        FamilyCodeStore.setSelectedPeer(this, selectedPeerId)
        pushState()
    }

    private fun appendChat(e: ChatEntry) {
        synchronized(chatLock) {
            chatHistory.add(e)
            while (chatHistory.size > 100) chatHistory.removeAt(0)
        }
    }

    private fun lanTargetAlive(target: Int): Boolean = target != 0 && peers.snapshot().any { it.id == target }

    private fun awayNotReadyText(eng: AwayEngine?): String = when {
        eng == null -> "AWAY: engine not started"
        !eng.connected -> "AWAY: not connected yet — check this phone's internet"
        else -> "AWAY: still preparing the family key — try again in a moment"
    }

    fun sendChat(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        val now = System.currentTimeMillis()
        when (mode) {
            Mode.BROADCAST -> {
                micError = "Use the Public Broadcast panel for public posts"
                pushState()
                return false
            }
            Mode.PRIVATE -> {
                val target = selectedPeerId
                if (!lanTargetAlive(target)) {
                    micError = "Private mode: tap a name in the list first"
                    pushState()
                    return false
                }
                sendRaw(IntercomProtocol.encodeChat(myId, chatSeq++, target, clean))
                appendChat(ChatEntry(myId, clean, true, true, now, false))
            }
            Mode.AWAY -> {
                val eng = away
                if (eng == null || !eng.ready) {
                    micError = awayNotReadyText(eng)
                    pushState()
                    return false
                }
                val target = selectedAwayMemberId
                if (target != 0 && !eng.isOnline(target, now)) {
                    micError = "AWAY: ${eng.memberName(target) ?: "That phone"} is not online right now"
                    pushState()
                    return false
                }
                if (!eng.sendChat(target, clean)) {
                    micError = "AWAY: message could not be sent — the connection dropped"
                    pushState()
                    return false
                }
                appendChat(ChatEntry(myId, clean, target != 0, true, now, true, myName))
            }
            Mode.PUBLIC -> {
                sendRaw(IntercomProtocol.encodeChat(myId, chatSeq++, 0, clean))
                appendChat(ChatEntry(myId, clean, false, true, now, false))
            }
        }
        micError = null
        pushState()
        return true
    }

    fun startTalk() {
        if (transmitting || !running || mode == Mode.BROADCAST) return
        if (mode == Mode.PRIVATE && !lanTargetAlive(selectedPeerId)) {
            micError = "Private mode: tap a name in the list first"
            pushState()
            return
        }
        if (mode == Mode.AWAY) {
            val eng = away
            if (eng == null || !eng.ready) {
                micError = awayNotReadyText(eng)
                pushState()
                return
            }
            val target = selectedAwayMemberId
            if (target == 0) {
                micError = "AWAY mode: tap a name in the family list first"
                pushState()
                return
            }
            if (!eng.isOnline(target, System.currentTimeMillis())) {
                micError = "AWAY: ${eng.memberName(target) ?: "That phone"} is not online right now"
                pushState()
                return
            }
            micError = null
            // Start recording before flagging "talking", so pushState never sees talking
            // without a recording and resets it mid-press.
            if (eng.beginClip(target)) {
                transmitting = true
            } else {
                micError = "AWAY: could not start recording — wait a moment, then hold TALK again"
            }
            pushState()
            return
        }
        if (txThread?.isAlive == true) return
        micError = null
        txPackets = 0
        silentFrames = 0
        silenceWarn = false
        val txMode = mode
        val txTarget = selectedPeerId
        val gen = ++txGen
        transmitting = true
        playQueue.clear()
        pushState()
        txThread = Thread({ txRun(txMode, txTarget, gen) }, "intercom-tx").apply { isDaemon = true; start() }
    }

    private fun tryCreateRecorder(source: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(IntercomProtocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        return try {
            val rec = AudioRecord(source, IntercomProtocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 2560))
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return null
            }
            try {
                audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                    ?.let { rec.setPreferredDevice(it) }
            } catch (e: Exception) {
            }
            try {
                AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
            } catch (e: Exception) {
            }
            try {
                NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
            } catch (e: Exception) {
            }
            try {
                AutomaticGainControl.create(rec.audioSessionId)?.enabled = true
            } catch (e: Exception) {
            }
            rec
        } catch (e: Exception) {
            null
        }
    }

    private fun releaseRecorder(rec: AudioRecord) {
        try {
            rec.stop()
        } catch (e: Exception) {
        }
        try {
            rec.release()
        } catch (e: Exception) {
        }
        if (recorder === rec) recorder = null
    }

    private fun txRun(txMode: Mode, txTarget: Int, gen: Int) {
        var streamed = false
        // Plain MIC first: VOICE_COMMUNICATION is quiet or silent on some phones outside a call.
        for (source in intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_COMMUNICATION)) {
            if (!transmitting || txGen != gen) break
            val rec = tryCreateRecorder(source) ?: continue
            recorder = rec
            val started = try {
                rec.startRecording()
                rec.recordingState == AudioRecord.RECORDSTATE_RECORDING
            } catch (e: Exception) {
                false
            }
            if (!started) {
                releaseRecorder(rec)
                continue
            }
            val ended = pump(rec, txMode, txTarget, gen)
            releaseRecorder(rec)
            if (ended) {
                streamed = true
                break
            }
        }
        if (!streamed && transmitting && txGen == gen && micError == null) {
            micError = "Mic blocked: voice mode and plain mode both failed"
        }
        if (txGen == gen) transmitting = false
        pushState()
    }

    /** Streams one recorder. Returns false only if it failed before sending anything (try the next source). */
    private fun pump(rec: AudioRecord, txMode: Mode, txTarget: Int, gen: Int): Boolean {
        val frame = ByteArray(IntercomProtocol.FRAME_BYTES)
        var seq = 0
        var sentAny = false
        while (transmitting && txGen == gen) {
            var filled = 0
            var readFailed = false
            while (filled < frame.size && transmitting && txGen == gen) {
                val n = try {
                    rec.read(frame, filled, frame.size - filled)
                } catch (e: Exception) {
                    -1
                }
                if (n <= 0) {
                    readFailed = true
                    break
                }
                filled += n
            }
            if (filled > 0) {
                noteFrameLevel(frame, filled)
                val packet = if (txMode == Mode.PRIVATE) {
                    IntercomProtocol.encodeAudioPrivate(myId, seq, txTarget, frame, filled)
                } else {
                    IntercomProtocol.encodeAudio(myId, seq, frame, filled)
                }
                seq++
                sendVoice(packet, txMode, txTarget)
                txPackets++
                pushStateThrottled()
                sentAny = true
            }
            // 1.5 s of pure digital silence: this mic source is blocked, try the other one.
            if (silentFrames == 75 && seq == 75) return false
            if (readFailed) {
                if (!transmitting || txGen != gen) return true
                if (!sentAny) return false
                micError = "Mic stopped mid-send — hold TALK to retry"
                return true
            }
        }
        return true
    }

    private fun noteFrameLevel(frame: ByteArray, filled: Int) {
        var peak = 0
        var i = 0
        while (i + 1 < filled) {
            val s = Math.abs(((frame[i].toInt() and 0xFF) or (frame[i + 1].toInt() shl 8)).toShort().toInt())
            if (s > peak) peak = s
            i += 2
        }
        if (peak == 0) {
            silentFrames++
            if (silentFrames >= 100) silenceWarn = true
        } else {
            silentFrames = 0
            silenceWarn = false
        }
    }

    fun stopTalk() {
        if (!transmitting) return
        transmitting = false
        if (mode == Mode.AWAY) away?.endClip()
        try {
            recorder?.stop()
        } catch (e: Exception) {
        }
        pushState()
    }

    fun addListener(l: StateListener) {
        listeners.add(l)
        pushState()
    }

    fun removeListener(l: StateListener) {
        listeners.remove(l)
    }

    // ---------------------------------------------------------------- UI state

    private fun pushStateThrottled() {
        if (System.currentTimeMillis() - lastPush > 400) pushState()
    }

    fun pushState() {
        val now = System.currentTimeMillis()
        lastPush = now
        // A voice message stops by itself at 30 s (or on a mic error). Leave "talking" state
        // then too, so the button stops saying RECORDING and incoming messages can play.
        if (mode == Mode.AWAY && transmitting && away?.isRecording == false) transmitting = false
        val snap = peers.snapshot()
        val chatSnap = synchronized(chatLock) { chatHistory.toList() }
        val eng = away
        val awaySnap = eng?.snapshot(now) ?: emptyList()

        val peerUi = snap.map { PeerUi(it.id, it.name, it.lastAudioMs > 0 && now - it.lastAudioMs < 700) }
        val lanSpeaker = snap.filter { it.lastAudioMs > 0 && now - it.lastAudioMs < 700 }.maxByOrNull { it.lastAudioMs }?.name
        val awaySpeaker = awaySnap.firstOrNull { it.lastAudioMs > 0 && now - it.lastAudioMs < 700 }?.name
        val chatUi = chatSnap.map { e ->
            val name = when {
                e.mine -> myName
                e.away -> e.senderName.ifEmpty { eng?.memberName(e.senderId) ?: "Family phone" }
                else -> snap.firstOrNull { it.id == e.senderId }?.name ?: "A phone"
            }
            ChatUi(e.senderId, name, e.text, e.isPrivate, e.mine, e.timeMs, e.away)
        }
        val awayUi = awaySnap.map {
            AwayPeerUi(it.id, it.name, eng?.isOnline(it, now) == true, it.lastAudioMs > 0 && now - it.lastAudioMs < 700, it.id == selectedAwayMemberId)
        }
        val state = UiState(
            myName = myName,
            peers = peerUi,
            transmitting = transmitting,
            speakingNow = awaySpeaker ?: lanSpeaker,
            detail = detailText(snap, now),
            mode = mode,
            selectedPeerId = selectedPeerId,
            chat = chatUi,
            awayPeers = awayUi,
            selectedAwayId = selectedAwayMemberId,
            awayConnected = eng?.ready == true,
            familyCode = familyCode
        )
        main.post { listeners.forEach { it.onState(state) } }
    }

    private fun hearingText(snap: List<PeerTable.Peer>, now: Long): String? {
        if (rxFromId == 0 || now - rxLastMs > 8000) return null
        val who = snap.firstOrNull { it.id == rxFromId }?.name ?: "a phone"
        val level = rxPeak * 100 / 32767
        val volumeOff = (audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 1) == 0
        val live = now - rxLastMs < 1500
        return when {
            rxPeak == 0 -> "Receiving from $who, but their microphone is sending SILENCE — check mic permission on that phone"
            volumeOff -> "Receiving from $who but MEDIA VOLUME IS OFF on this phone — turn the volume up"
            live -> "🔊 Hearing $who — level $level% (${rxPackets} packets)"
            else -> "Last heard $who: ${(rxLastMs - rxFirstMs + 500) / 1000}s, level $level%"
        }
    }

    private fun detailText(snap: List<PeerTable.Peer>, now: Long): String {
        val eng = away
        // Voice-message progress (recording, sending, delivered, played) wins in every mode,
        // because a message can arrive while you are on another tab.
        eng?.statusLine(now)?.let { return it }
        hearingText(snap, now)?.let { return it }
        if (transmitting) {
            if (silenceWarn) return "Mic is hearing silence — check Bluetooth / mic"
            return "Sending — $txPackets packets out"
        }
        micError?.let { return it }
        if (mode != Mode.AWAY) engineError?.let { return it }
        when (mode) {
            Mode.PRIVATE -> {
                val sel = snap.firstOrNull { it.id == selectedPeerId }
                return if (sel != null) "Private → ${sel.name}" else "Private mode — tap a name in the list"
            }
            Mode.BROADCAST -> return "Public Broadcast — nothing is sent until you share it"
            Mode.AWAY -> {
                if (eng == null) return "AWAY — engine not started"
                if (!eng.connected) {
                    val err = eng.lastError
                    return if (err != null) "AWAY — relay problem: $err (retrying)" else "AWAY — connecting to the relays…"
                }
                if (!eng.ready) return "AWAY — preparing the family key…"
                val relays = " • ${eng.relaysUp}/${AwayEngine.RELAYS.size} relays"
                val sel = selectedAwayMember() ?: return "AWAY — tap a name in the family list above$relays"
                return if (eng.isOnline(sel.id, now)) {
                    "AWAY → ${sel.name} • hold TALK, release to send$relays"
                } else {
                    "AWAY → ${sel.name} is not online right now$relays"
                }
            }
            Mode.PUBLIC -> {
                val ip = wifiIpText.ifEmpty { "…" }
                return "Wi-Fi $ip • listening on ${IntercomProtocol.GROUP}:${IntercomProtocol.PORT}"
            }
        }
    }

    override fun onDestroy() {
        stopTalk()
        try {
            away?.stop()
        } catch (e: Exception) {
        }
        netCallback?.let {
            try {
                connectivity?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
            }
        }
        netCallback = null
        running = false
        val rx = rxSocket
        val tx = txSocket
        val bye = IntercomProtocol.encodeBye(myId)
        // Say goodbye and close the sockets off the main thread.
        try {
            netExec.execute {
                repeat(2) { sendRawNow(bye) }
                try {
                    rx?.close()
                } catch (e: Exception) {
                }
                try {
                    tx?.close()
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
        }
        try {
            player?.release()
        } catch (e: Exception) {
        }
        try {
            multicastLock?.release()
        } catch (e: Exception) {
        }
        try {
            wakeLock?.release()
        } catch (e: Exception) {
        }
        netExec.shutdown()
        super.onDestroy()
    }
}
