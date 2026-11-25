package io.github.cptmaverick_22.zardit.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoLibrary
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import io.github.cptmaverick_22.zardit.data.HistoryItem
import io.github.cptmaverick_22.zardit.downloader.AppTab
import io.github.cptmaverick_22.zardit.downloader.DownloadMode
import io.github.cptmaverick_22.zardit.downloader.DownloadState
import io.github.cptmaverick_22.zardit.downloader.ImageFormat
import io.github.cptmaverick_22.zardit.downloader.ThemeMode
import io.github.cptmaverick_22.zardit.downloader.ZarditEngine

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

    fun cycleTheme() {
        val entries = ThemeMode.entries
        val next = entries[(entries.indexOf(ui.themeMode) + 1) % entries.size]
        viewModel.onThemeModeSelected(next)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                    text = "Media Downloader",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.clickable { cycleTheme() },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val themeIcon = when (ui.themeMode) {
                        ThemeMode.AUTO -> Icons.Rounded.BrightnessAuto
                        ThemeMode.LIGHT -> Icons.Rounded.LightMode
                        ThemeMode.DARK -> Icons.Rounded.DarkMode
                    }
                    Icon(themeIcon, contentDescription = "Theme", modifier = Modifier.size(16.dp))
                    Text(
                        text = ui.themeMode.label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            OutlinedButton(
                onClick = { viewModel.updateYtDlp() },
                enabled = !ui.isUpdatingYtDlp,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (ui.isUpdatingYtDlp) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "Engine",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        // Sleek Segmented Switcher: Downloader | History
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AppTab.entries.forEach { tab ->
                    val selected = ui.activeTab == tab
                    val label = if (tab == AppTab.HISTORY && ui.history.isNotEmpty()) {
                        "${tab.label} (${ui.history.size})"
                    } else {
                        tab.label
                    }
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(9.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .clickable { viewModel.onTabSelected(tab) },
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 9.dp, horizontal = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (tab == AppTab.DOWNLOADER) Icons.Rounded.Download else Icons.Rounded.History,
                                contentDescription = null,
                                tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        ui.updateBanner?.let { banner ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
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

        if (ui.activeTab == AppTab.DOWNLOADER) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Link input card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = ui.url,
                            onValueChange = viewModel::onUrlChanged,
                            label = { Text("Media link") },
                            placeholder = { Text("https://...") },
                            singleLine = true,
                            trailingIcon = {
                                if (ui.url.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.onUrlChanged("") }) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Clear")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    val pasted = clipboard.getText()?.text?.trim().orEmpty()
                                    if (pasted.isNotEmpty()) {
                                        viewModel.onUrlChanged(pasted)
                                        viewModel.fetch(pasted)
                                    }
                                },
                                enabled = !ui.isFetching,
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
                                onClick = { viewModel.fetch() },
                                enabled = !ui.isFetching,
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
                        }
                    }
                }

                // Preview Card
                ui.preview?.let { preview ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (preview.thumbnailUrl != null) {
                                AsyncImage(
                                    model = preview.thumbnailUrl,
                                    contentDescription = preview.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .width(124.dp)
                                        .height(72.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .width(124.dp)
                                        .height(72.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("MEDIA", style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = preview.platform,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = preview.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val meta = listOf(preview.author, preview.durationText)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · ")
                                Text(
                                    text = meta,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    // Playlist item selector
                    if (preview.isPlaylist && preview.playlistEntries.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            shape = RoundedCornerShape(14.dp),
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
                                        TextButton(onClick = viewModel::selectAllPlaylist) { Text("All") }
                                        TextButton(onClick = viewModel::selectNonePlaylist) { Text("None") }
                                    }
                                }

                                OutlinedTextField(
                                    value = ui.playlistRangeText,
                                    onValueChange = viewModel::onPlaylistRangeChanged,
                                    label = { Text("Range (e.g. 1-3, 5)") },
                                    singleLine = true,
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
                                                .clickable { viewModel.togglePlaylistIndex(entry.index) }
                                                .padding(vertical = 4.dp, horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Checkbox(
                                                checked = checked,
                                                onCheckedChange = { viewModel.togglePlaylistIndex(entry.index) },
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
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (preview.videoOptions.isNotEmpty()) {
                                    FilterChip(
                                        selected = ui.downloadMode == DownloadMode.VIDEO,
                                        onClick = { viewModel.onDownloadModeSelected(DownloadMode.VIDEO) },
                                        leadingIcon = {
                                            Icon(Icons.Rounded.VideoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                                        },
                                        label = { Text(DownloadMode.VIDEO.label) },
                                    )
                                }
                                if (preview.videoOptions.isNotEmpty() || preview.audioOptions.isNotEmpty()) {
                                    if (!preview.isPlaylist || preview.videoOptions.isNotEmpty()) {
                                        FilterChip(
                                            selected = ui.downloadMode == DownloadMode.AUDIO,
                                            onClick = { viewModel.onDownloadModeSelected(DownloadMode.AUDIO) },
                                            leadingIcon = {
                                                Icon(Icons.Rounded.Audiotrack, contentDescription = null, modifier = Modifier.size(16.dp))
                                            },
                                            label = { Text(DownloadMode.AUDIO.label) },
                                        )
                                    }
                                }
                                if (preview.imageUrls.isNotEmpty()) {
                                    FilterChip(
                                        selected = ui.downloadMode == DownloadMode.IMAGE,
                                        onClick = { viewModel.onDownloadModeSelected(DownloadMode.IMAGE) },
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
                                            onClick = { viewModel.onVideoHeightSelected(opt.height) },
                                        )
                                    }
                                }

                                DownloadMode.AUDIO -> {
                                    preview.audioOptions.forEach { opt ->
                                        FormatOptionRow(
                                            selected = ui.selectedAudioId == opt.id,
                                            title = opt.label,
                                            badge = "",
                                            subtitle = opt.subtitle,
                                            trailing = ZarditEngine.formatFileSize(opt.approxSizeBytes),
                                            onClick = { viewModel.onAudioOptionSelected(opt) },
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
                                            onClick = { viewModel.onImageFormatSelected(fmt) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Save folder + Download / Progress Card
                val isRunning = downloadState is DownloadState.Running
                val isJustCompleted = downloadState is DownloadState.Completed
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(14.dp),
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
                                    onClick = { viewModel.resetFolder() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Text("Default", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            OutlinedButton(
                                onClick = { folderPickerLauncher.launch(null) },
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
                                    OutlinedButton(onClick = { viewModel.cancelDownload() }) {
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
                            }

                            is DownloadState.Failed -> {
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
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

                        if (!isRunning && ui.preview != null && !isJustCompleted) {
                            val alreadyDownloaded = ui.existingDownloadPath != null
                            val playlistEmpty = ui.preview?.isPlaylist == true && ui.selectedPlaylistIndices.isEmpty()
                            Button(
                                onClick = { triggerDownload() },
                                enabled = !alreadyDownloaded && !playlistEmpty,
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
                                    ui.preview?.isPlaylist == true -> "Download (${ui.selectedPlaylistIndices.size} selected)"
                                    else -> "Download"
                                }
                                Text(buttonText)
                            }
                        }
                    }
                }
            }
        } else {
            HistoryTab(
                items = ui.history,
                onOpen = { item ->
                    item.contentUri?.let { uriStr ->
                        runCatching {
                            val uri = Uri.parse(uriStr)
                            val mime = when {
                                item.fileName.endsWith(".mp3", true) -> "audio/mpeg"
                                item.fileName.endsWith(".m4a", true) -> "audio/mp4"
                                item.fileName.endsWith(".png", true) -> "image/png"
                                item.fileName.endsWith(".webp", true) -> "image/webp"
                                item.fileName.endsWith(".jpg", true) || item.fileName.endsWith(".jpeg", true) -> "image/jpeg"
                                else -> "video/mp4"
                            }
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mime)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Open with"))
                        }
                    }
                },
                onShare = { item ->
                    item.contentUri?.let { uriStr ->
                        runCatching {
                            val uri = Uri.parse(uriStr)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "*/*"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share media"))
                        }
                    }
                },
                onRemove = viewModel::removeHistoryItem,
                onClearAll = viewModel::clearHistory,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HistoryTab(
    items: List<HistoryItem>,
    onOpen: (HistoryItem) -> Unit,
    onShare: (HistoryItem) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (items.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "No downloads yet. Downloaded videos, audio, and images will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            return
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${items.size} saved item${if (items.size == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onClearAll) {
                Text("Clear history")
            }
        }

        items.forEach { item ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = "${item.platform} · ${item.qualityLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.savedLocation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (item.contentUri != null) {
                            OutlinedButton(
                                onClick = { onOpen(item) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Open", style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(
                                onClick = { onShare(item) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Share", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { onRemove(item.id) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Remove")
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
        shape = RoundedCornerShape(10.dp),
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
