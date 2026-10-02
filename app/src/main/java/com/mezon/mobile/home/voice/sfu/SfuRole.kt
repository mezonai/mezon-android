package com.mezon.mobile.home.voice.sfu

enum class SfuRole(val wire: String) {
    SPEAKER("speaker"),
    AUDIENCE("audience");

    companion object {
        fun fromWire(value: String?): SfuRole = when (value) {
            "audience" -> AUDIENCE
            else -> SPEAKER
        }
    }
}
