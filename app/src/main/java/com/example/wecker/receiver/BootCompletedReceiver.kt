package com.example.wecker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.wecker.data.SettingsStore
import com.example.wecker.service.NightWatchService

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            if (SettingsStore.isMonitoringEnabled(context)) {
                val serviceIntent = Intent(context, NightWatchService::class.java).apply {
                    this.action = NightWatchService.ACTION_START
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}

