package com.sagar.shortsclipper.util

/**
 * Parse a free-text time into milliseconds.
 * Accepts "90", "1:30", "1:30.5", or "1:02:03". Returns null if unparseable.
 */
fun parseTimeToMs(text: String): Long? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val parts = t.split(":")
    return try {
        when (parts.size) {
            3 -> (parts[0].toLong() * 3600 + parts[1].toLong() * 60) * 1000 +
                    (parts[2].toDouble() * 1000).toLong()
            2 -> parts[0].toLong() * 60 * 1000 + (parts[1].toDouble() * 1000).toLong()
            1 -> (parts[0].toDouble() * 1000).toLong()
            else -> null
        }
    } catch (e: NumberFormatException) {
        null
    }
}

/** Format milliseconds as m:ss or h:mm:ss. */
fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
