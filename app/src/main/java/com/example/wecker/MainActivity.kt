package com.example.wecker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled
    .Alarm
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.wecker.data.NightscoutClient
import com.example.wecker.data.SettingsStore
import com.example.wecker.model.AlarmConfig
import com.example.wecker.model.CustomTone
import com.example.wecker.model.GlucoseReading
import com.example.wecker.service.NightWatchService
import com.example.wecker.ui.theme.WeckerTheme
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            WeckerTheme {
                WeckerApp()
            }
        }
    }
}

@Composable
private fun WeckerApp() {
    val context = LocalContext.current
    var config by remember { mutableStateOf(SettingsStore.loadConfig(context)) }
    var currentScreen by remember { mutableStateOf(AppScreen.HOME) }
    var infoText by remember { mutableStateOf("") }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000)
        }
    }

    // Tonauswahl frueh laden, damit der Sound-Screen sofort reagiert.
    LaunchedEffect(context) {
        launch(Dispatchers.IO) {
            ToneCache.getOrLoad(context.applicationContext)
        }
    }

    val runtimeStatus = remember(nowMillis) {
        RuntimeStatus(
            monitoringEnabled = SettingsStore.isMonitoringEnabled(context),
            lastValue = SettingsStore.loadLastValue(context),
            lastSuccessAt = SettingsStore.loadLastSuccessAt(context),
            lastAttemptAt = SettingsStore.loadLastAttemptAt(context),
            lastDataAt = SettingsStore.loadLastDataAt(context),
            failureStreak = SettingsStore.loadFailureStreak(context),
            snoozeUntil = SettingsStore.loadSnoozeUntil(context),
            lowerLimit = config.lowerLimit,
            upperLimit = config.upperLimit,
            history = SettingsStore.loadHistory(context)
        )
    }
    val batteryOptimizationsIgnored = remember(nowMillis) {
        areBatteryOptimizationsIgnored(context)
    }
    val dndPolicyGranted = remember(nowMillis) {
        isDndPolicyGranted(context)
    }

    Scaffold(
        topBar = {
            AppTopBar(
                screen = currentScreen,
                onOpenSound = { currentScreen = AppScreen.SOUND },
                onOpenNightscout = { currentScreen = AppScreen.NIGHTSCOUT },
                onBack = { currentScreen = AppScreen.HOME }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            when (currentScreen) {
                AppScreen.HOME -> HomeScreen(
                    status = runtimeStatus,
                    nowMillis = nowMillis,
                    onOpenTimer = { currentScreen = AppScreen.TIMER },
                    onOpenSnooze = { currentScreen = AppScreen.SNOOZE }
                )

                AppScreen.SOUND -> SoundSettingsScreen(
                    config = config,
                    onConfigChanged = {
                        config = it
                        SettingsStore.saveConfig(context, it)
                    },
                    onSave = {
                        config = it
                        SettingsStore.saveConfig(context, it)
                        currentScreen = AppScreen.HOME
                    },
                    onTestAlarm = {
                        val testIntent = Intent(context, NightWatchService::class.java).apply {
                            action = NightWatchService.ACTION_TEST_ALARM
                        }
                        ContextCompat.startForegroundService(context, testIntent)
                        infoText = "Testalarm gestartet"
                    }
                )

                AppScreen.NIGHTSCOUT -> NightscoutConfigScreen(
                    config = config,
                    onSave = {
                        config = it
                        SettingsStore.saveConfig(context, it)
                        currentScreen = AppScreen.HOME
                    }
                )

                AppScreen.TIMER -> TimerScreen(
                    status = runtimeStatus,
                    nowMillis = nowMillis,
                    batteryOptimizationsIgnored = batteryOptimizationsIgnored,
                    dndPolicyGranted = dndPolicyGranted,
                    onMonitoringToggle = { enabled ->
                        val err = setMonitoring(context, enabled)
                        infoText = err ?: if (enabled) "System AN" else "System AUS"
                    },
                    onDisableBatteryOptimizations = {
                        val err = requestIgnoreBatteryOptimizations(context)
                        infoText = err ?: "Akku-Ausnahme geöffnet"
                    },
                    onRequestDndPolicy = {
                        val err = requestDndPolicy(context)
                        infoText = err ?: "Nicht-stören-Einstellungen geöffnet"
                    }
                )

                AppScreen.SNOOZE -> SnoozeScreen(
                    status = runtimeStatus,
                    nowMillis = nowMillis,
                    onSave = { minutes ->
                        SettingsStore.saveSnoozeMinutes(context, minutes.toLong())
                        infoText = "Snooze gespeichert"
                        currentScreen = AppScreen.HOME
                    },
                    onEndSnooze = {
                        SettingsStore.clearSnooze(context)
                        infoText = "Snooze beendet"
                        currentScreen = AppScreen.HOME
                    }
                )
            }

            if (infoText.isNotBlank()) {
                Text(
                    text = infoText,
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AppTopBar(
    screen: AppScreen,
    onOpenSound: () -> Unit,
    onOpenNightscout: () -> Unit,
    onBack: () -> Unit
) {
    if (screen == AppScreen.HOME) {
        CenterAlignedTopAppBar(
            title = { Text("Wecker") },
            navigationIcon = {
                IconButton(onClick = onOpenSound) {
                    Icon(
                        imageVector = Icons.Filled.Alarm,
                        contentDescription = "Sound"
                    )
                }
            },
            actions = {
                IconButton(onClick = onOpenNightscout) {
                    Icon(
                        imageVector = Icons.Filled.Cloud,
                        contentDescription = "Nightscout"
                    )
                }
            }
        )
    } else {
        TopAppBar(
            title = {
                Text(
                    when (screen) {
                        AppScreen.HOME -> "Wecker"
                        AppScreen.SOUND -> "Sound"
                        AppScreen.NIGHTSCOUT -> "Server"
                        AppScreen.TIMER -> "System"
                        AppScreen.SNOOZE -> "Snooze"
                    }
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Zurück"
                    )
                }
            }
        )
    }
}

@Composable
private fun HomeScreen(
    status: RuntimeStatus,
    nowMillis: Long,
    onOpenTimer: () -> Unit,
    onOpenSnooze: () -> Unit
) {
    val snoozeRemainingMs = (status.snoozeUntil - nowMillis).coerceAtLeast(0L)
    val snoozeActive = snoozeRemainingMs > 0L

    Column(modifier = Modifier.fillMaxSize()) {
        // Oberer Bereich: Wert, Status und Buttons – nimmt den restlichen Platz ein.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = status.lastValue?.toString() ?: "--",
                style = MaterialTheme.typography.displayLarge,
                textAlign = TextAlign.Center
            )
            Text("mg/dL", style = MaterialTheme.typography.titleMedium)

            Text(
                "Sync: ${formatElapsed(status.lastSuccessAt, "noch nie", nowMillis)}",
                modifier = Modifier.padding(top = 12.dp)
            )
            Text("Messung: ${formatElapsed(status.lastDataAt, "unbekannt", nowMillis)}")
            Text("Fehler: ${status.failureStreak}")

            if (snoozeActive) {
                val snoozeMin = snoozeRemainingMs / 60_000
                val snoozeSec = (snoozeRemainingMs % 60_000) / 1000
                Text(
                    text = "💤 Snooze: %02d:%02d".format(snoozeMin, snoozeSec),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Button(
                onClick = onOpenTimer,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text("System")
            }

            Button(onClick = onOpenSnooze) {
                Text("Snooze")
            }
        }

        // Diagramm unten anliegend. offset(y = 16.dp) hebt den 16dp-Rand der App auf,
        // sodass es bündig an der unteren Bildschirmkante klebt.
        GlucoseChart(
            readings = status.history,
            lowerLimit = status.lowerLimit,
            upperLimit = status.upperLimit,
            nowMillis = nowMillis,
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = 16.dp)
                .height(190.dp)
        )
    }
}

/**
 * Verlaufsdiagramm der letzten 3h. Punkte werden an ihrer echten Zeitposition gezeichnet,
 * sodass Lücken (Sensor-/Empfangsfehler) sichtbar bleiben.
 * Gitter mit y-Beschriftung (0, untere Grenze, obere Grenze) am linken Rand und
 * x-Beschriftung (1h, 2h, 3h) am unteren Rand.
 * Farben: rot = zu tief, grün = im Zielbereich, gelb = zu hoch.
 * Vollständig ausfallsicher: rendert bei fehlenden Daten einen Hinweis statt abzustürzen.
 */
@Composable
private fun GlucoseChart(
    readings: List<GlucoseReading>,
    lowerLimit: Int,
    upperLimit: Int,
    nowMillis: Long,
    modifier: Modifier = Modifier
) {
    val windowMs = 3L * 60L * 60L * 1000L
    val startTime = nowMillis - windowMs

    val visible = remember(readings, nowMillis) {
        readings
            .filter { it.sgv > 0 && it.timestamp in startTime..nowMillis }
            .sortedBy { it.timestamp }
    }

    val colorLow = Color(0xFFE53935)
    val colorInRange = Color(0xFF43A047)
    val colorHigh = Color(0xFFFDD835)
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f)
    val limitLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val cardColor = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(cardColor)
    ) {
        if (visible.isEmpty()) {
            Text(
                text = "Keine Verlaufsdaten",
                style = MaterialTheme.typography.bodyMedium,
                color = labelColor,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
            ) {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@Canvas

                // Innenränder für Beschriftung: links y-Werte, unten Zeit.
                val leftPad = 34.dp.toPx()
                val bottomPad = 18.dp.toPx()
                val topPad = 6.dp.toPx()
                val plotLeft = leftPad
                val plotRight = w
                val plotTop = topPad
                val plotBottom = h - bottomPad
                val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
                val plotH = (plotBottom - plotTop).coerceAtLeast(1f)

                // Feste Y-Achse 0..LIMIT_MAX, damit Werte über der Obergrenze immer sichtbar
                // bleiben und die Skala nicht bei jedem Update springt.
                val maxV = LIMIT_MAX
                val range = maxV.toFloat()

                fun xFor(ts: Long): Float =
                    plotLeft + ((ts - startTime).toFloat() / windowMs.toFloat()).coerceIn(0f, 1f) * plotW

                fun yFor(v: Int): Float =
                    plotBottom - (v.toFloat() / range).coerceIn(0f, 1f) * plotH

                val textPaint = android.graphics.Paint().apply {
                    color = labelColor.toArgb()
                    textSize = 10.sp.toPx()
                    isAntiAlias = true
                }

                // Vertikales Gitter + Zeitbeschriftung (3h, 2h, 1h – links=älter).
                textPaint.textAlign = android.graphics.Paint.Align.CENTER
                val hourMarks = listOf(3, 2, 1) // Stunden zurück
                hourMarks.forEach { hoursAgo ->
                    val ts = nowMillis - hoursAgo * 60L * 60L * 1000L
                    val x = xFor(ts)
                    drawLine(
                        color = gridColor,
                        start = androidx.compose.ui.geometry.Offset(x, plotTop),
                        end = androidx.compose.ui.geometry.Offset(x, plotBottom),
                        strokeWidth = 1f
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        "${hoursAgo}h",
                        x,
                        h - 4.dp.toPx(),
                        textPaint
                    )
                }

                // Horizontales Gitter + y-Beschriftung: 0, untere Grenze, obere Grenze.
                textPaint.textAlign = android.graphics.Paint.Align.RIGHT
                data class YLevel(val value: Int, val strong: Boolean)
                val yLevels = listOf(
                    YLevel(0, false),
                    YLevel(lowerLimit, true),
                    YLevel(upperLimit, true),
                    YLevel(maxV, false) // 400 – obere Skalengrenze
                )
                yLevels.forEach { level ->
                    if (level.value < 0 || level.value > maxV) return@forEach
                    val y = yFor(level.value)
                    drawLine(
                        color = if (level.strong) limitLineColor else gridColor,
                        start = androidx.compose.ui.geometry.Offset(plotLeft, y),
                        end = androidx.compose.ui.geometry.Offset(plotRight, y),
                        strokeWidth = if (level.strong) 2f else 1f
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        level.value.toString(),
                        plotLeft - 6.dp.toPx(),
                        y + 3.5.dp.toPx(),
                        textPaint
                    )
                }

                // Messpunkte – keine Verbindungslinien, damit Lücken erkennbar sind.
                val radius = 2.5.dp.toPx()
                visible.forEach { reading ->
                    val color = when {
                        reading.sgv <= lowerLimit -> colorLow
                        reading.sgv >= upperLimit -> colorHigh
                        else -> colorInRange
                    }
                    drawCircle(
                        color = color,
                        radius = radius,
                        center = androidx.compose.ui.geometry.Offset(
                            xFor(reading.timestamp),
                            yFor(reading.sgv)
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun SnoozeScreen(
    status: RuntimeStatus,
    nowMillis: Long,
    onSave: (Int) -> Unit,
    onEndSnooze: () -> Unit
) {
    var snoozeMinutes by remember { mutableStateOf("") }
    val snoozeValue = snoozeMinutes.toIntOrNull()
    val canSave = snoozeValue != null && snoozeValue > 0
    val snoozeRemainingMs = (status.snoozeUntil - nowMillis).coerceAtLeast(0L)
    val snoozeActive = snoozeRemainingMs > 0L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (snoozeActive) {
            Text(
                text = "Aktiv: %02d:%02d".format(
                    snoozeRemainingMs / 60_000,
                    (snoozeRemainingMs % 60_000) / 1000
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Text("Kein Snooze aktiv", style = MaterialTheme.typography.titleMedium)
        }

        OutlinedTextField(
            value = snoozeMinutes,
            onValueChange = { snoozeMinutes = it.filter(Char::isDigit) },
            label = { Text("Minuten") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )

        Button(
            onClick = {
                val minutes = snoozeValue ?: return@Button
                onSave(minutes)
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Speichern")
        }

        Button(
            onClick = onEndSnooze,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Snooze beenden")
        }
    }
}

// Maximal erlaubter Grenzwert (mg/dL). Passt zur festen Y-Achse (0..400) des Diagramms.
private const val LIMIT_MAX = 400

/** Nur Ziffern zulassen und auf 0..LIMIT_MAX begrenzen, damit keine ungültigen Grenzen entstehen. */
private fun clampLimitInput(raw: String): String {
    val digits = raw.filter(Char::isDigit)
    if (digits.isEmpty()) return ""
    val value = digits.toIntOrNull() ?: return ""
    return if (value > LIMIT_MAX) LIMIT_MAX.toString() else value.toString()
}

@Composable
private fun NightscoutConfigScreen(
    config: AlarmConfig,
    onSave: (AlarmConfig) -> Unit
) {
    val client = remember { NightscoutClient() }
    var nightscoutUrl by remember(config.nightscoutUrl) { mutableStateOf(config.nightscoutUrl) }
    var lowerLimit by remember(config.lowerLimit) { mutableStateOf(config.lowerLimit.toString()) }
    var upperLimit by remember(config.upperLimit) { mutableStateOf(config.upperLimit.toString()) }
    var isEditing by remember { mutableStateOf(false) }
    var isValidating by remember { mutableStateOf(false) }
    var endpointOk by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }
    var infoText by remember { mutableStateOf("") }

    val low = lowerLimit.toIntOrNull()
    val high = upperLimit.toIntOrNull()
    val rangeOk = low != null && high != null &&
        low in 0..LIMIT_MAX && high in 0..LIMIT_MAX && low < high
    val canSave = isEditing && rangeOk

    LaunchedEffect(isEditing, nightscoutUrl) {
        if (!isEditing) return@LaunchedEffect
        if (nightscoutUrl.isBlank()) {
            endpointOk = false
            errorText = "URL fehlt"
            return@LaunchedEffect
        }

        isValidating = true
        errorText = ""
        infoText = "Prüfe Verbindung..."
        delay(500)
        val result = withContext(Dispatchers.IO) {
            client.validateEndpoint(nightscoutUrl, "")
        }
        when (result) {
            is NightscoutClient.ValidationResult.Success -> {
                endpointOk = true
                errorText = ""
                infoText = "Server erreichbar"
            }
            is NightscoutClient.ValidationResult.Failure -> {
                endpointOk = false
                errorText = result.message
                infoText = ""
            }
        }
        isValidating = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = nightscoutUrl,
            onValueChange = { nightscoutUrl = it },
            label = { Text("URL") },
            modifier = Modifier.fillMaxWidth(),
            enabled = isEditing,
            readOnly = !isEditing,
            singleLine = true
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = lowerLimit,
                onValueChange = { lowerLimit = clampLimitInput(it) },
                label = { Text("Min") },
                modifier = Modifier.weight(1f),
                enabled = isEditing,
                readOnly = !isEditing,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            OutlinedTextField(
                value = upperLimit,
                onValueChange = { upperLimit = clampLimitInput(it) },
                label = { Text("Max") },
                modifier = Modifier.weight(1f),
                enabled = isEditing,
                readOnly = !isEditing,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    if (isEditing) {
                        nightscoutUrl = config.nightscoutUrl
                        lowerLimit = config.lowerLimit.toString()
                        upperLimit = config.upperLimit.toString()
                        errorText = ""
                        infoText = ""
                        endpointOk = false
                        isEditing = false
                    } else {
                        infoText = ""
                        errorText = ""
                        isEditing = true
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isEditing) "Abbrechen" else "Bearbeiten")
            }

            Button(
                onClick = {
                    if (!rangeOk) {
                        errorText = "Min < Max"
                        return@Button
                    }
                    val safeLow = low ?: return@Button
                    val safeHigh = high ?: return@Button
                    onSave(
                        config.copy(
                            nightscoutUrl = nightscoutUrl.trim(),
                            nightscoutToken = "",
                            lowerLimit = safeLow,
                            upperLimit = safeHigh
                        )
                    )
                    infoText = "Gespeichert"
                    errorText = ""
                    isEditing = false
                },
                enabled = canSave,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isValidating) "Prüfe..." else "Speichern")
            }
        }

        if (isEditing && !rangeOk) {
            Text(
                "Min und Max müssen zwischen 0 und $LIMIT_MAX liegen (Min < Max)",
                color = MaterialTheme.colorScheme.error
            )
        }
        if (errorText.isNotBlank()) {
            Text(errorText, color = MaterialTheme.colorScheme.error)
        }
        if (infoText.isNotBlank()) {
            Text(infoText, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SoundSettingsScreen(
    config: AlarmConfig,
    onConfigChanged: (AlarmConfig) -> Unit,
    onSave: (AlarmConfig) -> Unit,
    onTestAlarm: () -> Unit
) {
    val context = LocalContext.current
    val systemTones = remember { mutableStateListOf<ToneOption>() }
    val customTones = remember { mutableStateListOf<CustomTone>() }
    var isLoadingTones by remember { mutableStateOf(true) }
    var localInfoText by remember { mutableStateOf("") }
    var localErrorText by remember { mutableStateOf("") }
    var renameTone by remember { mutableStateOf<CustomTone?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf("") }

    LaunchedEffect(context) {
        val loadedSystemTones = withContext(Dispatchers.IO) { ToneCache.getOrLoad(context.applicationContext) }
        val loadedCustomTones = SettingsStore.loadCustomTones(context)
        systemTones.clear()
        systemTones.addAll(loadedSystemTones)
        customTones.clear()
        customTones.addAll(loadedCustomTones)
        isLoadingTones = false
    }

    var selectedUri by remember(config.ringtoneUris) {
        mutableStateOf(config.ringtoneUris.firstOrNull().orEmpty())
    }
    var vibrationEnabled by remember(config.vibrationEnabled) { mutableStateOf(config.vibrationEnabled) }

    // Vorschau-State
    val scope = rememberCoroutineScope()
    val previewRingtone = remember { mutableStateOf<Ringtone?>(null) }
    val previewMediaPlayer = remember { mutableStateOf<MediaPlayer?>(null) }
    var previewJob by remember { mutableStateOf<Job?>(null) }

    fun stopPreview() {
        previewJob?.cancel()
        previewRingtone.value?.stop()
        previewRingtone.value = null
        previewMediaPlayer.value?.runCatching {
            stop()
            release()
        }
        previewMediaPlayer.value = null
    }

    fun playPreview(tone: ToneOption) {
        stopPreview()
        if (tone.uri.isBlank()) return
        if (tone.source == ToneSource.CUSTOM) {
            val parsedUri = runCatching { Uri.parse(tone.uri) }.getOrNull() ?: run {
                localErrorText = "Vorschau nicht möglich"
                return
            }
            val player = runCatching { MediaPlayer() }.getOrNull() ?: run {
                localErrorText = "Vorschau nicht möglich"
                return
            }
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // file://-URI: direkter Dateipfad robuster auf allen Android-Versionen
            val setSourceOk = runCatching {
                if ("file" == parsedUri.scheme) {
                    val path = parsedUri.path
                    val f = if (path != null) java.io.File(path) else null
                    if (f == null || !f.exists()) {
                        localErrorText = "Datei nicht gefunden"
                        player.release()
                        return
                    }
                    player.setDataSource(path)
                } else {
                    player.setDataSource(context, parsedUri)
                }
                true
            }.getOrElse {
                player.release()
                localErrorText = "Vorschau nicht möglich"
                return
            }
            if (!setSourceOk) return
            previewMediaPlayer.value = player
            player.setOnPreparedListener { mp ->
                runCatching { mp.start() }
                previewJob = scope.launch {
                    delay(5_000)
                    mp.runCatching { stop(); release() }
                    previewMediaPlayer.value = null
                }
            }
            player.setOnErrorListener { mp, _, _ ->
                mp.runCatching { release() }
                previewMediaPlayer.value = null
                localErrorText = "Vorschau nicht möglich"
                true
            }
            player.prepareAsync()
        } else {
            val ringtone = runCatching {
                RingtoneManager.getRingtone(context, Uri.parse(tone.uri))
            }.getOrNull() ?: return
            previewRingtone.value = ringtone
            ringtone.play()
            previewJob = scope.launch {
                delay(5_000)
                ringtone.stop()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            localInfoText = ""
            localErrorText = ""
            val existingNames = withContext(Dispatchers.IO) {
                (ToneCache.getOrLoad(context.applicationContext).map { it.label } +
                    SettingsStore.loadCustomTones(context).map { it.name })
                    .map(::normalizeToneName)
                    .toSet()
            }
            when (val result = withContext(Dispatchers.IO) {
                importCustomTone(context.applicationContext, uri, existingNames)
            }) {
                is CustomToneImportResult.Failure -> {
                    localErrorText = result.message
                }

                is CustomToneImportResult.Success -> {
                    val updated = customTones.toList() + result.tone
                    SettingsStore.saveCustomTones(context, updated)
                    customTones.clear()
                    customTones.addAll(updated)
                    localInfoText = "Ton hinzugefügt"
                    selectedUri = customToneUri(context, result.tone)
                    playPreview(result.tone.toToneOption(context))
                }
            }
        }
    }

    // Stoppe Vorschau wenn Screen verlassen wird
    DisposableEffect(Unit) {
        onDispose { stopPreview() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Ton")

        Button(
            onClick = {
                localInfoText = ""
                localErrorText = ""
                importLauncher.launch(arrayOf("audio/mpeg", "audio/mp3", "audio/*"))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(" MP3 hinzufügen")
        }

        if (isLoadingTones) {
            Text("Lade Töne...")
        } else {
            systemTones.forEach { tone ->
                ToneRow(
                    tone = tone,
                    selected = selectedUri == tone.uri,
                    onSelect = {
                        selectedUri = tone.uri
                        playPreview(tone)
                    }
                )
            }

            HorizontalDivider()
            Text("Eigene Töne", style = MaterialTheme.typography.titleSmall)

            if (customTones.isEmpty()) {
                Text("Keine eigenen Töne")
            } else {
                customTones.forEach { customTone ->
                    val tone = customTone.toToneOption(context)
                    ToneRow(
                        tone = tone,
                        selected = selectedUri == tone.uri,
                        onSelect = {
                            selectedUri = tone.uri
                            playPreview(tone)
                        },
                        trailingContent = {
                            IconButton(
                                onClick = {
                                    renameTone = customTone
                                    renameValue = customTone.name
                                    renameError = ""
                                }
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = "Bearbeiten")
                            }
                            IconButton(
                                onClick = {
                                    stopPreview()
                                    val updated = customTones.filterNot { it.id == customTone.id }
                                    SettingsStore.saveCustomTones(context, updated)
                                    deleteCustomToneFile(context, customTone)
                                    customTones.clear()
                                    customTones.addAll(updated)
                                    localInfoText = "Ton gelöscht"
                                    localErrorText = ""

                                    val deletedUri = customToneUri(context, customTone)
                                    if (selectedUri == deletedUri) {
                                        selectedUri = systemTones.firstOrNull()?.uri.orEmpty()
                                    }

                                    if (config.ringtoneUris.firstOrNull() == deletedUri) {
                                        val fallbackUri = systemTones.firstOrNull()?.uri.orEmpty()
                                        onConfigChanged(
                                            config.copy(
                                                ringtoneUris = listOfNotNull(fallbackUri.takeIf { it.isNotBlank() })
                                            )
                                        )
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Löschen")
                            }
                        }
                    )
                }
            }
        }

        if (localErrorText.isNotBlank()) {
            Text(localErrorText, color = MaterialTheme.colorScheme.error)
        }
        if (localInfoText.isNotBlank()) {
            Text(localInfoText, color = MaterialTheme.colorScheme.primary)
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Vibration")
            Switch(
                checked = vibrationEnabled,
                onCheckedChange = { vibrationEnabled = it }
            )
        }

        Button(onClick = { stopPreview(); onTestAlarm() }, modifier = Modifier.fillMaxWidth()) {
            Text("Testalarm")
        }

        Button(
            onClick = {
                stopPreview()
                val fallback = systemTones.firstOrNull()?.uri.orEmpty()
                val persisted = (if (selectedUri.isBlank()) fallback else selectedUri).takeIf { it.isNotBlank() }
                onSave(config.copy(ringtoneUris = listOfNotNull(persisted), vibrationEnabled = vibrationEnabled))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Speichern")
        }
    }

    if (renameTone != null) {
        AlertDialog(
            onDismissRequest = {
                renameTone = null
                renameError = ""
            },
            title = { Text("Tonname") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = renameValue,
                        onValueChange = {
                            renameValue = it
                            renameError = ""
                        },
                        singleLine = true,
                        label = { Text("Name") }
                    )
                    if (renameError.isNotBlank()) {
                        Text(renameError, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val currentTone = renameTone ?: return@TextButton
                        val candidate = renameValue.trim()
                        if (candidate.isBlank()) {
                            renameError = "Name fehlt"
                            return@TextButton
                        }
                        val existingNames = systemTones.map { it.label } +
                            customTones.filterNot { it.id == currentTone.id }.map { it.name }
                        if (existingNames.any { normalizeToneName(it) == normalizeToneName(candidate) }) {
                            renameError = "Name existiert schon"
                            return@TextButton
                        }

                        val updated = customTones.map {
                            if (it.id == currentTone.id) it.copy(name = candidate) else it
                        }
                        SettingsStore.saveCustomTones(context, updated)
                        customTones.clear()
                        customTones.addAll(updated)
                        localInfoText = "Name gespeichert"
                        localErrorText = ""
                        renameTone = null
                        renameError = ""
                    }
                ) {
                    Text("Speichern")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        renameTone = null
                        renameError = ""
                    }
                ) {
                    Text("Abbrechen")
                }
            }
        )
    }
}

@Composable
private fun TimerScreen(
    status: RuntimeStatus,
    nowMillis: Long,
    batteryOptimizationsIgnored: Boolean,
    dndPolicyGranted: Boolean,
    onMonitoringToggle: (Boolean) -> Unit,
    onDisableBatteryOptimizations: () -> Unit,
    onRequestDndPolicy: () -> Unit
) {
    var localEnabled by remember(status.monitoringEnabled) { mutableStateOf(status.monitoringEnabled) }
    var showDisableConfirm by remember { mutableStateOf(false) }

    if (showDisableConfirm) {
        AlertDialog(
            onDismissRequest = { showDisableConfirm = false },
            title = { Text("System deaktivieren?") },
            text = { Text("Möchtest du das Alarmsystem wirklich deaktivieren?") },
            confirmButton = {
                TextButton(onClick = {
                    showDisableConfirm = false
                    localEnabled = false
                    onMonitoringToggle(false)
                }) { Text("Deaktivieren") }
            },
            dismissButton = {
                TextButton(onClick = { showDisableConfirm = false }) { Text("Abbrechen") }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Alarmsystem")
            Switch(
                checked = localEnabled,
                onCheckedChange = { newValue ->
                    if (newValue) {
                        localEnabled = true
                        onMonitoringToggle(true)
                    } else {
                        showDisableConfirm = true
                    }
                }
            )
        }
        Text("Letzter Wert: ${status.lastValue?.toString() ?: "--"} mg/dL")
        Text("Letzte Messung: ${formatElapsed(status.lastDataAt, "unbekannt", nowMillis)}")
        Text("Sync: ${formatElapsed(status.lastSuccessAt, "noch nie", nowMillis)}")
        Text("Letzter Versuch: ${formatElapsed(status.lastAttemptAt, "noch nie", nowMillis)}")
        Text("Fehler: ${status.failureStreak}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Text(if (batteryOptimizationsIgnored) "Akku-Optimierung: aus" else "Akku-Optimierung: an")
            if (!batteryOptimizationsIgnored) {
                Button(onClick = onDisableBatteryOptimizations, modifier = Modifier.fillMaxWidth()) {
                    Text("Akku-Optimierung deaktivieren")
                }
            }

            Text(if (dndPolicyGranted) "Nicht stören: Alarm überschreibt" else "Nicht stören: keine Berechtigung")
            if (!dndPolicyGranted) {
                Button(onClick = onRequestDndPolicy, modifier = Modifier.fillMaxWidth()) {
                    Text("Nicht-stören-Berechtigung erteilen")
                }
            }
        }
    }
}

private fun areBatteryOptimizationsIgnored(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = context.getSystemService(PowerManager::class.java) ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun isDndPolicyGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val nm = context.getSystemService(NotificationManager::class.java) ?: return false
    return nm.isNotificationPolicyAccessGranted
}

private fun requestDndPolicy(context: Context): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "Auf diesem Gerät nicht verfügbar"

    val directIntent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val fallbackIntent = Intent(Settings.ACTION_SOUND_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return try {
        when {
            canHandleIntent(context, directIntent) -> {
                context.startActivity(directIntent)
                null
            }
            canHandleIntent(context, fallbackIntent) -> {
                context.startActivity(fallbackIntent)
                "Direkte Nicht-stören-Seite nicht verfügbar"
            }
            else -> "Nicht-stören-Einstellungen konnten nicht geöffnet werden"
        }
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "requestDndPolicy fehlgeschlagen", e)
        "Nicht-stören-Einstellungen konnten nicht geöffnet werden"
    }
}

private fun requestIgnoreBatteryOptimizations(context: Context): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
    val packageUri = Uri.parse("package:${context.packageName}")
    val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = packageUri
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return try {
        when {
            canHandleIntent(context, requestIntent) -> {
                context.startActivity(requestIntent)
                null
            }
            canHandleIntent(context, fallbackIntent) -> {
                context.startActivity(fallbackIntent)
                "Direkte Akku-Ausnahme nicht verfügbar"
            }
            else -> "Akku-Einstellungen konnten nicht geöffnet werden"
        }
    } catch (_: Exception) {
        try {
            if (canHandleIntent(context, fallbackIntent)) {
                context.startActivity(fallbackIntent)
                "Direkte Akku-Ausnahme nicht verfügbar"
            } else {
                "Akku-Einstellungen konnten nicht geöffnet werden"
            }
        } catch (_: Exception) {
            "Akku-Einstellungen konnten nicht geöffnet werden"
        }
    }
}

private fun canHandleIntent(context: Context, intent: Intent): Boolean {
    return intent.resolveActivity(context.packageManager) != null
}

private fun setMonitoring(context: Context, enabled: Boolean): String? {
    val intent = Intent(context, NightWatchService::class.java).apply {
        action = if (enabled) NightWatchService.ACTION_START else NightWatchService.ACTION_STOP
    }
    return try {
        if (enabled) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            // Beim Deaktivieren: SharedPrefs sofort clearen, damit die UI reagiert,
            // auch wenn der Service gerade nicht lÃ¤uft
            SettingsStore.setMonitoringEnabled(context, false)
            SettingsStore.setAlarmActive(context, false)
            runCatching { context.startService(intent) }
        }
        null
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "setMonitoring failed", e)
        e.message ?: "Fehler"
    }
}

private data class RuntimeStatus(
    val monitoringEnabled: Boolean,
    val lastValue: Int?,
    val lastSuccessAt: Long?,
    val lastAttemptAt: Long?,
    val lastDataAt: Long?,
    val failureStreak: Int,
    val snoozeUntil: Long,
    val lowerLimit: Int,
    val upperLimit: Int,
    val history: List<GlucoseReading>
)

private enum class AppScreen {
    HOME,
    SOUND,
    NIGHTSCOUT,
    TIMER,
    SNOOZE
}

private data class ToneOption(
    val label: String,
    val uri: String,
    val score: Int,
    val source: ToneSource = ToneSource.SYSTEM
)

private enum class ToneSource {
    SYSTEM,
    CUSTOM
}

private object ToneCache {
    @Volatile
    private var cached: List<ToneOption>? = null

    fun getOrLoad(context: Context): List<ToneOption> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val loaded = loadAggressiveTones(context)
            cached = loaded
            return loaded
        }
    }
}

private fun loadAggressiveTones(context: Context): List<ToneOption> {
    val keywords = listOf("alarm", "siren", "warning", "alert", "beep", "digital", "buzzer", "clock")
    val all = mutableListOf<ToneOption>()

    fun collect(type: Int, baseScore: Int, maxScan: Int) {
        val manager = RingtoneManager(context).apply { setType(type) }
        val cursor = manager.cursor ?: return
        cursor.use {
            val scanLimit = minOf(it.count, maxScan)
            for (index in 0 until scanLimit) {
                it.moveToPosition(index)
                val uri = manager.getRingtoneUri(index)?.toString().orEmpty()
                if (uri.isBlank()) continue
                val title = runCatching { it.getString(RingtoneManager.TITLE_COLUMN_INDEX) }
                    .getOrNull()
                    .orEmpty()
                val lowerTitle = title.lowercase()
                val hitScore = keywords.count { key -> lowerTitle.contains(key) }
                all += ToneOption(title, uri, baseScore + hitScore * 10)
            }
        }
    }

    collect(RingtoneManager.TYPE_ALARM, 100, maxScan = 80)
    collect(RingtoneManager.TYPE_RINGTONE, 60, maxScan = 40)

    return all
        .filter { it.label.isNotBlank() }
        .distinctBy { it.uri }
        .sortedByDescending { it.score }
        .take(8)
        .ifEmpty {
            defaultRingtones().mapIndexed { index, item ->
                ToneOption(item.first, item.second, 50 - index)
            }
        }
}

private fun formatElapsed(timestamp: Long?, emptyText: String, nowMillis: Long = System.currentTimeMillis()): String {
    if (timestamp == null || timestamp <= 0L) return emptyText
    val delta = (nowMillis - timestamp).coerceAtLeast(0L)
    val totalSeconds = delta / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d:%02d", hours, minutes, seconds)
}

private fun defaultRingtones(): List<Pair<String, String>> {
    val alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.toString().orEmpty()
    val ring = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)?.toString().orEmpty()
    val notif = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)?.toString().orEmpty()
    return listOf(
        "Standard Alarm" to alarm,
        "Standard Klingelton" to ring,
        "Standard Benachrichtigung" to notif
    ).distinctBy { it.second }
}

@Composable
private fun ToneRow(
    tone: ToneOption,
    selected: Boolean,
    onSelect: () -> Unit,
    trailingContent: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect
        )
        Text(
            text = tone.label,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onSelect)
        )
        trailingContent?.invoke()
    }
}

private fun CustomTone.toToneOption(context: Context): ToneOption {
    return ToneOption(
        label = name,
        uri = customToneUri(context, this),
        score = 0,
        source = ToneSource.CUSTOM
    )
}

private sealed interface CustomToneImportResult {
    data class Success(val tone: CustomTone) : CustomToneImportResult
    data class Failure(val message: String) : CustomToneImportResult
}

private fun normalizeToneName(value: String): String {
    return value.trim().lowercase(Locale.ROOT)
}

private fun importCustomTone(
    context: Context,
    sourceUri: Uri,
    existingNames: Set<String>
): CustomToneImportResult {
    val displayName = queryDisplayName(context, sourceUri)?.trim().orEmpty()
    val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val mimeType = context.contentResolver.getType(sourceUri).orEmpty().lowercase(Locale.ROOT)
    if (extension != "mp3" && !mimeType.contains("mpeg") && !mimeType.contains("mp3")) {
        return CustomToneImportResult.Failure("Nur MP3-Dateien sind erlaubt")
    }

    val baseName = displayName.substringBeforeLast('.', displayName)
        .trim()
        .ifBlank { "Eigener Ton" }
    if (existingNames.contains(normalizeToneName(baseName))) {
        return CustomToneImportResult.Failure("Name existiert schon")
    }

    val targetDir = File(context.filesDir, "custom_tones").apply { mkdirs() }
    val fileName = "tone_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.mp3"
    val targetFile = File(targetDir, fileName)

    return runCatching {
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        } ?: error("Datei konnte nicht gelesen werden")

        CustomToneImportResult.Success(
            CustomTone(
                id = UUID.randomUUID().toString(),
                name = baseName,
                fileName = fileName
            )
        )
    }.getOrElse {
        targetFile.delete()
        CustomToneImportResult.Failure("Import fehlgeschlagen")
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } else {
                    null
                }
            }
    }.getOrNull()
}

private fun customToneUri(context: Context, tone: CustomTone): String {
    return File(File(context.filesDir, "custom_tones"), tone.fileName).toURI().toString()
}

private fun deleteCustomToneFile(context: Context, tone: CustomTone) {
    runCatching {
        File(File(context.filesDir, "custom_tones"), tone.fileName).delete()
    }
}
