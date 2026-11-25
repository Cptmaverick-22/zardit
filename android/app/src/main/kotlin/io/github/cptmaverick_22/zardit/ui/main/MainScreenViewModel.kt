package io.github.cptmaverick_22.zardit.ui.main

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.cptmaverick_22.zardit.ZarditApp
import io.github.cptmaverick_22.zardit.data.HistoryItem
import io.github.cptmaverick_22.zardit.data.HistoryStore
import io.github.cptmaverick_22.zardit.data.ZarditPrefs
import io.github.cptmaverick_22.zardit.downloader.AppTab
import io.github.cptmaverick_22.zardit.downloader.AudioOption
import io.github.cptmaverick_22.zardit.downloader.DownloadMode
import io.github.cptmaverick_22.zardit.downloader.DownloadState
import io.github.cptmaverick_22.zardit.downloader.MediaPreview
import io.github.cptmaverick_22.zardit.downloader.ThemeMode
import io.github.cptmaverick_22.zardit.downloader.ZarditEngine
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
    val fetchError: String? = null,
    val preview: MediaPreview? = null,
    val downloadMode: DownloadMode = DownloadMode.VIDEO,
    val selectedVideoHeight: Int? = null,
    val selectedAudioId: String = "mp3_192",
    val existingDownloadPath: String? = null,
    val isUpdatingYtDlp: Boolean = false,
    val updateBanner: String? = null,
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
            history = historyStore.loadAll(),
        ),
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            DownloadService.state.collect { state ->
                if (state is DownloadState.Completed) {
                    refreshHistory()
                    checkDuplicateSelection()
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
        _uiState.update { it.copy(url = value, fetchError = null) }
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
        _uiState.update {
            it.copy(
                folderDisplayName = "Downloads/Zardit",
                hasCustomFolder = false,
            )
        }
        checkDuplicateSelection()
    }

    fun onDownloadModeSelected(mode: DownloadMode) {
        _uiState.update { it.copy(downloadMode = mode) }
        checkDuplicateSelection()
    }

    fun onVideoHeightSelected(height: Int) {
        _uiState.update { it.copy(selectedVideoHeight = height) }
        checkDuplicateSelection()
    }

    fun onAudioOptionSelected(option: AudioOption) {
        _uiState.update { it.copy(selectedAudioId = option.id) }
        checkDuplicateSelection()
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
            _uiState.update { it.copy(fetchError = "Engine init failed: ${err.message}") }
            return
        }

        DownloadService.resetState()
        _uiState.update { it.copy(isFetching = true, fetchError = null, existingDownloadPath = null) }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ZarditEngine.fetchPreview(target) }
                .onSuccess { preview ->
                    val defaultHeight = preview.videoOptions
                        .firstOrNull { it.height <= 1080 }?.height
                        ?: preview.videoOptions.firstOrNull()?.height
                    _uiState.update {
                        it.copy(
                            isFetching = false,
                            preview = preview,
                            selectedVideoHeight = defaultHeight,
                            fetchError = null,
                        )
                    }
                    checkDuplicateSelection()
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isFetching = false,
                            fetchError = err.message ?: "Could not fetch media info.",
                        )
                    }
                }
        }
    }

    private fun checkDuplicateSelection() {
        val current = _uiState.value
        val preview = current.preview ?: return
        val audioOpt = preview.audioOptions.find { it.id == current.selectedAudioId }
        val qTag = ZarditEngine.qualityTag(current.downloadMode, current.selectedVideoHeight, audioOpt)
        val ext = ZarditEngine.targetExtension(current.downloadMode, audioOpt)

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
        DownloadService.start(
            context = app,
            url = preview.url,
            mediaId = preview.mediaId,
            title = preview.title,
            platform = preview.platform,
            mode = current.downloadMode,
            videoHeight = current.selectedVideoHeight,
            audioId = current.selectedAudioId,
        )
    }

    fun cancelDownload() {
        DownloadService.cancel(app)
    }

    fun updateYtDlp() {
        if (_uiState.value.isUpdatingYtDlp) return
        _uiState.update { it.copy(isUpdatingYtDlp = true, updateBanner = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val msg = runCatching { ZarditEngine.updateYtDlp(app) }
                .getOrElse { err -> "Update failed: ${err.message}" }
            _uiState.update { it.copy(isUpdatingYtDlp = false, updateBanner = msg) }
        }
    }

    fun removeHistoryItem(id: String) {
        historyStore.remove(id)
        refreshHistory()
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
