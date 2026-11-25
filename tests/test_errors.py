import errno
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from downloader import DownloadError, _check_disk_space, friendly_error
from logs import diagnostics_text


def message_for(text):
    return friendly_error(Exception(text))


class FriendlyErrorTests(unittest.TestCase):
    def test_messages(self):
        cases = [
            ("Sign in to confirm your age. This video may be inappropriate",
             "age-restricted"),
            ("Sign in to confirm you're not a bot. Use --cookies-from-browser",
             "not a bot"),
            ("This video is available to this channel's members only. "
             "Join this channel to get access", "channel members"),
            ("Private video. Sign in if you've been granted access",
             "private"),
            ("Sign in required", "signed-in account"),
            ("Video unavailable. The uploader has not made this video "
             "available in your country", "your country"),
            ("Premieres in 3 hours", "premiere"),
            ("Video unavailable. This video contains content from X, who "
             "has blocked it on copyright grounds", "copyright"),
            ("Requested format is not available", "quality"),
            ("Unsupported URL: https://example.com", "isn't a video"),
            ("[youtube] abc: Video unavailable", "unavailable"),
            ("No supported JavaScript runtime could be found",
             "JavaScript"),
            ("unable to download video data: HTTP Error 403: Forbidden",
             "403"),
            ("HTTP Error 429: Too Many Requests", "429"),
            ("HTTP Error 503: Service Unavailable", "server problem"),
            ("[Errno 28] No space left on device", "disk is full"),
            ("[Errno 13] Permission denied: 'x'", "permission"),
            ("[Errno 36] File name too long", "too long"),
            ("Postprocessing: ffprobe and ffmpeg not found", "FFmpeg"),
            ("<urlopen error [Errno -2] Name or service not known>",
             "Network error"),
            ("The read operation timed out", "Network error"),
        ]

        for raw, expected in cases:
            with self.subTest(raw=raw):
                self.assertIn(expected.lower(), message_for(raw).lower())

    def test_colour_codes_and_prefix_are_removed(self):
        text = message_for(
            "\x1b[0;31mERROR:\x1b[0m [youtube] abc: Video unavailable"
        )
        self.assertNotIn("\x1b", text)
        self.assertNotIn("ERROR", text)

    def test_unknown_error_keeps_the_original_text(self):
        self.assertEqual(
            message_for("something odd"), "Download failed: something odd"
        )

    def test_os_errors_are_recognised_by_number(self):
        full = OSError(errno.ENOSPC, "whatever")
        denied = OSError(errno.EACCES, "whatever")

        self.assertIn("disk is full", friendly_error(full).lower())
        self.assertIn("permission", friendly_error(denied).lower())

    def test_specific_rules_win_over_general_ones(self):
        # Private videos also say "sign in", and age checks say "sign in".
        self.assertIn("private", message_for(
            "Private video. Sign in if you've been granted access").lower())
        self.assertIn("age-restricted", message_for(
            "Sign in to confirm your age").lower())


class DiskSpaceTests(unittest.TestCase):
    def test_huge_download_is_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(DownloadError) as caught:
                _check_disk_space(Path(folder), 10 ** 18, "video")

            self.assertIn("free disk space", str(caught.exception))

    def test_unknown_size_is_allowed(self):
        with tempfile.TemporaryDirectory() as folder:
            _check_disk_space(Path(folder), None, "video")

    def test_small_download_is_allowed(self):
        with tempfile.TemporaryDirectory() as folder:
            _check_disk_space(Path(folder), 1000, "audio")


class DiagnosticsTests(unittest.TestCase):
    def test_report_contents_and_privacy(self):
        text = diagnostics_text()

        self.assertIn("yt-dlp", text)
        self.assertIn("FFmpeg:", text)
        self.assertIn("Deno:", text)
        self.assertNotIn(str(Path.home()), text)


if __name__ == "__main__":
    unittest.main()