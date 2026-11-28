package io.github.cptmaverick_22.zardit.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import io.github.cptmaverick_22.zardit.BuildConfig
import io.github.cptmaverick_22.zardit.data.HistoryItem
import io.github.cptmaverick_22.zardit.downloader.AppTab
import io.github.cptmaverick_22.zardit.downloader.DownloadMode
import io.github.cptmaverick_22.zardit.downloader.DownloadState
import io.github.cptmaverick_22.zardit.downloader.ImageFormat
import io.github.cptmaverick_22.zardit.downloader.ZarditEngine
import io.github.cptmaverick_22.zardit.downloader.ZarditParser

private data class PlatformShortcut(
    val name: String,
    val launchUrl: String,
)

private val QUICK_PLATFORMS = listOf(
    PlatformShortcut("YouTube", "https://www.youtube.com/"),
    PlatformShortcut("Instagram", "https://www.instagram.com/"),
    PlatformShortcut("Facebook", "https://www.facebook.com/"),
    PlatformShortcut("X", "https://x.com/"),
    PlatformShortcut("Threads", "https://www.threads.net/"),
)

@Composable
fun MainScreen(
    viewModel: MainScreenViewModel,
) {
    val ui by viewModel.uiState.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val initResult by viewModel.engineInitResult.collectAsState()
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    var showIgLogin by remember { mutableStateOf(false) }
    var showIgDisconnect by remember { mutableStateOf(false) }
    var showFbLogin by remember { mutableStateOf(false) }
    var showFbDisconnect by remember { mutableStateOf(false) }
    var showAccountsSheet by remember { mutableStateOf(false) }
    var showUpdatesSheet by remember { mutableStateOf(false) }
    var pendingDeleteItem by remember { mutableStateOf<HistoryItem?>(null) }
    var showClearAllConfirm by remember { mutableStateOf(false) }

    val installPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.retryPendingApkInstall()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.startDownload()
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        viewModel.onFolderPicked(uri)
    }

    fun triggerDownload() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        viewModel.startDownload()
    }

    var dockVisible by remember { mutableStateOf(true) }
    val hasActiveTransfer = downloadState is DownloadState.Running || downloadState is DownloadState.Paused
    LaunchedEffect(ui.activeTab, hasActiveTransfer) {
        dockVisible = true
    }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -10f) {
                    dockVisible = false
                } else if (available.y > 10f) {
                    dockVisible = true
                }
                return Offset.Zero
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Compact Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Zardit",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        if (initResult == null) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        }
                    }
                    Text(
                        text = when (ui.activeTab) {
                            AppTab.DOWNLOADER -> "Save videos, audio & photos"
                            AppTab.HISTORY -> "Saved videos, audio & images"
                            AppTab.SETTINGS -> "Preferences, addons & updates"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val anyAccountConnected = ui.isInstagramConnected || ui.isFacebookConnected
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        1.dp,
                        if (anyAccountConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    ),
                    color = if (anyAccountConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.clickable { showAccountsSheet = true },
                ) {
                    Text(
                        text = if (anyAccountConnected) "IG/FB ✓" else "IG/FB",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (anyAccountConnected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }

                val hasAppUpdate = ui.appUpdateInfo?.available == true
                OutlinedButton(
                    onClick = { showUpdatesSheet = true },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    if (ui.isUpdatingYtDlp || ui.isCheckingAppUpdate || ui.isDownloadingApk) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "Updates",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                    )
                    if (hasAppUpdate) {
                        Spacer(Modifier.width(5.dp))
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }

            if (ui.showAppUpdateBanner && ui.appUpdateInfo?.available == true) {
                val info = ui.appUpdateInfo!!
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Rounded.SystemUpdate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = if (info.sameTagHotfix) {
                                "Updated build ready for Zardit ${info.tagName}"
                            } else {
                                "Zardit ${info.tagName} is available"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showUpdatesSheet = true }) {
                            Text("Update")
                        }
                        TextButton(onClick = { viewModel.dismissAppUpdateBanner() }) {
                            Text("Later")
                        }
                    }
                }
            }

            ui.updateBanner?.let { banner ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = banner,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (!ui.isUpdatingYtDlp) {
                            TextButton(onClick = { viewModel.dismissUpdateBanner() }) {
                                Text("OK")
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(nestedScrollConnection),
            ) {
                when (ui.activeTab) {
                    AppTab.DOWNLOADER -> HomeTab(
                        ui = ui,
                        downloadState = downloadState,
                        onUrlChanged = viewModel::onUrlChanged,
                        onPasteAndFetch = {
                            @Suppress("DEPRECATION")
                            val pasted = clipboard.getText()?.text?.trim().orEmpty()
                            if (pasted.isNotEmpty()) {
                                viewModel.onUrlChanged(pasted)
                                viewModel.fetch(pasted)
                            }
                        },
                        onFetch = { viewModel.fetch() },
                        onCopyDiagnostics = {
                            @Suppress("DEPRECATION")
                            clipboard.setText(AnnotatedString(viewModel.buildDiagnosticReport()))
                            viewModel.showDiagnosticsCopiedBanner()
                        },
                        onToggleImage = viewModel::toggleImageIndex,
                        onSelectAllImages = viewModel::selectAllImages,
                        onSelectNoneImages = viewModel::selectNoneImages,
                        onTogglePlaylist = viewModel::togglePlaylistIndex,
                        onSelectAllPlaylist = viewModel::selectAllPlaylist,
                        onSelectNonePlaylist = viewModel::selectNonePlaylist,
                        onPlaylistRangeChanged = viewModel::onPlaylistRangeChanged,
                        onDownloadModeSelected = viewModel::onDownloadModeSelected,
                        onVideoHeightSelected = viewModel::onVideoHeightSelected,
                        onAudioOptionSelected = viewModel::onAudioOptionSelected,
                        onImageFormatSelected = viewModel::onImageFormatSelected,
                        onPickFolder = { folderPickerLauncher.launch(null) },
                        onResetFolder = { viewModel.resetFolder() },
                        onTriggerDownload = { triggerDownload() },
                        onPauseDownload = { viewModel.pauseDownload() },
                        onResumeDownload = { viewModel.resumeDownload() },
                        onCancelDownload = { viewModel.cancelDownload() },
                        onOpenUrl = { launchUrl ->
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(launchUrl)))
                            }
                        },
                        onOpenHistoryItem = { item -> openHistoryMedia(context, item) },
                        onViewAllHistory = { viewModel.onTabSelected(AppTab.HISTORY) },
                        onOpenCompleted = { uriStr, fileName ->
                            openHistoryMedia(
                                context,
                                HistoryItem(
                                    id = "",
                                    mediaId = "",
                                    title = "",
                                    platform = "",
                                    qualityLabel = "",
                                    fileName = fileName,
                                    savedLocation = "",
                                    contentUri = uriStr,
                                    timestampMs = 0L,
                                ),
                            )
                        },
                        onShareCompleted = { uriStr ->
                            shareHistoryMedia(
                                context,
                                HistoryItem(
                                    id = "",
                                    mediaId = "",
                                    title = "",
                                    platform = "",
                                    qualityLabel = "",
                                    fileName = "",
                                    savedLocation = "",
                                    contentUri = uriStr,
                                    timestampMs = 0L,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    AppTab.HISTORY -> HistoryTab(
                        items = ui.history,
                        onOpen = { item -> openHistoryMedia(context, item) },
                        onShare = { item -> shareHistoryMedia(context, item) },
                        onRemove = { item -> pendingDeleteItem = item },
                        onClearAll = { showClearAllConfirm = true },
                        modifier = Modifier.fillMaxSize(),
                    )

                    AppTab.SETTINGS -> SettingsTab(
                        ui = ui,
                        onPickFolder = { folderPickerLauncher.launch(null) },
                        onResetFolder = { viewModel.resetFolder() },
                        onDefaultQualitySelected = viewModel::setDefaultVideoQuality,
                        onWifiOnlyChanged = viewModel::setWifiOnlyDownloads,
                        onThemeModeSelected = viewModel::onThemeModeSelected,
                        onManageAccounts = { showAccountsSheet = true },
                        onInstallFfmpeg = viewModel::installFfmpegAddon,
                        onRemoveFfmpeg = viewModel::removeFfmpegAddon,
                        onOpenUpdates = { showUpdatesSheet = true },
                        onAutoCheckUpdatesChanged = viewModel::setAutoCheckUpdates,
                        onUpdateYtDlp = viewModel::updateYtDlp,
                        onCopyDiagnostics = {
                            @Suppress("DEPRECATION")
                            clipboard.setText(AnnotatedString(viewModel.buildDiagnosticReport()))
                            viewModel.showDiagnosticsCopiedBanner()
                        },
                        onClearHistory = { showClearAllConfirm = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = dockVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            FloatingBottomDock(
                activeTab = ui.activeTab,
                historyCount = ui.history.size,
                downloadState = downloadState,
                activeThumbnailUrl = ui.preview?.thumbnailUrl,
                onTabSelected = viewModel::onTabSelected,
                onPauseDownload = viewModel::pauseDownload,
                onResumeDownload = viewModel::resumeDownload,
            )
        }
    }

    if (showAccountsSheet) {
        AlertDialog(
            onDismissRequest = { showAccountsSheet = false },
            title = { Text("Account Connections") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Public videos, reels, and photos always work without signing in. Connect an account locally (AES-GCM encrypted on device) only if you need private posts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Instagram", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    text = if (ui.isInstagramConnected) {
                                        "Connected · Private & public posts"
                                    } else {
                                        "Optional · Sign in for private posts"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (ui.isInstagramConnected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            if (ui.isInstagramConnected) {
                                OutlinedButton(
                                    onClick = { showAccountsSheet = false; showIgDisconnect = true },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    Text("Disconnect", maxLines = 1, softWrap = false)
                                }
                            } else {
                                Button(
                                    onClick = { showAccountsSheet = false; showIgLogin = true },
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                ) {
                                    Text("Connect", maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Facebook", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    text = if (ui.isFacebookConnected) {
                                        "Connected · Private & public posts"
                                    } else {
                                        "Optional · Sign in for private posts"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (ui.isFacebookConnected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            if (ui.isFacebookConnected) {
                                OutlinedButton(
                                    onClick = { showAccountsSheet = false; showFbDisconnect = true },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    Text("Disconnect", maxLines = 1, softWrap = false)
                                }
                            } else {
                                Button(
                                    onClick = { showAccountsSheet = false; showFbLogin = true },
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                ) {
                                    Text("Connect", maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAccountsSheet = false }) { Text("Close") }
            },
        )
    }

    if (showIgLogin) {
        InstagramLoginDialog(
            onConnected = {
                showIgLogin = false
                viewModel.onInstagramConnected()
            },
            onDismiss = { showIgLogin = false },
        )
    }

    if (showFbLogin) {
        FacebookLoginDialog(
            onConnected = {
                showFbLogin = false
                viewModel.onFacebookConnected()
            },
            onDismiss = { showFbLogin = false },
        )
    }

    if (showIgDisconnect) {
        AlertDialog(
            onDismissRequest = { showIgDisconnect = false },
            title = { Text("Disconnect Instagram?") },
            text = {
                Text("This will permanently wipe your locally encrypted Instagram session and cookies from this device.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showIgDisconnect = false
                        viewModel.disconnectInstagram()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text("Disconnect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showIgDisconnect = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showFbDisconnect) {
        AlertDialog(
            onDismissRequest = { showFbDisconnect = false },
            title = { Text("Disconnect Facebook?") },
            text = {
                Text("This will permanently wipe your locally encrypted Facebook session and cookies from this device.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFbDisconnect = false
                        viewModel.disconnectFacebook()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text("Disconnect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFbDisconnect = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    pendingDeleteItem?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDeleteItem = null },
            title = { Text("Remove download?") },
            text = {
                Text("Remove \"${item.title}\" from History only, or also delete the saved file from your phone?")
            },
            confirmButton = {
                if (item.contentUri != null) {
                    Button(
                        onClick = {
                            viewModel.removeHistoryItem(item, deleteFile = true)
                            pendingDeleteItem = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Text("Delete file too")
                    }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { pendingDeleteItem = null }) {
                        Text("Cancel")
                    }
                    TextButton(
                        onClick = {
                            viewModel.removeHistoryItem(item, deleteFile = false)
                            pendingDeleteItem = null
                        },
                    ) {
                        Text("History only")
                    }
                }
            },
        )
    }

    if (showClearAllConfirm) {
        val hasSavedFiles = ui.history.any { it.contentUri != null }
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("Clear all history?") },
            text = {
                Text(
                    if (hasSavedFiles) {
                        "Clear all ${ui.history.size} item(s) from History only, or also delete the saved files from your device?"
                    } else {
                        "Remove all ${ui.history.size} item(s) from History?"
                    },
                )
            },
            confirmButton = {
                if (hasSavedFiles) {
                    Button(
                        onClick = {
                            showClearAllConfirm = false
                            viewModel.clearHistory(deleteFiles = true)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Text("Delete files too")
                    }
                } else {
                    Button(
                        onClick = {
                            showClearAllConfirm = false
                            viewModel.clearHistory(deleteFiles = false)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Text("Clear history")
                    }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { showClearAllConfirm = false }) {
                        Text("Cancel")
                    }
                    if (hasSavedFiles) {
                        TextButton(
                            onClick = {
                                showClearAllConfirm = false
                                viewModel.clearHistory(deleteFiles = false)
                            },
                        ) {
                            Text("History only")
                        }
                    }
                }
            },
        )
    }

    if (showUpdatesSheet) {
        val appInfo = ui.appUpdateInfo
        val shortSha = BuildConfig.GIT_COMMIT.take(7)
        val appVerLine = if (shortSha.isNotEmpty()) {
            "v${BuildConfig.VERSION_NAME} ($shortSha)"
        } else {
            "v${BuildConfig.VERSION_NAME}"
        }
        AlertDialog(
            onDismissRequest = { if (!ui.isDownloadingApk) showUpdatesSheet = false },
            title = { Text("Zardit Updates") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Zardit App",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = appVerLine,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = ui.appUpdateStatus ?: "Check GitHub Releases for app updates (SHA-256 verified).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (appInfo?.available == true && appInfo.releaseNotes.isNotEmpty()) {
                                val noteLines = remember(appInfo.releaseNotes) {
                                    appInfo.releaseNotes
                                        .lineSequence()
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                        .toList()
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 156.dp),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .verticalScroll(rememberScrollState())
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        noteLines.forEach { line ->
                                            val isHeading = !line.startsWith("• ") &&
                                                line.length <= 42 &&
                                                !line.endsWith(".")
                                            Text(
                                                text = line,
                                                style = if (isHeading) {
                                                    MaterialTheme.typography.labelMedium
                                                } else {
                                                    MaterialTheme.typography.bodySmall
                                                },
                                                fontWeight = if (isHeading) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (isHeading) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            if (ui.isDownloadingApk) {
                                LinearProgressIndicator(
                                    progress = { ui.apkDownloadPct / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            val canInstallNow = appInfo?.available == true && appInfo.asset != null
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.checkAppUpdateNow() },
                                    enabled = !ui.isCheckingAppUpdate && !ui.isDownloadingApk,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        text = when {
                                            ui.isCheckingAppUpdate -> "Checking…"
                                            canInstallNow -> "Recheck"
                                            else -> "Check App"
                                        },
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                                if (canInstallNow) {
                                    Button(
                                        onClick = { viewModel.downloadAndInstallAppUpdate() },
                                        enabled = !ui.isDownloadingApk,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                        modifier = Modifier.weight(1.25f),
                                    ) {
                                        Text(
                                            text = if (ui.isDownloadingApk) "${ui.apkDownloadPct}%" else "Install",
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "yt-dlp Extraction Engine",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Keeps platform extractors working when sites change.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = {
                                    showUpdatesSheet = false
                                    viewModel.updateYtDlp()
                                },
                                enabled = !ui.isUpdatingYtDlp,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (ui.isUpdatingYtDlp) "Updating Engine…" else "Update yt-dlp Engine")
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Notify daily on new app updates",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = ui.autoCheckUpdates,
                            onCheckedChange = { viewModel.setAutoCheckUpdates(it) },
                        )
                    }

                    TextButton(
                        onClick = {
                            @Suppress("DEPRECATION")
                            clipboard.setText(AnnotatedString(viewModel.buildDiagnosticReport()))
                            showUpdatesSheet = false
                            viewModel.showDiagnosticsCopiedBanner()
                        },
                    ) {
                        Text("Copy Diagnostics Report")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showUpdatesSheet = false },
                    enabled = !ui.isDownloadingApk,
                ) {
                    Text("Close")
                }
            },
        )
    }

    if (ui.needInstallPermissionPrompt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissInstallPermissionPrompt() },
            title = { Text("Allow App Installs") },
            text = {
                Text(
                    "The update APK passed SHA-256 verification. Allow Zardit to install packages in Android settings so the update can launch automatically when you return.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.dismissInstallPermissionPrompt()
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}"),
                        )
                        installPermLauncher.launch(intent)
                    },
                ) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissInstallPermissionPrompt() }) {
                    Text("Later")
                }
            },
        )
    }
}

@Composable
private fun HomeTab(
    ui: MainUiState,
    downloadState: DownloadState,
    onUrlChanged: (String) -> Unit,
    onPasteAndFetch: () -> Unit,
    onFetch: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onToggleImage: (Int) -> Unit,
    onSelectAllImages: () -> Unit,
    onSelectNoneImages: () -> Unit,
    onTogglePlaylist: (Int) -> Unit,
    onSelectAllPlaylist: () -> Unit,
    onSelectNonePlaylist: () -> Unit,
    onPlaylistRangeChanged: (String) -> Unit,
    onDownloadModeSelected: (DownloadMode) -> Unit,
    onVideoHeightSelected: (Int) -> Unit,
    onAudioOptionSelected: (io.github.cptmaverick_22.zardit.downloader.AudioOption) -> Unit,
    onImageFormatSelected: (ImageFormat) -> Unit,
    onPickFolder: () -> Unit,
    onResetFolder: () -> Unit,
    onTriggerDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenHistoryItem: (HistoryItem) -> Unit,
    onViewAllHistory: () -> Unit,
    onOpenCompleted: (String, String) -> Unit,
    onShareCompleted: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val detectedPlatform = remember(ui.url) {
        if (ui.url.isBlank()) null else ZarditParser.detectPlatformName(ui.url)
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Prominent URL Search / Paste Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Paste media link",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (detectedPlatform != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = detectedPlatform,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = ui.url,
                    onValueChange = onUrlChanged,
                    placeholder = { Text("Paste YouTube, Instagram, Facebook, X, Threads…") },
                    leadingIcon = {
                        Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (ui.url.isNotEmpty()) {
                            IconButton(onClick = { onUrlChanged("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onPasteAndFetch,
                        enabled = !ui.isFetching,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Paste & Fetch", maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = onFetch,
                        enabled = !ui.isFetching,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (ui.isFetching) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Fetch", maxLines = 1)
                        }
                    }
                }

                ui.fetchStatusText?.let { status ->
                    Text(
                        text = status,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                ui.fetchError?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = onCopyDiagnostics,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("Copy Diagnostics", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // Idle Quick-Launch Platforms + Recent Downloads Strip (shown when no preview is active)
        if (ui.preview == null && !ui.isFetching) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Quick browse platforms",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Open an app to copy a link, then tap Paste & Fetch above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        QUICK_PLATFORMS.forEach { platform ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onOpenUrl(platform.launchUrl) },
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 9.dp, horizontal = 2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    PlatformLogo(
                                        platform = platform.name,
                                        modifier = Modifier.size(30.dp),
                                    )
                                    Text(
                                        text = platform.name,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            letterSpacing = (-0.2).sp,
                                        ),
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Clip,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (ui.history.isNotEmpty()) {
                val recent = remember(ui.history) { ui.history.take(3) }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Recent downloads",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            TextButton(
                                onClick = onViewAllHistory,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("View all (${ui.history.size})")
                            }
                        }
                        recent.forEach { item ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onOpenHistoryItem(item) },
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    HistoryThumbnailBox(
                                        item = item,
                                        modifier = Modifier
                                            .width(72.dp)
                                            .height(48.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${item.platform} · ${item.qualityLabel}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (item.contentUri != null) {
                                        Icon(
                                            Icons.Rounded.PlayArrow,
                                            contentDescription = "Open",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Rich Preview Hero Card
        ui.preview?.let { preview ->
            val isImageOnly = preview.videoOptions.isEmpty() && preview.imageUrls.isNotEmpty()
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (preview.thumbnailUrl != null) {
                            AsyncImage(
                                model = preview.thumbnailUrl,
                                contentDescription = preview.title,
                                contentScale = if (isImageOnly) ContentScale.Fit else ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text("MEDIA", style = MaterialTheme.typography.labelMedium)
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp),
                        ) {
                            Text(
                                text = preview.platform,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                        if (preview.durationText.isNotBlank() && preview.durationText != "Unknown") {
                            Surface(
                                color = Color.Black.copy(alpha = 0.72f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp),
                            ) {
                                Text(
                                    text = preview.durationText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = preview.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (preview.author.isNotBlank()) {
                            Text(
                                text = preview.author,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // Carousel photo selector
            if (preview.imageUrls.size > 1) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Photos: ${ui.selectedImageIndices.size}/${preview.imageUrls.size}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = onSelectAllImages) { Text("All") }
                                TextButton(onClick = onSelectNoneImages) { Text("None") }
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            preview.imageUrls.forEachIndexed { idx, imgUrl ->
                                val selected = idx in ui.selectedImageIndices
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    ),
                                    modifier = Modifier
                                        .width(110.dp)
                                        .clickable { onToggleImage(idx) },
                                ) {
                                    Box {
                                        AsyncImage(
                                            model = imgUrl,
                                            contentDescription = "Photo ${idx + 1}",
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(110.dp)
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                        )
                                        Checkbox(
                                            checked = selected,
                                            onCheckedChange = { onToggleImage(idx) },
                                            modifier = Modifier.align(Alignment.TopEnd),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Playlist item selector
            if (preview.isPlaylist && preview.playlistEntries.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Selected: ${ui.selectedPlaylistIndices.size}/${preview.playlistEntries.size}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = onSelectAllPlaylist) { Text("All") }
                                TextButton(onClick = onSelectNonePlaylist) { Text("None") }
                            }
                        }

                        OutlinedTextField(
                            value = ui.playlistRangeText,
                            onValueChange = onPlaylistRangeChanged,
                            label = { Text("Range (e.g. 1-3, 5)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            preview.playlistEntries.forEach { entry ->
                                val checked = entry.index in ui.selectedPlaylistIndices
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onTogglePlaylist(entry.index) }
                                        .padding(vertical = 4.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { onTogglePlaylist(entry.index) },
                                    )
                                    Text(
                                        text = "${entry.index}. ${entry.title}",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (entry.durationText.isNotEmpty()) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = entry.durationText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Format selection card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (preview.videoOptions.isNotEmpty()) {
                            FilterChip(
                                selected = ui.downloadMode == DownloadMode.VIDEO,
                                onClick = { onDownloadModeSelected(DownloadMode.VIDEO) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.VideoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = { Text(DownloadMode.VIDEO.label) },
                            )
                        }
                        if (preview.audioOptions.isNotEmpty()) {
                            FilterChip(
                                selected = ui.downloadMode == DownloadMode.AUDIO,
                                onClick = { onDownloadModeSelected(DownloadMode.AUDIO) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Audiotrack, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = { Text(DownloadMode.AUDIO.label) },
                            )
                        }
                        if (preview.imageUrls.isNotEmpty() || (preview.isPlaylist && preview.playlistEntries.any { !it.thumbnailUrl.isNullOrBlank() })) {
                            FilterChip(
                                selected = ui.downloadMode == DownloadMode.IMAGE,
                                onClick = { onDownloadModeSelected(DownloadMode.IMAGE) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = { Text(DownloadMode.IMAGE.label) },
                            )
                        }
                    }

                    when (ui.downloadMode) {
                        DownloadMode.VIDEO -> {
                            preview.videoOptions.forEach { opt ->
                                FormatOptionRow(
                                    selected = ui.selectedVideoHeight == opt.height,
                                    title = opt.label,
                                    badge = opt.badge,
                                    subtitle = opt.codecHint,
                                    trailing = ZarditEngine.formatFileSize(opt.approxSizeBytes),
                                    onClick = { onVideoHeightSelected(opt.height) },
                                )
                            }
                        }

                        DownloadMode.AUDIO -> {
                            preview.audioOptions.forEach { opt ->
                                FormatOptionRow(
                                    selected = ui.selectedAudioId == opt.id,
                                    title = opt.label,
                                    badge = if (opt.requiresFfmpeg) "MP3" else "",
                                    subtitle = opt.subtitle,
                                    trailing = ZarditEngine.formatFileSize(opt.approxSizeBytes),
                                    onClick = { onAudioOptionSelected(opt) },
                                )
                            }
                        }

                        DownloadMode.IMAGE -> {
                            ImageFormat.entries.forEach { fmt ->
                                FormatOptionRow(
                                    selected = ui.selectedImageFormat == fmt,
                                    title = fmt.label,
                                    badge = "",
                                    subtitle = fmt.subtitle,
                                    trailing = "",
                                    onClick = { onImageFormatSelected(fmt) },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Save folder + Download / Progress Card (shown when a preview or active/completed download exists)
        val isRunning = downloadState is DownloadState.Running
        val isPaused = downloadState is DownloadState.Paused
        val isJustCompleted = downloadState is DownloadState.Completed
        if (ui.preview != null || downloadState !is DownloadState.Idle) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Save to: ${ui.folderDisplayName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (ui.hasCustomFolder) {
                            TextButton(
                                onClick = onResetFolder,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("Default", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        OutlinedButton(
                            onClick = onPickFolder,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text("Change…", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }

                    if (!isJustCompleted && !isRunning) {
                        ui.existingDownloadPath?.let { existing ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = "Already saved: $existing",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    when (val state = downloadState) {
                        is DownloadState.Running -> {
                            if (state.progressPercent > 0f) {
                                LinearProgressIndicator(
                                    progress = { state.progressPercent / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = state.statusLine,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedButton(
                                    onClick = onPauseDownload,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.Pause,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Pause", maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = onCancelDownload,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Text("Cancel", maxLines = 1)
                                }
                            }
                        }

                        is DownloadState.Paused -> {
                            if (state.progressPercent > 0f) {
                                LinearProgressIndicator(
                                    progress = { (state.progressPercent / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Paused · ${state.statusLine}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Button(
                                    onClick = onResumeDownload,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Resume", maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = onCancelDownload,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Text("Cancel", maxLines = 1)
                                }
                            }
                        }

                        is DownloadState.Completed -> {
                            val prefix = if (state.alreadyExisted) "Already downloaded: " else "Saved to "
                            Text(
                                text = "$prefix${state.savedLocation}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            )
                            state.contentUri?.let { uriStr ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { onOpenCompleted(uriStr, state.fileName) },
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    ) {
                                        Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Open")
                                    }
                                    OutlinedButton(
                                        onClick = { onShareCompleted(uriStr) },
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    ) {
                                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Share")
                                    }
                                }
                            }
                        }

                        is DownloadState.Failed -> {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            OutlinedButton(
                                onClick = onCopyDiagnostics,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text("Copy Diagnostics", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        DownloadState.Cancelled -> {
                            Text(
                                text = "Download cancelled.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        DownloadState.Idle -> Unit
                    }

                    if (!isRunning && !isPaused && ui.preview != null && !isJustCompleted) {
                        val alreadyDownloaded = ui.existingDownloadPath != null
                        val playlistEmpty = ui.preview.isPlaylist && ui.selectedPlaylistIndices.isEmpty()
                        val imagesEmpty = ui.downloadMode == DownloadMode.IMAGE &&
                            ui.preview.imageUrls.isNotEmpty() &&
                            ui.selectedImageIndices.isEmpty()
                        Button(
                            onClick = onTriggerDownload,
                            enabled = !alreadyDownloaded && !playlistEmpty && !imagesEmpty,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = if (alreadyDownloaded) Icons.Rounded.CheckCircle else Icons.Rounded.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            val buttonText = when {
                                alreadyDownloaded -> "Already Downloaded"
                                ui.preview.isPlaylist -> "Download (${ui.selectedPlaylistIndices.size} selected)"
                                ui.downloadMode == DownloadMode.IMAGE && ui.preview.imageUrls.size > 1 ->
                                    "Download (${ui.selectedImageIndices.size} selected)"
                                else -> "Download"
                            }
                            Text(buttonText)
                        }
                    }
                }
            }
        }

        Spacer(
            modifier = Modifier
                .navigationBarsPadding()
                .height(82.dp),
        )
    }
}

@Composable
private fun FloatingBottomDock(
    activeTab: AppTab,
    historyCount: Int,
    downloadState: DownloadState,
    activeThumbnailUrl: String?,
    onTabSelected: (AppTab) -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActiveTransfer = downloadState is DownloadState.Running || downloadState is DownloadState.Paused

    AnimatedContent(
        targetState = isActiveTransfer,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "floating_dock_morph",
        modifier = modifier.fillMaxWidth(),
    ) { downloading ->
        if (downloading) {
            val title = when (downloadState) {
                is DownloadState.Running -> downloadState.title
                is DownloadState.Paused -> downloadState.title
                else -> "Downloading"
            }
            val progress = when (downloadState) {
                is DownloadState.Running -> downloadState.progressPercent
                is DownloadState.Paused -> downloadState.progressPercent
                else -> 0f
            }
            val subtitle = when (downloadState) {
                is DownloadState.Running -> downloadState.statusLine
                is DownloadState.Paused -> "Paused · ${downloadState.progressPercent.toInt()}%"
                else -> ""
            }
            val isPausedState = downloadState is DownloadState.Paused

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Circular Home pill
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    border = BorderStroke(
                        1.dp,
                        if (activeTab == AppTab.DOWNLOADER) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.85f)
                        },
                    ),
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .clickable { onTabSelected(AppTab.DOWNLOADER) },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Home,
                            contentDescription = "Home",
                            tint = if (activeTab == AppTab.DOWNLOADER) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                // Active transfer pill (thumbnail + title/progress + pause/resume button)
                Surface(
                    shape = RoundedCornerShape(30.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.85f)),
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .weight(1f)
                        .height(58.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .clickable { onTabSelected(AppTab.DOWNLOADER) },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 8.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (!activeThumbnailUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = activeThumbnailUrl,
                                contentDescription = title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = title.ifBlank { "Zardit Download" },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (progress > 0f) {
                                LinearProgressIndicator(
                                    progress = { (progress / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(2.dp)
                                        .clip(RoundedCornerShape(1.dp)),
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(2.dp)
                                        .clip(RoundedCornerShape(1.dp)),
                                )
                            }
                        }

                        IconButton(
                            onClick = { if (isPausedState) onResumeDownload() else onPauseDownload() },
                            modifier = Modifier.size(42.dp),
                        ) {
                            Icon(
                                imageVector = if (isPausedState) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                contentDescription = if (isPausedState) "Resume download" else "Pause download",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.85f)),
                shadowElevation = 12.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(5.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppTab.entries.forEach { tab ->
                        val selected = activeTab == tab
                        val icon = when (tab) {
                            AppTab.DOWNLOADER -> Icons.Rounded.Home
                            AppTab.HISTORY -> Icons.Rounded.History
                            AppTab.SETTINGS -> Icons.Rounded.Settings
                        }
                        val contentColor = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(27.dp))
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f)
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .clickable { onTabSelected(tab) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(1.dp),
                            ) {
                                if (tab == AppTab.HISTORY && historyCount > 0) {
                                    BadgedBox(
                                        badge = {
                                            Badge {
                                                Text(
                                                    text = if (historyCount > 99) "99+" else "$historyCount",
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            }
                                        },
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = tab.label,
                                            tint = contentColor,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = tab.label,
                                        tint = contentColor,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = contentColor,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormatOptionRow(
    selected: Boolean,
    title: String,
    badge: String,
    subtitle: String,
    trailing: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (badge.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (trailing.isNotEmpty()) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
