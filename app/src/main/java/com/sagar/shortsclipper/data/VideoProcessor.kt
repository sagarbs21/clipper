package com.sagar.shortsclipper.data

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.sagar.shortsclipper.model.CropMode
import java.io.File

private const val OUT_WIDTH = 1080
private const val OUT_HEIGHT = 1920

/**
 * Trims a segment and reformats it to vertical 1080x1920 (YouTube Shorts) using
 * Media3 Transformer. The input can be a remote stream URL or a local file.
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

        val layout = when (cropMode) {
            CropMode.CENTER -> Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            CropMode.FIT -> Presentation.LAYOUT_SCALE_TO_FIT
            CropMode.STRETCH -> Presentation.LAYOUT_STRETCH_TO_FIT
        }

        val presentation = Presentation.createForWidthAndHeight(OUT_WIDTH, OUT_HEIGHT, layout)
        val effects = Effects(emptyList(), listOf<Effect>(presentation))

        val edited = EditedMediaItem.Builder(mediaItem)
            .setEffects(effects)
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
        t.start(edited, outputPath)
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
