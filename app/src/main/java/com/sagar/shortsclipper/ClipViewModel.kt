package com.sagar.shortsclipper

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sagar.shortsclipper.data.AiClipPlanner
import com.sagar.shortsclipper.data.CaptionsRepository
import com.sagar.shortsclipper.data.LocalVideoRepository
import com.sagar.shortsclipper.data.MediaStoreSaver
import com.sagar.shortsclipper.data.Prefs
import com.sagar.shortsclipper.data.VideoProcessor
import com.sagar.shortsclipper.data.YoutubeRepository
import com.sagar.shortsclipper.model.ClipSpec
import com.sagar.shortsclipper.model.CropMode
import com.sagar.shortsclipper.model.OutputQuality
import com.sagar.shortsclipper.model.VideoMeta
import com.sagar.shortsclipper.util.formatMs
import com.sagar.shortsclipper.util.parseTimeToMs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ClipViewModel(app: Application) : AndroidViewModel(app) {

    var url by mutableStateOf("")
    var meta by mutableStateOf<VideoMeta?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var exporting by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0)
        private set
    var status by mutableStateOf("")
        private set
    var cropMode by mutableStateOf(CropMode.CENTER)

    // Settings
    var quality by mutableStateOf(Prefs.getQuality(app))
        private set
    var apiKey by mutableStateOf(Prefs.getApiKey(app))
        private set

    // AI state
    var suggesting by mutableStateOf(false)
        private set
    var contentType by mutableStateOf<String?>(null)
        private set

    val clips = mutableStateListOf<ClipSpec>()

    private var nextId = 1L
    private val processor = VideoProcessor(app)

    fun setQuality(q: OutputQuality) {
        quality = q
        Prefs.setQuality(getApplication<Application>(), q)
    }

    fun setApiKey(key: String) {
        apiKey = key
        Prefs.setApiKey(getApplication<Application>(), key)
    }

    fun fetch() {
        val u = url.trim()
        if (u.isEmpty()) {
            status = "Paste a YouTube link first."
            return
        }
        loading = true
        status = "Fetching video info..."
        viewModelScope.launch {
            try {
                val m = withContext(Dispatchers.IO) { YoutubeRepository.fetch(u) }
                meta = m
                clips.clear()
                addClip()
                status = "Loaded: ${m.title}"
            } catch (e: Exception) {
                meta = null
                status = "Fetch failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                loading = false
            }
        }
    }

    /** Load a local video chosen from the device's file picker. */
    fun loadLocalVideo(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        // Keep read access across process restarts where the provider allows it.
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers don't grant persistable permission; temporary access still works.
        }

        loading = true
        status = "Reading video..."
        viewModelScope.launch {
            try {
                val m = withContext(Dispatchers.IO) {
                    LocalVideoRepository.read(getApplication<Application>(), uri)
                }
                meta = m
                clips.clear()
                addClip()
                status = "Loaded: ${m.title}"
            } catch (e: Exception) {
                meta = null
                status = "Couldn't read video: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                loading = false
            }
        }
    }

    /** Ask the AI to detect the content type and propose trend-style Shorts. */
    fun suggestClips() {
        val m = meta ?: run {
            status = "Load a video first."
            return
        }
        val key = apiKey.trim()
        if (key.isEmpty()) {
            status = "Add your Gemini API key in Settings to use AI suggestions."
            return
        }

        suggesting = true
        contentType = null
        status = "Analyzing with AI..."
        viewModelScope.launch {
            try {
                val transcript = withContext(Dispatchers.IO) {
                    m.subtitleVttUrl?.let { CaptionsRepository.fetchTranscript(it) }
                }
                val plan = withContext(Dispatchers.IO) {
                    AiClipPlanner.plan(
                        meta = m,
                        transcript = transcript,
                        apiKey = key,
                        maxClips = AI_MAX_CLIPS,
                        maxClipSec = AI_MAX_CLIP_SEC
                    )
                }
                contentType = plan.contentType
                if (plan.suggestions.isEmpty()) {
                    status = "AI couldn't suggest clips for this video. Add clips manually."
                } else {
                    clips.clear()
                    plan.suggestions.forEach { s ->
                        clips.add(
                            ClipSpec(
                                id = nextId++,
                                start = formatMs((s.startSec * 1000).toLong()),
                                end = formatMs((s.endSec * 1000).toLong()),
                                name = s.title.take(60)
                            )
                        )
                    }
                    val src = if (transcript != null) "captions" else "title only"
                    status = "AI suggested ${clips.size} clip(s) • detected: ${plan.contentType} • based on $src. Review, then export."
                }
            } catch (e: Exception) {
                status = "AI suggestion failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                suggesting = false
            }
        }
    }

    fun addClip() {
        clips.add(
            ClipSpec(id = nextId++, start = "0:00", end = "0:30", name = "clip_${clips.size + 1}")
        )
    }

    fun removeClip(id: Long) {
        clips.removeAll { it.id == id }
    }

    fun updateClip(id: Long, start: String? = null, end: String? = null, name: String? = null) {
        val i = clips.indexOfFirst { it.id == id }
        if (i >= 0) {
            val c = clips[i]
            clips[i] = c.copy(
                start = start ?: c.start,
                end = end ?: c.end,
                name = name ?: c.name
            )
        }
    }

    fun export() {
        val m = meta ?: run {
            status = "Fetch a video first."
            return
        }
        if (clips.isEmpty()) {
            status = "Add at least one clip."
            return
        }

        // Rough free-space pre-check (temp file + final copy).
        val totalClipSec = clips.sumOf { c ->
            val s = parseTimeToMs(c.start) ?: 0L
            val e = parseTimeToMs(c.end) ?: 0L
            ((e - s).coerceAtLeast(0L)) / 1000
        }
        val estimatedBytes = totalClipSec * BYTES_PER_SEC_ESTIMATE * 2
        val usable = getApplication<Application>().cacheDir.usableSpace
        if (usable in 1 until estimatedBytes) {
            status = "Low storage: ~${estimatedBytes / (1024 * 1024)} MB may be needed. Free some space and retry."
            return
        }

        exporting = true
        progress = 0
        val q = quality
        viewModelScope.launch {
            var saved = 0
            try {
                val snapshot = clips.toList()
                snapshot.forEachIndexed { index, clip ->
                    val startMs = parseTimeToMs(clip.start) ?: 0L
                    var endMs = parseTimeToMs(clip.end) ?: (m.durationSec * 1000)
                    val maxMs = m.durationSec * 1000
                    if (endMs > maxMs) endMs = maxMs
                    if (endMs <= startMs) {
                        status = "Clip ${index + 1}: end must be after start. Skipped."
                        return@forEachIndexed
                    }

                    status = "Exporting clip ${index + 1} of ${snapshot.size}..."
                    progress = 0

                    val safeName = (clip.name.ifBlank { "clip_${index + 1}" })
                        .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                    val tempFile = File(
                        getApplication<Application>().cacheDir,
                        "$safeName-${System.currentTimeMillis()}.mp4"
                    )

                    exportOne(m.sourceUri, startMs, endMs, q.width, q.height, tempFile)
                    saveOutput(tempFile, safeName)
                    saved++
                }
                progress = 100
                status = if (saved > 0) {
                    "Done. $saved clip(s) saved to Movies/ShortsClipper — check your gallery."
                } else {
                    "Nothing exported. Check your clip times."
                }
            } catch (e: Exception) {
                status = "Export error: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                exporting = false
            }
        }
    }

    private suspend fun exportOne(
        inputUri: String,
        startMs: Long,
        endMs: Long,
        outWidth: Int,
        outHeight: Int,
        outFile: File
    ) {
        val done = CompletableDeferred<Unit>()

        // Transformer must be started on the main thread (it needs a Looper).
        withContext(Dispatchers.Main) {
            processor.export(
                inputUri = inputUri,
                startMs = startMs,
                endMs = endMs,
                cropMode = cropMode,
                outWidth = outWidth,
                outHeight = outHeight,
                outputPath = outFile.absolutePath,
                callback = object : VideoProcessor.Callback {
                    override fun onDone(outputPath: String) {
                        done.complete(Unit)
                    }

                    override fun onError(message: String) {
                        done.completeExceptionally(RuntimeException(message))
                    }
                }
            )
        }

        // Poll progress on the main thread until the export finishes.
        val poller = viewModelScope.launch(Dispatchers.Main) {
            while (!done.isCompleted) {
                progress = processor.pollProgress()
                delay(300)
            }
        }

        try {
            done.await()
        } finally {
            poller.cancel()
        }
    }

    private suspend fun saveOutput(file: File, name: String) {
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStoreSaver.saveToGallery(getApplication<Application>(), file, name)
                file.delete()
            } else {
                val dir = File(
                    getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                    "ShortsClipper"
                )
                dir.mkdirs()
                file.copyTo(File(dir, "$name.mp4"), overwrite = true)
                file.delete()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        processor.cancel()
    }

    companion object {
        private const val AI_MAX_CLIPS = 5
        private const val AI_MAX_CLIP_SEC = 60
        // Upper-bound estimate (~1.3 MB/s at 1080p) used only for the storage pre-check.
        private const val BYTES_PER_SEC_ESTIMATE = 1_300_000L
    }
}
