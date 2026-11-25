package com.khalsa.intercom

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.security.SecureRandom
import java.util.UUID

object Prefs {
    const val DEFAULT_BROKER = "ssl://broker.hivemq.com:8883"

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("intercom", Context.MODE_PRIVATE)

    /** Random per-install ID; used to ignore our own messages and as the MQTT client ID. */
    fun deviceId(c: Context): String {
        val p = sp(c)
        p.getString("device_id", null)?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "")
        p.edit().putString("device_id", id).apply()
        return id
    }

    fun pairCode(c: Context): String = sp(c).getString("pair_code", "") ?: ""
    fun setPairCode(c: Context, v: String) = sp(c).edit().putString("pair_code", v).apply()

    fun deviceName(c: Context): String =
        sp(c).getString("device_name", null) ?: (Build.MODEL ?: "Phone")
    fun setDeviceName(c: Context, v: String) = sp(c).edit().putString("device_name", v).apply()

    fun broker(c: Context): String = sp(c).getString("broker", DEFAULT_BROKER) ?: DEFAULT_BROKER
    fun setBroker(c: Context, v: String) = sp(c).edit().putString("broker", v).apply()

    fun brokerUser(c: Context): String = sp(c).getString("broker_user", "") ?: ""
    fun setBrokerUser(c: Context, v: String) = sp(c).edit().putString("broker_user", v).apply()

    fun brokerPass(c: Context): String = sp(c).getString("broker_pass", "") ?: ""
    fun setBrokerPass(c: Context, v: String) = sp(c).edit().putString("broker_pass", v).apply()

    /** Hold a partial wake lock so the connection survives deep sleep (more reliable, more battery). */
    fun keepAwake(c: Context): Boolean = sp(c).getBoolean("keep_awake", true)
    fun setKeepAwake(c: Context, v: Boolean) = sp(c).edit().putBoolean("keep_awake", v).apply()

    /** True while the user wants the intercom running (also used to restart after reboot). */
    fun enabled(c: Context): Boolean = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    // No 0/O/1/I/L so codes are easy to read aloud and type.
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    fun generatePairCode(): String {
        val r = SecureRandom()
        return (0 until 4).joinToString("-") {
            (0 until 4).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
        }
    }

    fun normalizeCode(code: String): String =
        code.uppercase().filter { it.isLetterOrDigit() }
}
