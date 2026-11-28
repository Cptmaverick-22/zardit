package io.github.cptmaverick_22.zardit.downloader

import io.github.cptmaverick_22.zardit.ui.main.MainScreenViewModel
import io.github.cptmaverick_22.zardit.ui.main.MainUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegAddonManagerTest {

    @Test
    fun `matches Android FFmpeg addon asset names only`() {
        assertTrue(FfmpegAddonManager.isMatchingFfmpegAssetName("Zardit-FFmpeg-Addon-1.5.1-android-arm64.apk"))
        assertTrue(FfmpegAddonManager.isMatchingFfmpegAssetName("ffmpeg-android-arm64-v8a.apk"))
        assertFalse(FfmpegAddonManager.isMatchingFfmpegAssetName("ffmpeg-android-arm64"))
        assertFalse(FfmpegAddonManager.isMatchingFfmpegAssetName("Zardit-FFmpeg-Addon-1.5.1-android-arm64.apk.sha256"))
        assertFalse(FfmpegAddonManager.isMatchingFfmpegAssetName("Zardit-1.5.1-android-arm64.apk"))
        assertFalse(FfmpegAddonManager.isMatchingFfmpegAssetName("SHA256SUMS.txt"))
    }

    @Test
    fun `qualityTag and targetExtension support MP3 addon options`() {
        val mp3High = ZarditEngine.findAudioOption("mp3_320")!!
        assertTrue(mp3High.requiresFfmpeg)
        assertEquals("320kbps", ZarditEngine.qualityTag(DownloadMode.AUDIO, null, mp3High))
        assertEquals("mp3", ZarditEngine.targetExtension(DownloadMode.AUDIO, mp3High))

        val mp3Mid = ZarditEngine.findAudioOption("mp3_256")!!
        assertTrue(mp3Mid.requiresFfmpeg)
        assertEquals("256kbps", ZarditEngine.qualityTag(DownloadMode.AUDIO, null, mp3Mid))

        val mp3Low = ZarditEngine.findAudioOption("mp3_128")!!
        assertTrue(mp3Low.requiresFfmpeg)
        assertEquals("128kbps", ZarditEngine.qualityTag(DownloadMode.AUDIO, null, mp3Low))

        val orig = ZarditEngine.findAudioOption("orig_m4a")!!
        assertFalse(orig.requiresFfmpeg)
        assertEquals("original", ZarditEngine.qualityTag(DownloadMode.AUDIO, null, orig))
        assertEquals("m4a", ZarditEngine.targetExtension(DownloadMode.AUDIO, orig))
    }

    @Test
    fun `app tabs include Home, History, and Settings`() {
        assertEquals(listOf("Home", "History", "Settings"), AppTab.entries.map { it.label })
    }

    @Test
    fun `reddit urls are flagged as unsupported`() {
        assertTrue(ZarditParser.isUnsupportedRedditUrl("https://www.reddit.com/r/videos/comments/abc/"))
        assertTrue(ZarditParser.isUnsupportedRedditUrl("https://v.redd.it/xyz123"))
        assertFalse(ZarditParser.isUnsupportedRedditUrl("https://www.threads.net/@user/post/abc"))
        assertEquals("Web", ZarditParser.detectPlatformName("https://www.reddit.com/r/videos/comments/abc/"))
    }

    @Test
    fun `paused download state preserves title progress and status`() {
        val paused = DownloadState.Paused(
            title = "Sample Video",
            progressPercent = 42.5f,
            statusLine = "42% · 1.2 MB/s",
        )
        assertEquals("Sample Video", paused.title)
        assertEquals(42.5f, paused.progressPercent)
        assertEquals("42% · 1.2 MB/s", paused.statusLine)
    }

    @Test
    fun `unseen history badge increments only outside history tab`() {
        assertEquals(0, MainUiState().unseenHistoryCount)
        assertEquals(
            1,
            MainScreenViewModel.computeNextUnseenCount(
                activeTab = AppTab.DOWNLOADER,
                currentUnseen = 0,
                addedCount = 1,
                alreadyExisted = false,
            ),
        )
        assertEquals(
            3,
            MainScreenViewModel.computeNextUnseenCount(
                activeTab = AppTab.SETTINGS,
                currentUnseen = 1,
                addedCount = 2,
                alreadyExisted = false,
            ),
        )
        assertEquals(
            0,
            MainScreenViewModel.computeNextUnseenCount(
                activeTab = AppTab.HISTORY,
                currentUnseen = 2,
                addedCount = 1,
                alreadyExisted = false,
            ),
        )
        assertEquals(
            1,
            MainScreenViewModel.computeNextUnseenCount(
                activeTab = AppTab.DOWNLOADER,
                currentUnseen = 1,
                addedCount = 1,
                alreadyExisted = true,
            ),
        )
    }
}
