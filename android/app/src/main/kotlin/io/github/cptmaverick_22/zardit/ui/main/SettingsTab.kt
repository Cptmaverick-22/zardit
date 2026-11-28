package io.github.cptmaverick_22.zardit.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cptmaverick_22.zardit.BuildConfig
import io.github.cptmaverick_22.zardit.downloader.ThemeMode
import io.github.cptmaverick_22.zardit.downloader.ZarditEngine

private val DEFAULT_QUALITY_CHOICES = listOf(2160, 1440, 1080, 720, 480)

@Composable
internal fun SettingsTab(
    ui: MainUiState,
    onPickFolder: () -> Unit,
    onResetFolder: () -> Unit,
    onDefaultQualitySelected: (Int) -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onManageAccounts: () -> Unit,
    onInstallFfmpeg: () -> Unit,
    onRemoveFfmpeg: () -> Unit,
    onOpenUpdates: () -> Unit,
    onAutoCheckUpdatesChanged: (Boolean) -> Unit,
    onUpdateYtDlp: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Downloads section
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Downloads",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Download location",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = ui.folderDisplayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (ui.hasCustomFolder) {
                        TextButton(
                            onClick = onResetFolder,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("Reset", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    OutlinedButton(
                        onClick = onPickFolder,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("Change", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Preferred video quality",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DEFAULT_QUALITY_CHOICES.forEach { height ->
                            FilterChip(
                                selected = ui.defaultVideoQuality == height,
                                onClick = { onDefaultQualitySelected(height) },
                                label = { Text("${height}p") },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Rounded.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Wi-Fi only downloads",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Pause starting new downloads on cellular data",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = ui.wifiOnlyDownloads,
                        onCheckedChange = onWifiOnlyChanged,
                    )
                }
            }
        }

        // Appearance section
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
                    text = "Appearance",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeMode.entries.forEach { mode ->
                        val icon = when (mode) {
                            ThemeMode.AUTO -> Icons.Rounded.BrightnessAuto
                            ThemeMode.LIGHT -> Icons.Rounded.LightMode
                            ThemeMode.DARK -> Icons.Rounded.DarkMode
                        }
                        FilterChip(
                            selected = ui.themeMode == mode,
                            onClick = { onThemeModeSelected(mode) },
                            leadingIcon = {
                                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            label = { Text(mode.label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // Optional FFmpeg Addon section
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Rounded.Audiotrack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "FFmpeg Audio Addon",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    val sizeLabel = ui.ffmpegSizeBytes?.let { ZarditEngine.formatFileSize(it) }
                    Text(
                        text = if (ui.ffmpegInstalled) {
                            "Installed" + (sizeLabel?.let { " · $it" } ?: "")
                        } else {
                            "Optional"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (ui.ffmpegInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = "Base APK stays lightweight using Media3. Add the optional Zardit FFmpeg companion plugin APK (SHA-256 verified) for 128–320 kbps MP3, embedded cover art, and fallback muxing — uninstall cleanly anytime.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ui.ffmpegStatus?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (ui.isInstallingFfmpeg) {
                    LinearProgressIndicator(
                        progress = { ui.ffmpegDownloadPct / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ui.ffmpegInstalled) {
                        OutlinedButton(
                            onClick = onRemoveFfmpeg,
                            enabled = !ui.isInstallingFfmpeg,
                        ) {
                            Text("Remove Addon")
                        }
                    } else {
                        Button(
                            onClick = onInstallFfmpeg,
                            enabled = !ui.isInstallingFfmpeg,
                        ) {
                            Text(if (ui.isInstallingFfmpeg) "Installing ${ui.ffmpegDownloadPct}%…" else "Install FFmpeg Addon")
                        }
                    }
                }
            }
        }

        // Accounts, Updates & Storage
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
                    text = "Accounts & Updates",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Rounded.ManageAccounts, contentDescription = null, modifier = Modifier.size(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Account Connections",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val connectedText = buildList {
                            if (ui.isInstagramConnected) add("Instagram")
                            if (ui.isFacebookConnected) add("Facebook")
                        }.joinToString(" & ").ifEmpty { "None connected · Public links ready" }
                        Text(
                            text = connectedText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = onManageAccounts,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("Manage", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Rounded.SystemUpdate, contentDescription = null, modifier = Modifier.size(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "App Updates (v${BuildConfig.VERSION_NAME})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (ui.appUpdateInfo?.available == true) {
                                "Update available: ${ui.appUpdateInfo.tagName}"
                            } else {
                                "Daily background check"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ui.appUpdateInfo?.available == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Switch(
                        checked = ui.autoCheckUpdates,
                        onCheckedChange = onAutoCheckUpdatesChanged,
                    )
                    OutlinedButton(
                        onClick = onOpenUpdates,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("Check", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onUpdateYtDlp,
                        enabled = !ui.isUpdatingYtDlp,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (ui.isUpdatingYtDlp) "Updating…" else "Update Engine", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onCopyDiagnostics,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Diagnostics", maxLines = 1)
                    }
                }

                if (ui.history.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onClearHistory,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clear Download History (${ui.history.size})")
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
