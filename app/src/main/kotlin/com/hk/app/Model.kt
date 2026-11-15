package com.hk.app

import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import java.util.Locale

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val genre: String,
    val color1: Color,
    val color2: Color,
    val lyrics: List<String> = emptyList(),
    val audioUri: Uri? = null,
    val albumArtBitmap: ImageBitmap? = null,
    val folderPath: String = "Music"
)

data class VideoItem(
    val id: Long,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val folder: String,
    val uri: Uri,
    val dateAdded: Long = 0L,
    val mimeType: String = "video/mp4"
) {
    val isPortrait: Boolean get() = height > width
}

data class LrcLine(val timeMs: Long, val text: String)

private val LRC_TIME_TAG =
    Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

/**
 * Parses real LRC content. Supports multiple timestamps per line,
 * [mm:ss], [mm:ss.xx] and [mm:ss.xxx] formats.
 */
fun parseLrc(rawLyrics: List<String>): List<LrcLine> {
    val result = mutableListOf<LrcLine>()
    for (raw in rawLyrics) {
        val line = raw.removePrefix("\uFEFF").trim()
        if (line.isEmpty()) continue
        val matches = LRC_TIME_TAG.findAll(line).toList()
        if (matches.isEmpty()) {
            // Skip metadata tags such as [ar:...], [ti:...]
            if (!line.startsWith("[")) result.add(LrcLine(0L, line))
            continue
        }
        val text = line.substring(matches.last().range.last + 1).trim()
        for (m in matches) {
            val minutes = m.groupValues[1].toLongOrNull() ?: 0L
            val seconds = m.groupValues[2].toLongOrNull() ?: 0L
            val fraction = m.groupValues[3]
            val millis = when {
                fraction.isEmpty() -> 0L
                fraction.length == 1 -> fraction.toLong() * 100L
                fraction.length == 2 -> fraction.toLong() * 10L
                else -> fraction.toLong()
            }
            result.add(LrcLine(minutes * 60_000L + seconds * 1_000L + millis, text))
        }
    }
    return result.sortedBy { it.timeMs }
}

fun formatTime(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", safe / 60, safe % 60)
}

fun formatTimeMs(millis: Long): String = formatTime((millis / 1000L).toInt())

fun Color.toHex(): String {
    val red = (this.red * 255f).toInt().coerceIn(0, 255)
    val green = (this.green * 255f).toInt().coerceIn(0, 255)
    val blue = (this.blue * 255f).toInt().coerceIn(0, 255)
    return String.format(Locale.US, "#%02X%02X%02X", red, green, blue)
}
