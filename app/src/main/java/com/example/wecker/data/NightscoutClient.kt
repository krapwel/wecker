package com.example.wecker.data

import com.example.wecker.model.AlarmConfig
import com.example.wecker.model.GlucoseReading
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.SSLException

class NightscoutClient {
    sealed class Result {
        data class Success(
            val sgv: Int,
            val dataTimestamp: Long,
            val history: List<GlucoseReading> = emptyList()
        ) : Result()
        data class Failure(val message: String) : Result()
    }

    sealed class ValidationResult {
        data object Success : ValidationResult()
        data class Failure(val message: String) : ValidationResult()
    }

    fun validateEndpoint(urlInput: String, tokenInput: String): ValidationResult {
        if (urlInput.isBlank()) return ValidationResult.Failure("URL fehlt")

        val base = urlInput.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return ValidationResult.Failure("URL muss mit http:// oder https:// starten")
        }

        val tokenQuery = if (tokenInput.isNotBlank()) {
            "?token=${URLEncoder.encode(tokenInput.trim(), Charsets.UTF_8.name())}"
        } else {
            ""
        }
        val url = "$base/api/v1/status.json$tokenQuery"

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 6_000
            setRequestProperty("Accept", "application/json")
        }

        return try {
            connection.connect()
            if (connection.responseCode in 200..299) {
                ValidationResult.Success
            } else {
                ValidationResult.Failure("HTTP-Fehler ${connection.responseCode}")
            }
        } catch (e: UnknownHostException) {
            ValidationResult.Failure("Server nicht gefunden – URL prüfen")
        } catch (e: SocketTimeoutException) {
            ValidationResult.Failure("Zeitüberschreitung – Server antwortet nicht")
        } catch (e: SSLException) {
            ValidationResult.Failure("SSL-Fehler – Zertifikat ungültig")
        } catch (e: Exception) {
            ValidationResult.Failure("Verbindung fehlgeschlagen")
        } finally {
            connection.disconnect()
        }
    }

    fun fetchCurrentSgv(config: AlarmConfig): Result {
        if (config.nightscoutUrl.isBlank()) {
            return Result.Failure("Nightscout-URL fehlt")
        }

        val base = config.nightscoutUrl.trim().trimEnd('/')
        val tokenQuery = if (config.nightscoutToken.isNotBlank()) {
            "&token=${URLEncoder.encode(config.nightscoutToken.trim(), Charsets.UTF_8.name())}"
        } else {
            ""
        }
        // Mehrere Eintraege laden (ca. 3-4h), damit das Verlaufsdiagramm gefuellt wird.
        // Der neueste Eintrag [0] bleibt Grundlage der Alarm-Logik – unveraendertes Verhalten.
        val url = "$base/api/v1/entries/sgv.json?count=48$tokenQuery"

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
        }

        return try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                return Result.Failure("HTTP-Fehler ${connection.responseCode}")
            }
            val body = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            val array = JSONArray(body)
            if (array.length() == 0) {
                return Result.Failure("Keine Daten empfangen")
            }

            var currentSgv = -1
            var currentTimestamp = 0L
            val history = ArrayList<GlucoseReading>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                if (!obj.has("sgv")) continue
                val sgv = obj.optInt("sgv", -1)
                if (sgv <= 0) continue
                val ts = obj.optLong("date", 0L)
                // Erster gueltiger Eintrag = neuester Wert (Nightscout liefert absteigend).
                if (currentSgv == -1) {
                    currentSgv = sgv
                    currentTimestamp = ts
                }
                if (ts > 0L) {
                    history.add(GlucoseReading(sgv = sgv, timestamp = ts))
                }
            }
            if (currentSgv == -1) {
                return Result.Failure("Kein Messwert in der Antwort")
            }
            Result.Success(
                sgv = currentSgv,
                dataTimestamp = currentTimestamp,
                history = history
            )
        } catch (e: UnknownHostException) {
            Result.Failure("Server nicht gefunden")
        } catch (e: SocketTimeoutException) {
            Result.Failure("Zeitüberschreitung")
        } catch (e: SSLException) {
            Result.Failure("SSL-Fehler")
        } catch (e: Exception) {
            Result.Failure("Verbindungsfehler")
        } finally {
            connection.disconnect()
        }
    }
}
