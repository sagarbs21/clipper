package com.sagar.shortsclipper.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.sagar.shortsclipper.model.VideoMeta

/**
 * Reads metadata from a local video chosen via the system file picker.
 * The content:// URI is passed straight to Media3 Transformer for clipping,
 * exactly like a remote stream URL.
 */
object LocalVideoRepository {

    /** Blocking metadata read. Run on a background dispatcher. */
    fun read(context: Context, uri: Uri): VideoMeta {
        val name = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "Local video"

        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            val durationMs =
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "?"
            val height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "?"

            if (durationMs <= 0L) {
                throw IllegalStateException("This file doesn't look like a readable video.")
            }

            return VideoMeta(
                title = name,
                uploader = "On this device",
                durationSec = durationMs / 1000,
                sourceUri = uri.toString(),
                resolution = "${width}x${height}",
                isLocal = true
            )
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
    }
}
