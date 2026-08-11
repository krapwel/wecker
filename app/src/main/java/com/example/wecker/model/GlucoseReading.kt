package com.example.wecker.model

/**
 * Ein einzelner Blutzucker-Messwert mit Zeitstempel (ms seit Epoch).
 * Wird fuer das Verlaufsdiagramm der letzten Stunden verwendet.
 */
data class GlucoseReading(
    val sgv: Int,
    val timestamp: Long
)
