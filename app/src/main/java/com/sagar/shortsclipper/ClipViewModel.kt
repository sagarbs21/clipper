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
import com.sagar.shortsclipper.data.YouTubeUploader
import com.sagar.shortsclipper.data.YoutubeRepository
import com.sagar.shortsclipper.model.AiProvider
import com.sagar.shortsclipper.model.ClipSpec
import com.sagar.shortsclipper.model.CropMode
import com.sagar.shortsclipper.model.ExportedClip
import com.sagar.shortsclipper.model.OutputQuality
import com.sagar.shortsclipper.model.UploadStatus
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
    var cropMode by mutableStateOf(CropMode.FIT)

    // Settings
    var quality by mutableStateOf(Prefs.getQuality(app))
        private set

    private val initialProvider = Prefs.getProvider(app)
    var provider by mutableStateOf(initialProvider)
        private set
    var apiKey by mutableStateOf(Prefs.getApiKey(app, initialProvider))
        private set
    var model by mutableStateOf(Prefs.getModel(app, initialProvider))
        private set

    // AI state
    var suggesting by mutableStateOf(false)
        private set
    var contentType by mutableStateOf<String?>(null)
        private set
    var autoRunning by mutableStateOf(false)
        private set

    // YouTube account / upload state
    var ytClientId by mutableStateOf(Prefs.getYtClientId(app))
        private set
    var ytClientSecret by mutableStateOf(Prefs.getYtClientSecret(app))
        private set
    var ytConnected by mutableStateOf(Prefs.getYtRefreshToken(app).isNotBlank())
        private set
    var ytChannelTitle by mutableStateOf(Prefs.getYtChannelTitle(app))
        private set
    var ytConnecting by mutableStateOf(false)
        private set
    var ytUserCode by mutableStateOf<String?>(null)
        private set
    var ytVerificationUrl by mutableStateOf<String?>(null)
        private set
    var uploadPrivacy by mutableStateOf(Prefs.getYtPrivacy(app))
        private set

    val clips = mutableStateListOf<ClipSpec>()
    val exportedClips = mutableStateListOf<ExportedClip>()
    private var lastTranscript: String? = null

    private var nextId = 1L
    private val processor = VideoProcessor(app)

    fun updateQuality(q: OutputQuality) {
        quality = q
        Prefs.setQuality(getApplication<Application>(), q)
    }

    fun updateProvider(p: AiProvider) {
        provider = p
        apiKey = Prefs.getApiKey(getApplication<Application>(), p)
        model = Prefs.getModel(getApplication<Application>(), p)
        Prefs.setProvider(getApplication<Application>(), p)
    }

    fun updateApiKey(key: String) {
        apiKey = key
        Prefs.setApiKey(getApplication<Application>(), provider, key)
    }

    fun updateModel(m: String) {
        model = m
        Prefs.setModel(getApplication<Application>(), provider, m)
    }

    // ----- YouTube connection (OAuth device flow) -----

    fun updateYtClientId(v: String) {
        ytClientId = v
        Prefs.setYtClientId(getApplication<Application>(), v)
    }

    fun updateYtClientSecret(v: String) {
        ytClientSecret = v
        Prefs.setYtClientSecret(getApplication<Application>(), v)
    }

    fun updateUploadPrivacy(v: String) {
        uploadPrivacy = v
        Prefs.setYtPrivacy(getApplication<Application>(), v)
    }

    fun connectYouTube() {
        val ctx = getApplication<Application>()
        val clientId = Prefs.getYtClientId(ctx)
        val clientSecret = Prefs.getYtClientSecret(ctx)
        if (clientId.isBlank() || clientSecret.isBlank()) {
            status = "Enter your YouTube Client ID and Client Secret first."
            return
        }
        ytConnecting = true
        ytUserCode = null
        ytVerificationUrl = null
        status = "Starting YouTube sign-in..."
        viewModelScope.launch {
            try {
                val dc = withContext(Dispatchers.IO) {
                    YouTubeUploader.requestDeviceCode(clientId)
                }
                ytUserCode = dc.userCode
                ytVerificationUrl = dc.verificationUrl
                status = "Open ${dc.verificationUrl} and enter code ${dc.userCode}"

                var intervalMs = dc.intervalSec * 1000L
                val deadline = System.currentTimeMillis() + dc.expiresInSec * 1000L
                while (System.currentTimeMillis() < deadline) {
                    delay(intervalMs)
                    val outcome = withContext(Dispatchers.IO) {
                        YouTubeUploader.poll(clientId, clientSecret, dc.deviceCode)
                    }
                    when (outcome) {
                        is YouTubeUploader.PollOutcome.Pending -> Unit
                        is YouTubeUploader.PollOutcome.SlowDown -> intervalMs += 2000L
                        is YouTubeUploader.PollOutcome.Authorized -> {
                            val t = outcome.tokens
                            Prefs.saveYtTokens(
                                ctx, t.refreshToken, t.accessToken,
                                System.currentTimeMillis() + t.expiresInSec * 1000L
                            )
                            val title = withContext(Dispatchers.IO) {
                                YouTubeUploader.fetchChannelTitle(t.accessToken)
                            } ?: "YouTube"
                            Prefs.setYtChannelTitle(ctx, title)
                            ytChannelTitle = title
                            ytConnected = true
                            ytUserCode = null
                            status = "Connected to YouTube: $title"
                            return@launch
                        }
                        is YouTubeUploader.PollOutcome.Failed -> {
                            ytUserCode = null
                            status = "YouTube sign-in failed: ${outcome.message}"
                            return@launch
                        }
                    }
                }
                ytUserCode = null
                status = "YouTube sign-in timed out. Try again."
            } catch (e: Exception) {
                ytUserCode = null
                status = "YouTube sign-in error: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                ytConnecting = false
            }
        }
    }

    fun disconnectYouTube() {
        Prefs.clearYtTokens(getApplication<Application>())
        ytConnected = false
        ytChannelTitle = ""
        ytUserCode = null
        status = "Disconnected from YouTube."
    }

    /** Returns a valid access token, refreshing if needed. Runs on IO. */
    private suspend fun validAccessToken(): String = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val now = System.currentTimeMillis()
        val access = Prefs.getYtAccessToken(ctx)
        if (access.isNotBlank() && Prefs.getYtAccessExpiry(ctx) > now + 60_000L) {
            return@withContext access
        }
        val refresh = Prefs.getYtRefreshToken(ctx)
        if (refresh.isBlank()) throw IllegalStateException("Not connected to YouTube.")
        val t = YouTubeUploader.refresh(
            Prefs.getYtClientId(ctx), Prefs.getYtClientSecret(ctx), refresh
        )
        Prefs.saveYtTokens(ctx, null, t.accessToken, now + t.expiresInSec * 1000L)
        t.accessToken
    }

    // ----- Exported clips: metadata + upload -----

    private fun updateExportedClip(id: Long, transform: (ExportedClip) -> ExportedClip) {
        val i = exportedClips.indexOfFirst { it.id == id }
        if (i >= 0) exportedClips[i] = transform(exportedClips[i])
    }

    fun editExportedClip(
        id: Long,
        title: String? = null,
        description: String? = null,
        tags: String? = null
    ) {
        updateExportedClip(id) {
            it.copy(
                title = title ?: it.title,
                description = description ?: it.description,
                tags = tags ?: it.tags
            )
        }
    }

    fun removeExportedClip(id: Long) {
        exportedClips.removeAll { it.id == id }
    }

    fun generateMetadataFor(id: Long) {
        val key = apiKey.trim()
        if (key.isEmpty()) {
            status = "Add your ${provider.label} API key in Settings."
            return
        }
        val current = exportedClips.firstOrNull { it.id == id } ?: return
        val sourceTitle = meta?.title ?: ""
        updateExportedClip(id) { it.copy(message = "Generating metadata...") }
        viewModelScope.launch {
            try {
                val md = withContext(Dispatchers.IO) {
                    AiClipPlanner.generateMetadata(
                        provider = provider,
                        model = model.ifBlank { provider.defaultModel },
                        apiKey = key,
                        sourceTitle = sourceTitle,
                        clipHint = current.title,
                        transcript = lastTranscript
                    )
                }
                updateExportedClip(id) {
                    it.copy(
                        title = md.title,
                        description = md.description,
                        tags = md.tags.joinToString(", "),
                        message = "Metadata generated — review and upload."
                    )
                }
            } catch (e: Exception) {
                updateExportedClip(id) { it.copy(message = "Metadata failed: ${e.message ?: "error"}") }
            }
        }
    }

    fun uploadClip(id: Long) {
        val clip = exportedClips.firstOrNull { it.id == id } ?: return
        if (!ytConnected) {
            updateExportedClip(id) { it.copy(status = UploadStatus.FAILED, message = "Connect YouTube in Settings first.") }
            return
        }
        if (clip.status == UploadStatus.UPLOADING) return
        updateExportedClip(id) { it.copy(status = UploadStatus.UPLOADING, message = "Uploading...") }
        viewModelScope.launch {
            try {
                val token = validAccessToken()
                val tagList = clip.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val privacy = uploadPrivacy
                val videoId = withContext(Dispatchers.IO) {
                    YouTubeUploader.upload(
                        accessToken = token,
                        file = File(clip.filePath),
                        title = clip.title.ifBlank { "Untitled Short" },
                        description = clip.description,
                        tags = tagList,
                        privacyStatus = privacy
                    )
                }
                updateExportedClip(id) {
                    it.copy(
                        status = UploadStatus.DONE,
                        videoId = videoId,
                        message = "Uploaded ($privacy): https://youtu.be/$videoId"
                    )
                }
            } catch (e: Exception) {
                updateExportedClip(id) {
                    it.copy(status = UploadStatus.FAILED, message = "Upload failed: ${e.message ?: "error"}")
                }
            }
        }
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
            status = "Add your ${provider.label} API key in Settings to use AI suggestions."
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
                lastTranscript = transcript
                val plan = withContext(Dispatchers.IO) {
                    AiClipPlanner.plan(
                        provider = provider,
                        model = model.ifBlank { provider.defaultModel },
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

    /**
     * One-tap pipeline for a YouTube link: fetch -> AI picks segments -> export ->
     * generate upload metadata. Stops there so you can review and upload.
     * Self-contained so it never interferes with the manual buttons.
     */
    fun autoClip() {
        val u = url.trim()
        if (u.isEmpty()) {
            status = "Paste a YouTube link first."
            return
        }
        val key = apiKey.trim()
        if (key.isEmpty()) {
            status = "Add your ${provider.label} API key in Settings for Auto-clip."
            return
        }
        if (autoRunning) return

        autoRunning = true
        progress = 0
        contentType = null
        status = "Auto-clip: fetching video..."
        viewModelScope.launch {
            try {
                val m = withContext(Dispatchers.IO) { YoutubeRepository.fetch(u) }
                meta = m

                status = "Auto-clip: finding the best moments..."
                val transcript = withContext(Dispatchers.IO) {
                    m.subtitleVttUrl?.let { CaptionsRepository.fetchTranscript(it) }
                }
                lastTranscript = transcript
                val plan = withContext(Dispatchers.IO) {
                    AiClipPlanner.plan(
                        provider = provider,
                        model = model.ifBlank { provider.defaultModel },
                        meta = m,
                        transcript = transcript,
                        apiKey = key,
                        maxClips = AI_MAX_CLIPS,
                        maxClipSec = AI_MAX_CLIP_SEC
                    )
                }
                contentType = plan.contentType
                clips.clear()
                if (plan.suggestions.isEmpty()) {
                    status = "Auto-clip: AI found no segments. Try manual clips."
                    return@launch
                }
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

                val q = quality
                val snapshot = clips.toList()
                var saved = 0
                snapshot.forEachIndexed { index, clip ->
                    val startMs = parseTimeToMs(clip.start) ?: 0L
                    var endMs = parseTimeToMs(clip.end) ?: (m.durationSec * 1000)
                    val maxMs = m.durationSec * 1000
                    if (endMs > maxMs) endMs = maxMs
                    if (endMs <= startMs) return@forEachIndexed

                    status = "Auto-clip: exporting ${index + 1} of ${snapshot.size}..."
                    progress = 0
                    val safeName = clip.name.ifBlank { "clip_${index + 1}" }
                        .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                    val exportsDir = File(getApplication<Application>().filesDir, "exports")
                        .apply { mkdirs() }
                    val outFile = File(exportsDir, "$safeName-${System.currentTimeMillis()}.mp4")

                    exportOne(m.sourceUri, startMs, endMs, q.width, q.height, outFile)
                    saveCopyToGallery(outFile, safeName)

                    status = "Auto-clip: writing caption ${index + 1} of ${snapshot.size}..."
                    val md = try {
                        withContext(Dispatchers.IO) {
                            AiClipPlanner.generateMetadata(
                                provider = provider,
                                model = model.ifBlank { provider.defaultModel },
                                apiKey = key,
                                sourceTitle = m.title,
                                clipHint = clip.name,
                                transcript = transcript
                            )
                        }
                    } catch (e: Exception) {
                        null
                    }

                    exportedClips.add(
                        ExportedClip(
                            id = nextId++,
                            filePath = outFile.absolutePath,
                            title = md?.title ?: clip.name,
                            description = md?.description.orEmpty(),
                            tags = md?.tags?.joinToString(", ").orEmpty()
                        )
                    )
                    saved++
                }
                progress = 100
                status = if (saved > 0) {
                    "Auto-clip done • detected: ${plan.contentType} • $saved clip(s) ready below. Review & upload."
                } else {
                    "Auto-clip: nothing exported. Check the video."
                }
            } catch (e: Exception) {
                status = "Auto-clip failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                autoRunning = false
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
                    val exportsDir = File(getApplication<Application>().filesDir, "exports")
                        .apply { mkdirs() }
                    val outFile = File(exportsDir, "$safeName-${System.currentTimeMillis()}.mp4")

                    exportOne(m.sourceUri, startMs, endMs, q.width, q.height, outFile)
                    saveCopyToGallery(outFile, safeName)
                    exportedClips.add(
                        ExportedClip(
                            id = nextId++,
                            filePath = outFile.absolutePath,
                            title = clip.name.ifBlank { "clip_${index + 1}" }
                        )
                    )
                    saved++
                }
                progress = 100
                status = if (saved > 0) {
                    "Done. $saved clip(s) saved to your gallery. Add metadata below and upload."
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

    /** Saves a copy to the gallery but keeps [file] so it can be uploaded. */
    private suspend fun saveCopyToGallery(file: File, name: String) {
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStoreSaver.saveToGallery(getApplication<Application>(), file, name)
            } else {
                val dir = File(
                    getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                    "ShortsClipper"
                )
                dir.mkdirs()
                file.copyTo(File(dir, "$name.mp4"), overwrite = true)
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
