import sys
import tempfile
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from downloader import (
    DownloadCancelled,
    DownloadError,
    PlaylistLinkError,
    ProgressTracker,
    Reporter,
    cleanup_incomplete_files,
    detect_platform,
    format_duration,
    format_file_size,
    has_playlist_and_video,
    is_mix_list,
    is_video_entry,
    parse_range,
    sanitize_name,
    validate_url,
    validate_youtube_url,
)


class SanitizeNameTests(unittest.TestCase):
    def test_replaces_and_removes_unsafe_characters(self):
        self.assertEqual(
            sanitize_name('Part 1: What?? | "Best" *Live*...'),
            "Part 1 - What - 'Best' Live",
        )

    def test_slashes_become_dashes(self):
        self.assertEqual(sanitize_name("a/b\\c"), "a-b-c")

    def test_reserved_windows_names(self):
        self.assertEqual(sanitize_name("CON"), "_CON")

    def test_empty_uses_fallback(self):
        self.assertEqual(sanitize_name(""), "untitled")
        self.assertEqual(sanitize_name("???", fallback="x"), "x")

    def test_leading_and_trailing_dots(self):
        self.assertEqual(sanitize_name("..hidden."), "hidden")

    def test_invisible_characters_removed(self):
        self.assertEqual(sanitize_name("a\u200bb"), "ab")

    def test_length_limit_in_bytes(self):
        self.assertEqual(len(sanitize_name("a" * 300)), 150)

    def test_never_cuts_a_character_in_half(self):
        result = sanitize_name("ह" * 100, max_bytes=10)
        self.assertEqual(result, "ह" * 3)
        self.assertLessEqual(len(result.encode("utf-8")), 10)


class ParseRangeTests(unittest.TestCase):
    def test_empty_means_everything(self):
        self.assertEqual(parse_range("", 5), {1, 2, 3, 4, 5})
        self.assertEqual(parse_range("  ", 5), {1, 2, 3, 4, 5})

    def test_mixed_selection(self):
        self.assertEqual(parse_range("1-3, 5", 10), {1, 2, 3, 5})

    def test_open_ended_ranges(self):
        self.assertEqual(parse_range("-4", 10), {1, 2, 3, 4})
        self.assertEqual(parse_range("7-", 10), {7, 8, 9, 10})

    def test_end_is_clamped_to_total(self):
        self.assertEqual(parse_range("5-999", 10), set(range(5, 11)))

    def test_duplicates_collapse(self):
        self.assertEqual(parse_range("2, 2", 10), {2})

    def test_empty_parts_are_skipped(self):
        self.assertEqual(parse_range("1,,3", 10), {1, 3})

    def test_invalid_input(self):
        for text in ("0", "999", "3-1", "abc", "1-a", "0-3", ",", "1-3, 999-1000"):
            with self.subTest(text=text):
                self.assertIsNone(parse_range(text, 10))


class LinkTests(unittest.TestCase):
    def test_validate_youtube_url(self):
        good = (
            "https://youtu.be/abc",
            "https://www.youtube.com/watch?v=abc",
            "http://music.youtube.com/watch?v=abc",
        )
        bad = (
            "",
            "ftp://youtube.com/x",
            "https://notyoutube.com/x",
            "https://youtube.com.evil.com/x",
            "not a link",
        )
        for url in good:
            self.assertTrue(validate_youtube_url(url), url)
        for url in bad:
            self.assertFalse(validate_youtube_url(url), url)

    def test_video_inside_playlist(self):
        self.assertTrue(has_playlist_and_video(
            "https://www.youtube.com/watch?v=abc123abc12&list=PLxyz"))
        self.assertTrue(has_playlist_and_video(
            "https://youtu.be/abc123abc12?list=PLxyz"))
        self.assertTrue(has_playlist_and_video(
            "https://www.youtube.com/watch?v=abc&list=RDCLAKxyz"))

    def test_not_a_video_inside_a_playlist(self):
        self.assertFalse(has_playlist_and_video(
            "https://www.youtube.com/playlist?list=PLxyz"))
        self.assertFalse(has_playlist_and_video(
            "https://www.youtube.com/watch?v=abc"))
        self.assertFalse(has_playlist_and_video(
            "https://example.com/watch?v=a&list=PL1"))

    def test_mix_lists_are_not_playlists(self):
        self.assertFalse(has_playlist_and_video(
            "https://www.youtube.com/watch?v=abc&list=RDabc"))
        self.assertTrue(is_mix_list("RDabc"))
        self.assertTrue(is_mix_list("RDMM"))
        self.assertFalse(is_mix_list("RDCLAKxyz"))
        self.assertFalse(is_mix_list("PLabc"))
        self.assertFalse(is_mix_list(None))

    def test_video_entries(self):
        self.assertTrue(is_video_entry({"id": "dQw4w9WgXcQ", "ie_key": "Youtube"}))
        self.assertTrue(is_video_entry({"id": "dQw4w9WgXcQ"}))
        self.assertTrue(is_video_entry({"id": "ig_12345", "ie_key": "Instagram"}))
        self.assertTrue(is_video_entry({"id": "tw_987", "ie_key": "Twitter"}))
        self.assertFalse(is_video_entry(
            {"id": "UC1234567890123456789012", "ie_key": "YoutubeTab"}))
        self.assertFalse(is_video_entry({"id": "short"}))
        self.assertFalse(is_video_entry({}))

    def test_multiplatform_url_validation_and_detection(self):
        cases = [
            ("https://www.youtube.com/watch?v=abc", "YouTube"),
            ("https://youtu.be/abc", "YouTube"),
            ("https://www.instagram.com/reel/C12345/", "Instagram"),
            ("https://www.facebook.com/watch/?v=12345", "Facebook"),
            ("https://fb.watch/abc123/", "Facebook"),
            ("https://www.reddit.com/r/python/comments/abc/title/", "Reddit"),
            ("https://v.redd.it/xyz123", "Reddit"),
            ("https://x.com/user/status/12345", "X / Twitter"),
            ("https://twitter.com/user/status/12345", "X / Twitter"),
            ("https://www.threads.net/@user/post/abc", "Threads"),
            ("https://www.threads.com/@user/post/abc", "Threads"),
        ]
        for url, platform in cases:
            with self.subTest(url=url):
                self.assertTrue(validate_url(url))
                self.assertEqual(detect_platform(url), platform)

        for bad in (
            "",
            "not a link",
            "ftp://instagram.com/p/1",
            "https://localhost/video",
            "https://localhost.localdomain/test",
            "http://127.0.0.1/",
            "http://192.168.1.1/",
            "http://10.0.0.1/media",
            "http://169.254.169.254/latest/meta-data",
            "http://100.64.0.1/status",
            "http://[::1]/",
            "http://[fe80::1]/",
            "http://example.com@127.0.0.1/",
            "http://printer.local/clip.mp4",
        ):
            with self.subTest(bad=bad):
                self.assertFalse(validate_url(bad))


class FormattingTests(unittest.TestCase):
    def test_file_size(self):
        self.assertEqual(format_file_size(None), "Unknown")
        self.assertEqual(format_file_size(500), "500.0 B")
        self.assertEqual(format_file_size(1536), "1.5 KB")

    def test_duration(self):
        self.assertEqual(format_duration(None), "Unknown")
        self.assertEqual(format_duration(3725), "01:02:05")

    def test_playlist_error_is_a_download_error(self):
        self.assertTrue(issubclass(PlaylistLinkError, DownloadError))


class CleanupTests(unittest.TestCase):
    def test_removes_only_leftovers_of_this_video(self):
        remove = [
            "Title [720p] [abc].f137.mp4",
            "Title [720p] [abc].f140.m4a.part",
            "Title [720p] [abc].mp4.part",
            "Title [720p] [abc].mp4.part-Frag3",
            "Title [720p] [abc].mp4.ytdl",
            "Title [128kbps] [abc].webp",
            "Title [128kbps] [abc].temp.mp3",
        ]
        keep = [
            "Title [720p] [abc].mp4",
            "Title [128kbps] [abc].mp3",
            "Song.final [720p] [abc].mp4",
            "Other [720p] [zzz].f137.mp4",
        ]

        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)

            for name in remove + keep:
                (folder / name).write_text("x")

            removed = cleanup_incomplete_files(folder, "abc")

            self.assertEqual(set(removed), set(remove))
            self.assertEqual(
                {file.name for file in folder.iterdir()}, set(keep)
            )


class ProgressTrackerTests(unittest.TestCase):
    class Recorder(Reporter):
        def __init__(self):
            self.calls = []

        def progress(self, downloaded, total, speed, eta):
            self.calls.append((downloaded, total, speed, eta))

    def test_reports_overall_progress_and_eta(self):
        recorder = self.Recorder()
        tracker = ProgressTracker(recorder, expected_total=100)

        tracker({"status": "downloading", "filename": "a",
                 "downloaded_bytes": 50, "speed": 10})

        self.assertEqual(recorder.calls[-1], (50, 100, 10, 5.0))

    def test_total_grows_and_eta_is_never_negative(self):
        recorder = self.Recorder()
        tracker = ProgressTracker(recorder, expected_total=100)

        tracker({"status": "downloading", "filename": "a",
                 "downloaded_bytes": 150, "speed": 10})

        downloaded, total, _, eta = recorder.calls[-1]
        self.assertEqual((downloaded, total), (150, 150))
        self.assertGreaterEqual(eta, 0)

    def test_streams_add_up(self):
        recorder = self.Recorder()
        tracker = ProgressTracker(recorder, expected_total=200)

        tracker({"status": "finished", "filename": "video",
                 "total_bytes": 120})
        tracker({"status": "downloading", "filename": "audio",
                 "downloaded_bytes": 30, "speed": 10})

        self.assertEqual(recorder.calls[-1][0], 150)

    def test_cancel_stops_the_download(self):
        cancel = threading.Event()
        cancel.set()
        tracker = ProgressTracker(self.Recorder(), cancel=cancel)

        with self.assertRaises(DownloadCancelled):
            tracker({"status": "downloading", "filename": "a",
                     "downloaded_bytes": 1})


if __name__ == "__main__":
    unittest.main()