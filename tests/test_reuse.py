import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import downloader
from downloader import Reporter, _run_with_retries, _without_selection
from yt_dlp.utils import DownloadError as YtdlpDownloadError
from yt_dlp.utils import UnavailableVideoError

URL = "https://www.youtube.com/watch?v=abcdefghijk"


class FakeYdl:
    """Stands in for YoutubeDL and records how it was used."""

    def __init__(self, process_error=None, download_error=None):
        self.process_error = process_error
        self.download_error = download_error
        self.process_calls = []
        self.download_calls = []

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def process_ie_result(self, info, download=True):
        self.process_calls.append((info, download))

        if self.process_error:
            raise self.process_error

    def download(self, urls):
        self.download_calls.append(urls)

        if self.download_error:
            raise self.download_error

        return 0

    def report_error(self, message):
        raise YtdlpDownloadError(f"ERROR: {message}")


def make_info():
    return {
        "id": "abcdefghijk",
        "title": "Title",
        "formats": [{"format_id": "18"}],
        "requested_formats": [{"format_id": "137"}],
        "ext": "mp4",
        "height": 720,
    }


class WithoutSelectionTests(unittest.TestCase):
    def test_removes_the_earlier_choice_without_touching_the_original(self):
        info = make_info()
        clean = _without_selection(info)

        self.assertNotIn("requested_formats", clean)
        self.assertNotIn("ext", clean)
        self.assertNotIn("height", clean)
        self.assertEqual(clean["formats"], info["formats"])
        self.assertIn("requested_formats", info)


class ReuseTests(unittest.TestCase):
    def run_it(self, fakes, info, delays=(0, 0)):
        with mock.patch.object(downloader, "_ydl", side_effect=fakes), \
                mock.patch.object(downloader, "RETRY_DELAYS", delays):
            _run_with_retries({}, URL, Reporter(), None, info)

    def test_reuses_the_information_and_skips_extraction(self):
        fake = FakeYdl()
        info = make_info()

        self.run_it([fake], info)

        self.assertEqual(len(fake.process_calls), 1)
        self.assertEqual(fake.download_calls, [])

        sent, download = fake.process_calls[0]
        self.assertTrue(download)
        self.assertNotIn("requested_formats", sent)
        self.assertIn("requested_formats", info)

    def test_without_information_it_extracts_normally(self):
        fake = FakeYdl()

        self.run_it([fake], None)

        self.assertEqual(fake.process_calls, [])
        self.assertEqual(fake.download_calls, [[URL]])

    def test_falls_back_when_reuse_fails_unexpectedly(self):
        fake = FakeYdl(process_error=KeyError("surprise"))

        self.run_it([fake], make_info())

        self.assertEqual(len(fake.process_calls), 1)
        self.assertEqual(fake.download_calls, [[URL]])

    def test_a_retry_extracts_again_for_fresh_links(self):
        first = FakeYdl(process_error=YtdlpDownloadError(
            "ERROR: unable to download video data: HTTP Error 403: Forbidden"
        ))
        second = FakeYdl()

        self.run_it([first, second], make_info())

        self.assertEqual(len(first.process_calls), 1)
        self.assertEqual(second.process_calls, [])
        self.assertEqual(second.download_calls, [[URL]])

    def test_gives_up_after_the_last_attempt(self):
        error = YtdlpDownloadError("ERROR: HTTP Error 403: Forbidden")
        fakes = [
            FakeYdl(process_error=error),
            FakeYdl(download_error=error),
            FakeYdl(download_error=error),
        ]

        with self.assertRaises(YtdlpDownloadError):
            self.run_it(fakes, make_info())

    def test_disk_errors_become_ordinary_download_errors(self):
        fake = FakeYdl(process_error=UnavailableVideoError(
            OSError(28, "No space left on device")
        ))

        with self.assertRaises(YtdlpDownloadError) as caught:
            self.run_it([fake], make_info())

        self.assertIn("No space left", str(caught.exception))
        self.assertIn(
            "disk is full",
            downloader.friendly_error(caught.exception).lower(),
        )


if __name__ == "__main__":
    unittest.main()