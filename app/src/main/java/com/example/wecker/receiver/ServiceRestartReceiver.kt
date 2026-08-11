package com.example.wecker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.wecker.data.SettingsStore
import com.example.wecker.service.NightWatchService

class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!SettingsStore.isMonitoringEnabled(context)) return
        try {
            val serviceIntent = Intent(context, NightWatchService::class.java).apply {
                action = NightWatchService.ACTION_START
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Restart des Monitoring-Services fehlgeschlagen", e)
        }
    }

    companion object {
        private const val TAG = "ServiceRestartReceiver"
    }
}

