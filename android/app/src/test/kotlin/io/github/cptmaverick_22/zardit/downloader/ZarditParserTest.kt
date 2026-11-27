package io.github.cptmaverick_22.zardit.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZarditParserTest {

    @Test
    fun isSafePublicUrl_blocksLocalPrivateAndCredentialUrls() {
        val blocked = listOf(
            "",
            "   ",
            "ftp://www.youtube.com/watch?v=123",
            "javascript:alert(1)",
            "http://127.0.0.1/",
            "http://127.0.0.1:8080/video.mp4",
            "http://localhost/",
            "https://localhost.localdomain/test",
            "http://192.168.1.1/",
            "http://10.0.0.5/media",
            "http://172.16.0.10/media",
            "http://169.254.169.254/latest/meta-data",
            "http://100.64.0.1/status",
            "http://[::1]/",
            "http://[fe80::1]/",
            "http://[fd00::1]/",
            "http://example.com@127.0.0.1/",
            "https://user:pass@youtube.com/watch?v=1",
            "http://printer.local/media",
            "http://intranet/",
        )
        for (url in blocked) {
            assertFalse("Expected URL to be blocked: $url", ZarditParser.isSafePublicUrl(url))
        }

        val allowed = listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://www.instagram.com/share/BAWRAVe3vK",
            "https://www.threads.net/@user/post/C8abc123",
            "https://8.8.8.8/public.mp4",
        )
        for (url in allowed) {
            assertTrue("Expected URL to be allowed: $url", ZarditParser.isSafePublicUrl(url))
        }
    }

    @Test
    fun parseDashManifest_prefersBestCodecAndComputesTotalSize() {
        val mpd = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period>
              <AdaptationSet contentType="video">
                <Representation id="v1080_vp9" mimeType="video/mp4" codecs="vp09.00.40.08"
                    width="1080" height="1920" bandwidth="2200000" FBContentLength="3000000">
                  <BaseURL>https://cdn.example.com/1080_vp9.mp4</BaseURL>
                </Representation>
                <Representation id="v1080_h264" mimeType="video/mp4" codecs="avc1.64002a"
                    width="1080" height="1920" bandwidth="2500000" FBContentLength="3200000">
                  <BaseURL>https://cdn.example.com/1080_h264.mp4</BaseURL>
                </Representation>
                <Representation id="v720" mimeType="video/mp4" codecs="avc1.4d401f"
                    width="720" height="1280" bandwidth="1200000" FBContentLength="1500000">
                  <BaseURL>https://cdn.example.com/720.mp4</BaseURL>
                </Representation>
                <Representation id="v_local" mimeType="video/mp4" codecs="avc1.4d401f"
                    width="1440" height="2560" bandwidth="5000000">
                  <BaseURL>http://127.0.0.1/ssrf.mp4</BaseURL>
                </Representation>
              </AdaptationSet>
              <AdaptationSet contentType="audio">
                <Representation id="a1" mimeType="audio/mp4" codecs="mp4a.40.5"
                    bandwidth="64000" FBContentLength="200000">
                  <BaseURL>https://cdn.example.com/audio.m4a</BaseURL>
                </Representation>
              </AdaptationSet>
            </Period></MPD>
        """.trimIndent()

        val parsed = ZarditParser.parseDashManifest(mpd)
        assertNotNull(parsed)
        assertEquals(listOf(1080, 720), parsed!!.videoOptions.map { it.height })
        assertEquals("https://cdn.example.com/1080_h264.mp4", parsed.videoOptions[0].directUrl)
        assertEquals(3_400_000L, parsed.videoOptions[0].approxSizeBytes)
        assertEquals("https://cdn.example.com/audio.m4a", parsed.audioUrl)
        assertTrue(parsed.videoOptions[0].needsAudioMux)
    }

    @Test
    fun classifyVideoTier_handlesPortraitAndUltrawide() {
        val buckets = listOf(2160, 1440, 1080, 720, 480, 360)
        assertEquals(1080, ZarditParser.classifyVideoTier(1080, 1920, "", buckets))
        assertEquals(1080, ZarditParser.classifyVideoTier(1920, 866, "", buckets))
        assertEquals(1440, ZarditParser.classifyVideoTier(2560, 1156, "", buckets))
        assertEquals(720, ZarditParser.classifyVideoTier(0, 0, "720p", buckets))
        assertNull(ZarditParser.classifyVideoTier(10, 10, "", buckets))
    }

    @Test
    fun matchesExistingDownloadName_deduplicatesShareTokensAndShortcodes() {
        val shareFile = "Video by alice [720p] [DNRGa-5S8p7c933kqqQH1UK0cmn2PQCh3NlFSk0].mp4"
        assertTrue(ZarditParser.matchesExistingDownloadName(shareFile, "DNRGa-5S8p7", "720p", "mp4"))
        assertTrue(
            ZarditParser.matchesExistingDownloadName(
                "Clip [1080p] [DNRGa-5S8p7].mp4",
                "DNRGa-5S8p7c933kqqQH1UK0cmn2PQCh3NlFSk0",
                "1080p",
                "mp4",
            ),
        )
        assertFalse(ZarditParser.matchesExistingDownloadName(shareFile, "DNRGa-5S8p7", "1080p", "mp4"))
        assertFalse(ZarditParser.matchesExistingDownloadName(shareFile, "DNRGa-5S8p7", "720p", "m4a"))
    }

    @Test
    fun cleanInstagramTitleAndSanitizeTitle_stripBoilerplateAndUnsafeChars() {
        val cleaned = ZarditParser.cleanInstagramTitle(
            "42 likes - alice on Aug 6: \"Code. Compile.\n\n#coding #viral #reels\"",
            username = "alice",
            mediaId = "ABC123",
        )
        assertEquals("Code. Compile", cleaned)
        assertEquals("Part 1 - Live", ZarditParser.sanitizeTitle("Part 1: *Live* #tag", "fallback"))
        assertEquals("fallback", ZarditParser.sanitizeTitle("///???", "fallback"))
    }

    @Test
    fun parseRangeSelection_handlesRangesClampingAndBlanks() {
        assertEquals(setOf(1, 2, 3, 4, 5), ZarditParser.parseRangeSelection("", 5))
        assertEquals(setOf(1, 2, 3, 5), ZarditParser.parseRangeSelection("1-3, 5", 8))
        assertEquals(setOf(3, 4, 5), ZarditParser.parseRangeSelection("5-3", 6))
        assertEquals(emptySet<Int>(), ZarditParser.parseRangeSelection("abc, 99", 5))
    }

    @Test
    fun extractMinimalInstagramCookies_keepsOnlyRequiredSessionFields() {
        val header = "mid=abc; sessionid=secret_sess; csrftoken=tok_1; ds_user_id=99; ig_did=noise"
        val map = ZarditParser.extractMinimalInstagramCookies(header)
        assertEquals(
            mapOf("sessionid" to "secret_sess", "csrftoken" to "tok_1", "ds_user_id" to "99"),
            map,
        )
        assertTrue(ZarditParser.extractMinimalInstagramCookies("csrftoken=only_csrf").isEmpty())
    }
}
