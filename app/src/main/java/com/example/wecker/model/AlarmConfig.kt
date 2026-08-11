package com.example.wecker.model

data class AlarmConfig(
    val nightscoutUrl: String = "",
    val nightscoutToken: String = "",
    val lowerLimit: Int = 80,
    val upperLimit: Int = 180,
    val ringtoneUris: List<String> = emptyList(),
    val vibrationEnabled: Boolean = true
)


