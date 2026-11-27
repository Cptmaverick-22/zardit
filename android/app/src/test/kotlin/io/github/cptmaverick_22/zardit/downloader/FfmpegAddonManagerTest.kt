package io.github.cptmaverick_22.zardit.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegAddonManagerTest {

    @Test
    fun `matches Android FFmpeg addon asset names only`() {
        assertTrue(FfmpegAddonManager.isMatchingFfmpegAssetName("ffmpeg-android-arm64"))
        assertTrue(FfmpegAddonManager.isMatchingFfmpegAssetName("ffmpeg-android-arm64-v8a.bin"))
        assertFalse(FfmpegAddonManager.isMatchingFfmpegAssetName("ffmpeg-android-arm64.sha256"))
        assertFalse(FfmpegAddonManager.isMatchingFfmpegAssetName("Zardit-1.4.2-android-arm64.apk"))
        assertFalse(FfmpegAddonManager.isMatchingFfmpegAssetName("SHA256SUMS.txt"))
    }

    @Test
    fun `qualityTag and targetExtension support MP3 addon options`() {
        val mp3High = ZarditEngine.findAudioOption("mp3_320")!!
        assertTrue(mp3High.requiresFfmpeg)
        assertEquals("320kbps", ZarditEngine.qualityTag(DownloadMode.AUDIO, null, mp3High))
        assertEquals("mp3", ZarditEngine.targetExtension(DownloadMode.AUDIO, mp3High))

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
}
