package com.example.wecker.alarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

object AlarmPlayer {
    private const val TAG = "AlarmPlayer"

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val alarmScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var rotationJob: Job? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    fun start(context: Context, ringtoneUris: List<String>, vibrationEnabled: Boolean) {
        val isPlaying = runCatching { mediaPlayer?.isPlaying == true }.getOrElse { false }
        if (rotationJob?.isActive == true || isPlaying) return

        val appContext = context.applicationContext

        // Lautstärke maximieren – Fehler ignorieren (z.B. DND-Modus)
        try {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            listOf(AudioManager.STREAM_ALARM, AudioManager.STREAM_RING, AudioManager.STREAM_MUSIC)
                .forEach { stream ->
                    runCatching {
                        val maxVolume = audioManager.getStreamMaxVolume(stream)
                        audioManager.setStreamVolume(stream, maxVolume, 0)
                    }
                }

            // Audio-Fokus anfordern
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(false)
                    .build()
                audioFocusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio-Setup teilweise fehlgeschlagen", e)
        }

        val effectiveUris = ringtoneUris
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI.toString()) }

        if (effectiveUris.size == 1) {
            playTone(appContext, effectiveUris.first(), looping = true)
        } else {
            rotationJob = alarmScope.launch {
                var index = 0
                while (isActive) {
                    playTone(appContext, effectiveUris[index], looping = false)
                    index = (index + 1) % effectiveUris.size
                    delay(8_000)
                }
            }
        }

        if (vibrationEnabled && hasVibrationPermission(appContext)) {
            try {
                val pattern = longArrayOf(0, 1000, 150, 1000, 150, 1200, 150, 1200)
                vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val manager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    manager.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, 0)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Vibration fehlgeschlagen", e)
            }
        }
    }

    private fun playTone(context: Context, ringtoneUri: String, looping: Boolean) {
        mediaPlayer?.runCatching { stop(); release() }
        mediaPlayer = null

        val success = tryPlayUri(context, ringtoneUri, looping)
        if (!success) {
            // Fallback: Standard-Alarm-Ton
            val fallback = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI.toString()
            if (fallback != ringtoneUri) {
                Log.w(TAG, "Fallback auf Standard-Alarm")
                tryPlayUri(context, fallback, looping)
            }
        }
    }

    private fun tryPlayUri(context: Context, ringtoneUri: String, looping: Boolean): Boolean {
        return runCatching {
            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )

            val uri = Uri.parse(ringtoneUri)
            if ("file" == uri.scheme) {
                // Direkte Dateipfadbehandlung für file://-URIs (robuster auf allen Android-Versionen)
                val path = uri.path
                if (path == null || !File(path).exists()) {
                    player.release()
                    Log.w(TAG, "Datei nicht gefunden: $path")
                    return@runCatching false
                }
                player.setDataSource(path)
            } else {
                player.setDataSource(context, uri)
            }

            player.isLooping = looping

            // prepareAsync mit OnPreparedListener – blockiert NICHT den Main-Thread
            player.setOnPreparedListener { mp ->
                runCatching { mp.start() }.onFailure { e ->
                    Log.e(TAG, "MediaPlayer.start() fehlgeschlagen", e)
                    runCatching { mp.release() }
                    if (mediaPlayer === mp) mediaPlayer = null
                }
            }
            player.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "MediaPlayer Fehler what=$what extra=$extra")
                runCatching { mp.release() }
                if (mediaPlayer === mp) mediaPlayer = null
                true
            }
            mediaPlayer = player
            player.prepareAsync()
            true
        }.getOrElse { e ->
            Log.e(TAG, "tryPlayUri fehlgeschlagen uri=$ringtoneUri", e)
            false
        }
    }

    fun stop(context: Context? = null) {
        rotationJob?.cancel()
        rotationJob = null
        runCatching { mediaPlayer?.stop(); mediaPlayer?.release() }
        mediaPlayer = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        // Audio-Fokus freigeben
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = audioFocusRequest
            if (req != null && context != null) {
                runCatching {
                    val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.abandonAudioFocusRequest(req)
                }
            }
        }
        audioFocusRequest = null
    }

    private fun hasVibrationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.VIBRATE
        ) == PackageManager.PERMISSION_GRANTED
    }
}


