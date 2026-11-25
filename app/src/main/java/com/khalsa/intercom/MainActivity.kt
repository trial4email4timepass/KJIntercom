package com.khalsa.intercom

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity(), IntercomService.Listener {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var peerText: TextView
    private lateinit var powerSwitch: Switch
    private lateinit var talkButton: Button
    private lateinit var talkHint: TextView
    private lateinit var sentText: TextView
    private lateinit var historyList: LinearLayout
    private lateinit var nameInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var brokerInput: EditText
    private lateinit var userInput: EditText
    private lateinit var passInput: EditText
    private lateinit var keepAwakeSwitch: Switch

    private lateinit var recorder: VoiceRecorder
    private val main = Handler(Looper.getMainLooper())
    private var recordStart = 0L
    private var suppressSwitch = false

    private val ticker = object : Runnable {
        override fun run() {
            if (recorder.isRecording) {
                val s = (SystemClock.elapsedRealtime() - recordStart) / 1000
                talkButton.text = "● ${s}s\nRELEASE\nTO SEND"
                main.postDelayed(this, 250)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        recorder = VoiceRecorder(this)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        peerText = findViewById(R.id.peerText)
        powerSwitch = findViewById(R.id.powerSwitch)
        talkButton = findViewById(R.id.talkButton)
        talkHint = findViewById(R.id.talkHint)
        sentText = findViewById(R.id.sentText)
        historyList = findViewById(R.id.historyList)
        nameInput = findViewById(R.id.nameInput)
        codeInput = findViewById(R.id.codeInput)
        brokerInput = findViewById(R.id.brokerInput)
        userInput = findViewById(R.id.userInput)
        passInput = findViewById(R.id.passInput)
        keepAwakeSwitch = findViewById(R.id.keepAwakeSwitch)

        nameInput.setText(Prefs.deviceName(this))
        codeInput.setText(Prefs.pairCode(this))
        brokerInput.setText(Prefs.broker(this))
        userInput.setText(Prefs.brokerUser(this))
        passInput.setText(Prefs.brokerPass(this))
        keepAwakeSwitch.isChecked = Prefs.keepAwake(this)

        findViewById<Button>(R.id.generateButton).setOnClickListener {
            codeInput.setText(Prefs.generatePairCode())
            toast("New code made. Type the same code on the other phone, then tap Save & connect on both.")
        }
        findViewById<Button>(R.id.shareButton).setOnClickListener { shareCode() }
        findViewById<Button>(R.id.saveButton).setOnClickListener { saveAndConnect() }
        findViewById<Button>(R.id.batteryButton).setOnClickListener { askBatteryExemption() }
        findViewById<Button>(R.id.testButton).setOnClickListener {
            val last = IntercomService.history.firstOrNull()
            val svc = IntercomService.instance
            if (last == null || svc == null) toast("No received message yet") else svc.replay(last)
        }
        keepAwakeSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setKeepAwake(this, checked)
            if (IntercomService.instance != null) restartService()
        }
        val advBox = findViewById<View>(R.id.advancedBox)
        val advToggle = findViewById<TextView>(R.id.advancedToggle)
        advToggle.setOnClickListener {
            val show = advBox.visibility != View.VISIBLE
            advBox.visibility = if (show) View.VISIBLE else View.GONE
            advToggle.text = if (show) "Advanced: relay server ▾" else "Advanced: relay server ▸"
        }

        powerSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            if (checked) saveAndConnect() else turnOff()
        }

        setupTalkButton()
        requestStartupPermissions()

        // First launch: offer a code so the user only has to copy it to the other phone.
        if (Prefs.pairCode(this).isEmpty()) codeInput.setText(Prefs.generatePairCode())
    }

    override fun onResume() {
        super.onResume()
        IntercomService.listeners.add(this)
        if (Prefs.enabled(this) && IntercomService.instance == null &&
            Prefs.normalizeCode(Prefs.pairCode(this)).length >= 8
        ) {
            IntercomService.start(this)
        }
        onStatus(IntercomService.status)
        onHistoryChanged()
    }

    override fun onPause() {
        IntercomService.listeners.remove(this)
        if (recorder.isRecording) finishRecording(send = false)
        super.onPause()
    }

    // ---------------------------------------------------------------- talk button

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTalkButton() {
        talkButton.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true) // don't let the scroll view steal the hold
                    startRecording()
                    true
                }
                MotionEvent.ACTION_UP -> { finishRecording(send = true); true }
                MotionEvent.ACTION_CANCEL -> { finishRecording(send = false); true }
                else -> true
            }
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        if (IntercomService.instance == null) {
            toast("Turn the intercom on first (switch at the top)")
            return
        }
        IntercomService.instance?.setHoldPlayback(true)
        if (!recorder.start { finishRecording(send = true) }) {
            IntercomService.instance?.setHoldPlayback(false)
            toast("Microphone is busy or unavailable")
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        talkButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        talkButton.isPressed = true
        recordStart = SystemClock.elapsedRealtime()
        main.post(ticker)
    }

    private fun finishRecording(send: Boolean) {
        if (!recorder.isRecording) return
        main.removeCallbacks(ticker)
        talkButton.isPressed = false
        talkButton.text = "HOLD\nTO TALK"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val file = recorder.stop()
        IntercomService.instance?.setHoldPlayback(false)
        if (!send) { file?.delete(); return }
        if (file == null) {
            toast("Too short – hold the button while you speak")
            return
        }
        val svc = IntercomService.instance
        if (svc == null) {
            file.delete()
            toast("Intercom is off")
            return
        }
        talkButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        svc.sendAudio(file)
        sentText.text = "Sending…"
    }

    // ---------------------------------------------------------------- service callbacks

    override fun onStatus(status: String) {
        statusText.text = status
        val color = when {
            status.startsWith("Connected") -> Color.parseColor("#2E7D32")
            status == "Off" -> Color.parseColor("#9E9E9E")
            else -> Color.parseColor("#F9A825")
        }
        (statusDot.background.mutate() as? GradientDrawable)?.setColor(color)
        suppressSwitch = true
        powerSwitch.isChecked = IntercomService.instance != null
        suppressSwitch = false
    }

    override fun onSent(msgId: Long, ok: Boolean, error: String?) {
        sentText.text = if (ok) "✓ Sent – waiting for it to play…" else "✗ Not sent: $error"
    }

    override fun onPlayedByPeer(msgId: Long, peerName: String) {
        sentText.text = "✓✓ Played on $peerName"
    }

    override fun onPeerSeen(peerName: String) {
        val t = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        peerText.text = "Paired phone: $peerName (seen $t)"
    }

    override fun onHistoryChanged() {
        historyList.removeAllViews()
        val items = IntercomService.history
        if (items.isEmpty()) {
            historyList.addView(TextView(this).apply {
                text = "Nothing yet"
                setTextColor(getColor(R.color.muted))
                setPadding(24, 24, 24, 24)
            })
            return
        }
        val fmt = DateFormat.getTimeInstance(DateFormat.SHORT)
        for (item in items) {
            historyList.addView(TextView(this).apply {
                text = "▶  ${item.from}  ·  ${fmt.format(Date(item.time))}"
                setTextColor(getColor(R.color.text))
                textSize = 15f
                setPadding(24, 28, 24, 28)
                setOnClickListener { IntercomService.instance?.replay(item) ?: toast("Intercom is off") }
            })
        }
    }

    // ---------------------------------------------------------------- settings

    private fun saveAndConnect() {
        val code = codeInput.text.toString().trim()
        if (Prefs.normalizeCode(code).length < 8) {
            toast("Pair code is too short. Tap \"New code\".")
            suppressSwitch = true; powerSwitch.isChecked = false; suppressSwitch = false
            return
        }
        Prefs.setPairCode(this, code)
        Prefs.setDeviceName(this, nameInput.text.toString().trim().ifEmpty { Build.MODEL ?: "Phone" })
        Prefs.setBroker(this, brokerInput.text.toString().trim().ifEmpty { Prefs.DEFAULT_BROKER })
        Prefs.setBrokerUser(this, userInput.text.toString().trim())
        Prefs.setBrokerPass(this, passInput.text.toString())
        Prefs.setEnabled(this, true)
        restartService()
        toast("Saved. Use the same pair code on the other phone.")
    }

    private fun turnOff() {
        Prefs.setEnabled(this, false)
        IntercomService.stop(this)
        main.postDelayed({ onStatus(IntercomService.status) }, 300)
    }

    private fun restartService() {
        IntercomService.stop(this)
        main.postDelayed({ IntercomService.start(this) }, 600)
    }

    private fun shareCode() {
        val code = codeInput.text.toString().trim()
        if (code.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Intercom pair code: $code")
        }
        startActivity(Intent.createChooser(send, "Send pair code"))
    }

    // ---------------------------------------------------------------- permissions

    private fun requestStartupPermissions() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Already allowed. On Xiaomi/Oppo/Vivo/Samsung also set battery to \"Unrestricted\" and allow Autostart in the app's settings.")
            openAppSettings()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {}
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
