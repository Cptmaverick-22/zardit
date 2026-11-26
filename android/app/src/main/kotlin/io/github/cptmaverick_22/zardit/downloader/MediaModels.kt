package io.github.cptmaverick_22.zardit.downloader

enum class ThemeMode(val label: String) {
    AUTO("Auto"),
    LIGHT("Light"),
    DARK("Dark"),
}

enum class AppTab(val label: String) {
    DOWNLOADER("Downloader"),
    HISTORY("History"),
}

enum class DownloadMode(val label: String) {
    VIDEO("Video (MP4)"),
    AUDIO("Audio"),
    IMAGE("Image"),
}

enum class ImageFormat(val id: String, val label: String, val subtitle: String, val ext: String) {
    ORIGINAL("orig", "Original", "Native resolution · No re-encoding", "jpg"),
    PNG("png", "PNG", "Lossless · High quality", "png"),
    JPG("jpg", "JPG", "Universal · Compact", "jpg"),
    WEBP("webp", "WebP", "Modern · Smallest size", "webp"),
}

data class VideoOption(
    val height: Int,
    val label: String,
    val badge: String,
    val codecHint: String,
    val approxSizeBytes: Long?,
    val formatId: String? = null,
    val needsAudioMux: Boolean = false,
    val directUrl: String? = null,
)

data class AudioOption(
    val id: String,
    val label: String,
    val subtitle: String,
    val bitrateKbps: Int?,
    val isOriginal: Boolean = false,
    val approxSizeBytes: Long? = null,
)

data class PlaylistEntry(
    val index: Int,
    val id: String,
    val title: String,
    val url: String,
    val durationText: String,
    val thumbnailUrl: String?,
)

data class MediaPreview(
    val mediaId: String,
    val url: String,
    val title: String,
    val author: String,
    val platform: String,
    val durationText: String,
    val thumbnailUrl: String?,
    val videoOptions: List<VideoOption>,
    val audioOptions: List<AudioOption>,
    val imageUrls: List<String> = emptyList(),
    val isPlaylist: Boolean = false,
    val playlistEntries: List<PlaylistEntry> = emptyList(),
    val directVideoUrl: String? = null,
    val directAudioUrl: String? = null,
    val usedInstagramAuth: Boolean = false,
    val sanitizedTitle: String = title,
)

data class SaveResult(
    val fileName: String,
    val savedLocation: String,
    val contentUri: String?,
)

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(
        val title: String,
        val progressPercent: Float,
        val statusLine: String,
    ) : DownloadState

    data class Completed(
        val title: String,
        val savedLocation: String,
        val alreadyExisted: Boolean = false,
    ) : DownloadState

    data class Failed(
        val message: String,
    ) : DownloadState

    data object Cancelled : DownloadState
}
