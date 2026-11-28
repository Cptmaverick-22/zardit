package io.github.cptmaverick_22.zardit.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.cptmaverick_22.zardit.MainActivity
import io.github.cptmaverick_22.zardit.R
import io.github.cptmaverick_22.zardit.data.DiagnosticLog
import io.github.cptmaverick_22.zardit.data.HistoryItem
import io.github.cptmaverick_22.zardit.data.HistoryStore
import io.github.cptmaverick_22.zardit.data.ZarditPrefs
import io.github.cptmaverick_22.zardit.downloader.AudioOption
import io.github.cptmaverick_22.zardit.downloader.DownloadMode
import io.github.cptmaverick_22.zardit.downloader.DownloadState
import io.github.cptmaverick_22.zardit.downloader.ImageFormat
import io.github.cptmaverick_22.zardit.downloader.ZarditEngine
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private var activeProcessId: String? = null
    private val cancelled = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var lastTitle: String = "Zardit Download"
    private var lastPct: Float = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                paused.set(true)
                activeProcessId?.let { ZarditEngine.cancel(it) }
                activeJob?.cancel()
                val pct = lastPct
                _state.value = DownloadState.Paused(
                    title = lastTitle,
                    progressPercent = pct,
                    statusLine = if (pct > 0f) "Paused · ${pct.toInt()}%" else "Paused",
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RESUME -> {
                val resumeIntent = lastStartIntent
                if (resumeIntent != null) {
                    return onStartCommand(resumeIntent, flags, startId)
                }
                _state.value = DownloadState.Cancelled
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_CANCEL -> {
                cancelled.set(true)
                paused.set(false)
                activeProcessId?.let { ZarditEngine.cancel(it) }
                activeJob?.cancel()
                pausedWorkDir?.deleteRecursively()
                pausedWorkDir = null
                lastStartIntent = null
                _state.value = DownloadState.Cancelled
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val isResume = intent.getBooleanExtra(EXTRA_IS_RESUME, false)
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val mediaId = intent.getStringExtra(EXTRA_MEDIA_ID) ?: ""
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Zardit Download"
                val platform = intent.getStringExtra(EXTRA_PLATFORM) ?: ""
                val modeName = intent.getStringExtra(EXTRA_MODE) ?: DownloadMode.VIDEO.name
                val mode = DownloadMode.valueOf(modeName)
                val videoHeight = intent.getIntExtra(EXTRA_VIDEO_HEIGHT, 1080)
                val audioId = intent.getStringExtra(EXTRA_AUDIO_ID)
                val audioOption = ZarditEngine.findAudioOption(audioId)
                val imageFormatName = intent.getStringExtra(EXTRA_IMAGE_FORMAT) ?: ImageFormat.ORIGINAL.name
                val imageFormat = runCatching { ImageFormat.valueOf(imageFormatName) }.getOrDefault(ImageFormat.ORIGINAL)
                val imageUrls = intent.getStringArrayListExtra(EXTRA_IMAGE_URLS).orEmpty()
                val batchUrls = intent.getStringArrayListExtra(EXTRA_BATCH_URLS).orEmpty()
                val batchTitles = intent.getStringArrayListExtra(EXTRA_BATCH_TITLES).orEmpty()
                val batchIds = intent.getStringArrayListExtra(EXTRA_BATCH_IDS).orEmpty()
                val batchThumbs = intent.getStringArrayListExtra(EXTRA_BATCH_THUMBS).orEmpty()
                val thumbnailUrl = intent.getStringExtra(EXTRA_THUMBNAIL_URL)
                val directVideoUrl = intent.getStringExtra(EXTRA_DIRECT_VIDEO_URL)
                val directAudioUrl = intent.getStringExtra(EXTRA_DIRECT_AUDIO_URL)
                val useInstagramCookies = intent.getBooleanExtra(EXTRA_USE_IG_COOKIES, false)
                val customTitle = intent.getStringExtra(EXTRA_CUSTOM_TITLE)
                val formatId = intent.getStringExtra(EXTRA_FORMAT_ID)
                val needsAudioMux = intent.getBooleanExtra(EXTRA_NEEDS_AUDIO_MUX, false)

                if (!isResume) {
                    pausedWorkDir?.deleteRecursively()
                    pausedWorkDir = File(applicationContext.cacheDir, "zardit_dl_work_${mediaId.hashCode()}")
                }
                lastStartIntent = Intent(intent).apply { putExtra(EXTRA_IS_RESUME, true) }

                startDownload(
                    url = url,
                    mediaId = mediaId,
                    title = title,
                    platform = platform,
                    mode = mode,
                    videoHeight = videoHeight,
                    audioOption = audioOption,
                    imageFormat = imageFormat,
                    imageUrls = imageUrls,
                    batchUrls = batchUrls,
                    batchTitles = batchTitles,
                    batchIds = batchIds,
                    batchThumbs = batchThumbs,
                    thumbnailUrl = thumbnailUrl,
                    directVideoUrl = directVideoUrl,
                    directAudioUrl = directAudioUrl,
                    useInstagramCookies = useInstagramCookies,
                    customTitle = customTitle,
                    formatId = formatId,
                    needsAudioMux = needsAudioMux,
                    resumeWorkDir = if (isResume) pausedWorkDir else null,
                )
            }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(
        url: String,
        mediaId: String,
        title: String,
        platform: String,
        mode: DownloadMode,
        videoHeight: Int,
        audioOption: AudioOption?,
        imageFormat: ImageFormat,
        imageUrls: List<String>,
        batchUrls: List<String>,
        batchTitles: List<String>,
        batchIds: List<String>,
        batchThumbs: List<String>,
        thumbnailUrl: String?,
        directVideoUrl: String?,
        directAudioUrl: String?,
        useInstagramCookies: Boolean,
        customTitle: String?,
        formatId: String?,
        needsAudioMux: Boolean,
        resumeWorkDir: File? = null,
    ) {
        ensureNotificationChannel()
        cancelled.set(false)
        paused.set(false)
        lastTitle = title
        if (resumeWorkDir == null) lastPct = 0f

        val initialNotification = buildNotification(
            title = title,
            text = "Awakening Charizard…",
            progress = 0,
            indeterminate = true,
            ongoing = true,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        _state.value = DownloadState.Running(
            title = title,
            progressPercent = 0f,
            statusLine = "Awakening Charizard…",
        )

        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                val prefs = ZarditPrefs(applicationContext)
                val treeUri = prefs.getTreeUri()
                val historyStore = HistoryStore(applicationContext)

                if (mode == DownloadMode.IMAGE && imageUrls.isNotEmpty()) {
                    var lastLocation = ""
                    var lastContentUri: String? = null
                    var lastFileName = ""
                    imageUrls.forEachIndexed { idx, imgUrl ->
                        if (cancelled.get()) return@launch
                        val prefix = if (imageUrls.size > 1) "[${idx + 1}/${imageUrls.size}] " else ""
                        val itemId = if (imageUrls.size > 1) "${mediaId}_${idx + 1}" else mediaId
                        val historyId = "${itemId}_${imageFormat.id}_${imageFormat.ext}"
                        val saved = ZarditEngine.downloadImage(
                            context = applicationContext,
                            imageUrl = imgUrl,
                            title = title,
                            mediaId = itemId,
                            format = imageFormat,
                            treeUri = treeUri,
                        ) { pct, status ->
                            updateProgress(title, pct, "$prefix$status")
                        }
                        val thumbPath = ZarditEngine.cacheHistoryThumbnail(applicationContext, historyId, imgUrl)
                        lastLocation = saved.savedLocation
                        lastContentUri = saved.contentUri
                        lastFileName = saved.fileName
                        historyStore.add(
                            HistoryItem(
                                id = historyId,
                                mediaId = itemId,
                                title = if (imageUrls.size > 1) "$title (${idx + 1})" else title,
                                platform = platform,
                                qualityLabel = imageFormat.label,
                                fileName = saved.fileName,
                                savedLocation = saved.savedLocation,
                                contentUri = saved.contentUri,
                                timestampMs = System.currentTimeMillis(),
                                thumbnailPath = thumbPath,
                                fileSizeBytes = saved.fileSizeBytes,
                            ),
                        )
                    }
                    if (!cancelled.get()) {
                        finishCompleted(
                            title = title,
                            savedLocation = lastLocation,
                            alreadyExisted = false,
                            contentUri = if (imageUrls.size == 1) lastContentUri else null,
                            fileName = if (imageUrls.size == 1) lastFileName else "",
                        )
                    }
                    return@launch
                }

                if (batchUrls.isNotEmpty()) {
                    var lastLocation = ""
                    var downloadedCount = 0
                    val qTag = ZarditEngine.qualityTag(mode, videoHeight, audioOption, imageFormat)
                    val ext = ZarditEngine.targetExtension(mode, audioOption, imageFormat)

                    batchUrls.forEachIndexed { idx, itemUrl ->
                        if (cancelled.get()) return@launch
                        val itemTitle = batchTitles.getOrElse(idx) { "Item ${idx + 1}" }
                        val itemId = batchIds.getOrElse(idx) { "${mediaId}_${idx + 1}" }
                        val itemThumb = batchThumbs.getOrNull(idx) ?: thumbnailUrl
                        val prefix = "[${idx + 1}/${batchUrls.size}] "

                        val existing = ZarditEngine.findExistingDownload(
                            context = applicationContext,
                            mediaId = itemId,
                            qualityTag = qTag,
                            extension = ext,
                            treeUri = treeUri,
                        )
                        if (existing != null) {
                            lastLocation = existing
                            updateProgress(title, ((idx + 1) * 100f / batchUrls.size), "${prefix}Already saved, skipping…")
                            return@forEachIndexed
                        }

                        val saved = if (mode == DownloadMode.IMAGE) {
                            ZarditEngine.downloadImage(
                                context = applicationContext,
                                imageUrl = itemUrl,
                                title = itemTitle,
                                mediaId = itemId,
                                format = imageFormat,
                                treeUri = treeUri,
                            ) { pct, status ->
                                updateProgress(title, pct, "$prefix$status")
                            }
                        } else {
                            val pid = UUID.randomUUID().toString()
                            activeProcessId = pid
                            ZarditEngine.download(
                                context = applicationContext,
                                url = itemUrl,
                                mode = mode,
                                videoHeight = videoHeight,
                                audioOption = audioOption,
                                treeUri = treeUri,
                                processId = pid,
                                customTitle = itemTitle,
                                coverUrl = itemThumb?.takeIf { it.isNotBlank() } ?: thumbnailUrl,
                                artistName = platform.substringBefore(" ·").takeIf { it.isNotBlank() },
                            ) { pct, status ->
                                updateProgress(title, pct, "$prefix$status")
                            }
                        }
                        downloadedCount++
                        lastLocation = saved.savedLocation
                        val historyId = "${itemId}_${qTag}_$ext"
                        val thumbPath = ZarditEngine.cacheHistoryThumbnail(
                            applicationContext,
                            historyId,
                            if (mode == DownloadMode.IMAGE) itemUrl else itemThumb,
                        )
                        historyStore.add(
                            HistoryItem(
                                id = historyId,
                                mediaId = itemId,
                                title = itemTitle,
                                platform = platform,
                                qualityLabel = if (mode == DownloadMode.IMAGE) imageFormat.label else qTag,
                                fileName = saved.fileName,
                                savedLocation = saved.savedLocation,
                                contentUri = saved.contentUri,
                                timestampMs = System.currentTimeMillis(),
                                thumbnailPath = thumbPath,
                                fileSizeBytes = saved.fileSizeBytes,
                            ),
                        )
                    }

                    if (!cancelled.get()) {
                        val summary = if (downloadedCount > 0) {
                            "$downloadedCount item${if (downloadedCount == 1) "" else "s"} saved (latest: $lastLocation)"
                        } else {
                            lastLocation
                        }
                        finishCompleted(title, summary, alreadyExisted = downloadedCount == 0)
                    }
                    return@launch
                }

                // Single Video or Audio item
                val qTag = ZarditEngine.qualityTag(mode, videoHeight, audioOption)
                val ext = ZarditEngine.targetExtension(mode, audioOption)
                val existing = ZarditEngine.findExistingDownload(
                    context = applicationContext,
                    mediaId = mediaId,
                    qualityTag = qTag,
                    extension = ext,
                    treeUri = treeUri,
                )
                if (existing != null) {
                    _state.value = DownloadState.Completed(
                        title = title,
                        savedLocation = existing,
                        alreadyExisted = true,
                    )
                    return@launch
                }

                val pid = UUID.randomUUID().toString()
                activeProcessId = pid
                val activeWorkDir = resumeWorkDir ?: pausedWorkDir
                val saved = ZarditEngine.download(
                    context = applicationContext,
                    url = url,
                    mode = mode,
                    videoHeight = videoHeight,
                    audioOption = audioOption,
                    treeUri = treeUri,
                    processId = pid,
                    directVideoUrl = directVideoUrl,
                    directAudioUrl = directAudioUrl,
                    useInstagramCookies = useInstagramCookies,
                    customTitle = customTitle,
                    formatId = formatId,
                    needsAudioMux = needsAudioMux,
                    resumeDir = activeWorkDir,
                    shouldKeepWorkDirOnExit = { paused.get() },
                    coverUrl = thumbnailUrl,
                    artistName = platform.substringBefore(" ·").takeIf { it.isNotBlank() },
                ) { pct, statusLine ->
                    updateProgress(title, pct, statusLine)
                }

                if (!cancelled.get() && !paused.get()) {
                    pausedWorkDir?.deleteRecursively()
                    pausedWorkDir = null
                    lastStartIntent = null
                    val historyId = "${mediaId}_${qTag}_$ext"
                    val thumbPath = ZarditEngine.cacheHistoryThumbnail(applicationContext, historyId, thumbnailUrl)
                    historyStore.add(
                        HistoryItem(
                            id = historyId,
                            mediaId = mediaId,
                            title = title,
                            platform = platform,
                            qualityLabel = qTag,
                            fileName = saved.fileName,
                            savedLocation = saved.savedLocation,
                            contentUri = saved.contentUri,
                            timestampMs = System.currentTimeMillis(),
                            thumbnailPath = thumbPath,
                            fileSizeBytes = saved.fileSizeBytes,
                        ),
                    )
                    finishCompleted(
                        title = title,
                        savedLocation = saved.savedLocation,
                        alreadyExisted = false,
                        contentUri = saved.contentUri,
                        fileName = saved.fileName,
                    )
                }
            } catch (t: Throwable) {
                if (!cancelled.get() && !paused.get()) {
                    pausedWorkDir?.deleteRecursively()
                    pausedWorkDir = null
                    DiagnosticLog.log("DownloadErr", t.message ?: t::class.java.simpleName)
                    val msg = t.message?.take(200) ?: "Download failed."
                    _state.value = DownloadState.Failed(msg)
                    val mgr = getSystemService(NotificationManager::class.java)
                    mgr.notify(
                        NOTIFICATION_ID + 1,
                        buildNotification(
                            title = "Download failed",
                            text = msg,
                            progress = 0,
                            indeterminate = false,
                            ongoing = false,
                        ),
                    )
                }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun updateProgress(title: String, pct: Float, statusLine: String) {
        if (cancelled.get() || paused.get()) return
        lastPct = pct
        _state.value = DownloadState.Running(
            title = title,
            progressPercent = pct,
            statusLine = statusLine,
        )
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(
            NOTIFICATION_ID,
            buildNotification(
                title = title,
                text = statusLine,
                progress = pct.toInt(),
                indeterminate = pct <= 0f,
                ongoing = true,
            ),
        )
    }

    private fun finishCompleted(
        title: String,
        savedLocation: String,
        alreadyExisted: Boolean,
        contentUri: String? = null,
        fileName: String = "",
    ) {
        _state.value = DownloadState.Completed(
            title = title,
            savedLocation = savedLocation,
            alreadyExisted = alreadyExisted,
            contentUri = contentUri,
            fileName = fileName,
        )
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(
            NOTIFICATION_ID + 1,
            buildNotification(
                title = "Saved: $title",
                text = savedLocation,
                progress = 100,
                indeterminate = false,
                ongoing = false,
            ),
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Zardit media download progress"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean,
        ongoing: Boolean,
    ) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(title)
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(ongoing)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .apply {
            if (ongoing) {
                setProgress(100, progress, indeterminate)
                val cancelPending = PendingIntent.getService(
                    this@DownloadService,
                    1,
                    Intent(this@DownloadService, DownloadService::class.java).apply {
                        action = ACTION_CANCEL
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                addAction(0, "Cancel", cancelPending)
            }
        }
        .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "zardit_downloads"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_START = "io.github.cptmaverick_22.zardit.action.START"
        private const val ACTION_PAUSE = "io.github.cptmaverick_22.zardit.action.PAUSE"
        private const val ACTION_RESUME = "io.github.cptmaverick_22.zardit.action.RESUME"
        private const val ACTION_CANCEL = "io.github.cptmaverick_22.zardit.action.CANCEL"
        private const val EXTRA_IS_RESUME = "extra_is_resume"
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_MEDIA_ID = "extra_media_id"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_PLATFORM = "extra_platform"
        private const val EXTRA_MODE = "extra_mode"
        private const val EXTRA_VIDEO_HEIGHT = "extra_video_height"
        private const val EXTRA_AUDIO_ID = "extra_audio_id"
        private const val EXTRA_IMAGE_FORMAT = "extra_image_format"
        private const val EXTRA_IMAGE_URLS = "extra_image_urls"
        private const val EXTRA_BATCH_URLS = "extra_batch_urls"
        private const val EXTRA_BATCH_TITLES = "extra_batch_titles"
        private const val EXTRA_BATCH_IDS = "extra_batch_ids"
        private const val EXTRA_BATCH_THUMBS = "extra_batch_thumbs"
        private const val EXTRA_THUMBNAIL_URL = "extra_thumbnail_url"
        private const val EXTRA_DIRECT_VIDEO_URL = "extra_direct_video_url"
        private const val EXTRA_DIRECT_AUDIO_URL = "extra_direct_audio_url"
        private const val EXTRA_USE_IG_COOKIES = "extra_use_ig_cookies"
        private const val EXTRA_CUSTOM_TITLE = "extra_custom_title"
        private const val EXTRA_FORMAT_ID = "extra_format_id"
        private const val EXTRA_NEEDS_AUDIO_MUX = "extra_needs_audio_mux"

        private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
        val state: StateFlow<DownloadState> = _state.asStateFlow()

        @Volatile
        private var lastStartIntent: Intent? = null

        @Volatile
        private var pausedWorkDir: File? = null

        fun resetState() {
            if (_state.value !is DownloadState.Running && _state.value !is DownloadState.Paused) {
                _state.value = DownloadState.Idle
            }
        }

        fun start(
            context: Context,
            url: String,
            mediaId: String,
            title: String,
            platform: String,
            mode: DownloadMode,
            videoHeight: Int?,
            audioId: String?,
            imageFormat: ImageFormat = ImageFormat.ORIGINAL,
            imageUrls: List<String> = emptyList(),
            batchUrls: List<String> = emptyList(),
            batchTitles: List<String> = emptyList(),
            batchIds: List<String> = emptyList(),
            batchThumbs: List<String> = emptyList(),
            thumbnailUrl: String? = null,
            directVideoUrl: String? = null,
            directAudioUrl: String? = null,
            useInstagramCookies: Boolean = false,
            customTitle: String? = null,
            formatId: String? = null,
            needsAudioMux: Boolean = false,
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_MEDIA_ID, mediaId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_PLATFORM, platform)
                putExtra(EXTRA_MODE, mode.name)
                putExtra(EXTRA_VIDEO_HEIGHT, videoHeight ?: 1080)
                putExtra(EXTRA_AUDIO_ID, audioId)
                putExtra(EXTRA_IMAGE_FORMAT, imageFormat.name)
                putStringArrayListExtra(EXTRA_IMAGE_URLS, ArrayList(imageUrls))
                putStringArrayListExtra(EXTRA_BATCH_URLS, ArrayList(batchUrls))
                putStringArrayListExtra(EXTRA_BATCH_TITLES, ArrayList(batchTitles))
                putStringArrayListExtra(EXTRA_BATCH_IDS, ArrayList(batchIds))
                putStringArrayListExtra(EXTRA_BATCH_THUMBS, ArrayList(batchThumbs))
                putExtra(EXTRA_THUMBNAIL_URL, thumbnailUrl)
                putExtra(EXTRA_DIRECT_VIDEO_URL, directVideoUrl)
                putExtra(EXTRA_DIRECT_AUDIO_URL, directAudioUrl)
                putExtra(EXTRA_USE_IG_COOKIES, useInstagramCookies)
                putExtra(EXTRA_CUSTOM_TITLE, customTitle)
                putExtra(EXTRA_FORMAT_ID, formatId)
                putExtra(EXTRA_NEEDS_AUDIO_MUX, needsAudioMux)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pause(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun resume(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_RESUME
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
