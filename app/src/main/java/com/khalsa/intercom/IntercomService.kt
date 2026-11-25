package com.khalsa.intercom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.File
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Foreground service that keeps an always-on connection to the relay, so voice messages from
 * the paired phone are received and played automatically — no need to touch the phone.
 */
class IntercomService : Service(), MqttCallbackExtended {

    data class Received(val id: Long, val from: String, val time: Long, val file: File)

    interface Listener {
        fun onStatus(status: String) {}
        fun onHistoryChanged() {}
        fun onSent(msgId: Long, ok: Boolean, error: String?) {}
        fun onPlayedByPeer(msgId: Long, peerName: String) {}
        fun onPeerSeen(peerName: String) {}
    }

    companion object {
        private const val CHANNEL_ID = "intercom"
        private const val NOTIF_ID = 1
        private const val MAX_HISTORY = 25

        @Volatile var instance: IntercomService? = null
            private set
        @Volatile var status: String = "Off"
            private set

        val listeners = CopyOnWriteArraySet<Listener>()
        val history = CopyOnWriteArrayList<Received>() // newest first

        fun start(c: Context) {
            val i = Intent(c, IntercomService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, IntercomService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: Handler
    private lateinit var workerThread: HandlerThread
    private lateinit var player: AudioPlayer
    private val random = SecureRandom()

    @Volatile private var client: MqttAsyncClient? = null
    @Volatile private var crypto: PairCrypto? = null
    @Volatile private var started = false
    private var myId = ""
    private var myName = ""
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopped = false
    @Volatile private var everConnected = false
    private val seenIds = LinkedHashSet<Long>()

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        instance = this
        player = AudioPlayer(this)
        workerThread = HandlerThread("intercom-net").also { it.start() }
        worker = Handler(workerThread.looper)
        createChannel()
        goForeground("Starting…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(status) // every startForegroundService() call must be answered with startForeground()
        if (!started) {
            started = true
            stopped = false
            myId = Prefs.deviceId(this)
            myName = Prefs.deviceName(this)
            val code = Prefs.pairCode(this)
            if (Prefs.normalizeCode(code).length < 8) {
                setStatus("Set a pair code first")
                stopSelf()
                return START_NOT_STICKY
            }
            if (Prefs.keepAwake(this)) acquireWakeLock()
            worker.post {
                crypto = PairCrypto(code) // key derivation takes a moment: keep it off the main thread
                connect()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        instance = null
        val c = client
        client = null
        worker.post {
            try { c?.disconnectForcibly(500, 500) } catch (_: Exception) {}
            try { c?.close() } catch (_: Exception) {}
            workerThread.quitSafely()
        }
        player.release()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        setStatus("Off")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- connection

    private fun connect() {
        if (stopped) return
        try {
            val broker = Prefs.broker(this).trim().ifEmpty { Prefs.DEFAULT_BROKER }
            val clientId = "kji" + myId.take(20)
            val c = client ?: MqttAsyncClient(broker, clientId, MemoryPersistence()).also {
                it.setCallback(this)
                it.setBufferOpts(DisconnectedBufferOptions().apply {
                    setBufferEnabled(true)        // queue messages sent while briefly offline
                    setBufferSize(20)
                    setPersistBuffer(false)
                    setDeleteOldestMessages(true)
                })
                client = it
            }
            if (stopped) { try { c.close() } catch (_: Exception) {}; client = null; return }
            val opts = MqttConnectOptions().apply {
                setCleanSession(false)        // broker keeps messages for us during short drop-outs
                setAutomaticReconnect(true)
                setKeepAliveInterval(60)
                setConnectionTimeout(20)
                setMaxInflight(20)
                val user = Prefs.brokerUser(this@IntercomService)
                if (user.isNotEmpty()) {
                    userName = user
                    password = Prefs.brokerPass(this@IntercomService).toCharArray()
                }
            }
            setStatus("Connecting…")
            c.connect(opts, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) { /* handled in connectComplete */ }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    // Paho only auto-reconnects after a successful first connect, so retry ourselves.
                    if (!everConnected && !stopped) {
                        setStatus("No connection – retrying… (${exception?.message ?: "offline"})")
                        worker.postDelayed({ connect() }, 5000)
                    }
                }
            })
        } catch (e: Exception) {
            setStatus("Error: ${e.message}")
            worker.postDelayed({ connect() }, 10000)
        }
    }

    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
        everConnected = true
        val c = client ?: return
        val topic = crypto?.topic ?: return
        try {
            c.subscribe(topic, 1)
        } catch (e: Exception) {
            setStatus("Subscribe failed: ${e.message}")
            return
        }
        setStatus("Connected – listening")
        sendControl(Packet.TYPE_PING, 0L)
    }

    override fun connectionLost(cause: Throwable?) {
        setStatus("Connection lost – reconnecting…")
    }

    override fun deliveryComplete(token: IMqttDeliveryToken?) {}

    override fun messageArrived(topic: String?, message: MqttMessage?) {
        val bytes = message?.payload ?: return
        worker.post { handleIncoming(bytes) }
    }

    // ---------------------------------------------------------------- receiving

    private fun handleIncoming(bytes: ByteArray) {
        val plain = crypto?.decrypt(bytes) ?: return
        val p = Packet.parse(plain) ?: return
        if (p.senderId == myId) return                 // our own message echoed back
        synchronized(seenIds) {                        // QoS 1 may deliver twice
            if (!seenIds.add(p.msgId)) return
            if (seenIds.size > 200) seenIds.remove(seenIds.first())
        }
        notifyListeners { it.onPeerSeen(p.senderName) }
        when (p.type) {
            Packet.TYPE_AUDIO -> onAudio(p)
            Packet.TYPE_ACK -> notifyListeners { it.onPlayedByPeer(p.ackOf, p.senderName) }
            Packet.TYPE_PING -> { /* presence only */ }
        }
    }

    private fun onAudio(p: Packet) {
        if (p.audio.isEmpty()) return
        val dir = File(cacheDir, "rx").apply { mkdirs() }
        val f = File(dir, "rx_${p.msgId}.m4a")
        f.writeBytes(p.audio)
        val item = Received(p.msgId, p.senderName, System.currentTimeMillis(), f)
        history.add(0, item)
        while (history.size > MAX_HISTORY) history.removeAt(history.size - 1).file.delete()
        notifyListeners { it.onHistoryChanged() }

        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.time))
        main.post {
            player.enqueue(f) {
                sendControl(Packet.TYPE_ACK, p.msgId) // tell the sender it was played
            }
            updateNotification("Last message from ${p.senderName} at $time")
        }
    }

    /** Replay an older message on request. */
    fun replay(item: Received) {
        main.post { player.enqueue(item.file) }
    }

    fun setHoldPlayback(hold: Boolean) {
        main.post { player.holdPlayback = hold }
    }

    // ---------------------------------------------------------------- sending

    /** Encrypts and sends a recorded clip. Returns the message id. */
    fun sendAudio(file: File): Long {
        val id = random.nextLong()
        worker.post {
            val ok = publish(Packet(Packet.TYPE_AUDIO, id, myId, myName, System.currentTimeMillis(), 0L, file.readBytes()))
            file.delete()
            notifyListeners { it.onSent(id, ok == null, ok) }
        }
        return id
    }

    private fun sendControl(type: Int, ackOf: Long) {
        worker.post {
            publish(Packet(type, random.nextLong(), myId, myName, System.currentTimeMillis(), ackOf))
        }
    }

    /** Returns null on success, or an error message. Must run on the worker thread. */
    private fun publish(p: Packet): String? {
        val c = client ?: return "Not connected"
        val cr = crypto ?: return "Not ready"
        return try {
            c.publish(cr.topic, cr.encrypt(p.toBytes()), 1, false)
            null
        } catch (e: Exception) {
            e.message ?: "Send failed"
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun setStatus(s: String) {
        status = s
        main.post {
            if (instance != null) updateNotification(s)
            listeners.forEach { it.onStatus(s) }
        }
    }

    private fun notifyListeners(block: (Listener) -> Unit) {
        main.post { listeners.forEach(block) }
    }

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KJIntercom::listen").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Intercom", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Shows that the intercom is listening"
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_intercom)
            .setContentTitle("Intercom is on")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) { /* notifications may be blocked by the user; the service still runs */ }
    }

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}
