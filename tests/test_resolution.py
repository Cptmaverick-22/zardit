import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from downloader import (
    _video_tag,
    available_qualities,
    resolution_label,
    video_selection,
)


def stream(width, height, note=None, ext="mp4", vcodec="avc1"):
    return {
        "width": width, "height": height, "format_note": note,
        "ext": ext, "vcodec": vcodec,
    }


class ResolutionTests(unittest.TestCase):
    def test_youtube_label_wins(self):
        self.assertEqual(resolution_label(stream(1920, 960, "1080p")), 1080)
        self.assertEqual(resolution_label(stream(1920, 1080, "1080p60")), 1080)
        self.assertEqual(resolution_label(stream(3840, 1920, "2160p")), 2160)

    def test_without_a_label_the_shorter_side_is_used(self):
        self.assertEqual(resolution_label(stream(1920, 1080)), 1080)
        self.assertEqual(resolution_label(stream(1080, 1920)), 1080)
        self.assertEqual(resolution_label({"height": 720}), 720)
        self.assertIsNone(resolution_label({}))

    def test_wide_video_uses_youtubes_names(self):
        info = {"formats": [
            stream(256, 128, "144p"),
            stream(640, 320, "360p"),
            stream(1920, 960, "1080p"),
            stream(3840, 1920, "2160p"),
            stream(256, 128),       # Unlabelled variants are ignored
            stream(3840, 1920),
        ]}

        self.assertEqual(available_qualities(info), [2160, 1080, 360, 144])

    def test_labelled_variants_are_deduplicated(self):
        info = {"formats": [
            stream(1920, 1080, "1080p", vcodec="avc1"),
            stream(1920, 1080, "1080p60", vcodec="av01"),
            stream(1280, 720, "720p"),
        ]}

        self.assertEqual(available_qualities(info), [1080, 720])

    def test_unlabelled_formats_are_used_when_nothing_is_labelled(self):
        info = {"formats": [
            stream(1080, 1920),
            stream(720, 1280),
            stream(720, 1280, ext="webm", vcodec="vp9"),
            {"ext": "m4a", "vcodec": "none"},
        ]}

        self.assertEqual(available_qualities(info), [1080, 720])

    def test_selection_prefers_h264_and_sorts_by_resolution(self):
        exact = video_selection(720)
        ceiling = video_selection(720, ceiling=True)

        self.assertEqual(
            exact["format_sort"], ["res:720", "proto", "vcodec:h264"]
        )        
        self.assertNotIn("/bestvideo+bestaudio/best", exact["format"])
        self.assertTrue(
            ceiling["format"].endswith("/bestvideo+bestaudio/best")
        )

    def test_file_name_tag_uses_the_chosen_picture(self):
        selected = {
            "requested_formats": [
                stream(1920, 960, "1080p"), {"vcodec": "none"},
            ]
        }

        self.assertEqual(_video_tag(selected, 960), "[1080p]")
        self.assertEqual(_video_tag(None, 720), "[720p]")


if __name__ == "__main__":
    unittest.main()