package com.sagar.shortsclipper.data

import com.sagar.shortsclipper.model.AiProvider
import com.sagar.shortsclipper.model.ModelOption
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Asks each provider what it currently serves, and picks the best free model from that.
 *
 * Hardcoding model names doesn't survive contact with reality: providers retire them on a
 * few months' notice, so a pinned id quietly turns into "model not found" and the whole
 * AI side of the app stops working. Reading the live list means the app follows whatever
 * the provider offers today, and the fallbacks below are only for when the list can't be
 * fetched at all.
 */
object ModelCatalog {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Blocking network call. Run on a background dispatcher. */
    fun fetch(provider: AiProvider, apiKey: String): List<ModelOption> = when (provider) {
        AiProvider.GEMINI -> fetchGemini(apiKey)
        AiProvider.OPENROUTER -> fetchOpenRouter(apiKey)
        AiProvider.GROQ, AiProvider.OPENAI -> fetchOpenAiStyle(provider, apiKey)
    }

    /** Best free model, falling back to the best paid one if the provider has no free tier. */
    fun pickBest(options: List<ModelOption>): ModelOption? {
        val free = options.filter { it.free }
        return (if (free.isNotEmpty()) free else options).maxByOrNull { score(it) }
    }

    // ----- Providers -----

    private fun fetchGemini(apiKey: String): List<ModelOption> {
        val body = get(
            Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
                .header("x-goog-api-key", apiKey)
                .build()
        )
        val models = JSONObject(body).optJSONArray("models") ?: return emptyList()
        val out = ArrayList<ModelOption>()
        for (i in 0 until models.length()) {
            val m = models.optJSONObject(i) ?: continue
            // Only models this app can actually call. This also drops anything that has
            // moved to a different generation endpoint.
            val methods = m.optJSONArray("supportedGenerationMethods")
            val supported = (0 until (methods?.length() ?: 0))
                .any { methods?.optString(it) == "generateContent" }
            if (!supported) continue

            val id = m.optString("name").removePrefix("models/")
            if (id.isBlank() || !isTextModel(id)) continue
            out.add(
                ModelOption(
                    id = id,
                    // Google's free tier covers Flash and Flash-Lite but not Pro.
                    free = !id.lowercase(Locale.US).contains("pro"),
                    contextTokens = m.optInt("inputTokenLimit", 0)
                )
            )
        }
        return out
    }

    private fun fetchOpenAiStyle(provider: AiProvider, apiKey: String): List<ModelOption> {
        val body = get(
            Request.Builder()
                .url("${provider.baseUrl}/models")
                .header("Authorization", "Bearer $apiKey")
                .build()
        )
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<ModelOption>()
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val id = m.optString("id")
            if (id.isBlank() || !isTextModel(id)) continue
            if (m.has("active") && !m.optBoolean("active", true)) continue
            out.add(
                ModelOption(
                    id = id,
                    // Everything Groq serves is usable on its free tier; OpenAI has none.
                    free = provider == AiProvider.GROQ,
                    contextTokens = m.optInt("context_window", 0)
                )
            )
        }
        return out
    }

    private fun fetchOpenRouter(apiKey: String): List<ModelOption> {
        val body = get(
            Request.Builder()
                .url("${AiProvider.OPENROUTER.baseUrl}/models")
                .header("Authorization", "Bearer $apiKey")
                .build()
        )
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<ModelOption>()
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val id = m.optString("id")
            if (id.isBlank() || !isTextModel(id)) continue

            // OpenRouter also lists zero-priced music and image models. Requiring a
            // text-only output keeps those out without guessing from the name.
            val outputs = m.optJSONObject("architecture")?.optJSONArray("output_modalities")
            val textOut = outputs == null || (0 until outputs.length())
                .all { outputs.optString(it) == "text" }
            if (!textOut) continue

            val pricing = m.optJSONObject("pricing")
            val free = pricing != null &&
                pricing.optString("prompt").toDoubleOrNull() == 0.0 &&
                pricing.optString("completion").toDoubleOrNull() == 0.0
            out.add(ModelOption(id = id, free = free, contextTokens = m.optInt("context_length", 0)))
        }
        return out
    }

    private fun get(request: Request): String {
        client.newCall(request).execute().use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw RuntimeException("Could not list models (HTTP ${it.code}).")
            }
            return raw
        }
    }

    // ----- Ranking -----

    /**
     * Ranks on what can be read off the id and the context window, deliberately without
     * naming any model: a list of favourites would go stale exactly the way the pinned
     * defaults did. Generation number dominates, then parameter count, then context.
     */
    private fun score(m: ModelOption): Double {
        val id = m.id.lowercase(Locale.US)
        var score = generation(id) * 10.0
        score += sizeInBillions(id) * 0.05
        score += min(m.contextTokens / 1000.0, 400.0) * 0.02
        if (isWeak(id)) score -= 8.0
        // Previews and experiments work, but they're the first things to be withdrawn.
        if (id.contains("preview") || id.contains("-exp")) score -= 3.0
        return score
    }

    /**
     * Pulls the generation out of an id: 3.6 from "gemini-3.6-flash", 3 from
     * "nemotron-3-ultra-550b-a55b". Numbers followed by b or m are parameter counts,
     * not versions, and anything above 20 is a date or a size rather than a generation.
     */
    private fun generation(id: String): Double =
        NUMBER.findAll(id)
            .filterNot { it.value.endsWith("b") || it.value.endsWith("m") }
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .filter { it <= 20.0 }
            .maxOrNull() ?: 0.0

    private fun sizeInBillions(id: String): Double =
        SIZE.findAll(id).mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull() ?: 0.0

    private fun isTextModel(id: String): Boolean {
        val lower = id.lowercase(Locale.US)
        return NOT_CHAT.none { lower.contains(it) }
    }

    /**
     * Matched against whole segments of the id, not as substrings: "mini" is inside
     * "gemini", which would otherwise penalise every Gemini model equally and quietly
     * cancel out the penalty that Flash-Lite is supposed to carry.
     */
    private fun isWeak(id: String): Boolean = id.split(SEPARATOR).any { it in WEAK }

    private val NUMBER = Regex("""(\d{1,4}(?:\.\d)?)([bm])?""")
    private val SIZE = Regex("""(\d{1,4})b(?![a-z0-9])""")
    private val SEPARATOR = Regex("[^a-z0-9]+")

    /** Smaller variants: usable, but not the "best" model when something fuller exists. */
    private val WEAK = setOf("lite", "nano", "tiny", "mini", "small", "xs", "instant")

    /** Anything that isn't a text-in/text-out chat model. */
    private val NOT_CHAT = listOf(
        "embed", "whisper", "tts", "speech", "voice", "audio", "transcribe", "realtime",
        "rerank", "moderation", "guard", "safety", "dall-e", "imagen", "image",
        "veo", "lyria", "sora", "banana", "video", "aqa"
    )
}
