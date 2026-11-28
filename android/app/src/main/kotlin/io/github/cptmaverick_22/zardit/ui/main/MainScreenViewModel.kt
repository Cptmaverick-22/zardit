package io.github.cptmaverick_22.zardit.ui.main

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.cptmaverick_22.zardit.ZarditApp
import io.github.cptmaverick_22.zardit.data.DiagnosticLog
import io.github.cptmaverick_22.zardit.data.FacebookAuth
import io.github.cptmaverick_22.zardit.data.HistoryItem
import io.github.cptmaverick_22.zardit.data.HistoryStore
import io.github.cptmaverick_22.zardit.data.InstagramAuth
import io.github.cptmaverick_22.zardit.data.ZarditPrefs
import io.github.cptmaverick_22.zardit.downloader.ZarditParser
import io.github.cptmaverick_22.zardit.downloader.AppTab
import io.github.cptmaverick_22.zardit.downloader.AppUpdateInfo
import io.github.cptmaverick_22.zardit.downloader.AppUpdater
import io.github.cptmaverick_22.zardit.downloader.AudioOption
import io.github.cptmaverick_22.zardit.downloader.DownloadMode
import io.github.cptmaverick_22.zardit.downloader.DownloadState
import io.github.cptmaverick_22.zardit.downloader.FfmpegAddonAvailability
import io.github.cptmaverick_22.zardit.downloader.FfmpegAddonManager
import io.github.cptmaverick_22.zardit.downloader.ImageFormat
import io.github.cptmaverick_22.zardit.downloader.MediaPreview
import io.github.cptmaverick_22.zardit.downloader.ThemeMode
import io.github.cptmaverick_22.zardit.downloader.ZarditEngine
import io.github.cptmaverick_22.zardit.downloader.ZarditFlavor
import io.github.cptmaverick_22.zardit.service.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val activeTab: AppTab = AppTab.DOWNLOADER,
    val url: String = "",
    val themeMode: ThemeMode = ThemeMode.AUTO,
    val defaultVideoQuality: Int = 1080,
    val wifiOnlyDownloads: Boolean = false,
    val folderDisplayName: String = "Downloads/Zardit",
    val hasCustomFolder: Boolean = false,
    val isFetching: Boolean = false,
    val fetchStatusText: String? = null,
    val fetchError: String? = null,
    val preview: MediaPreview? = null,
    val downloadMode: DownloadMode = DownloadMode.VIDEO,
    val selectedVideoHeight: Int? = null,
    val selectedAudioId: String = "orig_m4a",
    val selectedImageFormat: ImageFormat = ImageFormat.ORIGINAL,
    val selectedImageIndices: Set<Int> = emptySet(),
    val selectedPlaylistIndices: Set<Int> = emptySet(),
    val playlistRangeText: String = "",
    val existingDownloadPath: String? = null,
    val isUpdatingYtDlp: Boolean = false,
    val updateBanner: String? = null,
    val appUpdateInfo: AppUpdateInfo? = null,
    val showAppUpdateBanner: Boolean = false,
    val isCheckingAppUpdate: Boolean = false,
    val appUpdateStatus: String? = null,
    val isDownloadingApk: Boolean = false,
    val apkDownloadPct: Int = 0,
    val autoCheckUpdates: Boolean = true,
    val pendingInstallApkPath: String? = null,
    val needInstallPermissionPrompt: Boolean = false,
    val ffmpegInstalled: Boolean = false,
    val ffmpegSizeBytes: Long? = null,
    val isInstallingFfmpeg: Boolean = false,
    val ffmpegDownloadPct: Int = 0,
    val ffmpegStatus: String? = null,
    val isInstagramConnected: Boolean = false,
    val isFacebookConnected: Boolean = false,
    val history: List<HistoryItem> = emptyList(),
    val unseenHistoryCount: Int = 0,
)

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ZarditApp
    private val prefs = ZarditPrefs(application)
    private val historyStore = HistoryStore(application)

    val engineInitResult: StateFlow<Result<Unit>?> = app.initResult
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val downloadState: StateFlow<DownloadState> = DownloadService.state

    private val _uiState = MutableStateFlow(
        MainUiState(
            themeMode = prefs.getThemeMode(),
            defaultVideoQuality = prefs.getDefaultVideoQuality(),
            wifiOnlyDownloads = prefs.isWifiOnlyDownloadsEnabled(),
            folderDisplayName = prefs.getFolderDisplayName(),
            hasCustomFolder = prefs.getTreeUri() != null,
            autoCheckUpdates = prefs.isAppUpdateAutoCheckEnabled(),
            ffmpegInstalled = FfmpegAddonManager.isInstalled(application),
            ffmpegSizeBytes = FfmpegAddonManager.installedSizeBytes(application),
            isInstagramConnected = InstagramAuth.hasSession(application),
            isFacebookConnected = FacebookAuth.hasSession(application),
            history = historyStore.loadAll(),
        ),
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            DownloadService.state.collect { state ->
                if (state is DownloadState.Completed) {
                    val prevIds = _uiState.value.history.mapTo(HashSet()) { it.id }
                    val loaded = historyStore.loadAll()
                    val added = loaded.count { it.id !in prevIds }
                    _uiState.update { cur ->
                        cur.copy(
                            history = loaded,
                            unseenHistoryCount = computeNextUnseenCount(
                                activeTab = cur.activeTab,
                                currentUnseen = cur.unseenHistoryCount,
                                addedCount = added,
                                alreadyExisted = state.alreadyExisted,
                            ),
                        )
                    }
                }
            }
        }
        checkAppUpdateInBackground()
    }

    fun onTabSelected(tab: AppTab) {
        if (tab == AppTab.HISTORY) {
            _uiState.update {
                it.copy(
                    activeTab = tab,
                    history = historyStore.loadAll(),
                    unseenHistoryCount = 0,
                )
            }
        } else {
            _uiState.update { it.copy(activeTab = tab) }
        }
    }

    fun onUrlChanged(value: String) {
        if (value.isBlank()) {
            DownloadService.resetState()
            _uiState.update {
                it.copy(
                    url = value,
                    fetchError = null,
                    preview = null,
                    existingDownloadPath = null,
                    selectedPlaylistIndices = emptySet(),
                    selectedImageIndices = emptySet(),
                    playlistRangeText = "",
                )
            }
        } else {
            _uiState.update { it.copy(url = value, fetchError = null) }
        }
    }

    fun onThemeModeSelected(mode: ThemeMode) {
        prefs.setThemeMode(mode)
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun setDefaultVideoQuality(height: Int) {
        prefs.setDefaultVideoQuality(height)
        _uiState.update { it.copy(defaultVideoQuality = height) }
    }

    fun setWifiOnlyDownloads(enabled: Boolean) {
        prefs.setWifiOnlyDownloadsEnabled(enabled)
        _uiState.update { it.copy(wifiOnlyDownloads = enabled) }
    }

    fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { app.contentResolver.takePersistableUriPermission(uri, flags) }
        prefs.setTreeUri(uri)
        DownloadService.resetState()
        _uiState.update {
            it.copy(
                folderDisplayName = prefs.getFolderDisplayName(),
                hasCustomFolder = true,
            )
        }
        checkDuplicateSelection()
    }

    fun resetFolder() {
        prefs.setTreeUri(null)
        DownloadService.resetState()
        _uiState.update {
            it.copy(
                folderDisplayName = "Downloads/Zardit",
                hasCustomFolder = false,
            )
        }
        checkDuplicateSelection()
    }

    fun onDownloadModeSelected(mode: DownloadMode) {
        DownloadService.resetState()
        _uiState.update { it.copy(downloadMode = mode) }
        checkDuplicateSelection()
    }

    fun onVideoHeightSelected(height: Int) {
        DownloadService.resetState()
        _uiState.update { it.copy(selectedVideoHeight = height) }
        checkDuplicateSelection()
    }

    fun onAudioOptionSelected(option: AudioOption) {
        DownloadService.resetState()
        _uiState.update { it.copy(selectedAudioId = option.id) }
        checkDuplicateSelection()
    }

    fun onImageFormatSelected(format: ImageFormat) {
        DownloadService.resetState()
        _uiState.update { it.copy(selectedImageFormat = format) }
        checkDuplicateSelection()
    }

    fun toggleImageIndex(index: Int) {
        DownloadService.resetState()
        _uiState.update { state ->
            val updated = state.selectedImageIndices.toMutableSet()
            if (!updated.add(index)) updated.remove(index)
            state.copy(selectedImageIndices = updated)
        }
    }

    fun selectAllImages() {
        val total = _uiState.value.preview?.imageUrls?.size ?: return
        _uiState.update { it.copy(selectedImageIndices = (0 until total).toSet()) }
    }

    fun selectNoneImages() {
        _uiState.update { it.copy(selectedImageIndices = emptySet()) }
    }

    fun togglePlaylistIndex(index: Int) {
        DownloadService.resetState()
        _uiState.update { state ->
            val updated = state.selectedPlaylistIndices.toMutableSet()
            if (!updated.add(index)) updated.remove(index)
            state.copy(selectedPlaylistIndices = updated)
        }
    }

    fun selectAllPlaylist() {
        val total = _uiState.value.preview?.playlistEntries?.size ?: return
        _uiState.update { it.copy(selectedPlaylistIndices = (1..total).toSet(), playlistRangeText = "") }
    }

    fun selectNonePlaylist() {
        _uiState.update { it.copy(selectedPlaylistIndices = emptySet(), playlistRangeText = "") }
    }

    fun onPlaylistRangeChanged(rangeText: String) {
        val total = _uiState.value.preview?.playlistEntries?.size ?: return
        val parsed = ZarditEngine.parseRangeSelection(rangeText, total)
        _uiState.update { it.copy(playlistRangeText = rangeText, selectedPlaylistIndices = parsed) }
    }

    fun dismissUpdateBanner() {
        _uiState.update { it.copy(updateBanner = null) }
    }

    fun handleSharedUrl(sharedText: String) {
        val extracted = extractFirstUrl(sharedText) ?: return
        _uiState.update { it.copy(activeTab = AppTab.DOWNLOADER, url = extracted, fetchError = null) }
        fetch(extracted)
    }

    fun fetch(explicitUrl: String? = null) {
        val target = (explicitUrl ?: _uiState.value.url).trim()
        if (target.isEmpty()) {
            _uiState.update { it.copy(fetchError = "Paste a media link first.") }
            return
        }
        if (app.initResult.value == null) {
            _uiState.update { it.copy(fetchError = "Engine is still starting up, try again in a moment.") }
            return
        }
        app.initResult.value?.onFailure { err ->
            val detail = err.message ?: err::class.java.simpleName
            _uiState.update { it.copy(fetchError = "Engine init failed: $detail") }
            return
        }

        DownloadService.resetState()
        _uiState.update {
            it.copy(
                isFetching = true,
                fetchStatusText = ZarditFlavor.randomFetchLine(),
                fetchError = null,
                existingDownloadPath = null,
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ZarditEngine.fetchPreview(app, target) }
                .onSuccess { rawPreview ->
                    val preview = rawPreview.copy(
                        audioOptions = ZarditEngine.audioOptionsWithAddon(app, rawPreview.audioOptions),
                    )
                    val preferredCap = _uiState.value.defaultVideoQuality
                    val defaultHeight = preview.videoOptions
                        .firstOrNull { it.height <= preferredCap }?.height
                        ?: preview.videoOptions.firstOrNull()?.height
                    val defaultMode = if (preview.imageUrls.isNotEmpty() && preview.videoOptions.isEmpty()) {
                        DownloadMode.IMAGE
                    } else {
                        DownloadMode.VIDEO
                    }
                    val allPlaylistIndices = if (preview.isPlaylist) {
                        (1..preview.playlistEntries.size).toSet()
                    } else {
                        emptySet()
                    }
                    _uiState.update {
                        it.copy(
                            isFetching = false,
                            fetchStatusText = null,
                            preview = preview,
                            downloadMode = defaultMode,
                            selectedVideoHeight = defaultHeight,
                            selectedImageIndices = preview.imageUrls.indices.toSet(),
                            selectedPlaylistIndices = allPlaylistIndices,
                            playlistRangeText = "",
                            fetchError = null,
                        )
                    }
                    checkDuplicateSelection()
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isFetching = false,
                            fetchStatusText = null,
                            fetchError = err.message ?: "Could not fetch media info.",
                        )
                    }
                }
        }
    }

    private fun checkDuplicateSelection() {
        val current = _uiState.value
        val preview = current.preview ?: return
        if (preview.isPlaylist) {
            _uiState.update { it.copy(existingDownloadPath = null) }
            return
        }
        val audioOpt = preview.audioOptions.find { it.id == current.selectedAudioId }
        val qTag = ZarditEngine.qualityTag(
            current.downloadMode,
            current.selectedVideoHeight,
            audioOpt,
            current.selectedImageFormat,
        )
        val ext = ZarditEngine.targetExtension(
            current.downloadMode,
            audioOpt,
            current.selectedImageFormat,
        )

        viewModelScope.launch(Dispatchers.IO) {
            val existing = ZarditEngine.findExistingDownload(
                context = app,
                mediaId = preview.mediaId,
                qualityTag = qTag,
                extension = ext,
                treeUri = prefs.getTreeUri(),
            )
            _uiState.update { it.copy(existingDownloadPath = existing) }
        }
    }

    fun startDownload() {
        val current = _uiState.value
        val preview = current.preview ?: return
        if (current.existingDownloadPath != null) return
        if (current.wifiOnlyDownloads && !ZarditEngine.isWifiConnected(app)) {
            _uiState.update {
                it.copy(updateBanner = "Wi-Fi Only mode is enabled in Settings. Connect to Wi-Fi or turn it off to download.")
            }
            return
        }

        val basePlatform = preview.platform.substringBefore(" · ")
        val mediaKind = when (current.downloadMode) {
            DownloadMode.AUDIO -> "AUDIO"
            DownloadMode.IMAGE -> "IMAGE"
            DownloadMode.VIDEO -> "VIDEO"
        }

        if (preview.isPlaylist) {
            val selected = preview.playlistEntries.filter { it.index in current.selectedPlaylistIndices }
            if (selected.isEmpty()) return
            if (current.downloadMode == DownloadMode.IMAGE) {
                val withThumb = selected.filter { !it.thumbnailUrl.isNullOrBlank() }
                if (withThumb.isEmpty()) return
                DownloadService.start(
                    context = app,
                    url = preview.url,
                    mediaId = preview.mediaId,
                    title = preview.title,
                    platform = "$basePlatform · IMAGE",
                    mode = DownloadMode.IMAGE,
                    videoHeight = null,
                    audioId = null,
                    imageFormat = current.selectedImageFormat,
                    batchUrls = withThumb.map { it.thumbnailUrl!! },
                    batchTitles = withThumb.map { it.title },
                    batchIds = withThumb.map { it.id },
                    batchThumbs = withThumb.map { it.thumbnailUrl.orEmpty() },
                    thumbnailUrl = preview.thumbnailUrl,
                )
            } else {
                DownloadService.start(
                    context = app,
                    url = preview.url,
                    mediaId = preview.mediaId,
                    title = preview.title,
                    platform = "$basePlatform · $mediaKind",
                    mode = current.downloadMode,
                    videoHeight = current.selectedVideoHeight,
                    audioId = current.selectedAudioId,
                    batchUrls = selected.map { it.url },
                    batchTitles = selected.map { it.title },
                    batchIds = selected.map { it.id },
                    batchThumbs = selected.map { it.thumbnailUrl.orEmpty() },
                    thumbnailUrl = preview.thumbnailUrl,
                )
            }
            return
        }

        val chosenImages = if (current.downloadMode == DownloadMode.IMAGE && preview.imageUrls.isNotEmpty()) {
            preview.imageUrls.filterIndexed { idx, _ -> idx in current.selectedImageIndices }
                .ifEmpty { return }
        } else {
            preview.imageUrls
        }

        val selectedVidOpt = preview.videoOptions.firstOrNull { it.height == current.selectedVideoHeight }
        DownloadService.start(
            context = app,
            url = preview.url,
            mediaId = preview.mediaId,
            title = preview.title,
            platform = "$basePlatform · $mediaKind",
            mode = current.downloadMode,
            videoHeight = current.selectedVideoHeight,
            audioId = current.selectedAudioId,
            imageFormat = current.selectedImageFormat,
            imageUrls = chosenImages,
            thumbnailUrl = preview.thumbnailUrl ?: chosenImages.firstOrNull(),
            directVideoUrl = selectedVidOpt?.directUrl ?: preview.directVideoUrl,
            directAudioUrl = preview.directAudioUrl,
            useInstagramCookies = preview.usedInstagramAuth ||
                (ZarditParser.detectPlatformName(preview.url) == "Facebook" && current.isFacebookConnected),
            customTitle = preview.sanitizedTitle,
            formatId = selectedVidOpt?.formatId,
            needsAudioMux = selectedVidOpt?.needsAudioMux ?: false,
        )
    }

    fun onInstagramConnected() {
        _uiState.update {
            it.copy(
                isInstagramConnected = InstagramAuth.hasSession(app),
                updateBanner = "Instagram connected locally (AES-GCM encrypted).",
            )
        }
    }

    fun disconnectInstagram() {
        InstagramAuth.clearSession(app)
        _uiState.update {
            it.copy(
                isInstagramConnected = false,
                updateBanner = "Instagram session disconnected and wiped.",
            )
        }
    }

    fun onFacebookConnected() {
        _uiState.update {
            it.copy(
                isFacebookConnected = FacebookAuth.hasSession(app),
                updateBanner = "Facebook connected locally (AES-GCM encrypted).",
            )
        }
    }

    fun disconnectFacebook() {
        FacebookAuth.clearSession(app)
        _uiState.update {
            it.copy(
                isFacebookConnected = false,
                updateBanner = "Facebook session disconnected and wiped.",
            )
        }
    }

    fun cancelDownload() {
        DownloadService.cancel(app)
    }

    fun pauseDownload() {
        DownloadService.pause(app)
    }

    fun resumeDownload() {
        DownloadService.resume(app)
    }

    fun updateYtDlp() {
        if (_uiState.value.isUpdatingYtDlp) return
        _uiState.update { it.copy(isUpdatingYtDlp = true, updateBanner = "Teaching Charizard new moves…") }
        viewModelScope.launch(Dispatchers.IO) {
            val msg = runCatching { ZarditEngine.updateYtDlp(app) }
                .getOrElse { err -> "Update failed: ${err.message}" }
            _uiState.update { it.copy(isUpdatingYtDlp = false, updateBanner = msg) }
        }
    }

    private fun checkAppUpdateInBackground() {
        viewModelScope.launch(Dispatchers.IO) {
            val info = AppUpdater.backgroundCheckIfDue(app) ?: return@launch
            AppUpdater.notifyUpdateIfPermitted(app, info)
            _uiState.update {
                it.copy(
                    appUpdateInfo = info,
                    showAppUpdateBanner = true,
                    appUpdateStatus = if (info.sameTagHotfix) {
                        "Updated build for ${info.tagName} is available."
                    } else {
                        "${info.tagName} is available."
                    },
                )
            }
        }
    }

    fun checkAppUpdateNow() {
        if (_uiState.value.isCheckingAppUpdate || _uiState.value.isDownloadingApk) return
        _uiState.update { it.copy(isCheckingAppUpdate = true, appUpdateStatus = "Checking GitHub Releases…") }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { AppUpdater.checkForUpdate(app) }
                .onSuccess { info ->
                    val status = when {
                        info.available && info.sameTagHotfix ->
                            "Refreshed build available for ${info.tagName} (SHA-256 verified)."
                        info.available ->
                            "Zardit ${info.tagName} is ready to install."
                        else ->
                            "Zardit v${info.currentVersion} is up to date."
                    }
                    _uiState.update {
                        it.copy(
                            isCheckingAppUpdate = false,
                            appUpdateInfo = info,
                            showAppUpdateBanner = info.available,
                            appUpdateStatus = status,
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isCheckingAppUpdate = false,
                            appUpdateStatus = err.message ?: "Could not check for app updates.",
                        )
                    }
                }
        }
    }

    fun downloadAndInstallAppUpdate() {
        val info = _uiState.value.appUpdateInfo ?: return
        val asset = info.asset ?: run {
            _uiState.update { it.copy(appUpdateStatus = "No verified APK found in ${info.tagName}.") }
            return
        }
        if (_uiState.value.isDownloadingApk) return
        _uiState.update {
            it.copy(
                isDownloadingApk = true,
                apkDownloadPct = 0,
                appUpdateStatus = "Downloading & verifying ${asset.name}…",
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                AppUpdater.downloadAndVerifyApk(app, asset) { pct ->
                    _uiState.update { it.copy(apkDownloadPct = pct) }
                }
            }.onSuccess { apkFile ->
                _uiState.update {
                    it.copy(
                        isDownloadingApk = false,
                        apkDownloadPct = 100,
                        pendingInstallApkPath = apkFile.absolutePath,
                        showAppUpdateBanner = false,
                    )
                }
                if (AppUpdater.canInstallPackages(app)) {
                    runCatching { AppUpdater.launchApkInstall(app, apkFile) }
                        .onSuccess {
                            _uiState.update {
                                it.copy(
                                    needInstallPermissionPrompt = false,
                                    appUpdateStatus = "SHA-256 verified. Follow system prompt to finish updating.",
                                )
                            }
                        }
                        .onFailure { err ->
                            _uiState.update {
                                it.copy(appUpdateStatus = "Could not open installer: ${err.message}")
                            }
                        }
                } else {
                    _uiState.update {
                        it.copy(
                            needInstallPermissionPrompt = true,
                            appUpdateStatus = "Verified! Allow 'Install unknown apps' to install ${info.tagName}.",
                        )
                    }
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isDownloadingApk = false,
                        appUpdateStatus = err.message ?: "APK update failed.",
                    )
                }
            }
        }
    }

    fun retryPendingApkInstall() {
        val path = _uiState.value.pendingInstallApkPath ?: return
        val file = java.io.File(path)
        if (!file.isFile) {
            _uiState.update { it.copy(needInstallPermissionPrompt = false, pendingInstallApkPath = null) }
            return
        }
        if (AppUpdater.canInstallPackages(app)) {
            _uiState.update { it.copy(needInstallPermissionPrompt = false) }
            runCatching { AppUpdater.launchApkInstall(app, file) }
                .onFailure { err ->
                    _uiState.update { it.copy(appUpdateStatus = "Could not open installer: ${err.message}") }
                }
        }
    }

    fun dismissInstallPermissionPrompt() {
        _uiState.update { it.copy(needInstallPermissionPrompt = false) }
    }

    fun dismissAppUpdateBanner() {
        _uiState.value.appUpdateInfo?.let { AppUpdater.dismissUpdate(app, it) }
        _uiState.update { it.copy(showAppUpdateBanner = false) }
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        prefs.setAppUpdateAutoCheckEnabled(enabled)
        _uiState.update { it.copy(autoCheckUpdates = enabled) }
    }

    fun installFfmpegAddon() {
        if (_uiState.value.isInstallingFfmpeg) return
        _uiState.update {
            it.copy(
                isInstallingFfmpeg = true,
                ffmpegDownloadPct = 0,
                ffmpegStatus = "Checking release assets for FFmpeg addon…",
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                when (val avail = FfmpegAddonManager.checkAvailability()) {
                    is FfmpegAddonAvailability.NotPublished -> avail.message
                    is FfmpegAddonAvailability.Available -> {
                        _uiState.update {
                            it.copy(ffmpegStatus = "Downloading & verifying ${avail.asset.name}…")
                        }
                        val apkFile = FfmpegAddonManager.downloadAndVerifyApk(app, avail.asset) { pct ->
                            _uiState.update { it.copy(ffmpegDownloadPct = pct) }
                        }
                        _uiState.update {
                            it.copy(pendingInstallApkPath = apkFile.absolutePath)
                        }
                        if (AppUpdater.canInstallPackages(app)) {
                            runCatching { AppUpdater.launchApkInstall(app, apkFile) }
                                .fold(
                                    onSuccess = {
                                        _uiState.update { it.copy(needInstallPermissionPrompt = false) }
                                        "SHA-256 verified. Follow Android prompt to finish installing Zardit FFmpeg Addon."
                                    },
                                    onFailure = { err ->
                                        "Could not launch addon installer: ${err.message}"
                                    },
                                )
                        } else {
                            _uiState.update { it.copy(needInstallPermissionPrompt = true) }
                            "Verified! Allow 'Install unknown apps' to install Zardit FFmpeg Addon."
                        }
                    }
                }
            }.onSuccess { msg ->
                refreshFfmpegAddonState(statusOverride = msg)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isInstallingFfmpeg = false,
                        ffmpegStatus = err.message ?: "Could not install FFmpeg addon.",
                    )
                }
            }
        }
    }

    fun removeFfmpegAddon() {
        val launched = FfmpegAddonManager.launchUninstall(app)
        _uiState.update {
            it.copy(
                ffmpegStatus = if (launched) {
                    "Confirm uninstall in Android prompt to remove Zardit FFmpeg Addon cleanly."
                } else {
                    "FFmpeg addon is not installed."
                },
            )
        }
        if (!launched) {
            refreshFfmpegAddonState()
        }
    }

    fun refreshFfmpegAddonState(statusOverride: String? = null) {
        val installed = FfmpegAddonManager.isInstalled(app)
        val size = FfmpegAddonManager.installedSizeBytes(app)
        _uiState.update { state ->
            val updatedPreview = state.preview?.let { p ->
                val base = p.audioOptions.filterNot { it.requiresFfmpeg }
                val refreshed = if (installed) ZarditEngine.audioOptionsWithAddon(app, base) else base
                p.copy(audioOptions = refreshed)
            }
            val nextStatus = when {
                statusOverride != null -> statusOverride
                !state.isInstallingFfmpeg && state.ffmpegInstalled != installed -> if (installed) {
                    "FFmpeg addon active: MP3 encoding, cover art & fallback muxing unlocked."
                } else {
                    "FFmpeg addon removed. Base MP4/M4A features unaffected."
                }
                else -> state.ffmpegStatus
            }
            state.copy(
                isInstallingFfmpeg = false,
                ffmpegInstalled = installed,
                ffmpegSizeBytes = size,
                ffmpegStatus = nextStatus,
                selectedAudioId = if (!installed && state.selectedAudioId.startsWith("mp3_")) "orig_m4a" else state.selectedAudioId,
                preview = updatedPreview,
            )
        }
    }

    fun buildDiagnosticReport(): String = DiagnosticLog.buildReport(app)

    fun showDiagnosticsCopiedBanner() {
        _uiState.update { it.copy(updateBanner = "Diagnostics copied to clipboard.") }
    }

    fun removeHistoryItem(item: HistoryItem, deleteFile: Boolean) {
        if (deleteFile) {
            viewModelScope.launch(Dispatchers.IO) {
                ZarditEngine.deleteSavedFile(app, item.contentUri)
                historyStore.remove(item.id)
                refreshHistory()
            }
        } else {
            historyStore.remove(item.id)
            refreshHistory()
        }
    }

    fun clearHistory(deleteFiles: Boolean = false) {
        if (deleteFiles) {
            val items = _uiState.value.history
            viewModelScope.launch(Dispatchers.IO) {
                items.forEach { item ->
                    ZarditEngine.deleteSavedFile(app, item.contentUri)
                }
                historyStore.clear()
                refreshHistory()
            }
        } else {
            historyStore.clear()
            refreshHistory()
        }
    }

    private fun refreshHistory() {
        val loaded = historyStore.loadAll()
        _uiState.update {
            it.copy(
                history = loaded,
                unseenHistoryCount = if (it.activeTab == AppTab.HISTORY) {
                    0
                } else {
                    it.unseenHistoryCount.coerceAtMost(loaded.size)
                },
            )
        }
    }

    private fun extractFirstUrl(text: String): String? {
        val regex = Regex("https?://\\S+")
        return regex.find(text)?.value
    }

    companion object {
        fun computeNextUnseenCount(
            activeTab: AppTab,
            currentUnseen: Int,
            addedCount: Int,
            alreadyExisted: Boolean,
        ): Int = when {
            activeTab == AppTab.HISTORY -> 0
            !alreadyExisted && addedCount > 0 -> currentUnseen + addedCount
            else -> currentUnseen
        }
    }
}
