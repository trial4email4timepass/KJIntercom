package com.khalsa.intercom

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/** Records a short AAC voice clip (small enough to send quickly over mobile data). */
class VoiceRecorder(private val context: Context) {

    companion object {
        const val MAX_MS = 30_000
        private const val MIN_MS = 500L
    }

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording get() = recorder != null

    /** @param onMaxReached called (on the main thread) when the 30-second limit is hit. */
    fun start(onMaxReached: () -> Unit): Boolean {
        if (recorder != null) return true
        val out = File(context.cacheDir, "tx_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(22050)
            r.setAudioEncodingBitRate(32000)
            r.setMaxDuration(MAX_MS)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxReached()
            }
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = out
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            r.release()
            out.delete()
            false
        }
    }

    /** Stops recording. Returns the clip, or null if it was too short / failed. */
    fun stop(): File? {
        val r = recorder ?: return null
        val f = file
        recorder = null
        file = null
        val length = SystemClock.elapsedRealtime() - startedAt
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            false // thrown when stopped almost immediately
        } finally {
            r.release()
        }
        if (!ok || length < MIN_MS || f == null || !f.exists() || f.length() == 0L) {
            f?.delete()
            return null
        }
        return f
    }

    fun cancel() {
        stop()?.delete()
    }
}
