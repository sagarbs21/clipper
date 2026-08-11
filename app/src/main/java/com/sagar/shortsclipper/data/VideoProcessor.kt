package com.sagar.shortsclipper.data

import android.content.Context
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GaussianBlurWithFrameOverlaid
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.sagar.shortsclipper.model.CropMode
import java.io.File

private const val BLUR_SIGMA = 25f

/**
 * Trims a segment and reformats it to a vertical 9:16 video (YouTube Shorts) using
 * Media3 Transformer. The input can be a remote stream URL or a local file, and the
 * output resolution is configurable (e.g. 1080x1920 or 720x1280).
 *
 * Note: [export] and [pollProgress] must be called from a thread with a Looper
 * (the main thread), because Transformer requires it.
 */
@OptIn(UnstableApi::class)
class VideoProcessor(private val context: Context) {

    interface Callback {
        fun onDone(outputPath: String)
        fun onError(message: String)
    }

    private var transformer: Transformer? = null

    fun export(
        inputUri: String,
        audioUri: String?,
        startMs: Long,
        endMs: Long,
        cropMode: CropMode,
        outWidth: Int,
        outHeight: Int,
        bitrate: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        outputPath: String,
        callback: Callback
    ) {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .setEndPositionMs(endMs)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(inputUri)
            .setClippingConfiguration(clipping)
            .build()

        // Transformer fails if the output path already exists; clear any stale file.
        File(outputPath).takeIf { it.exists() }?.delete()

        // Without this the encoder falls back to Media3's heuristic, which is well
        // below what a Short deserves at these resolutions.
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(bitrate).build()
            )
            .setEnableFallback(true)
            .build()

        val t = Transformer.Builder(context)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    callback.onDone(outputPath)
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException
                ) {
                    callback.onError(exception.message ?: "Export failed")
                }
            })
            .build()

        transformer = t

        val videoEffects = when (cropMode) {
            CropMode.BLUR -> blurredFillEffects(outWidth, outHeight, sourceWidth, sourceHeight)
                ?: fitEffects(outWidth, outHeight, Presentation.LAYOUT_SCALE_TO_FIT)
            CropMode.CENTER -> fitEffects(outWidth, outHeight, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
            CropMode.STRETCH -> fitEffects(outWidth, outHeight, Presentation.LAYOUT_STRETCH_TO_FIT)
            CropMode.FIT -> fitEffects(outWidth, outHeight, Presentation.LAYOUT_SCALE_TO_FIT)
        }

        val edited = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(audioUri != null)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        if (audioUri == null) {
            t.start(edited, outputPath)
            return
        }

        // Adaptive YouTube sources arrive as two URLs. One video sequence plus one
        // audio sequence is the multi-sequence case Media3 1.3.1 does support.
        val audioItem = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(audioUri).setClippingConfiguration(clipping).build()
        ).setRemoveVideo(true).build()

        t.start(
            Composition.Builder(
                EditedMediaItemSequence(edited),
                EditedMediaItemSequence(audioItem)
            ).build(),
            outputPath
        )
    }

    private fun fitEffects(outWidth: Int, outHeight: Int, layout: Int): List<Effect> =
        listOf(Presentation.createForWidthAndHeight(outWidth, outHeight, layout))

    /**
     * Reels/Shorts look: the whole source frame stays sharp and centred, and only the
     * leftover canvas around it is filled with a blurred blow-up of the same frame.
     *
     * [GaussianBlurWithFrameOverlaid] does the compositing in one shader pass. It grows
     * the frame by `1 / scaleSharp` in each direction, stretches a blurred copy over that
     * larger frame, then draws the untouched frame back on top at its original size. So we
     * first scale the source down to the box it should occupy inside the canvas, and pick
     * the scale factors that grow that box back to exactly [outWidth] x [outHeight].
     *
     * Returns null when blurred fill can't or shouldn't be used, so the caller falls back
     * to a plain fit.
     */
    private fun blurredFillEffects(
        outWidth: Int,
        outHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ): List<Effect>? {
        // The blur shader family requires API 26.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        if (sourceWidth <= 0 || sourceHeight <= 0) return null

        // The letterbox box the untouched frame fills inside the canvas.
        var sharpWidth = outWidth
        var sharpHeight = outHeight
        if (sourceWidth.toLong() * outHeight > outWidth.toLong() * sourceHeight) {
            sharpHeight = (outWidth.toLong() * sourceHeight / sourceWidth).toInt()
        } else {
            sharpWidth = (outHeight.toLong() * sourceWidth / sourceHeight).toInt()
        }
        sharpWidth = (sharpWidth / 2) * 2
        sharpHeight = (sharpHeight / 2) * 2
        if (sharpWidth <= 0 || sharpHeight <= 0) return null

        // Source is already 9:16, so there is no background left to fill.
        if (sharpWidth >= outWidth && sharpHeight >= outHeight) return null

        return listOf<Effect>(
            // Crop rather than fit here: integer rounding above can leave the box a
            // fraction off the source ratio, and a sub-pixel crop beats a black edge.
            Presentation.createForWidthAndHeight(
                sharpWidth, sharpHeight, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            ),
            GaussianBlurWithFrameOverlaid(
                BLUR_SIGMA,
                sharpWidth.toFloat() / outWidth,
                sharpHeight.toFloat() / outHeight
            ),
            // Normalise away any rounding so the encoder gets the exact output size.
            Presentation.createForWidthAndHeight(
                outWidth, outHeight, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            )
        )
    }

    /** Returns 0..100 while running. Call on the main thread. */
    fun pollProgress(): Int {
        val holder = ProgressHolder()
        val t = transformer ?: return 0
        t.getProgress(holder)
        return holder.progress
    }

    fun cancel() {
        transformer?.cancel()
        transformer = null
    }
}
