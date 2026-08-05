package com.sagar.shortsclipper.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MetadataRetriever
import java.util.concurrent.TimeUnit

/**
 * Reads the pixel size of a video source. The blurred-fill layout needs the real
 * frame shape to work out how much of the 9:16 canvas the sharp frame should cover.
 */
@OptIn(UnstableApi::class)
object VideoDimensions {

    private const val REMOTE_TIMEOUT_SEC = 25L

    /**
     * Size as it will be displayed, i.e. with the rotation metadata already applied,
     * because decoders hand rotated frames to the effects pipeline upright.
     */
    fun read(mmr: MediaMetadataRetriever): Pair<Int, Int>? {
        val width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        if (width == null || height == null || width <= 0 || height <= 0) return null
        val rotation =
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        return upright(width, height, rotation)
    }

    /**
     * Blocking probe of a content://, file:// or http(s) source. Run on a background
     * dispatcher. Returns null when the source can't be read, so callers can fall back.
     *
     * ExoPlayer's retriever goes first because it reads remote streams through the same
     * stack that the export itself uses, which the platform retriever often can't.
     */
    fun probe(context: Context, uri: String): Pair<Int, Int>? =
        viaExoPlayer(context, uri) ?: viaPlatform(context, uri)

    private fun viaExoPlayer(context: Context, uri: String): Pair<Int, Int>? {
        return try {
            val item = MediaItem.fromUri(uri)
            val groups = MetadataRetriever.retrieveMetadata(context, item)
                .get(REMOTE_TIMEOUT_SEC, TimeUnit.SECONDS)
            for (i in 0 until groups.length) {
                val format = groups.get(i).getFormat(0)
                if (format.width > 0 && format.height > 0) {
                    return upright(format.width, format.height, format.rotationDegrees)
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun viaPlatform(context: Context, uri: String): Pair<Int, Int>? {
        val mmr = MediaMetadataRetriever()
        return try {
            if (uri.startsWith("http")) {
                mmr.setDataSource(uri, HashMap())
            } else {
                mmr.setDataSource(context, Uri.parse(uri))
            }
            read(mmr)
        } catch (e: Exception) {
            null
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    private fun upright(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
        if (rotationDegrees == 90 || rotationDegrees == 270) height to width else width to height
}
