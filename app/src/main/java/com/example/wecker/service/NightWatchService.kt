package com.example.wecker.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.wecker.AlarmActivity
import com.example.wecker.MainActivity
import com.example.wecker.R
import com.example.wecker.alarm.AlarmPlayer
import com.example.wecker.data.NightscoutClient
import com.example.wecker.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NightWatchService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = NightscoutClient()
    private var monitoringJob: Job? = null
    private var failureStreak: Int = 0
    private var alarmActive: Boolean = false
    private var currentAlarmMode: AlarmMode = AlarmMode.REAL
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        try {
            createChannels()
            failureStreak = SettingsStore.loadFailureStreak(this)
            alarmActive = SettingsStore.isAlarmActive(this)
        } catch (e: Exception) {
            Log.e(TAG, "onCreate failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val action = intent?.action
            if (action == null) {
                // START_STICKY Neustart: Monitoring wiederherstellen wenn es vorher aktiv war
                Log.d(TAG, "Service neugestartet (intent=null) – stelle Zustand wieder her")
                if (SettingsStore.isMonitoringEnabled(this)) {
                    startMonitoring()
                } else {
                    ensureForeground("Bereit")
                }
                return START_STICKY
            }
            when (action) {
                ACTION_START -> startMonitoring()
                ACTION_STOP -> stopMonitoring()
                ACTION_DISMISS_ALARM -> dismissAlarm(storeCooldown = true)
                ACTION_DISMISS_TEST_ALARM -> dismissAlarm(storeCooldown = false)
                ACTION_TEST_ALARM -> {
                    ensureForeground("Testalarm")
                    triggerAlarm(
                        "Testalarm",
                        mode = AlarmMode.TEST,
                        ignoreCooldown = true,
                        reasonCategory = REASON_TEST
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand error action=${intent?.action}", e)
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        SettingsStore.setMonitoringEnabled(this, true)
        ensureForeground("Überwachung gestartet")
        acquireWakeLock()
        if (monitoringJob?.isActive == true) return

        monitoringJob = serviceScope.launch {
            while (isActive) {
                try {
                    checkNightscoutAndAct()
                } catch (e: Exception) {
                    Log.e(TAG, "checkNightscoutAndAct error", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun ensureForeground(status: String) {
        val notification = try {
            monitoringNotification(status)
        } catch (e: Exception) {
            Log.e(TAG, "monitoringNotification failed", e)
            buildFallbackNotification()
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID_MONITOR,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                )
            } else {
                startForeground(NOTIFICATION_ID_MONITOR, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground(typed) failed, trying untyped", e)
            try {
                startForeground(NOTIFICATION_ID_MONITOR, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "startForeground(untyped) also failed", e2)
            }
        }
    }

    private fun buildFallbackNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Wecker")
            .setContentText("Läuft")
            .setOngoing(true)
            .build()

    private fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        SettingsStore.setMonitoringEnabled(this, false)
        if (!alarmActive) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        // Wenn Alarm aktiv: Service bleibt am Leben bis der Alarm wirklich bestätigt wird
    }

    private fun dismissAlarm(storeCooldown: Boolean) {
        alarmActive = false
        currentAlarmMode = AlarmMode.REAL
        SettingsStore.setAlarmActive(this, false)
        if (storeCooldown) {
            SettingsStore.saveDismissedAt(this)
        }
        try {
            AlarmPlayer.stop(this)
        } catch (e: Exception) {
            Log.e(TAG, "AlarmPlayer.stop failed", e)
        }
        try {
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID_ALARM)
        } catch (e: Exception) {
            Log.e(TAG, "cancel alarm notification failed", e)
        }
        // Wenn Monitoring inzwischen deaktiviert wurde, Service jetzt sauber beenden
        if (!SettingsStore.isMonitoringEnabled(this)) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun checkNightscoutAndAct() {
        val config = SettingsStore.loadConfig(this)
        if (config.nightscoutUrl.isBlank()) {
            updateMonitoringStatus("Nightscout-URL fehlt")
            return
        }

        when (val result = client.fetchCurrentSgv(config)) {
            is NightscoutClient.Result.Success -> {
                failureStreak = 0
                SettingsStore.saveLastStatus(
                    this,
                    result.sgv,
                    failureStreak,
                    wasSuccessful = true,
                    lastDataAt = result.dataTimestamp
                )
                try {
                    SettingsStore.saveHistory(this, result.history)
                } catch (e: Exception) {
                    Log.e(TAG, "saveHistory failed", e)
                }

                val dataAgeMs = if (result.dataTimestamp > 0) {
                    System.currentTimeMillis() - result.dataTimestamp
                } else {
                    0L
                }
                val dataAgeMin = dataAgeMs / 60_000

                if (dataAgeMs > STALE_DATA_THRESHOLD_MS) {
                    updateMonitoringStatus("Sensorfehler: Letzte Messung vor ${dataAgeMin} min")
                    triggerAlarm(
                        "Sensorfehler: Keine neue Messung seit ${dataAgeMin} min",
                        reasonCategory = REASON_SENSOR
                    )
                    return
                }

                val outOfRange = result.sgv <= config.lowerLimit || result.sgv >= config.upperLimit
                updateMonitoringStatus("Aktuell: ${result.sgv} mg/dL (vor ${dataAgeMin} min)")
                if (outOfRange) {
                    val category = if (result.sgv <= config.lowerLimit) REASON_LOW else REASON_HIGH
                    triggerAlarm(
                        "Wert außerhalb Bereich: ${result.sgv} mg/dL",
                        reasonCategory = category,
                        reasonValue = result.sgv
                    )
                }
            }

            is NightscoutClient.Result.Failure -> {
                failureStreak += 1
                SettingsStore.saveLastStatus(this, null, failureStreak, wasSuccessful = false)
                updateMonitoringStatus("Serverfehler #$failureStreak")
                if (failureStreak >= MAX_FAILURE_STREAK) {
                    triggerAlarm(
                        "Nightscout ${MAX_FAILURE_STREAK}× nicht erreichbar",
                        reasonCategory = REASON_CONNECTION
                    )
                }
            }
        }
    }

    private fun triggerAlarm(
        reason: String,
        mode: AlarmMode = AlarmMode.REAL,
        ignoreCooldown: Boolean = false,
        reasonCategory: String = REASON_UNKNOWN,
        reasonValue: Int? = null
    ) {
        if (ignoreCooldown && alarmActive) {
            dismissAlarm(storeCooldown = false)
        }
        if (alarmActive) return
        if (!ignoreCooldown) {
            val now = System.currentTimeMillis()
            val snoozeUntil = SettingsStore.loadSnoozeUntil(this)
            if (snoozeUntil > now) {
                val remainingMin = ((snoozeUntil - now) / 60_000) + 1
                updateMonitoringStatus("Stille: noch ~${remainingMin} min")
                return
            }
            if (snoozeUntil > 0L) {
                SettingsStore.clearSnooze(this)
            }
        }

        val config = SettingsStore.loadConfig(this)
        alarmActive = true
        currentAlarmMode = mode
        SettingsStore.setAlarmActive(this, true)

        try {
            AlarmPlayer.start(
                context = this,
                ringtoneUris = config.ringtoneUris,
                vibrationEnabled = config.vibrationEnabled
            )
        } catch (e: Exception) {
            Log.e(TAG, "AlarmPlayer.start failed", e)
        }

        try {
            val alarmIntent = Intent(this, AlarmActivity::class.java).apply {
                putExtra(EXTRA_ALARM_MODE, currentAlarmMode.name)
                putExtra(EXTRA_ALARM_REASON, reasonCategory)
                if (reasonValue != null) {
                    putExtra(EXTRA_ALARM_VALUE, reasonValue)
                }
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            val fullScreenPendingIntent = PendingIntent.getActivity(
                this,
                2001,
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_ALARM)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.alarm_title))
                .setContentText(reason)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(fullScreenPendingIntent)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .build()

            if (canPostNotifications()) {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID_ALARM, notification)
            } else {
                startActivity(alarmIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "triggerAlarm notification/activity failed", e)
        }
    }

    private fun updateMonitoringStatus(status: String) {
        try {
            if (canPostNotifications()) {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID_MONITOR, monitoringNotification(status))
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateMonitoringStatus failed", e)
        }
    }

    private fun monitoringNotification(status: String): Notification {
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            1001,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.monitoring_title))
            .setContentText(status)
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MONITOR,
                    getString(R.string.monitoring_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALARM,
                    getString(R.string.alarm_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    override fun onDestroy() {
        monitoringJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "wecker:NightWatch"
            ).also {
                // Timeout 24h – wird bei stopMonitoring manuell freigegeben
                it.acquire(24 * 60 * 60 * 1000L)
            }
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "acquireWakeLock failed", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "releaseWakeLock failed", e)
        }
        wakeLock = null
    }

    private fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "NightWatchService"

        const val ACTION_START = "com.example.wecker.action.START_MONITORING"
        const val ACTION_STOP = "com.example.wecker.action.STOP_MONITORING"
        const val ACTION_DISMISS_ALARM = "com.example.wecker.action.DISMISS_ALARM"
        const val ACTION_DISMISS_TEST_ALARM = "com.example.wecker.action.DISMISS_TEST_ALARM"
        const val ACTION_TEST_ALARM = "com.example.wecker.action.TEST_ALARM"
        const val EXTRA_ALARM_MODE = "com.example.wecker.extra.ALARM_MODE"
        const val EXTRA_ALARM_REASON = "com.example.wecker.extra.ALARM_REASON"
        const val EXTRA_ALARM_VALUE = "com.example.wecker.extra.ALARM_VALUE"

        // Alarm-Ursachen fuer die Anzeige im Alarm-Screen.
        const val REASON_LOW = "LOW"
        const val REASON_HIGH = "HIGH"
        const val REASON_SENSOR = "SENSOR"
        const val REASON_CONNECTION = "CONNECTION"
        const val REASON_TEST = "TEST"
        const val REASON_UNKNOWN = "UNKNOWN"

        private const val CHANNEL_MONITOR = "monitor_channel"
        private const val CHANNEL_ALARM = "alarm_channel"
        private const val NOTIFICATION_ID_MONITOR = 7001
        private const val NOTIFICATION_ID_ALARM = 7002
        private const val MAX_FAILURE_STREAK = 8
        private const val POLL_INTERVAL_MS = 2 * 60 * 1000L
        private const val STALE_DATA_THRESHOLD_MS = 15 * 60 * 1000L
    }
}

private enum class AlarmMode {
    REAL,
    TEST
}
