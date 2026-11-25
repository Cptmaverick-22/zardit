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
import io.github.cptmaverick_22.zardit.data.HistoryItem
import io.github.cptmaverick_22.zardit.data.HistoryStore
import io.github.cptmaverick_22.zardit.data.ZarditPrefs
import io.github.cptmaverick_22.zardit.downloader.AudioOption
import io.github.cptmaverick_22.zardit.downloader.DownloadMode
import io.github.cptmaverick_22.zardit.downloader.DownloadState
import io.github.cptmaverick_22.zardit.downloader.ImageFormat
import io.github.cptmaverick_22.zardit.downloader.ZarditEngine
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled.set(true)
                activeProcessId?.let { ZarditEngine.cancel(it) }
                activeJob?.cancel()
                _state.value = DownloadState.Cancelled
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val mediaId = intent.getStringExtra(EXTRA_MEDIA_ID) ?: ""
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Zardit Download"
                val platform = intent.getStringExtra(EXTRA_PLATFORM) ?: ""
                val modeName = intent.getStringExtra(EXTRA_MODE) ?: DownloadMode.VIDEO.name
                val mode = DownloadMode.valueOf(modeName)
                val videoHeight = intent.getIntExtra(EXTRA_VIDEO_HEIGHT, 1080)
                val audioId = intent.getStringExtra(EXTRA_AUDIO_ID)
                val audioOption = ZarditEngine.defaultAudioOptions.find { it.id == audioId }
                val imageFormatName = intent.getStringExtra(EXTRA_IMAGE_FORMAT) ?: ImageFormat.ORIGINAL.name
                val imageFormat = runCatching { ImageFormat.valueOf(imageFormatName) }.getOrDefault(ImageFormat.ORIGINAL)
                val imageUrls = intent.getStringArrayListExtra(EXTRA_IMAGE_URLS).orEmpty()
                val batchUrls = intent.getStringArrayListExtra(EXTRA_BATCH_URLS).orEmpty()
                val batchTitles = intent.getStringArrayListExtra(EXTRA_BATCH_TITLES).orEmpty()
                val batchIds = intent.getStringArrayListExtra(EXTRA_BATCH_IDS).orEmpty()

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
    ) {
        ensureNotificationChannel()
        cancelled.set(false)

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
                    imageUrls.forEachIndexed { idx, imgUrl ->
                        if (cancelled.get()) return@launch
                        val prefix = if (imageUrls.size > 1) "[${idx + 1}/${imageUrls.size}] " else ""
                        val itemId = if (imageUrls.size > 1) "${mediaId}_${idx + 1}" else mediaId
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
                        lastLocation = saved.savedLocation
                        historyStore.add(
                            HistoryItem(
                                id = "${itemId}_${imageFormat.id}_${imageFormat.ext}",
                                mediaId = itemId,
                                title = if (imageUrls.size > 1) "$title (${idx + 1})" else title,
                                platform = platform,
                                qualityLabel = imageFormat.label,
                                fileName = saved.fileName,
                                savedLocation = saved.savedLocation,
                                contentUri = saved.contentUri,
                                timestampMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                    if (!cancelled.get()) {
                        finishCompleted(title, lastLocation, alreadyExisted = false)
                    }
                    return@launch
                }

                if (batchUrls.isNotEmpty()) {
                    var lastLocation = ""
                    var downloadedCount = 0
                    val qTag = ZarditEngine.qualityTag(mode, videoHeight, audioOption)
                    val ext = ZarditEngine.targetExtension(mode, audioOption)

                    batchUrls.forEachIndexed { idx, itemUrl ->
                        if (cancelled.get()) return@launch
                        val itemTitle = batchTitles.getOrElse(idx) { "Item ${idx + 1}" }
                        val itemId = batchIds.getOrElse(idx) { "${mediaId}_${idx + 1}" }
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

                        val pid = UUID.randomUUID().toString()
                        activeProcessId = pid
                        val saved = ZarditEngine.download(
                            context = applicationContext,
                            url = itemUrl,
                            mode = mode,
                            videoHeight = videoHeight,
                            audioOption = audioOption,
                            treeUri = treeUri,
                            processId = pid,
                        ) { pct, status ->
                            updateProgress(title, pct, "$prefix$status")
                        }
                        downloadedCount++
                        lastLocation = saved.savedLocation
                        historyStore.add(
                            HistoryItem(
                                id = "${itemId}_${qTag}_$ext",
                                mediaId = itemId,
                                title = itemTitle,
                                platform = platform,
                                qualityLabel = qTag,
                                fileName = saved.fileName,
                                savedLocation = saved.savedLocation,
                                contentUri = saved.contentUri,
                                timestampMs = System.currentTimeMillis(),
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
                val saved = ZarditEngine.download(
                    context = applicationContext,
                    url = url,
                    mode = mode,
                    videoHeight = videoHeight,
                    audioOption = audioOption,
                    treeUri = treeUri,
                    processId = pid,
                ) { pct, statusLine ->
                    updateProgress(title, pct, statusLine)
                }

                if (!cancelled.get()) {
                    historyStore.add(
                        HistoryItem(
                            id = "${mediaId}_${qTag}_$ext",
                            mediaId = mediaId,
                            title = title,
                            platform = platform,
                            qualityLabel = qTag,
                            fileName = saved.fileName,
                            savedLocation = saved.savedLocation,
                            contentUri = saved.contentUri,
                            timestampMs = System.currentTimeMillis(),
                        ),
                    )
                    finishCompleted(title, saved.savedLocation, alreadyExisted = false)
                }
            } catch (t: Throwable) {
                if (!cancelled.get()) {
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
        if (cancelled.get()) return
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

    private fun finishCompleted(title: String, savedLocation: String, alreadyExisted: Boolean) {
        _state.value = DownloadState.Completed(
            title = title,
            savedLocation = savedLocation,
            alreadyExisted = alreadyExisted,
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
        private const val ACTION_CANCEL = "io.github.cptmaverick_22.zardit.action.CANCEL"
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

        private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
        val state: StateFlow<DownloadState> = _state.asStateFlow()

        fun resetState() {
            if (_state.value !is DownloadState.Running) {
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
