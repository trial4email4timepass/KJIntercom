package com.khalsa.intercom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Turns the intercom back on after the phone restarts (or the app is updated). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs.enabled(context) && Prefs.normalizeCode(Prefs.pairCode(context)).length >= 8) {
            try {
                IntercomService.start(context)
            } catch (_: Exception) { /* system refused a background start; user can reopen the app */ }
        }
    }
}
