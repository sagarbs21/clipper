package com.sagar.shortsclipper.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Downloads a WebVTT caption file and condenses it into a compact, timestamped
 * transcript ("[m:ss] text") that the AI planner can reason about.
 *
 * Best-effort only: returns null on any problem (no captions, network error, etc.).
 */
object CaptionsRepository {

    private const val MAX_CHARS = 6000
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:102.0) Gecko/20100101 Firefox/102.0"

    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Blocking network call. Run on a background dispatcher. */
    fun fetchTranscript(vttUrl: String): String? {
        return try {
            val request = Request.Builder().url(vttUrl).header("User-Agent", UA).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                parseVtt(body).takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVtt(raw: String): String {
        val sb = StringBuilder()
        val lines = raw.replace("\r\n", "\n").split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val arrow = line.indexOf("-->")
            if (arrow > 0) {
                val startToken = line.substring(0, arrow).trim().substringBefore(" ")
                val stampSec = parseVttTimeToSec(startToken)
                i++
                val text = StringBuilder()
                while (i < lines.size && lines[i].trim().isNotEmpty()) {
                    text.append(stripTags(lines[i].trim())).append(' ')
                    i++
                }
                val cleaned = text.toString().trim()
                if (cleaned.isNotEmpty() && stampSec >= 0) {
                    sb.append('[').append(formatMmSs(stampSec)).append("] ")
                        .append(cleaned).append('\n')
                    if (sb.length >= MAX_CHARS) break
                }
            } else {
                i++
            }
        }
        return sb.toString().trim()
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]*>"), "").trim()

    private fun parseVttTimeToSec(token: String): Long {
        // Accepts HH:MM:SS.mmm or MM:SS.mmm
        return try {
            val parts = token.split(":")
            when (parts.size) {
                3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toDouble().toLong()
                2 -> parts[0].toLong() * 60 + parts[1].toDouble().toLong()
                else -> -1
            }
        } catch (e: Exception) {
            -1
        }
    }

    private fun formatMmSs(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
