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
    val subtitleVttUrl: String? = null,
    /** Displayed frame size (rotation applied), or 0 when unknown. Drives blurred fill. */
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    /**
     * Separate audio track URL. YouTube only keeps a 360p muxed stream, so anything
     * sharper arrives as video-only and the audio has to be merged back in.
     */
    val audioUri: String? = null
)

/** How the source frame is mapped into the 9:16 output. */
enum class CropMode(val label: String, val hint: String) {
    FIT("Fit · no crop", "Keeps the whole frame, with plain black bars above and below."),
    BLUR(
        "Blurred fill",
        "Keeps the whole frame sharp and fills only the bars around it with a blurred " +
            "blow-up of the same frame, like Reels."
    ),
    CENTER("Crop to fill", "Fills the screen by cutting off the left and right edges."),
    STRETCH("Stretch", "Fills the screen by squeezing the frame. Distorts faces.")
}

/**
 * Output resolution / size preset (always 9:16), with the video bitrate to encode at.
 *
 * Height matters more than it looks: a 16:9 source fitted into a 1080-wide canvas is
 * squeezed down to 1080x607, so most of the original detail is thrown away before
 * encoding. Taller canvases keep more of it, at the cost of file size.
 */
enum class OutputQuality(
    val label: String,
    val width: Int,
    val height: Int,
    val bitrate: Int
) {
    HD("720p · smallest", 720, 1280, 6_000_000),
    FHD("1080p", 1080, 1920, 12_000_000),
    QHD("1440p · sharper", 1440, 2560, 24_000_000),
    UHD("4K · sharpest", 2160, 3840, 45_000_000)
}

/**
 * AI backend used for clip suggestions. All except Gemini speak the OpenAI-compatible
 * chat-completions API, so they share one code path.
 */
enum class AiProvider(
    val label: String,
    val baseUrl: String,      // empty for Gemini (uses its own REST endpoint)
    /**
     * Used only when the live model list can't be fetched. The app normally asks the
     * provider what it serves today, because pinned ids go stale within months.
     */
    val fallbackModel: String,
    val keyUrl: String
) {
    GEMINI("Gemini", "", "gemini-3-flash-preview", "aistudio.google.com/apikey"),
    GROQ("Groq (free)", "https://api.groq.com/openai/v1", "openai/gpt-oss-120b", "console.groq.com/keys"),
    // OpenRouter's own router across whatever is free right now, so it can't go stale.
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "openrouter/free", "openrouter.ai/keys"),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "platform.openai.com/api-keys")
}

/** One model a provider is currently serving. */
data class ModelOption(
    val id: String,
    val free: Boolean,
    val contextTokens: Int
)

/** A single user-defined clip. Times are free text (seconds, mm:ss, or h:mm:ss). */
data class ClipSpec(
    val id: Long,
    val start: String = "0:00",
    val end: String = "0:30",
    val name: String = ""
)

/**
 * A moment the on-device pass flagged as promising, before any AI is involved.
 * [loudness] and [motion] are 0..1 relative to the rest of the same video.
 */
data class ClipCandidate(
    val startSec: Double,
    val endSec: Double,
    val loudness: Double,
    val motion: Double
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
