package com.example.wecker

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.addCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.example.wecker.service.NightWatchService
import com.example.wecker.ui.theme.WeckerTheme
import kotlinx.coroutines.delay
import kotlin.random.Random

private enum class ChallengeOperator {
    PLUS,
    TIMES
}

private data class AlarmChallenge(
    val first: Int,
    val second: Int,
    val third: Int,
    val op1: ChallengeOperator,
    val op2: ChallengeOperator,
    val expected: Int
) {
    val expression: String
        get() = "$first ${symbol(op1)} $second ${symbol(op2)} $third = ?"

    private fun symbol(op: ChallengeOperator): String =
        if (op == ChallengeOperator.PLUS) "+" else "*"
}

private fun generateChallenge(random: Random = Random): AlarmChallenge {
    val combinations = listOf(
        ChallengeOperator.PLUS to ChallengeOperator.PLUS,
        ChallengeOperator.PLUS to ChallengeOperator.TIMES,
        ChallengeOperator.TIMES to ChallengeOperator.TIMES
    )

    repeat(500) {
        val (op1, op2) = combinations[random.nextInt(combinations.size)]
        val first = random.nextInt(2, 10)
        val second = random.nextInt(2, 10)
        val third = random.nextInt(2, 10) // Multiplikator bleibt einstellig.

        val expected = when {
            op1 == ChallengeOperator.PLUS && op2 == ChallengeOperator.PLUS -> first + second + third
            op1 == ChallengeOperator.PLUS && op2 == ChallengeOperator.TIMES -> first + (second * third)
            else -> (first * second) * third
        }

        val hasThreeDigitOperand = first >= 100 || second >= 100 || third >= 100
        val invalidAdditionValues = when {
            op1 == ChallengeOperator.PLUS && op2 == ChallengeOperator.PLUS -> {
                first + second >= 100 || first + second + third >= 100
            }
            op1 == ChallengeOperator.PLUS && op2 == ChallengeOperator.TIMES -> {
                second * third >= 100 || first + (second * third) >= 100
            }
            else -> false
        }
        val invalidMultiplier = when {
            op1 == ChallengeOperator.PLUS && op2 == ChallengeOperator.TIMES -> third > 9
            op1 == ChallengeOperator.TIMES && op2 == ChallengeOperator.TIMES -> second > 9 || third > 9
            else -> false
        }

        if (!hasThreeDigitOperand && !invalidAdditionValues && !invalidMultiplier && expected in 0..99) {
            return AlarmChallenge(
                first = first,
                second = second,
                third = third,
                op1 = op1,
                op2 = op2,
                expected = expected
            )
        }
    }

    // Fallback, falls die Zufallsgenerierung unerwartet oft keine gueltige Aufgabe findet.
    return AlarmChallenge(
        first = 4,
        second = 5,
        third = 3,
        op1 = ChallengeOperator.PLUS,
        op2 = ChallengeOperator.TIMES,
        expected = 19
    )
}

class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        onBackPressedDispatcher.addCallback(this) {
            // Absichtlich leer: Alarm darf nicht durch Zurueck beendet werden.
        }
        enableEdgeToEdge()
        val reasonCategory = intent.getStringExtra(NightWatchService.EXTRA_ALARM_REASON)
            ?: NightWatchService.REASON_UNKNOWN
        val reasonValue = intent.getIntExtra(NightWatchService.EXTRA_ALARM_VALUE, -1)
        setContent {
            WeckerTheme {
                AlarmChallengeScreen(
                    reasonCategory = reasonCategory,
                    reasonValue = reasonValue,
                    onSolved = { dismissAlarm() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun dismissAlarm() {
        val dismissIntent = Intent(this, NightWatchService::class.java).apply {
            action = if (intent.getStringExtra(NightWatchService.EXTRA_ALARM_MODE) == "TEST") {
                NightWatchService.ACTION_DISMISS_TEST_ALARM
            } else {
                NightWatchService.ACTION_DISMISS_ALARM
            }
        }
        startService(dismissIntent)
        finish()
    }
}

@Composable
private fun AlarmChallengeScreen(
    reasonCategory: String,
    reasonValue: Int,
    onSolved: () -> Unit
) {
    var challenge by remember { mutableStateOf(generateChallenge()) }
    var answer by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    var confirmCountdown by remember { mutableStateOf(10) }
    val scrollState = rememberScrollState()

    // Countdown startet genau einmal beim Oeffnen des Alarm-Screens.
    LaunchedEffect(Unit) {
        while (confirmCountdown > 0) {
            delay(1000)
            confirmCountdown -= 1
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(scrollState)
            .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Alarm aktiv", style = MaterialTheme.typography.headlineMedium)

        AlarmReasonCard(reasonCategory = reasonCategory, reasonValue = reasonValue)

        Text(
            text = "Löse die Aufgabe zum Stoppen:",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            text = challenge.expression,
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(top = 16.dp, bottom = 16.dp)
        )

        OutlinedTextField(
            value = answer,
            onValueChange = { answer = it.filter(Char::isDigit) },
            label = { Text("Antwort") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.Black,
                unfocusedTextColor = Color.Black,
                focusedLabelColor = Color.Black,
                unfocusedLabelColor = Color.Black,
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White
            )
        )

        Button(
            onClick = {
                val parsed = answer.toIntOrNull()
                if (parsed == challenge.expected) {
                    onSolved()
                } else {
                    errorText = "Falsch. Neue Aufgabe!"
                    challenge = generateChallenge()
                    answer = ""
                }
            },
            enabled = confirmCountdown == 0,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = Color.Gray,
                disabledContentColor = Color.White
            ),
            modifier = Modifier.padding(top = 16.dp)
        ) {
            val buttonText = if (confirmCountdown > 0) {
                "Alarm stoppen ($confirmCountdown)"
            } else {
                "Alarm stoppen"
            }
            Text(buttonText)
        }

        if (errorText.isNotBlank()) {
            Text(
                text = errorText,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

/**
 * Zeigt gut lesbar den Grund des Alarms an. Weiße Karte, damit die geforderten
 * Farben (Schwarz bei Sensor-/Verbindungsfehler) auf dem dunklen Theme sichtbar sind.
 */
@Composable
private fun AlarmReasonCard(reasonCategory: String, reasonValue: Int) {
    // Farben mit ausreichendem Kontrast auf weißem Grund.
    val colorLow = Color(0xFFD32F2F)   // Rot – zu tief
    val colorHigh = Color(0xFFB8860B)  // kräftiges Gold/Gelb – zu hoch (lesbar auf Weiß)
    val colorError = Color.Black       // Sensor-/Verbindungsfehler

    val (label, color) = when (reasonCategory) {
        NightWatchService.REASON_LOW -> {
            val valueText = if (reasonValue > 0) " ($reasonValue)" else ""
            "Zu Tief$valueText" to colorLow
        }
        NightWatchService.REASON_HIGH -> {
            val valueText = if (reasonValue > 0) " ($reasonValue)" else ""
            "Zu Hoch$valueText" to colorHigh
        }
        NightWatchService.REASON_SENSOR -> "Sensorfehler" to colorError
        NightWatchService.REASON_CONNECTION -> "Verbindungsfehler" to colorError
        NightWatchService.REASON_TEST -> "Testalarm" to Color(0xFF555555)
        else -> return
    }

    Column(
        modifier = Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(vertical = 16.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Grund",
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xFF555555)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
