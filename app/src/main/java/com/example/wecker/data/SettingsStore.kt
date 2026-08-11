package com.example.wecker.data

import android.content.Context
import android.content.SharedPreferences
import android.media.RingtoneManager
import com.example.wecker.model.AlarmConfig
import com.example.wecker.model.CustomTone
import com.example.wecker.model.GlucoseReading

object SettingsStore {
    private const val PREFS = "night_watch_prefs"
    private const val KEY_URL = "url"
    private const val KEY_TOKEN = "token"
    private const val KEY_LOWER = "lower"
    private const val KEY_UPPER = "upper"
    private const val KEY_RINGTONE = "ringtone"
    private const val KEY_RINGTONES = "ringtones"
    private const val KEY_VIBRATION = "vibration"
    private const val KEY_MONITORING = "monitoring"
    private const val KEY_LAST_VALUE = "last_value"
    private const val KEY_LAST_SUCCESS_AT = "last_success_at"
    private const val KEY_LAST_ATTEMPT_AT = "last_attempt_at"
    private const val KEY_LAST_DATA_AT = "last_data_at"
    private const val KEY_FAILURE_STREAK = "failure_streak"
    private const val KEY_ALARM_ACTIVE = "alarm_active"
    private const val KEY_DISMISSED_AT = "dismissed_at"
    private const val KEY_SNOOZE_UNTIL = "snooze_until"
    private const val KEY_CUSTOM_TONES = "custom_tones"
    private const val KEY_HISTORY = "sgv_history"
    private const val DEFAULT_SNOOZE_MINUTES = 15L
    private const val HISTORY_WINDOW_MS = 3L * 60L * 60L * 1000L
    private const val HISTORY_MAX_POINTS = 60

    fun loadConfig(context: Context): AlarmConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val defaultRingtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.toString().orEmpty()
        val persistedList = prefs.getStringSafe(KEY_RINGTONES, "")
            .split("|")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val legacySingle = prefs.getStringSafe(KEY_RINGTONE, "")
        val ringtoneUris = when {
            persistedList.isNotEmpty() -> persistedList
            legacySingle.isNotBlank() -> listOf(legacySingle)
            defaultRingtone.isNotBlank() -> listOf(defaultRingtone)
            else -> emptyList()
        }
        return AlarmConfig(
            nightscoutUrl = prefs.getStringSafe(KEY_URL, ""),
            nightscoutToken = prefs.getStringSafe(KEY_TOKEN, ""),
            lowerLimit = prefs.getIntSafe(KEY_LOWER, 80),
            upperLimit = prefs.getIntSafe(KEY_UPPER, 180),
            ringtoneUris = ringtoneUris,
            vibrationEnabled = prefs.getBooleanSafe(KEY_VIBRATION, true)
        )
    }

    fun saveConfig(context: Context, config: AlarmConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URL, config.nightscoutUrl)
            .putString(KEY_TOKEN, config.nightscoutToken)
            .putInt(KEY_LOWER, config.lowerLimit)
            .putInt(KEY_UPPER, config.upperLimit)
            .putString(KEY_RINGTONE, config.ringtoneUris.firstOrNull().orEmpty())
            .putString(KEY_RINGTONES, config.ringtoneUris.joinToString("|"))
            .putBoolean(KEY_VIBRATION, config.vibrationEnabled)
            .apply()
    }

    fun setMonitoringEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MONITORING, enabled)
            .apply()
    }

    fun isMonitoringEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBooleanSafe(KEY_MONITORING, false)
    }

    fun saveLastStatus(
        context: Context,
        value: Int?,
        failureStreak: Int,
        wasSuccessful: Boolean,
        lastDataAt: Long? = null
    ) {
        val now = System.currentTimeMillis()
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FAILURE_STREAK, failureStreak)
            .putLong(KEY_LAST_ATTEMPT_AT, now)
        if (value != null) {
            editor.putInt(KEY_LAST_VALUE, value)
        }
        if (wasSuccessful) {
            editor.putLong(KEY_LAST_SUCCESS_AT, now)
        }
        if (lastDataAt != null && lastDataAt > 0L) {
            editor.putLong(KEY_LAST_DATA_AT, lastDataAt)
        }
        editor.apply()
    }

    fun loadLastValue(context: Context): Int? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_LAST_VALUE)) prefs.getIntSafe(KEY_LAST_VALUE, 0) else null
    }

    fun loadFailureStreak(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getIntSafe(KEY_FAILURE_STREAK, 0)
    }

    fun loadLastSuccessAt(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_LAST_SUCCESS_AT)) prefs.getLongSafe(KEY_LAST_SUCCESS_AT, 0L) else null
    }

    fun loadLastAttemptAt(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_LAST_ATTEMPT_AT)) prefs.getLongSafe(KEY_LAST_ATTEMPT_AT, 0L) else null
    }

    fun loadLastDataAt(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_LAST_DATA_AT)) prefs.getLongSafe(KEY_LAST_DATA_AT, 0L) else null
    }

    fun setAlarmActive(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ALARM_ACTIVE, active)
            .apply()
    }

    fun isAlarmActive(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBooleanSafe(KEY_ALARM_ACTIVE, false)
    }

    fun saveDismissedAt(context: Context) {
        saveSnoozeMinutes(context, DEFAULT_SNOOZE_MINUTES)
    }

    fun saveSnoozeMinutes(context: Context, minutes: Long) {
        val safeMinutes = minutes.coerceAtLeast(1L)
        saveSnoozeUntil(context, System.currentTimeMillis() + safeMinutes * 60_000L)
    }

    fun saveSnoozeUntil(context: Context, snoozeUntil: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_SNOOZE_UNTIL, snoozeUntil)
            .apply()
    }

    fun loadDismissedAt(context: Context): Long {
        return loadSnoozeUntil(context)
    }

    fun loadSnoozeUntil(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_SNOOZE_UNTIL)) {
            return prefs.getLongSafe(KEY_SNOOZE_UNTIL, 0L)
        }

        val legacyDismissedAt = if (prefs.contains(KEY_DISMISSED_AT)) {
            prefs.getLongSafe(KEY_DISMISSED_AT, 0L)
        } else {
            0L
        }

        return if (legacyDismissedAt > 0L) {
            legacyDismissedAt + DEFAULT_SNOOZE_MINUTES * 60_000L
        } else {
            0L
        }
    }

    fun clearSnooze(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SNOOZE_UNTIL)
            .remove(KEY_DISMISSED_AT)
            .apply()
    }

    /**
     * Speichert den Messwertverlauf (nur letzte 3h) als kompakten String "ts:sgv,ts:sgv,...".
     * Ausfallsicher: Fehler werden geschluckt, der Verlauf ist unkritisch fuer den Alarm.
     */
    fun saveHistory(context: Context, readings: List<GlucoseReading>) {
        try {
            val cutoff = System.currentTimeMillis() - HISTORY_WINDOW_MS
            val payload = readings.asSequence()
                .filter { it.timestamp >= cutoff && it.sgv > 0 }
                .sortedBy { it.timestamp }
                .toList()
                .takeLast(HISTORY_MAX_POINTS)
                .joinToString(",") { "${it.timestamp}:${it.sgv}" }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_HISTORY, payload)
                .apply()
        } catch (_: Exception) {
            // Verlauf ist optional – niemals abstuerzen.
        }
    }

    fun loadHistory(context: Context): List<GlucoseReading> {
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSafe(KEY_HISTORY, "")
            if (raw.isBlank()) return emptyList()
            val cutoff = System.currentTimeMillis() - HISTORY_WINDOW_MS
            raw.split(",").mapNotNull { entry ->
                val parts = entry.split(":")
                if (parts.size != 2) return@mapNotNull null
                val ts = parts[0].toLongOrNull() ?: return@mapNotNull null
                val sgv = parts[1].toIntOrNull() ?: return@mapNotNull null
                if (ts < cutoff || sgv <= 0) return@mapNotNull null
                GlucoseReading(sgv = sgv, timestamp = ts)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun loadCustomTones(context: Context): List<CustomTone> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSafe(KEY_CUSTOM_TONES, "")
        if (raw.isBlank()) return emptyList()

        return raw
            .split("\n")
            .mapNotNull { line ->
                val parts = line.split("\t")
                if (parts.size != 3) return@mapNotNull null
                val id = parts[0].trim()
                val name = parts[1].trim()
                val fileName = parts[2].trim()
                if (id.isBlank() || name.isBlank() || fileName.isBlank()) return@mapNotNull null
                CustomTone(id = id, name = name, fileName = fileName)
            }
    }

    fun saveCustomTones(context: Context, tones: List<CustomTone>) {
        val payload = tones
            .map { tone ->
                val safeId = tone.id.replace("\n", " ").replace("\t", " ").trim()
                val safeName = tone.name.replace("\n", " ").replace("\t", " ").trim()
                val safeFile = tone.fileName.replace("\n", " ").replace("\t", " ").trim()
                "$safeId\t$safeName\t$safeFile"
            }
            .filter { it.isNotBlank() }
            .joinToString("\n")

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_TONES, payload)
            .apply()
    }

    private fun SharedPreferences.getStringSafe(key: String, defaultValue: String): String {
        return runCatching { getString(key, defaultValue) ?: defaultValue }
            .getOrElse {
                edit().remove(key).apply()
                defaultValue
            }
    }

    private fun SharedPreferences.getIntSafe(key: String, defaultValue: Int): Int {
        return runCatching { getInt(key, defaultValue) }
            .getOrElse {
                edit().remove(key).apply()
                defaultValue
            }
    }

    private fun SharedPreferences.getLongSafe(key: String, defaultValue: Long): Long {
        return runCatching { getLong(key, defaultValue) }
            .getOrElse {
                edit().remove(key).apply()
                defaultValue
            }
    }

    private fun SharedPreferences.getBooleanSafe(key: String, defaultValue: Boolean): Boolean {
        return runCatching { getBoolean(key, defaultValue) }
            .getOrElse {
                edit().remove(key).apply()
                defaultValue
            }
    }
}





