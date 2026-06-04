package com.sagar.shortsclipper.data

import com.sagar.shortsclipper.model.VideoMeta
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Resolves a YouTube link into playable metadata using NewPipeExtractor.
 * No Google API key is needed.
 *
 * We pick the highest-resolution *muxed* (progressive) stream so a single URL
 * carries both audio and video, which keeps the Media3 pipeline simple.
 */
object YoutubeRepository {

    @Volatile
    private var initialized = false

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                NewPipe.init(DownloaderImpl.getInstance())
                initialized = true
            }
        }
    }

    /** Blocking network call. Run on a background dispatcher. */
    fun fetch(url: String): VideoMeta {
        ensureInit()
        val service = ServiceList.YouTube
        val info = StreamInfo.getInfo(service, url)

        val best = info.videoStreams
            .filter { it.isUrl && !it.content.isNullOrEmpty() }
            .maxByOrNull { resolutionValue(it.getResolution()) }
            ?: throw IllegalStateException(
                "No playable muxed stream was found for this video."
            )

        return VideoMeta(
            title = info.name ?: "Untitled",
            uploader = info.uploaderName ?: "Unknown",
            durationSec = info.duration,
            sourceUri = best.content,
            resolution = best.getResolution() ?: "?",
            isLocal = false,
            subtitleVttUrl = pickVttSubtitleUrl(info)
        )
    }

    /** Best-effort: prefer an English WebVTT caption track, else any WebVTT track. */
    private fun pickVttSubtitleUrl(info: StreamInfo): String? {
        val subs = try {
            info.subtitles
        } catch (e: Exception) {
            return null
        }
        val chosen = subs.firstOrNull {
            it.format == MediaFormat.VTT && (it.languageTag?.startsWith("en") == true)
        } ?: subs.firstOrNull { it.format == MediaFormat.VTT }
        return chosen?.content?.takeIf { chosen.isUrl && it.isNotEmpty() }
    }

    private fun resolutionValue(res: String?): Int {
        if (res.isNullOrEmpty()) return 0
        return Regex("(\\d+)").find(res)?.value?.toIntOrNull() ?: 0
    }
}
