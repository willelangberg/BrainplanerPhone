package com.brainplaner.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            android.util.Log.i("BootReceiver", "Device booted, checking if user is logged in")
            
            // Only start service if user is logged in
            if (UserAuth.isLoggedIn(context)) {
                android.util.Log.i("BootReceiver", "User logged in, starting PhoneAwarenessService in polling mode")
                PhoneAwarenessService.startPollingMode(context)
            } else {
                android.util.Log.i("BootReceiver", "No user logged in, skipping service start")
            }
        }
    }
}
