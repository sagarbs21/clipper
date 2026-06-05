package com.sagar.shortsclipper.data

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.sagar.shortsclipper.model.CropMode
import java.io.File

private const val BLUR_SIGMA = 20f

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
        startMs: Long,
        endMs: Long,
        cropMode: CropMode,
        outWidth: Int,
        outHeight: Int,
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

        val t = Transformer.Builder(context)
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

        if (cropMode == CropMode.BLUR) {
            // Whole frame (fit) on top of a blurred, zoomed copy of itself — like Reels/Shorts.
            t.start(buildBlurredComposition(mediaItem, outWidth, outHeight), outputPath)
        } else {
            val layout = when (cropMode) {
                CropMode.CENTER -> Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                CropMode.STRETCH -> Presentation.LAYOUT_STRETCH_TO_FIT
                else -> Presentation.LAYOUT_SCALE_TO_FIT // FIT (and fallback): keep whole frame
            }
            val presentation = Presentation.createForWidthAndHeight(outWidth, outHeight, layout)
            val edited = EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(emptyList(), listOf<Effect>(presentation)))
                .build()
            t.start(edited, outputPath)
        }
    }

    /**
     * Builds a 2-layer composition: a background that fills the 9:16 frame (cropped) and is
     * blurred, with the whole, uncropped frame fit on top. The top layer's letterbox area is
     * transparent so the blurred background shows through.
     */
    private fun buildBlurredComposition(
        mediaItem: MediaItem,
        outWidth: Int,
        outHeight: Int
    ): Composition {
        val background = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(true)
            .setEffects(
                Effects(
                    emptyList(),
                    listOf(
                        Presentation.createForWidthAndHeight(
                            outWidth, outHeight, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                        ),
                        GaussianBlur(BLUR_SIGMA)
                    )
                )
            )
            .build()

        val foreground = EditedMediaItem.Builder(mediaItem)
            .setEffects(
                Effects(
                    emptyList(),
                    listOf<Effect>(
                        Presentation.createForWidthAndHeight(
                            outWidth, outHeight, Presentation.LAYOUT_SCALE_TO_FIT
                        )
                    )
                )
            )
            .build()

        val backgroundSequence = EditedMediaItemSequence(background)
        val foregroundSequence = EditedMediaItemSequence(foreground)
        return Composition.Builder(backgroundSequence, foregroundSequence).build()
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
