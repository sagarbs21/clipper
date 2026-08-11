package com.sagar.shortsclipper.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.sagar.shortsclipper.model.ClipCandidate
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min

/**
 * Finds candidate moments by reading the compressed stream, without decoding it.
 *
 * How many bytes each second of the container holds is a surprisingly good stand-in for
 * what is happening on screen: video bitrate climbs with motion and scene changes, audio
 * bitrate climbs through loud, dense passages. That is enough to tell a static talking
 * head from a goal celebration, and it costs one pass over the container instead of a
 * full decode. Sync samples give us the real cut points to snap clip starts onto.
 *
 * Everything here is best-effort. A null result just means the caller falls back to
 * reasoning from the title and transcript alone.
 */
object ContentAnalyzer {

    /** Per-second signal strength, each normalised to 0..1 within this one video. */
    class Analysis(
        val motion: DoubleArray,
        val loudness: DoubleArray,
        /** Timestamps of sync samples, i.e. the points a clip can cleanly start on. */
        val cuts: List<Double>
    ) {
        /** False when both tracks were flat, which tells us nothing worth acting on. */
        val hasSignal: Boolean
            get() = motion.any { it > 0.0 } || loudness.any { it > 0.0 }
    }

    /** Blocking. Run on a background dispatcher. Returns null if the source can't be read. */
    fun analyze(context: Context, uri: String, durationSec: Long): Analysis? {
        if (durationSec <= 0) return null
        val extractor = MediaExtractor()
        return try {
            if (uri.startsWith("http")) {
                extractor.setDataSource(uri)
            } else {
                extractor.setDataSource(context, Uri.parse(uri), null)
            }
            scan(extractor, durationSec.toInt())
        } catch (e: Exception) {
            null
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    private fun scan(extractor: MediaExtractor, durationSec: Int): Analysis? {
        var videoTrack = -1
        var audioTrack = -1
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (videoTrack < 0 && mime.startsWith("video/")) videoTrack = i
            if (audioTrack < 0 && mime.startsWith("audio/")) audioTrack = i
        }
        if (videoTrack < 0 && audioTrack < 0) return null
        if (videoTrack >= 0) extractor.selectTrack(videoTrack)
        if (audioTrack >= 0) extractor.selectTrack(audioTrack)

        val bins = durationSec + 1
        val video = DoubleArray(bins)
        val audio = DoubleArray(bins)
        val cuts = ArrayList<Double>()

        // Only needed below API 28, where the sample size isn't exposed on its own.
        val buffer: ByteBuffer? =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                ByteBuffer.allocateDirect(4 * 1024 * 1024)
            } else {
                null
            }

        while (true) {
            val timeUs = extractor.sampleTime
            if (timeUs < 0) break
            val size = sampleSize(extractor, buffer)
            if (size < 0) break

            val second = (timeUs / 1_000_000L).toInt()
            if (second in 0 until bins) {
                when (extractor.sampleTrackIndex) {
                    videoTrack -> {
                        video[second] += size.toDouble()
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                            cuts.add(timeUs / 1_000_000.0)
                        }
                    }
                    audioTrack -> audio[second] += size.toDouble()
                }
            }
            if (!extractor.advance()) break
        }

        return Analysis(normalise(video), normalise(audio), cuts)
    }

    private fun sampleSize(extractor: MediaExtractor, buffer: ByteBuffer?): Long = try {
        if (buffer == null) {
            extractor.sampleSize
        } else {
            buffer.clear()
            extractor.readSampleData(buffer, 0).toLong()
        }
    } catch (e: Exception) {
        -1L
    }

    /**
     * Min-max rather than divide-by-peak: a constant-bitrate track carries no
     * information, and this correctly flattens it to zero instead of to a fake 1.0.
     */
    private fun normalise(values: DoubleArray): DoubleArray {
        if (values.isEmpty()) return values
        val max = values.maxOrNull() ?: return values
        val min = values.minOrNull() ?: return values
        val span = max - min
        if (span <= 0.0) return DoubleArray(values.size)
        return DoubleArray(values.size) { (values[it] - min) / span }
    }

    /**
     * Picks the [max] busiest non-overlapping windows of roughly [clipSec] seconds.
     * These are proposals for the model to rank and name, not final cuts.
     */
    fun candidates(
        analysis: Analysis,
        durationSec: Long,
        clipSec: Int,
        max: Int
    ): List<ClipCandidate> {
        val window = clipSec.coerceIn(MIN_CLIP_SEC, 60)
        val total = min(analysis.motion.size, analysis.loudness.size)
        if (total <= window) return emptyList()

        val scored = ArrayList<Triple<Int, Double, Pair<Double, Double>>>()
        for (start in 0..(total - window)) {
            var loud = 0.0
            var motion = 0.0
            for (i in start until start + window) {
                loud += analysis.loudness[i]
                motion += analysis.motion[i]
            }
            loud /= window
            motion /= window
            // Audio leads: a spike in loudness marks a reaction, a punchline or a goal
            // far more reliably than a busy picture does.
            scored.add(Triple(start, loud * 0.6 + motion * 0.4, loud to motion))
        }
        scored.sortByDescending { it.second }

        val picked = ArrayList<ClipCandidate>()
        for ((start, _, signals) in scored) {
            if (picked.size >= max) break
            val rawStart = start.toDouble()
            val rawEnd = rawStart + window
            // Skip anything overlapping a pick we already made, so we don't hand back
            // several views of the same moment.
            if (picked.any { rawStart < it.endSec && rawEnd > it.startSec }) continue

            val snapped = snapToCut(analysis.cuts, rawStart)
            val end = min(snapped + window, durationSec.toDouble())
            if (end - snapped < MIN_CLIP_SEC) continue
            picked.add(ClipCandidate(snapped, end, signals.first, signals.second))
        }
        return picked.sortedBy { it.startSec }
    }

    /** Nudge onto a nearby cut so the clip opens on a shot change, not mid-shot. */
    private fun snapToCut(cuts: List<Double>, startSec: Double): Double {
        val nearest = cuts.minByOrNull { abs(it - startSec) } ?: return startSec
        return if (abs(nearest - startSec) <= SNAP_WINDOW_SEC) nearest else startSec
    }

    private const val MIN_CLIP_SEC = 8
    private const val SNAP_WINDOW_SEC = 2.0
}
