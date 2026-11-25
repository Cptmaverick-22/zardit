import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import downloader
from config import AUDIO_BITRATES, AUDIO_CHOICES, ORIGINAL_AUDIO, audio_label
from downloader import DownloadError, _check_request, _plan
from downloader import find_existing_download

URL = "https://www.youtube.com/watch?v=abcdefghijk"


def step_keys(plan):
    return [step["key"] for step in plan["options"]["postprocessors"]]


class AudioChoiceTests(unittest.TestCase):
    def test_labels(self):
        self.assertEqual(audio_label(192), "192 kbps MP3")
        self.assertEqual(
            audio_label(ORIGINAL_AUDIO), "Original audio (no conversion)"
        )

    def test_original_comes_last(self):
        self.assertEqual(AUDIO_CHOICES[-1], ORIGINAL_AUDIO)
        self.assertEqual(AUDIO_CHOICES[:-1], AUDIO_BITRATES)


class PlanTests(unittest.TestCase):
    def test_original_audio_is_not_converted(self):
        plan = _plan("audio", ORIGINAL_AUDIO, False)

        self.assertEqual(plan["extension"], "m4a")
        self.assertEqual(plan["tag"], "[original]")
        self.assertEqual(plan["quality_label"], "original")
        self.assertEqual(plan["selection"]["format"], "bestaudio[ext=m4a]")
        self.assertNotIn("FFmpegExtractAudio", step_keys(plan))
        self.assertIn("EmbedThumbnail", step_keys(plan))
        self.assertIn("FFmpegMetadata", step_keys(plan))

    def test_mp3_is_converted_first(self):
        plan = _plan("audio", 192, False)

        self.assertEqual(plan["extension"], "mp3")
        self.assertEqual(plan["tag"], "[192kbps]")
        self.assertEqual(step_keys(plan)[0], "FFmpegExtractAudio")
        self.assertEqual(
            plan["options"]["postprocessors"][0]["preferredquality"], "192"
        )

    def test_plans_do_not_share_lists(self):
        first = _plan("audio", ORIGINAL_AUDIO, False)
        second = _plan("audio", ORIGINAL_AUDIO, False)

        self.assertIsNot(
            first["options"]["postprocessors"],
            second["options"]["postprocessors"],
        )


class RequestTests(unittest.TestCase):
    def setUp(self):
        patcher = mock.patch.object(
            downloader.shutil, "which", return_value="/usr/bin/ffmpeg"
        )
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_audio_choices_are_accepted(self):
        for choice in AUDIO_CHOICES:
            _check_request(URL, "audio", choice)

    def test_unknown_audio_choice_is_refused(self):
        for choice in (999, "orig", None):
            with self.assertRaises(DownloadError):
                _check_request(URL, "audio", choice)

    def test_video_is_still_checked(self):
        _check_request(URL, "video", 720)

        with self.assertRaises(DownloadError):
            _check_request(URL, "video", ORIGINAL_AUDIO)


class ExistingDownloadTests(unittest.TestCase):
    def test_finds_any_listed_extension(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            (folder / "Song [original] [abc].m4a").write_text("x")

            found = find_existing_download(
                folder, "abc", ("m4a", "webm"), "original"
            )

            self.assertEqual(found.name, "Song [original] [abc].m4a")

    def test_other_extension_is_not_matched(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            (folder / "Song [original] [abc].m4a").write_text("x")

            self.assertIsNone(
                find_existing_download(folder, "abc", "mp3", "original")
            )
            self.assertIsNone(
                find_existing_download(folder, "abc", "m4a", "192kbps")
            )

    def test_any_quality_with_one_extension(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            (folder / "Clip [360p] [abc].mp4").write_text("x")

            self.assertIsNotNone(find_existing_download(folder, "abc", "mp4"))


if __name__ == "__main__":
    unittest.main()