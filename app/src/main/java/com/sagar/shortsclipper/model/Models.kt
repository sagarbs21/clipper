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
    CENTER("Center crop"),
    FIT("Fit (bars)"),
    STRETCH("Stretch")
}

/** Output resolution / size preset (always 9:16). */
enum class OutputQuality(val label: String, val width: Int, val height: Int) {
    FHD("1080p", 1080, 1920),
    HD("720p (smaller)", 720, 1280)
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
