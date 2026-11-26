package com.khalsa.intercom.net

import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * One TLS connection to one MQTT relay. Reconnects on its own with backoff.
 *
 * Dead links are found fast: our own presence messages echo back every 10 s, a PINGREQ
 * goes out every 20 s no matter how much we are sending, and a missing PINGRESP or a
 * long silence closes the socket so the manager reconnects. (v2.2 only pinged after
 * 30 s without writes, which never happened while presence was publishing, so a dead
 * mobile connection looked "connected" for up to 90 s.)
 */
class MqttClient(
    val host: String,
    val port: Int,
    private val clientId: String,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected(client: MqttClient)
        fun onDisconnected(client: MqttClient)
        fun onMessage(client: MqttClient, topic: String, payload: ByteArray)
    }

    companion object {
        const val KEEPALIVE_SEC = 60
        const val PING_EVERY_MS = 20_000L
        const val PING_DEADLINE_MS = 10_000L
        const val INBOUND_DEADLINE_MS = 45_000L
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val HANDSHAKE_TIMEOUT_MS = 20_000
        private const val BACKOFF_MIN_MS = 2_000L
        private const val BACKOFF_MAX_MS = 30_000L
    }

    @Volatile var connected = false
        private set
    @Volatile var lastError: String? = null
        private set

    @Volatile private var running = false
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var generation = 0
    @Volatile private var lastInboundMs = 0L
    @Volatile private var lastPingMs = 0L
    @Volatile private var pingSentMs = 0L
    private var managerThread: Thread? = null
    private val writeLock = ReentrantLock()
    private val subLock = Any()
    private val subscriptions = LinkedHashSet<String>()
    private val packetIds = AtomicInteger(1)

    val label: String get() = host

    fun start() {
        if (running) return
        running = true
        managerThread = Thread({ managerRun() }, "mqtt-$host").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        if (connected) {
            try {
                writeRaw(MqttWire.encodeDisconnect())
            } catch (e: Exception) {
            }
        }
        connected = false
        closeSocket()
        managerThread?.interrupt()
        managerThread = null
    }

    fun subscribe(topic: String) {
        val added = synchronized(subLock) { subscriptions.add(topic) }
        if (added && connected) {
            try {
                writeRaw(MqttWire.encodeSubscribe(nextPacketId(), listOf(topic)))
            } catch (e: Exception) {
                closeSocket()
            }
        }
    }

    /** Sends one QoS 0 message. Returns false if it could not be written. Never call on the main thread. */
    fun publish(topic: String, payload: ByteArray): Boolean {
        if (!connected) return false
        return try {
            writeRaw(MqttWire.encodePublish(topic, payload))
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            closeSocket()
            false
        }
    }

    private fun nextPacketId(): Int = ((packetIds.getAndIncrement() and Int.MAX_VALUE) % 65535) + 1

    private fun writeRaw(bytes: ByteArray) {
        val out = output ?: throw IllegalStateException("not connected")
        writeLock.lock()
        try {
            out.write(bytes)
            out.flush()
        } finally {
            writeLock.unlock()
        }
    }

    /**
     * Ping write that never waits behind a stuck sender. If a big clip write is blocked on a
     * dead mobile link, the ping thread must stay free to notice the silence and close the
     * socket (which also unblocks that writer). Returns false when the lock was busy.
     */
    private fun tryWritePing(): Boolean {
        val out = output ?: throw IllegalStateException("not connected")
        if (!writeLock.tryLock(50, TimeUnit.MILLISECONDS)) return false
        try {
            out.write(MqttWire.encodePingreq())
            out.flush()
        } finally {
            writeLock.unlock()
        }
        return true
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (e: Exception) {
        }
        socket = null
        output = null
    }

    private fun managerRun() {
        var backoff = BACKOFF_MIN_MS
        while (running) {
            val served = try {
                connectAndServe()
            } catch (e: Exception) {
                false
            }
            if (!running) return
            if (served) backoff = BACKOFF_MIN_MS
            try {
                Thread.sleep(backoff)
            } catch (e: InterruptedException) {
                return
            }
            backoff = minOf(backoff * 2, BACKOFF_MAX_MS)
        }
    }

    /** Returns true if a session was established (so the next retry starts fast). */
    private fun connectAndServe(): Boolean {
        var sock: SSLSocket? = null
        var established = false
        try {
            sock = SSLSocketFactory.getDefault().createSocket() as SSLSocket
            val params = sock.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            sock.sslParameters = params
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            sock.soTimeout = HANDSHAKE_TIMEOUT_MS
            sock.startHandshake()
            socket = sock
            output = sock.getOutputStream()
            val gen = ++generation
            val input = sock.getInputStream()

            writeRaw(MqttWire.encodeConnect(clientId, KEEPALIVE_SEC))
            val connack = MqttWire.readPacket(input) ?: throw IllegalStateException("relay closed before CONNACK")
            if (connack.type != MqttWire.T_CONNACK || connack.connackCode != 0) {
                throw IllegalStateException("relay refused the connection (code ${connack.connackCode})")
            }
            lastInboundMs = System.currentTimeMillis()
            pingSentMs = 0L
            lastPingMs = lastInboundMs

            val subs = synchronized(subLock) { subscriptions.toList() }
            if (subs.isNotEmpty()) {
                val pid = nextPacketId()
                writeRaw(MqttWire.encodeSubscribe(pid, subs))
                while (true) {
                    val pkt = MqttWire.readPacket(input) ?: throw IllegalStateException("relay closed during subscribe")
                    lastInboundMs = System.currentTimeMillis()
                    if (pkt.type == MqttWire.T_SUBACK && pkt.packetId == pid) {
                        if (pkt.payload.any { (it.toInt() and 0xFF) == 0x80 }) {
                            throw IllegalStateException("relay refused the family subscription")
                        }
                        break
                    }
                    val topic = pkt.topic
                    if (pkt.type == MqttWire.T_PUBLISH && topic != null) listener.onMessage(this, topic, pkt.payload)
                }
            }

            sock.soTimeout = 0
            established = true
            lastError = null
            connected = true
            listener.onConnected(this)
            startPingThread(gen)

            while (running) {
                val pkt = MqttWire.readPacket(input) ?: break
                lastInboundMs = System.currentTimeMillis()
                pingSentMs = 0L
                val topic = pkt.topic
                if (pkt.type == MqttWire.T_PUBLISH && topic != null) {
                    try {
                        listener.onMessage(this, topic, pkt.payload)
                    } catch (e: Exception) {
                        // A bad message must never take the connection down.
                    }
                } else if (pkt.type == MqttWire.T_SUBACK && pkt.payload.any { (it.toInt() and 0xFF) == 0x80 }) {
                    lastError = "relay refused a subscription"
                    break
                }
            }
        } catch (e: Exception) {
            if (running) lastError = e.message ?: e.javaClass.simpleName
        } finally {
            val wasConnected = connected
            connected = false
            if (socket === sock) {
                closeSocket()
            } else {
                try {
                    sock?.close()
                } catch (e: Exception) {
                }
            }
            if (wasConnected) listener.onDisconnected(this)
        }
        return established
    }

    private fun startPingThread(gen: Int) {
        Thread({
            while (running && gen == generation && connected) {
                try {
                    Thread.sleep(2_000L)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (!connected || gen != generation) return@Thread
                val now = System.currentTimeMillis()
                val ps = pingSentMs
                if (ps > 0 && now - ps > PING_DEADLINE_MS) {
                    lastError = "relay stopped answering"
                    closeSocket()
                    return@Thread
                }
                if (now - lastInboundMs > INBOUND_DEADLINE_MS) {
                    lastError = "no data from relay"
                    closeSocket()
                    return@Thread
                }
                if (ps == 0L && now - lastPingMs >= PING_EVERY_MS) {
                    try {
                        if (tryWritePing()) {
                            lastPingMs = now
                            pingSentMs = now
                        }
                    } catch (e: Exception) {
                        closeSocket()
                        return@Thread
                    }
                }
            }
        }, "mqtt-ping-$host").apply {
            isDaemon = true
            start()
        }
    }
}
