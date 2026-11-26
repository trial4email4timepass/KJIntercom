package com.khalsa.intercom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.khalsa.intercom.core.AwayProtocol
import java.security.SecureRandom

private const val PREFS = "khalsa_intercom"

/** This phone's random id and display name. */
object Identity {
    fun id(ctx: Context): Int {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = sp.getInt("device_id", 0)
        if (v != 0) return v
        return regenerate(ctx)
    }

    fun regenerate(ctx: Context): Int {
        val v = SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("device_id", v).apply()
        return v
    }

    fun name(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("display_name", null)
            ?: Build.MODEL ?: "My phone"

    fun setName(ctx: Context, name: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("display_name", name).apply()
    }
}

/** Saved family code and UI choices. */
object FamilyCodeStore {
    private const val KEY_CODE = "family_code"
    private const val KEY_MODE = "ui_mode"
    private const val KEY_SELECTED_MEMBER = "away_selected_member"
    private const val KEY_SELECTED_PEER = "lan_selected_peer"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun code(ctx: Context): String {
        val existing = prefs(ctx).getString(KEY_CODE, "") ?: ""
        if (AwayProtocol.isValidCode(existing)) return existing
        val fresh = AwayProtocol.generateCode(SecureRandom())
        prefs(ctx).edit().putString(KEY_CODE, fresh).apply()
        return fresh
    }

    fun setCode(ctx: Context, code: String) = prefs(ctx).edit().putString(KEY_CODE, code).apply()
    fun selectedMember(ctx: Context): Int = prefs(ctx).getInt(KEY_SELECTED_MEMBER, 0)
    fun setSelectedMember(ctx: Context, id: Int) = prefs(ctx).edit().putInt(KEY_SELECTED_MEMBER, id).apply()
    fun modeName(ctx: Context): String? = prefs(ctx).getString(KEY_MODE, null)
    fun setModeName(ctx: Context, name: String) = prefs(ctx).edit().putString(KEY_MODE, name).apply()
    fun selectedPeer(ctx: Context): Int = prefs(ctx).getInt(KEY_SELECTED_PEER, 0)
    fun setSelectedPeer(ctx: Context, id: Int) = prefs(ctx).edit().putInt(KEY_SELECTED_PEER, id).apply()
}

object Notify {
    const val CHANNEL = "khalsa_intercom"
    const val ID = 42

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Intercom", NotificationManager.IMPORTANCE_LOW))
        }
    }
}
