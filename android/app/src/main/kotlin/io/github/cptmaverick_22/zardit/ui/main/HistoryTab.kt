package io.github.cptmaverick_22.zardit.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.cptmaverick_22.zardit.data.HistoryItem
import io.github.cptmaverick_22.zardit.downloader.ZarditEngine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal enum class HistoryFilter(val label: String) {
    ALL("All"),
    VIDEO("Video"),
    AUDIO("Audio"),
    IMAGE("Image"),
}

internal fun openHistoryMedia(context: Context, item: HistoryItem) {
    val uriStr = item.contentUri ?: return
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

internal fun shareHistoryMedia(context: Context, item: HistoryItem) {
    val uriStr = item.contentUri ?: return
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

internal fun historyMediaKind(item: HistoryItem): HistoryFilter {
    val lower = item.platform.uppercase(Locale.US)
    val fileLower = item.fileName.lowercase(Locale.US)
    return when {
        "AUDIO" in lower || fileLower.endsWith(".mp3") || fileLower.endsWith(".m4a") -> HistoryFilter.AUDIO
        "IMAGE" in lower || fileLower.endsWith(".jpg") || fileLower.endsWith(".jpeg") ||
            fileLower.endsWith(".png") || fileLower.endsWith(".webp") -> HistoryFilter.IMAGE
        else -> HistoryFilter.VIDEO
    }
}

private fun formatDateSectionLabel(timestampMs: Long): String {
    if (timestampMs <= 0L) return "Earlier"
    val cal = Calendar.getInstance()
    val todayYear = cal.get(Calendar.YEAR)
    val todayDay = cal.get(Calendar.DAY_OF_YEAR)

    val itemCal = Calendar.getInstance().apply { timeInMillis = timestampMs }
    val itemYear = itemCal.get(Calendar.YEAR)
    val itemDay = itemCal.get(Calendar.DAY_OF_YEAR)

    return when {
        itemYear == todayYear && itemDay == todayDay -> "Today"
        itemYear == todayYear && itemDay == todayDay - 1 -> "Yesterday"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(timestampMs))
    }
}

@Composable
internal fun HistoryThumbnailBox(
    item: HistoryItem,
    modifier: Modifier = Modifier,
) {
    val kind = remember(item.id, item.platform, item.fileName) { historyMediaKind(item) }
    val thumbFile = remember(item.thumbnailPath) {
        item.thumbnailPath?.let { File(it) }?.takeIf { it.isFile }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbFile != null) {
            AsyncImage(
                model = thumbFile,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val icon = when (kind) {
                HistoryFilter.AUDIO -> Icons.Rounded.Audiotrack
                HistoryFilter.IMAGE -> Icons.Rounded.Image
                else -> Icons.Rounded.VideoLibrary
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
internal fun HistoryTab(
    items: List<HistoryItem>,
    onOpen: (HistoryItem) -> Unit,
    onShare: (HistoryItem) -> Unit,
    onRemove: (HistoryItem) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf(HistoryFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(items, filter, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.US)
        items.filter { item ->
            val kindOk = filter == HistoryFilter.ALL || historyMediaKind(item) == filter
            val searchOk = q.isEmpty() ||
                q in item.title.lowercase(Locale.US) ||
                q in item.platform.lowercase(Locale.US) ||
                q in item.qualityLabel.lowercase(Locale.US)
            kindOk && searchOk
        }
    }

    val grouped = remember(filtered) {
        filtered.groupBy { formatDateSectionLabel(it.timestampMs) }
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (items.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "No downloads yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Downloaded videos, audio tracks, and images will appear here with thumbnails so you can play or share anytime.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${filtered.size} of ${items.size} saved",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onClearAll) {
                Text("Clear all")
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search downloads…") },
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HistoryFilter.entries.forEach { candidate ->
                FilterChip(
                    selected = filter == candidate,
                    onClick = { filter = candidate },
                    label = { Text(candidate.label) },
                )
            }
        }

        if (filtered.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "No matching items. Try another filter or search term.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            return
        }

        grouped.forEach { (dateLabel, groupItems) ->
            Text(
                text = dateLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            groupItems.forEach { item ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            HistoryThumbnailBox(
                                item = item,
                                modifier = Modifier
                                    .width(96.dp)
                                    .height(64.dp),
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
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
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                        )
                                    }
                                    item.fileSizeBytes?.let { bytes ->
                                        Text(
                                            text = ZarditEngine.formatFileSize(bytes),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
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
                            }
                        }
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
                            IconButton(onClick = { onRemove(item) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Remove")
                            }
                        }
                    }
                }
            }
        }
    }
}
