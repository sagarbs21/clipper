package com.sagar.shortsclipper.data

import com.sagar.shortsclipper.model.AiPlan
import com.sagar.shortsclipper.model.AiProvider
import com.sagar.shortsclipper.model.AiSuggestion
import com.sagar.shortsclipper.model.VideoMeta
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Classifies the content type and proposes vertical Shorts clips using a configurable
 * AI provider. Gemini uses its own REST API; Groq/OpenRouter/OpenAI use the shared
 * OpenAI-compatible chat-completions API.
 *
 * For YouTube videos a caption transcript is included so the model can reason about
 * actual moments; otherwise it works from title + duration. All calls are best-effort,
 * retry transient errors, and throw a readable message on failure.
 */
object AiClipPlanner {

    private const val GEMINI_ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s"

    private const val MAX_ATTEMPTS = 3

    private val client = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /** Thrown for transient failures (overload/timeout) that are worth retrying. */
    private class RetryableException(message: String) : Exception(message)

    /** Blocking network call. Run on a background dispatcher. Retries transient errors. */
    fun plan(
        provider: AiProvider,
        model: String,
        meta: VideoMeta,
        transcript: String?,
        apiKey: String,
        maxClips: Int,
        maxClipSec: Int
    ): AiPlan {
        val prompt = buildPrompt(meta, transcript, maxClips, maxClipSec)

        var lastRetryable: RetryableException? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return if (provider == AiProvider.GEMINI) {
                    executeGemini(prompt, model, apiKey, meta.durationSec, maxClipSec)
                } else {
                    executeOpenAiCompatible(
                        provider.baseUrl, model, prompt, apiKey, meta.durationSec, maxClipSec
                    )
                }
            } catch (e: RetryableException) {
                lastRetryable = e
                if (attempt < MAX_ATTEMPTS) {
                    // Backoff: 2s, 4s. Gives an overloaded model time to recover.
                    Thread.sleep(2000L * attempt)
                }
            }
        }
        throw RuntimeException(
            (lastRetryable?.message ?: "${provider.label} is busy") +
                ". Please try again in a moment."
        )
    }

    private fun executeGemini(
        prompt: String,
        model: String,
        apiKey: String,
        durationSec: Long,
        maxClipSec: Int
    ): AiPlan {
        val body = JSONObject().apply {
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
        }.toString()

        val request = Request.Builder()
            .url(GEMINI_ENDPOINT.format(model, apiKey))
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val raw = executeWithRetryableErrors(request, "Gemini")
        val modelText = extractModelText(raw) ?: throw RuntimeException("AI returned no content.")
        return parsePlan(modelText, durationSec, maxClipSec)
    }

    private fun executeOpenAiCompatible(
        baseUrl: String,
        model: String,
        prompt: String,
        apiKey: String,
        durationSec: Long,
        maxClipSec: Int
    ): AiPlan {
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.8)
            put("response_format", JSONObject().put("type", "json_object"))
            put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
            )
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val raw = executeWithRetryableErrors(request, "AI provider")
        val content = try {
            JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: Exception) {
            throw RuntimeException("AI returned no content.")
        }
        return parsePlan(content, durationSec, maxClipSec)
    }

    /** Runs the request; maps timeouts and 429/5xx to retryable errors. Returns the body. */
    private fun executeWithRetryableErrors(request: Request, label: String): String {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw RetryableException("Network/timeout (${e.message ?: "no response"})")
        }
        response.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                if (it.code == 429 || it.code in 500..599) {
                    throw RetryableException("$label busy (HTTP ${it.code})")
                }
                throw RuntimeException("$label error ${it.code}: ${extractApiError(raw)}")
            }
            return raw
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

    /** Some models wrap JSON in ```json fences or add prose; extract the object. */
    private fun extractJsonObject(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else text
    }

    private fun parsePlan(modelText: String, durationSec: Long, maxClipSec: Int): AiPlan {
        val root = JSONObject(extractJsonObject(modelText))
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
