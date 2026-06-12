package com.sagar.shortsclipper.data

import com.sagar.shortsclipper.model.AiPlan
import com.sagar.shortsclipper.model.AiProvider
import com.sagar.shortsclipper.model.AiSuggestion
import com.sagar.shortsclipper.model.VideoMeta
import com.sagar.shortsclipper.model.VideoMetadata
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
        val text = completeWithRetry(provider, model, prompt, apiKey)
        return parsePlan(text, meta.durationSec, maxClipSec)
    }

    /** Generates YouTube upload metadata (title, description, tags) for one clip. */
    fun generateMetadata(
        provider: AiProvider,
        model: String,
        apiKey: String,
        sourceTitle: String,
        clipHint: String,
        transcript: String?
    ): VideoMetadata {
        val prompt = buildMetadataPrompt(sourceTitle, clipHint, transcript)
        val text = completeWithRetry(provider, model, prompt, apiKey)
        return parseMetadata(text)
    }

    /** Runs one completion against the chosen provider, retrying transient errors. */
    private fun completeWithRetry(
        provider: AiProvider,
        model: String,
        prompt: String,
        apiKey: String
    ): String {
        var lastRetryable: RetryableException? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return if (provider == AiProvider.GEMINI) {
                    geminiText(prompt, model, apiKey)
                } else {
                    openAiText(provider.baseUrl, model, prompt, apiKey)
                }
            } catch (e: RetryableException) {
                lastRetryable = e
                if (attempt < MAX_ATTEMPTS) {
                    Thread.sleep(2000L * attempt) // backoff: 2s, 4s
                }
            }
        }
        throw RuntimeException(
            (lastRetryable?.message ?: "${provider.label} is busy") +
                ". Please try again in a moment."
        )
    }

    private fun geminiText(prompt: String, model: String, apiKey: String): String {
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
        return extractModelText(raw) ?: throw RuntimeException("AI returned no content.")
    }

    private fun openAiText(baseUrl: String, model: String, prompt: String, apiKey: String): String {
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
        return try {
            JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: Exception) {
            throw RuntimeException("AI returned no content.")
        }
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

    private fun buildMetadataPrompt(
        sourceTitle: String,
        clipHint: String,
        transcript: String?
    ): String {
        val transcriptBlock = if (!transcript.isNullOrBlank()) {
            "Transcript excerpt:\n${transcript.take(3000)}"
        } else {
            "(No transcript.)"
        }
        return """
            Write YouTube Shorts upload metadata for a vertical clip. Be catchy and
            trend-aware, but accurate to the content. Return ONLY JSON:
            {"title": "string (<=90 chars, strong hook)",
             "description": "2-3 sentences, then 3-6 relevant hashtags including #Shorts",
             "tags": ["keyword", "keyword2", "... 6-12 search keywords"]}

            Source video title: $sourceTitle
            This clip is about: $clipHint
            $transcriptBlock
        """.trimIndent()
    }

    private fun parseMetadata(modelText: String): VideoMetadata {
        val o = JSONObject(extractJsonObject(modelText))
        val title = o.optString("title").trim().take(100).ifBlank { "My Short #Shorts" }
        val description = o.optString("description").trim()
        val tags = o.optJSONArray("tags")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { t -> t.isNotBlank() } }
        } ?: emptyList()
        return VideoMetadata(title, description, tags)
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
