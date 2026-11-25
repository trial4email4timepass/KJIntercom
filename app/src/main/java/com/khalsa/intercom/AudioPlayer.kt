package com.khalsa.intercom

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.io.File

/**
 * Plays incoming messages one after another, automatically, with a short intercom chime first.
 * All methods must be called on the main thread.
 */
class AudioPlayer(private val context: Context) {

    private data class Item(val file: File, val onStart: () -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Item>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var busy = false

    /** While true (user is holding the talk button) incoming audio waits, so it isn't re-recorded. */
    var holdPlayback = false
        set(value) {
            field = value
            if (!value) playNext()
        }

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun enqueue(file: File, onStart: () -> Unit = {}) {
        queue.addLast(Item(file, onStart))
        playNext()
    }

    private fun playNext() {
        if (busy || holdPlayback) return
        val item = queue.removeFirstOrNull() ?: run { abandonFocus(); return }
        busy = true
        requestFocus()
        chime()
        main.postDelayed({ startPlayer(item) }, 350)
    }

    private fun chime() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
            main.postDelayed({ tg.release() }, 400)
        } catch (_: Exception) { /* some devices refuse ToneGenerator; not important */ }
    }

    private fun startPlayer(item: Item) {
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(item.file.absolutePath)
            mp.setOnCompletionListener { finish() }
            mp.setOnErrorListener { _, _, _ -> finish(); true }
            mp.prepare()
            player = mp
            item.onStart()
            mp.start()
        } catch (e: Exception) {
            finish()
        }
    }

    private fun finish() {
        player?.release()
        player = null
        busy = false
        main.postDelayed({ playNext() }, 250)
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        audioManager.requestAudioFocus(req)
        focusRequest = req
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    fun release() {
        queue.clear()
        player?.release()
        player = null
        busy = false
        abandonFocus()
    }
}
