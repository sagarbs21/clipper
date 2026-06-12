package com.sagar.shortsclipper.model

/**
 * Metadata + a playable source URI for a video. The source can be either a remote
 * YouTube stream URL or a local content:// URI for a file on the device.
 */
data class VideoMeta(
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val sourceUri: String,
    val resolution: String,
    val isLocal: Boolean = false,
    /** Best-effort WebVTT caption URL (YouTube only), used by the AI planner. */
    val subtitleVttUrl: String? = null
)

/** How the source frame is mapped into the 9:16 output. */
enum class CropMode(val label: String) {
    FIT("Fit · no crop"),
    BLUR("Blurred fill"),
    CENTER("Crop to fill"),
    STRETCH("Stretch")
}

/** Output resolution / size preset (always 9:16). */
enum class OutputQuality(val label: String, val width: Int, val height: Int) {
    FHD("1080p", 1080, 1920),
    HD("720p (smaller)", 720, 1280)
}

/**
 * AI backend used for clip suggestions. All except Gemini speak the OpenAI-compatible
 * chat-completions API, so they share one code path.
 */
enum class AiProvider(
    val label: String,
    val baseUrl: String,      // empty for Gemini (uses its own REST endpoint)
    val defaultModel: String,
    val keyUrl: String
) {
    GEMINI("Gemini", "", "gemini-2.5-flash", "aistudio.google.com/apikey"),
    GROQ("Groq (free)", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "console.groq.com/keys"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct:free", "openrouter.ai/keys"),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "platform.openai.com/api-keys")
}

/** A single user-defined clip. Times are free text (seconds, mm:ss, or h:mm:ss). */
data class ClipSpec(
    val id: Long,
    val start: String = "0:00",
    val end: String = "0:30",
    val name: String = ""
)

/** One AI-proposed clip. */
data class AiSuggestion(
    val startSec: Double,
    val endSec: Double,
    val title: String,
    val hashtags: List<String>,
    val reason: String
)

/** Result of an AI analysis: detected content type + proposed clips. */
data class AiPlan(
    val contentType: String,
    val suggestions: List<AiSuggestion>
)

/** AI-generated upload metadata for a clip. */
data class VideoMetadata(
    val title: String,
    val description: String,
    val tags: List<String>
)

enum class UploadStatus { IDLE, UPLOADING, DONE, FAILED }

/** A finished clip file that can be given metadata and uploaded to YouTube. */
data class ExportedClip(
    val id: Long,
    val filePath: String,
    val title: String = "",
    val description: String = "",
    val tags: String = "",            // comma-separated for easy editing
    val status: UploadStatus = UploadStatus.IDLE,
    val message: String = "",
    val videoId: String = ""
)
