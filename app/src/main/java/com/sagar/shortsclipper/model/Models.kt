package com.sagar.shortsclipper.model

/** Metadata + a directly playable (muxed audio+video) stream URL for a YouTube video. */
data class VideoMeta(
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val streamUrl: String,
    val resolution: String
)

/** How the source frame is mapped into the 9:16 (1080x1920) output. */
enum class CropMode(val label: String) {
    CENTER("Center crop"),
    FIT("Fit (bars)"),
    STRETCH("Stretch")
}

/** A single user-defined clip. Times are free text (seconds, mm:ss, or h:mm:ss). */
data class ClipSpec(
    val id: Long,
    val start: String = "0:00",
    val end: String = "0:30",
    val name: String = ""
)
