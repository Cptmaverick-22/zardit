package io.github.cptmaverick_22.zardit.ui.main

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.cptmaverick_22.zardit.ZarditApp
import io.github.cptmaverick_22.zardit.data.HistoryItem
import io.github.cptmaverick_22.zardit.data.HistoryStore
import io.github.cptmaverick_22.zardit.data.InstagramAuth
import io.github.cptmaverick_22.zardit.data.ZarditPrefs
import io.github.cptmaverick_22.zardit.downloader.AppTab
import io.github.cptmaverick_22.zardit.downloader.AudioOption
import io.github.cptmaverick_22.zardit.downloader.DownloadMode
import io.github.cptmaverick_22.zardit.downloader.DownloadState
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
    val isInstagramConnected: Boolean = false,
    val history: List<HistoryItem> = emptyList(),
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
            folderDisplayName = prefs.getFolderDisplayName(),
            hasCustomFolder = prefs.getTreeUri() != null,
            isInstagramConnected = InstagramAuth.hasSession(application),
            history = historyStore.loadAll(),
        ),
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            DownloadService.state.collect { state ->
                if (state is DownloadState.Completed) {
                    refreshHistory()
                }
            }
        }
    }

    fun onTabSelected(tab: AppTab) {
        _uiState.update { it.copy(activeTab = tab) }
        if (tab == AppTab.HISTORY) {
            refreshHistory()
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
                .onSuccess { preview ->
                    val defaultHeight = preview.videoOptions
                        .firstOrNull { it.height <= 1080 }?.height
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

        val basePlatform = preview.platform.substringBefore(" · ")
        val mediaKind = when (current.downloadMode) {
            DownloadMode.AUDIO -> "AUDIO"
            DownloadMode.IMAGE -> "IMAGE"
            DownloadMode.VIDEO -> "VIDEO"
        }

        if (preview.isPlaylist) {
            val selected = preview.playlistEntries.filter { it.index in current.selectedPlaylistIndices }
            if (selected.isEmpty()) return
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
            )
            return
        }

        val chosenImages = if (current.downloadMode == DownloadMode.IMAGE && preview.imageUrls.isNotEmpty()) {
            preview.imageUrls.filterIndexed { idx, _ -> idx in current.selectedImageIndices }
                .ifEmpty { return }
        } else {
            preview.imageUrls
        }

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
            directVideoUrl = preview.directVideoUrl,
            useInstagramCookies = preview.usedInstagramAuth,
            customTitle = preview.sanitizedTitle,
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

    fun cancelDownload() {
        DownloadService.cancel(app)
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

    fun clearHistory() {
        historyStore.clear()
        refreshHistory()
    }

    private fun refreshHistory() {
        _uiState.update { it.copy(history = historyStore.loadAll()) }
    }

    private fun extractFirstUrl(text: String): String? {
        val regex = Regex("https?://\\S+")
        return regex.find(text)?.value
    }
}
