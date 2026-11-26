package com.khalsa.intercom

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.khalsa.intercom.IntercomService.Mode
import com.khalsa.intercom.core.AwayProtocol
import java.io.File

class MainActivity : Activity() {

    private companion object {
        val GOLD = Color.rgb(242, 193, 78)
        val SOFT = Color.rgb(185, 199, 221)
        val DIM = Color.rgb(140, 160, 190)
        val SELECTED_ROW = Color.rgb(56, 92, 140)
        val BG = Color.rgb(11, 30, 58)
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }

    private var service: IntercomService? = null
    private var bound = false

    private lateinit var scrollView: ScrollView
    private lateinit var statusLine: TextView
    private lateinit var detailLine: TextView
    private lateinit var youLine: TextView
    private lateinit var peersHeader: TextView
    private lateinit var peersBox: LinearLayout
    private lateinit var ptt: Button
    private lateinit var hint: TextView
    private lateinit var chatSection: LinearLayout
    private lateinit var chatBox: LinearLayout
    private lateinit var chatHint: TextView
    private lateinit var chatInput: EditText
    private lateinit var modePubBtn: Button
    private lateinit var modePrivBtn: Button
    private lateinit var modeBcastBtn: Button
    private lateinit var modeAwayBtn: Button
    private lateinit var awayPanel: LinearLayout
    private lateinit var awayBox: LinearLayout
    private lateinit var awayStatus: TextView
    private lateinit var awayConnLine: TextView
    private lateinit var awayCodeText: TextView
    private lateinit var broadcastPanel: LinearLayout
    private lateinit var bcastMsg: EditText
    private lateinit var bcastRecBtn: Button
    private lateinit var bcastTime: TextView
    private lateinit var bcastStatus: TextView

    private var lastPeers: List<IntercomService.PeerUi>? = null
    private var lastPeersMode: Mode? = null
    private var lastPeersSel = -1
    private var lastAwayPeers: List<IntercomService.AwayPeerUi>? = null
    private var lastChat: List<IntercomService.ChatUi>? = null
    private var lastChatTail: IntercomService.ChatUi? = null

    private var mediaRecorder: MediaRecorder? = null
    private var recording = false
    private var recStartMs = 0L
    private val recHandler = Handler(Looper.getMainLooper())
    private val recTick = object : Runnable {
        override fun run() {
            if (!recording) return
            val s = ((SystemClock.elapsedRealtime() - recStartMs) / 1000).toInt()
            bcastTime.text = "0:" + minOf(s, 60).toString().padStart(2, '0') + " / 1:00"
            if (s >= 60) stopRecording() else recHandler.postDelayed(this, 200L)
        }
    }

    private val bgModeSel = GradientDrawable().apply {
        setColor(SELECTED_ROW)
        cornerRadius = 24f
        setStroke(3, GOLD)
    }
    private val bgModeUnsel = GradientDrawable().apply {
        setColor(Color.rgb(16, 38, 70))
        cornerRadius = 24f
        setStroke(2, Color.rgb(90, 110, 140))
    }
    private val bgIdle = GradientDrawable().apply {
        setColor(Color.rgb(27, 79, 130))
        cornerRadius = 48f
        setStroke(4, GOLD)
    }
    private val bgOnAir = GradientDrawable().apply {
        setColor(Color.rgb(198, 40, 40))
        cornerRadius = 48f
        setStroke(4, Color.WHITE)
    }

    private val listener = IntercomService.StateListener { render(it) }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            service = (b as? IntercomService.LocalBinder)?.service
            bound = true
            service?.addListener(listener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun weighted(leftMarginDp: Int = 0) = LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(leftMarginDp) }

    private fun row(topPadDp: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(topPadDp), 0, 0)
    }

    private fun modeBtn(text: String, m: Mode): Button = Button(this).apply {
        this.text = text
        textSize = 13f
        isAllCaps = false
        minHeight = dp(54)
        setPadding(dp(2), 0, dp(2), 0)
        setOnClickListener { service?.setMode(m) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(20), dp(22), dp(20), dp(18))
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(dp(20), dp(22), dp(20), dp(18) + insets.systemWindowInsetBottom)
                insets
            }
        }
        scrollView = ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(root)
        }

        root.addView(label("KHALSA INTERCOM", 27f, GOLD, true))
        root.addView(label("Family walkie-talkie — home Wi-Fi or long distance, no accounts", 13.5f, SOFT))

        modePubBtn = modeBtn("📢 Public Telecast", Mode.PUBLIC)
        modePrivBtn = modeBtn("🔒 Private", Mode.PRIVATE)
        modeBcastBtn = modeBtn("🌍 Public Broadcast", Mode.BROADCAST)
        modeAwayBtn = modeBtn("🌍 AWAY — long distance", Mode.AWAY)
        root.addView(row(14).apply {
            addView(modePubBtn, weighted())
            addView(modePrivBtn, weighted(6))
        })
        root.addView(row(6).apply {
            addView(modeBcastBtn, weighted())
            addView(modeAwayBtn, weighted(6))
        })

        // ---- Public Broadcast panel
        broadcastPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        bcastMsg = EditText(this).apply {
            hint = "Type a public message…"
            setTextColor(Color.WHITE)
            setHintTextColor(DIM)
            isSingleLine = false
            maxLines = 3
        }
        broadcastPanel.addView(bcastMsg)
        bcastRecBtn = button("⏺ Record voice") { if (recording) stopRecording() else startRecording() }
        bcastTime = label("0:00 / 1:00", 15f, Color.WHITE, true).apply { setPadding(dp(14), 0, 0, 0) }
        broadcastPanel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(bcastRecBtn, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(bcastTime)
        })
        broadcastPanel.addView(
            button("🌍 Send to public apps") { sendBroadcast() },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) }
        )
        bcastStatus = label("Record a voice take, type a message, or both — then send. Nothing is posted until you pick an app.", 12f, DIM).apply {
            setPadding(0, dp(6), 0, 0)
        }
        broadcastPanel.addView(bcastStatus)
        root.addView(broadcastPanel)

        // ---- AWAY panel
        awayPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        awayPanel.addView(label("Works on mobile data & Wi-Fi — both phones just need this app and the same family code.", 12.5f, SOFT))
        awayPanel.addView(label("Hold TALK and speak, then release: your message is sent and plays out loud on the other phone. You see ✓ when it arrives and ✓✓ when it has played.", 12f, DIM).apply {
            setPadding(0, dp(4), 0, 0)
        })
        awayPanel.addView(label("Voice & chat are sealed with your family code before they leave the phone — the internet relay only passes sealed packets it cannot read.", 12f, DIM).apply {
            setPadding(0, dp(4), 0, 0)
        })
        awayConnLine = label("", 13.5f, Color.WHITE, true).apply { setPadding(0, dp(8), 0, 0) }
        awayPanel.addView(awayConnLine)
        awayPanel.addView(label("YOUR FAMILY CODE — share it with your family:", 11.5f, DIM).apply { setPadding(0, dp(8), 0, 0) })
        awayCodeText = label("", 24f, GOLD, true).apply { setPadding(0, dp(2), 0, 0) }
        awayPanel.addView(awayCodeText)
        awayPanel.addView(row(4).apply {
            addView(button("📋 Copy code") { copyFamilyCode() }, weighted())
            addView(button("🔑 Join with a code") { joinCodeDialog() }, weighted(6))
        })
        awayPanel.addView(label("FAMILY — TAP A NAME TO TALK TO THEM:", 11.5f, DIM).apply { setPadding(0, dp(10), 0, 0) })
        awayBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        awayPanel.addView(awayBox)
        awayStatus = label("", 12f, GOLD).apply { setPadding(0, dp(4), 0, 0) }
        awayPanel.addView(awayStatus)
        root.addView(awayPanel)

        // ---- identity and status
        youLine = label("", 15f, Color.WHITE, true).apply { setPadding(0, dp(16), 0, 0) }
        root.addView(youLine)
        root.addView(
            Button(this).apply {
                text = "Change my name"
                setOnClickListener { renameDialog() }
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) }
        )
        statusLine = label("Starting…", 15f, GOLD, true).apply { setPadding(0, dp(14), 0, 0) }
        root.addView(statusLine)
        detailLine = label("", 12f, DIM).apply { setPadding(0, dp(4), 0, 0) }
        root.addView(detailLine)

        // ---- same-Wi-Fi list
        peersHeader = label("ON THIS WI-FI NOW (0)", 14f, SOFT, true).apply { setPadding(0, dp(18), 0, dp(6)) }
        root.addView(peersHeader)
        peersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(peersBox)

        // ---- chat
        chatSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, dp(4))
        }
        chatSection.addView(label("CHAT", 14f, SOFT, true).apply { setPadding(0, 0, 0, dp(6)) })
        chatBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatSection.addView(chatBox)
        chatHint = label("", 12f, DIM).apply { setPadding(0, dp(8), 0, dp(4)) }
        chatSection.addView(chatHint)
        chatInput = EditText(this).apply {
            hint = "Type a message…"
            setTextColor(Color.WHITE)
            setHintTextColor(DIM)
            isSingleLine = true
        }
        chatSection.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(chatInput, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(button("Send") {
                val t = chatInput.text.toString()
                if (t.isNotBlank() && service?.sendChat(t) == true) chatInput.text.clear()
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
        })
        root.addView(chatSection)

        // ---- talk button
        hint = label("Everyone with the app open on this Wi-Fi hears you while you hold the button.", 12.5f, SOFT).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(hint)
        ptt = Button(this).apply {
            text = "🎙  HOLD TO TALK"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = bgIdle
            isEnabled = false
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> if (service?.mode != Mode.BROADCAST) {
                        vibrate()
                        service?.startTalk()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        service?.stopTalk()
                        v.performClick()
                    }
                }
                true
            }
            setOnClickListener { }
        }
        root.addView(ptt, LinearLayout.LayoutParams(MATCH, dp(132)))
        root.addView(
            Button(this).apply {
                text = "Turn intercom off"
                setOnClickListener {
                    service?.removeListener(listener)
                    if (bound) {
                        try {
                            unbindService(conn)
                        } catch (e: Exception) {
                        }
                        bound = false
                    }
                    stopService(Intent(this@MainActivity, IntercomService::class.java))
                    finish()
                }
            },
            LinearLayout.LayoutParams(WRAP, WRAP).apply {
                topMargin = dp(10)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )

        setContentView(scrollView)
        ensurePermissionsAndStart()
    }

    // ---------------------------------------------------------------- Public Broadcast recording

    private fun broadcastFile(): File {
        val dir = File(cacheDir, "broadcast")
        dir.mkdirs()
        return File(dir, "public-message.m4a")
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            bcastStatus.text = "Microphone permission is needed to record — allow it, then reopen the app."
            return
        }
        val f = broadcastFile()
        if (f.exists()) f.delete()
        @Suppress("DEPRECATION")
        val mr = MediaRecorder()
        try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(44100)
            mr.setAudioEncodingBitRate(128_000)
            mr.setMaxDuration(60_000)
            mr.setOutputFile(f.absolutePath)
            mr.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopRecording()
            }
            mr.prepare()
            mr.start()
            mediaRecorder = mr
            recording = true
            recStartMs = SystemClock.elapsedRealtime()
            bcastRecBtn.text = "⏹ Stop"
            bcastTime.text = "0:00 / 1:00"
            bcastStatus.text = "Recording… tap Stop when done (max 60 s)."
            recHandler.post(recTick)
        } catch (e: Exception) {
            try {
                mr.release()
            } catch (e2: Exception) {
            }
            bcastStatus.text = "Recorder problem: " + (e.message ?: "could not start")
        }
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        recHandler.removeCallbacks(recTick)
        var stopFailed = false
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            stopFailed = true
        }
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
        }
        mediaRecorder = null
        bcastRecBtn.text = "⏺ Record voice"
        bcastTime.text = "0:00 / 1:00"
        val f = broadcastFile()
        when {
            stopFailed -> {
                if (f.exists()) f.delete()
                bcastStatus.text = "That take was too short — hold a second, then record again."
            }
            f.exists() && f.length() > 0 ->
                bcastStatus.text = "Voice take ready (${f.length() / 1024} KB). Send it, or record again to replace it."
            else -> bcastStatus.text = "That take was empty — try recording again."
        }
    }

    private fun sendBroadcast() {
        if (recording) stopRecording()
        val text = bcastMsg.text.toString().trim()
        val f = broadcastFile()
        val hasTake = f.exists() && f.length() > 0
        val intent = Intent(Intent.ACTION_SEND)
        if (hasTake) {
            val uri = BroadcastFileProvider.uriFor(this, f)
            intent.type = "audio/mp4"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.clipData = ClipData.newRawUri("public message", uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (text.isNotEmpty()) intent.putExtra(Intent.EXTRA_TEXT, text)
        } else if (text.isNotEmpty()) {
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_TEXT, text)
        } else {
            bcastStatus.text = "Type a message or record a voice take first."
            return
        }
        try {
            startActivity(Intent.createChooser(intent, "Send public message via…"))
        } catch (e: Exception) {
            bcastStatus.text = "No app on this phone can take that share."
        }
    }

    // ---------------------------------------------------------------- dialogs and helpers

    private fun vibrate() {
        try {
            @Suppress("DEPRECATION")
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vib.vibrate(VibrationEffect.createOneShot(35L, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
        }
    }

    private fun renameDialog() {
        val edit = EditText(this).apply {
            setText(Identity.name(this@MainActivity))
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle("Your name on the intercom")
            .setView(edit)
            .setPositiveButton("Save") { _, _ ->
                val n = edit.text.toString()
                if (n.isNotBlank()) {
                    val svc = service
                    if (svc != null) svc.rename(n) else Identity.setName(this, n.trim().take(24))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyFamilyCode() {
        val code = FamilyCodeStore.code(this)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("KHALSA Intercom family code", code))
        awayStatus.text = "Code copied — send it to your family. They open this app, tap 🌍 AWAY, then “Join with a code”."
    }

    private fun joinCodeDialog() {
        val codeEdit = EditText(this).apply {
            hint = "Family code — e.g. AB3FG HJKMP"
            isSingleLine = true
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(label("Type the family code shown on the other phone. This phone leaves its current family and joins that one.", 13f, SOFT))
            addView(codeEdit)
        }
        AlertDialog.Builder(this)
            .setTitle("Join with a family code")
            .setView(box)
            .setPositiveButton("Join") { _, _ ->
                val code = AwayProtocol.normalizeCode(codeEdit.text.toString())
                when {
                    !AwayProtocol.isValidCode(code) ->
                        awayStatus.text = "That code doesn't look right — it is 10 letters and numbers, like AB3FG HJKMP."
                    code == FamilyCodeStore.code(this) ->
                        awayStatus.text = "That is already this phone's family code."
                    else -> {
                        val svc = service
                        if (svc != null) svc.joinFamily(code) else FamilyCodeStore.setCode(this, code)
                        awayStatus.text = "Joined the new family — when their app is open with the same code, their name appears in the list."
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun ensurePermissionsAndStart() {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 7) else startIntercom()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startIntercom()
            return
        }
        statusLine.text = "Microphone permission is needed — a walkie-talkie cannot work without it. Tap the app settings to allow it, then reopen."
        hint.text = "Allow Microphone in Settings → Apps → KHALSA Intercom → Permissions."
    }

    private fun startIntercom() {
        val intent = Intent(this, IntercomService::class.java)
        startForegroundService(intent)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
        statusLine.text = "Listening…"
    }

    // ---------------------------------------------------------------- rendering

    private fun styleModeBtn(btn: Button, selected: Boolean) {
        btn.background = if (selected) bgModeSel else bgModeUnsel
        btn.setTextColor(if (selected) Color.WHITE else SOFT)
        btn.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun setPtt(text: String, onAir: Boolean, enabled: Boolean = true) {
        ptt.text = text
        ptt.background = if (onAir) bgOnAir else bgIdle
        ptt.isEnabled = enabled
    }

    private fun render(state: IntercomService.UiState) {
        youLine.text = "You: ${state.myName}"
        detailLine.text = state.detail
        styleModeBtn(modePubBtn, state.mode == Mode.PUBLIC)
        styleModeBtn(modePrivBtn, state.mode == Mode.PRIVATE)
        styleModeBtn(modeBcastBtn, state.mode == Mode.BROADCAST)
        styleModeBtn(modeAwayBtn, state.mode == Mode.AWAY)

        if (state.mode == Mode.BROADCAST) {
            broadcastPanel.visibility = View.VISIBLE
        } else {
            if (recording) stopRecording()
            broadcastPanel.visibility = View.GONE
        }
        awayPanel.visibility = if (state.mode == Mode.AWAY) View.VISIBLE else View.GONE
        awayConnLine.text = if (state.awayConnected) "🟢 Connected — long distance is live" else "⏳ Connecting… (needs internet — mobile data or Wi-Fi)"
        if (state.familyCode.isNotEmpty()) awayCodeText.text = AwayProtocol.displayCode(state.familyCode)

        if (state.awayPeers != lastAwayPeers) {
            lastAwayPeers = state.awayPeers
            awayBox.removeAllViews()
            if (state.awayPeers.isEmpty()) {
                awayBox.addView(
                    label(
                        "Nobody else has joined this family yet — or their app is closed right now.\nShare your code: they install this app, tap 🌍 AWAY, then “Join with a code” and type it once. Their name appears here whenever their app is open.",
                        13.5f, SOFT
                    )
                )
            } else {
                for (c in state.awayPeers) {
                    val suffix = when {
                        c.speaking -> "  — speaking…"
                        c.selected -> "  — talking target"
                        !c.online -> "  — not online now"
                        else -> ""
                    }
                    val r = label((if (c.online) "🟢" else "⚪") + "  " + c.name + suffix, 15.5f, Color.WHITE)
                    r.setPadding(dp(6), dp(7), dp(6), dp(7))
                    if (c.selected) {
                        r.setBackgroundColor(SELECTED_ROW)
                        r.typeface = Typeface.DEFAULT_BOLD
                    }
                    r.setOnClickListener { service?.selectAwayMember(c.id) }
                    awayBox.addView(r)
                }
                awayBox.addView(label("Tap a name to choose who you talk to. Chat goes to the tapped name too — or to everyone in the family when no name is tapped.", 11.5f, DIM).apply {
                    setPadding(0, dp(4), 0, 0)
                })
            }
        }

        chatSection.visibility = if (state.mode == Mode.BROADCAST) View.GONE else View.VISIBLE
        peersHeader.text = "ON THIS WI-FI NOW (${state.peers.size})"
        if (state.peers != lastPeers || state.mode != lastPeersMode || state.selectedPeerId != lastPeersSel) {
            lastPeers = state.peers
            lastPeersMode = state.mode
            lastPeersSel = state.selectedPeerId
            peersBox.removeAllViews()
            if (state.peers.isEmpty()) {
                peersBox.addView(label("Nobody yet — open the app on the other phone, on this same Wi-Fi.", 14f, SOFT))
            } else {
                for (peer in state.peers) {
                    val selected = state.mode == Mode.PRIVATE && peer.id == state.selectedPeerId
                    val suffix = when {
                        peer.speaking -> "  — speaking…"
                        selected -> "  — private target"
                        else -> ""
                    }
                    val r = label((if (peer.speaking) "🟢" else "⚪") + "  " + peer.name + suffix, 16f, Color.WHITE)
                    r.setPadding(dp(6), dp(5), dp(6), dp(5))
                    if (selected) {
                        r.setBackgroundColor(SELECTED_ROW)
                        r.typeface = Typeface.DEFAULT_BOLD
                    }
                    if (state.mode == Mode.PRIVATE) r.setOnClickListener { service?.toggleSelectPeer(peer.id) }
                    peersBox.addView(r)
                }
            }
        }

        if (state.chat != lastChat) {
            lastChat = state.chat
            chatBox.removeAllViews()
            if (state.chat.isEmpty()) {
                chatBox.addView(label("No messages yet — type below and tap Send. Handy when it's too noisy to talk.", 13.5f, SOFT))
            } else {
                for (m in state.chat) {
                    val who = if (m.mine) "You" else m.senderName
                    val lock = if (m.isPrivate) " 🔒" else ""
                    val globe = if (m.away) " 🌍" else ""
                    chatBox.addView(label("$who$lock$globe:  ${m.text}", 14.5f, if (m.mine) GOLD else Color.WHITE).apply {
                        setPadding(dp(2), dp(3), dp(2), dp(3))
                    })
                }
            }
        }
        val tail = state.chat.lastOrNull()
        if (tail != null && tail != lastChatTail) scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        lastChatTail = tail

        val selName = state.peers.firstOrNull { it.id == state.selectedPeerId }?.name
        val awaySel = state.awayPeers.firstOrNull { it.selected }
        chatHint.text = when (state.mode) {
            Mode.PRIVATE -> if (selName != null) "Chat goes to: 🔒 $selName only" else "Chat: tap a name in the list above, then your typing goes to them only"
            Mode.AWAY -> if (awaySel != null) "Chat goes to: 🌍 ${awaySel.name} only (long distance)" else "Chat goes to: everyone in your family (long distance)"
            else -> "Chat goes to: everyone on this Wi-Fi"
        }
        hint.text = when (state.mode) {
            Mode.PRIVATE -> "Private: tap a name in the list, then hold TALK — only that phone hears you."
            Mode.AWAY -> "AWAY: tap a family name above, hold TALK and speak, then release — it plays on their phone, on mobile data or Wi-Fi."
            Mode.PUBLIC -> "Everyone with the app open on this Wi-Fi hears you while you hold the button."
            Mode.BROADCAST -> "Broadcast: nothing leaves this phone until you pick an app in the share sheet."
        }

        if (state.mode == Mode.BROADCAST) {
            statusLine.text = "🌍 Public Broadcast — record or type, then send"
            setPtt("🌍 BROADCAST MODE — USE THE PANEL", onAir = false, enabled = false)
            return
        }
        if (state.transmitting) {
            statusLine.text = when (state.mode) {
                Mode.PRIVATE -> "🔴 ON AIR — only ${selName ?: "your chosen phone"} can hear you"
                Mode.AWAY -> "🔴 RECORDING for 🌍 ${awaySel?.name ?: "your family phone"} — release to send"
                else -> "🔴 ON AIR — everyone on this Wi-Fi hears you"
            }
            setPtt(if (state.mode == Mode.AWAY) "● RECORDING — RELEASE TO SEND" else "● ON AIR — RELEASE TO STOP", onAir = true)
            return
        }
        setPtt("🎙  HOLD TO TALK", onAir = false)
        val awaySpeaking = state.awayPeers.firstOrNull { it.speaking }
        statusLine.text = when {
            awaySpeaking != null -> "🔊 🌍 ${awaySpeaking.name} is speaking…"
            state.speakingNow != null -> "🔊 ${state.speakingNow} is speaking…"
            state.mode == Mode.AWAY && awaySel != null && awaySel.online -> "🌍 AWAY → ${awaySel.name}"
            state.mode == Mode.AWAY && awaySel != null -> "🌍 ${awaySel.name} is not online right now"
            state.mode == Mode.AWAY -> "🌍 AWAY — tap a name in the family list"
            state.mode == Mode.PRIVATE && selName != null -> "🔒 Private → $selName"
            state.mode == Mode.PRIVATE -> "🔒 Private — tap a name in the list"
            else -> "Listening…"
        }
    }

    override fun onPause() {
        service?.stopTalk()
        super.onPause()
    }

    override fun onDestroy() {
        service?.stopTalk()
        if (recording) stopRecording()
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
        }
        service?.removeListener(listener)
        if (bound) {
            try {
                unbindService(conn)
            } catch (e: Exception) {
            }
            bound = false
        }
        super.onDestroy()
    }
}
