package com.sagar.shortsclipper.data

import com.sagar.shortsclipper.model.AiPlan
import com.sagar.shortsclipper.model.AiSuggestion
import com.sagar.shortsclipper.model.VideoMeta
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Uses Google Gemini to (1) classify the content type and (2) propose vertical
 * Shorts clips. For YouTube videos a caption transcript is included so the model
 * can reason about actual moments; otherwise it works from title + duration.
 *
 * Requires a free API key from Google AI Studio (https://aistudio.google.com/apikey).
 * All calls are best-effort and throw a readable message on failure.
 */
object AiClipPlanner {

    // Change this if a newer/older Gemini model is preferred or this one is retired.
    private const val MODEL = "gemini-2.5-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s"

    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Blocking network call. Run on a background dispatcher. */
    fun plan(
        meta: VideoMeta,
        transcript: String?,
        apiKey: String,
        maxClips: Int,
        maxClipSec: Int
    ): AiPlan {
        val prompt = buildPrompt(meta, transcript, maxClips, maxClipSec)

        val requestJson = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.8)
                    .put("responseMimeType", "application/json")
            )
        }

        val url = ENDPOINT.format(MODEL, apiKey)
        val request = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw RuntimeException("Gemini error ${response.code}: ${extractApiError(raw)}")
            }
            val modelText = extractModelText(raw)
                ?: throw RuntimeException("AI returned no content.")
            return parsePlan(modelText, meta.durationSec, maxClipSec)
        }
    }

    private fun buildPrompt(
        meta: VideoMeta,
        transcript: String?,
        maxClips: Int,
        maxClipSec: Int
    ): String {
        val transcriptBlock = if (!transcript.isNullOrBlank()) {
            "Timestamped transcript (use these timestamps):\n$transcript"
        } else {
            "(No transcript available. Infer likely structure from the title and duration.)"
        }
        return """
            You are an expert short-form video editor who makes viral vertical Shorts.
            Analyze the video below and do two things:

            1) Classify "contentType" as exactly one of:
               movie, tv_series, music, sports, gaming, podcast, tutorial, news, vlog, comedy, other.
            2) Propose up to $maxClips clips that would perform well as vertical 9:16 Shorts,
               following current short-form trends (strong hook in first 2s, self-contained,
               emotional or high-impact, quotable). Each clip must be between 8 and $maxClipSec seconds,
               and must lie within 0..${meta.durationSec} seconds of the source.
               For movies/tv_series, prefer iconic/quotable beats and avoid spoilers in titles.
               For music, prefer the catchiest hook/chorus. For sports/gaming, prefer peak action.

            Return ONLY JSON in this exact shape:
            {
              "contentType": "string",
              "clips": [
                {"startSec": number, "endSec": number, "title": "string",
                 "hashtags": ["#tag", "#tag2"], "reason": "short why"}
              ]
            }

            Video title: ${meta.title}
            Uploader: ${meta.uploader}
            Duration (sec): ${meta.durationSec}
            $transcriptBlock
        """.trimIndent()
    }

    private fun extractModelText(rawResponse: String): String? {
        return try {
            JSONObject(rawResponse)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        } catch (e: Exception) {
            null
        }
    }

    private fun extractApiError(rawResponse: String): String {
        return try {
            JSONObject(rawResponse).getJSONObject("error").optString("message")
                .ifBlank { rawResponse.take(200) }
        } catch (e: Exception) {
            rawResponse.take(200)
        }
    }

    private fun parsePlan(modelText: String, durationSec: Long, maxClipSec: Int): AiPlan {
        val root = JSONObject(modelText)
        val contentType = root.optString("contentType", "other").ifBlank { "other" }
        val clipsArray = root.optJSONArray("clips") ?: JSONArray()

        val suggestions = mutableListOf<AiSuggestion>()
        for (i in 0 until clipsArray.length()) {
            val obj = clipsArray.optJSONObject(i) ?: continue
            var start = obj.optDouble("startSec", -1.0)
            var end = obj.optDouble("endSec", -1.0)
            if (start < 0 || end <= start) continue

            // Clamp to the source and to the max length.
            start = start.coerceIn(0.0, durationSec.toDouble())
            end = end.coerceIn(0.0, durationSec.toDouble())
            if (end - start > maxClipSec) end = start + maxClipSec
            if (end <= start) continue

            val title = obj.optString("title", "Clip ${i + 1}").ifBlank { "Clip ${i + 1}" }
            val reason = obj.optString("reason", "")
            val hashtags = obj.optJSONArray("hashtags")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { t -> t.isNotBlank() } }
            } ?: emptyList()

            suggestions.add(AiSuggestion(start, end, title, hashtags, reason))
        }
        return AiPlan(contentType = contentType, suggestions = suggestions)
    }
}
