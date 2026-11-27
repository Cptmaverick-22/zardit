import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config
import downloader
from zardit_core import facebook, instagram


class FacebookExtractionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        for patcher in (
            mock.patch.object(config, "CONFIG_DIR", root / "config"),
            mock.patch.object(config, "SETTINGS_FILE", root / "config" / "settings.json"),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_facebook_reel_without_dimensions_stays_video_not_image(self):
        info = {
            "id": "1270315127190256",
            "title": "My Reel",
            "ext": "mp4",
            "thumbnail": "https://scontent.xx.fbcdn.net/thumb.avif",
            "formats": [
                {
                    "format_id": "sd",
                    "ext": "mp4",
                    "vcodec": None,
                    "acodec": None,
                    "width": None,
                    "height": None,
                    "format_note": None,
                },
                {
                    "format_id": "hd",
                    "ext": "mp4",
                    "vcodec": None,
                    "acodec": None,
                    "width": None,
                    "height": None,
                    "format_note": None,
                },
            ],
        }
        self.assertEqual(downloader.available_qualities(info), [720, 480])
        self.assertFalse(downloader.is_image_only_info(info))

    def test_parses_single_fb_photo_and_ignores_avatars_or_blurred_thumbs(self):
        html_doc = (
            '<html><head><title>Sunset Shot</title></head><body>'
            '{"blurred_image":{"uri":"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/t39.30808-6\\/blur.jpg"}}'
            '{"profile_picture":{"uri":"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/t39.30808-1\\/avatar.jpg"}}'
            '{"created_time":1795706195,"image":{"uri":"https:\\/\\/scontent.fccu2-3.fna.fbcdn.net\\/v\\/t51.82787-15\\/929416835_n.webp?stp=dst-jpg_tt6&cstp=mx1440x1440&ctp=s1440x1440"}}'
            '</body></html>'
        )
        parsed = facebook.parse_facebook_html(
            html_doc,
            "https://www.facebook.com/photo/?fbid=2986017488440647&set=a.277510472624709",
        )
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed["_zardit_media_kind"], "image")
        self.assertEqual(parsed["id"], "2986017488440647")
        self.assertIn("929416835_n.webp", parsed["image_url"])

    def test_parses_multi_photo_carousel_from_viewer_and_photo_image(self):
        html_doc = (
            '<html><head><meta property="og:title" content="Adam Digital"></head><body>'
            '"viewer_image":{"height":855,"width":1080},"photo_image":{"uri":"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/t39.99422-6\\/p1.png?oh=1"}'
            '"viewer_image":{"height":1024,"width":1080},"photo_image":{"uri":"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/t39.99422-6\\/p2.png?oh=2"}'
            '"viewer_image":{"height":855,"width":1080},"photo_image":{"uri":"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/t39.99422-6\\/p1.png?oh=1"}'
            '</body></html>'
        )
        parsed = facebook.parse_facebook_html(html_doc, "https://www.facebook.com/share/1GuMXrC4am/")
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed["_type"], "playlist")
        self.assertEqual(len(parsed["entries"]), 2)
        self.assertEqual(parsed["entries"][0]["width"], 1080)
        self.assertEqual(parsed["entries"][0]["height"], 855)

    def test_offline_source_parser_routes_private_facebook_video(self):
        fb_html = (
            '<html><head><meta property="og:title" content="Private Clip"></head><body>'
            '{"browser_native_hd_url":"https:\\/\\/video.xx.fbcdn.net\\/hd.mp4?oh=1",'
            '"browser_native_sd_url":"https:\\/\\/video.xx.fbcdn.net\\/sd.mp4?oh=2"}'
            '</body></html>'
        )
        parsed = instagram.parse_instagram_source(fb_html, "https://www.facebook.com/reel/123456789")
        self.assertEqual(downloader.available_qualities(parsed), [720, 480])

    def test_cookie_options_include_facebook_and_404_triggers_image_fallback(self):
        config.save_ig_browser("firefox")
        self.assertEqual(
            downloader._ig_cookie_options("https://www.facebook.com/share/p/1Fth93qtdn/"),
            {"cookiesfrombrowser": ("firefox",)},
        )
        self.assertEqual(
            downloader._ig_cookie_options("https://www.youtube.com/watch?v=abcdefghijk"),
            {},
        )

        class FakeYdl:
            def __init__(self, **_opts):
                pass
            def __enter__(self):
                return self
            def __exit__(self, *_a):
                return False
            def extract_info(self, _url, download=False):
                raise RuntimeError("ERROR: [facebook] pfbid123: Unable to download webpage: HTTP Error 404: Not Found")

        fake_img = downloader._make_image_info("https://scontent.xx.fbcdn.net/photo.jpg", title="FB Photo")
        with mock.patch.object(downloader, "_ydl", FakeYdl), \
                mock.patch.object(downloader, "_extract_facebook_images", return_value=fake_img):
            res = downloader.fetch_video_info("https://www.facebook.com/share/p/1Fth93qtdn/")
        self.assertEqual(res["_zardit_media_kind"], "image")
        self.assertEqual(res["image_url"], "https://scontent.xx.fbcdn.net/photo.jpg")

    def test_facebook_registered_users_error_uses_facebook_guidance(self):
        config.save_ig_browser(None)
        msg = downloader.friendly_error(
            RuntimeError(
                "ERROR: [facebook] 1128089906668302: This video is only available for "
                "registered users. Use --cookies-from-browser or --cookies for the authentication."
            ),
        )
        self.assertIn("Facebook", msg)
        self.assertIn("IG / FB", msg)
        self.assertNotIn("Instagram", msg)


if __name__ == "__main__":
    unittest.main()
