package com.jointheparty.app.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FIELD DEBUG (2026-07-24): an on-screen diagnostic sink so the app reports
 * its own recognition/playback state without adb. Every line also goes to
 * logcat (tag JTP). Rendered by SessionScreen's debug overlay; remove the
 * overlay (and stop calling this) before any user-facing release.
 */
object DebugLog {
    private const val MAX_LINES = 16
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun log(message: String) {
        android.util.Log.i("JTP", message)
        val stamped = "${clock.format(Date())}  $message"
        _lines.update { (it + stamped).takeLast(MAX_LINES) }
    }
}
