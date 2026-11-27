import io
import os
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from PySide6.QtCore import QSize, Qt
from PySide6.QtWidgets import QApplication, QBoxLayout
from rich.console import Console
from rich.theme import Theme

from zardit_core import app_updater
import config
import downloader
import gui
import history
import main
import theme
import zardit_app


class ThemeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)

        for patcher in (
            mock.patch.object(config, "CONFIG_DIR", root / "config"),
            mock.patch.object(
                config, "SETTINGS_FILE", root / "config" / "settings.json"
            ),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_normalize_theme_mode(self):
        self.assertEqual(theme.normalize_theme_mode("dark"), "dark")
        self.assertEqual(theme.normalize_theme_mode("LIGHT"), "light")
        self.assertEqual(theme.normalize_theme_mode("auto"), "system")
        self.assertEqual(theme.normalize_theme_mode("invalid"), "system")
        self.assertEqual(theme.normalize_theme_mode(None), "system")

    def test_save_and_load_theme(self):
        self.assertEqual(theme.get_saved_theme(), "system")
        theme.save_theme("dark")
        self.assertEqual(theme.get_saved_theme(), "dark")
        theme.save_theme("light")
        self.assertEqual(theme.get_saved_theme(), "light")

    def test_detect_terminal_theme_from_colorfgbg(self):
        with mock.patch.dict(os.environ, {"COLORFGBG": "0;15"}, clear=False):
            self.assertEqual(theme.detect_terminal_theme(), "light")

        with mock.patch.dict(os.environ, {"COLORFGBG": "15;0"}, clear=False):
            self.assertEqual(theme.detect_terminal_theme(), "dark")

    def test_qt_stylesheet_builds_for_both_modes(self):
        light_qss = theme.build_qt_stylesheet("light")
        dark_qss = theme.build_qt_stylesheet("dark")

        self.assertIn(theme.GUI_PALETTES["light"]["bg"], light_qss)
        self.assertIn(theme.GUI_PALETTES["dark"]["bg"], dark_qss)
        self.assertNotEqual(light_qss, dark_qss)


class GuiInterfaceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)

        for patcher in (
            mock.patch.object(config, "CONFIG_DIR", root / "config"),
            mock.patch.object(
                config, "SETTINGS_FILE", root / "config" / "settings.json"
            ),
            mock.patch.object(
                config, "default_download_dir", lambda: root / "downloads"
            ),
            mock.patch.object(gui.UpdateNotice, "start", lambda _self: None),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

        self.window = gui.MainWindow()
        self.addCleanup(self._cleanup_window)

    def _cleanup_window(self):
        self.window.close()
        self.window.deleteLater()
        self.app.processEvents()

    @classmethod
    def tearDownClass(cls):
        cls.app.processEvents()
        cls.app = None

    def test_theme_switching_updates_active_theme_and_buttons(self):
        self.window.set_theme_mode("dark")
        self.assertEqual(self.window.active_theme, "dark")
        self.assertTrue(self.window.theme_buttons["dark"].isChecked())
        self.assertEqual(theme.get_saved_theme(), "dark")

        self.window.set_theme_mode("light")
        self.assertEqual(self.window.active_theme, "light")
        self.assertTrue(self.window.theme_buttons["light"].isChecked())
        self.assertEqual(theme.get_saved_theme(), "light")

    def test_responsive_layout_stretches_full_width(self):
        self.window.update_responsive_layout(520)
        self.assertTrue(self.window._is_narrow)
        self.assertFalse(self.window._is_wide)
        self.assertEqual(
            self.window.card_layout.direction(),
            QBoxLayout.Direction.TopToBottom,
        )
        margins = self.window.content_layout.contentsMargins()
        self.assertEqual(margins.left(), 16)
        self.assertEqual(margins.right(), 16)

        self.window.update_responsive_layout(1400)
        self.assertFalse(self.window._is_narrow)
        self.assertTrue(self.window._is_wide)
        self.assertEqual(
            self.window.card_layout.direction(),
            QBoxLayout.Direction.LeftToRight,
        )
        wide_margins = self.window.content_layout.contentsMargins()
        self.assertEqual(wide_margins.left(), 24)
        self.assertEqual(wide_margins.right(), 24)

    def test_history_view_play_filter_and_recording(self):
        media_dir = Path(self.temporary.name) / "media"
        media_dir.mkdir(parents=True, exist_ok=True)
        video_file = media_dir / "Clip [1080p] [abc].mp4"
        audio_file = media_dir / "Song [192kbps] [xyz].mp3"
        video_file.write_bytes(b"video-bytes")
        audio_file.write_bytes(b"audio-bytes")

        history.add_history_entry(
            title="Clip Title",
            file_path=video_file,
            kind="video",
            quality="1080p MP4",
            channel="Chan A",
            video_id="abc",
        )
        history.add_history_entry(
            title="Song Title",
            file_path=audio_file,
            kind="audio",
            quality="192 kbps MP3",
            channel="Chan B",
            video_id="xyz",
        )

        self.window.show_page("history")
        self.assertEqual(self.window.history_list.count(), 2)
        self.assertIn("2", self.window.nav_history_btn.text())

        self.window.set_history_filter("audio")
        self.assertEqual(self.window.history_list.count(), 1)

        self.window.set_history_filter("all")
        self.window.history_search.setText("Clip")
        self.assertEqual(self.window.history_list.count(), 1)

        entry = self.window.history_list.item(0).data(Qt.ItemDataRole.UserRole)
        with mock.patch.object(gui.QDesktopServices, "openUrl") as open_mock:
            self.window.play_history_entry(entry)
            open_mock.assert_called_once()

        # Verify download completion automatically adds to history.
        new_file = media_dir / "Fresh [720p] [fresh].mp4"
        new_file.write_bytes(b"fresh")
        self.window.summary = SimpleNamespace(
            title="Fresh Video", channel="Fresh Chan", duration=60
        )
        self.window.info = {"id": "fresh"}
        self.window.download_kind = "video"
        self.window.download_setting = 720
        self.window.history_search.clear()
        with mock.patch.object(gui, "notify"):
            self.window.on_download_done(
                downloader.DownloadResult(
                    "completed", media_dir, new_file.name, new_file
                )
            )
        self.assertEqual(len(history.load_history()), 3)

    def test_history_thumbnails_recent_strip_and_cleanup(self):
        from PySide6.QtCore import QBuffer, QIODevice
        from PySide6.QtGui import QColor, QImage

        img = QImage(32, 24, QImage.Format.Format_RGB32)
        img.fill(QColor("#E63946"))
        buf = QBuffer()
        buf.open(QIODevice.OpenModeFlag.WriteOnly)
        img.save(buf, "PNG")
        png_bytes = bytes(buf.data())

        media_dir = Path(self.temporary.name) / "media"
        media_dir.mkdir(parents=True, exist_ok=True)
        clip = media_dir / "ThumbClip [1080p] [t1].mp4"
        clip.write_bytes(b"video")

        entry = history.add_history_entry(
            title="Thumb Clip",
            file_path=clip,
            kind="video",
            quality="1080p MP4",
            platform="YouTube",
            thumbnail_bytes=png_bytes,
        )
        self.assertIsNotNone(entry)
        thumb_path = Path(entry["thumbnail_path"])
        self.assertTrue(thumb_path.is_file())

        self.window.refresh_history()
        self.assertFalse(self.window.recent_section.isHidden())
        self.assertEqual(self.window.recent_rows_layout.count(), 1)
        self.assertEqual(self.window.history_list.count(), 1)

        history.remove_history_entry(entry["id"], delete_file=False)
        self.assertFalse(thumb_path.exists())

    def test_concurrent_playlist_and_parallel_stream_probing(self):
        info = {
            "id": "test",
            "title": "Test",
            "webpage_url": "https://www.youtube.com/watch?v=test",
            "extractor": "youtube",
            "formats": [
                {"format_id": "248", "vcodec": "avc1", "acodec": "none", "width": 1920, "height": 1080, "ext": "mp4", "protocol": "https", "url": "https://cdn.example.com/1080.mp4"},
                {"format_id": "136", "vcodec": "avc1", "acodec": "none", "width": 1280, "height": 720, "ext": "mp4", "protocol": "https", "url": "https://cdn.example.com/720.mp4"},
                {"format_id": "140", "vcodec": "none", "acodec": "mp4a.40.2", "ext": "m4a", "protocol": "https", "url": "https://cdn.example.com/audio.m4a"},
            ],
        }
        sizes = {
            "https://cdn.example.com/1080.mp4": 2_000_000,
            "https://cdn.example.com/720.mp4": 1_000_000,
            "https://cdn.example.com/audio.m4a": 250_000,
        }
        with mock.patch.object(
            downloader, "_probe_stream_size",
            side_effect=lambda stream, _cache: sizes.get(stream.get("url")),
        ) as probe_mock:
            opts = downloader.video_format_options(info)
            self.assertEqual([o.value for o in opts], [1080, 720])
            self.assertEqual(opts[0].size, 2_250_000)
            self.assertEqual(opts[1].size, 1_250_000)
            self.assertEqual(probe_mock.call_count, 3)

        out_dir = Path(self.temporary.name) / "pl_out"
        entries = [
            {"id": "v1", "title": "First"},
            {"id": "v2", "title": "Second"},
        ]

        def fake_dl(entry_url, kind, setting, folder, pre_info, is_pl, reporter, cancel, video_id=None):
            dest = folder / f"{video_id}.mp4"
            dest.write_bytes(b"ok")
            return downloader.DownloadResult("completed", folder, dest.name, dest)

        with mock.patch.object(downloader, "get_output_directory", return_value=out_dir), \
                mock.patch.object(downloader, "_download", side_effect=fake_dl):
            res = downloader.download_playlist(entries, "video", 1080, "TestList", max_workers=2)
            self.assertEqual(res.completed, 2)
            self.assertEqual(len(res.completed_items), 2)

    def test_status_banner_visibility_and_diagnostics(self):
        self.window.show_status("Something failed", "error")
        self.assertFalse(self.window.status_banner.isHidden())
        self.assertFalse(self.window.diagnostics_button.isHidden())
        self.assertEqual(
            self.window.status_banner.property("statusLevel"), "error"
        )

        self.window.show_status("")
        self.assertTrue(self.window.status_banner.isHidden())

    def test_fetched_video_and_playlist_states(self):
        self.assertFalse(self.window.hero_card.isHidden())

        video_opts = [
            downloader.FormatOption(
                value=1080,
                label="1080p",
                badge="Full HD",
                details="MP4 · H.264 · 60fps",
                size=1024,
            ),
            downloader.FormatOption(
                value=720,
                label="720p",
                badge="HD",
                details="MP4 · H.264",
                size=512,
            ),
        ]
        summary = SimpleNamespace(
            title="Sample Video",
            channel="Sample Channel",
            duration=125,
            upload_date="2026-01-01",
            thumbnail=None,
        )
        self.window.on_fetched(
            ("video", {"title": "Sample Video", "duration": 125}, summary, video_opts, None)
        )
        self.assertEqual(self.window.state, "ready")
        self.assertTrue(self.window.hero_card.isHidden())
        self.assertEqual(self.window.type_badge.text(), "VIDEO")
        self.assertFalse(self.window.card.isHidden())
        self.assertTrue(self.window.playlist_options.isHidden())
        self.assertEqual(self.window.format_list.count(), 2)
        self.assertEqual(self.window.selected_quality(), 1080)

        self.window.audio_radio.setChecked(True)
        self.assertEqual(self.window.format_list.count(), len(config.AUDIO_CHOICES))

        playlist = SimpleNamespace(
            title="Sample Playlist",
            entries=[
                {"id": "a", "title": "First"},
                {"id": "b", "title": "Second"},
            ],
        )
        self.window.video_radio.setChecked(True)
        self.window.on_fetched(("playlist", playlist, None))
        self.assertEqual(self.window.type_badge.text(), "PLAYLIST")
        self.assertFalse(self.window.playlist_options.isHidden())
        self.assertEqual(
            self.window.format_list.count(), len(config.PLAYLIST_QUALITIES)
        )
        self.assertEqual(self.window.checked_count(), 2)
        self.assertEqual(self.window.size().width() >= 480, True)
        self.assertIsInstance(self.window.size(), QSize)

    def test_paste_and_fetch_from_clipboard(self):
        QApplication.clipboard().setText("")
        with mock.patch.object(self.window, "fetch") as fetch_mock:
            self.window.paste_and_fetch()
            fetch_mock.assert_not_called()
            self.assertEqual(
                self.window.status_banner.property("statusLevel"), "warning"
            )

        QApplication.clipboard().setText("not a media link")
        with mock.patch.object(self.window, "fetch") as fetch_mock:
            self.window.paste_and_fetch()
            fetch_mock.assert_not_called()
            self.assertEqual(
                self.window.status_banner.property("statusLevel"), "error"
            )

        QApplication.clipboard().setText(
            "Check this out https://www.instagram.com/reel/C12345/"
        )
        with mock.patch.object(self.window, "fetch") as fetch_mock:
            self.window.paste_and_fetch()
            self.assertEqual(
                self.window.url_input.text(),
                "https://www.instagram.com/reel/C12345/",
            )
            fetch_mock.assert_called_once()

    def test_extract_url_and_structured_formats(self):
        self.assertEqual(
            downloader.extract_media_url("  https://www.reddit.com/r/videos/comments/abc/  "),
            "https://www.reddit.com/r/videos/comments/abc/",
        )
        self.assertEqual(
            downloader.detect_platform("https://www.threads.net/@user/post/abc"),
            "Threads",
        )
        self.assertIsNone(downloader.extract_media_url("not a link"))

        non_mp4_info = {
            "formats": [
                {"ext": "webm", "vcodec": "vp9", "height": 1080, "width": 1920},
                {"ext": "webm", "vcodec": "vp9", "height": 720, "width": 1280},
            ]
        }
        self.assertEqual(downloader.available_qualities(non_mp4_info), [1080, 720])

        audio_opts = downloader.audio_format_options({"duration": 100})
        self.assertEqual(len(audio_opts), len(config.AUDIO_CHOICES))
        self.assertEqual(audio_opts[0].value, config.AUDIO_CHOICES[0])
        self.assertIsNotNone(audio_opts[0].size)

    def test_stream_size_estimates_via_vbr_and_head_probe(self):
        # Fallback to vbr + abr when tbr and filesize are absent.
        vbr_selected = {
            "duration": 20,
            "requested_formats": [
                {"vcodec": "avc1", "vbr": 1000},
                {"acodec": "mp4a", "abr": 128},
            ],
        }
        self.assertEqual(
            downloader._streams_size(vbr_selected),
            int((1000 + 128) * 125 * 20),
        )

        # Fallback to HTTPS HEAD Content-Length (e.g. Instagram CDN streams).
        class FakeResp:
            def __init__(self, url, length):
                self._url = url
                self.headers = {"Content-Length": str(length)}

            def geturl(self):
                return self._url

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

        ig_selected = {
            "requested_formats": [
                {
                    "vcodec": "avc1",
                    "protocol": "https",
                    "url": "https://cdninstagram.com/v/video.mp4",
                },
                {
                    "acodec": "mp4a",
                    "protocol": "https",
                    "url": "https://cdninstagram.com/v/audio.m4a",
                },
            ]
        }

        def fake_urlopen(req, timeout=3):
            if "video.mp4" in req.full_url:
                return FakeResp(req.full_url, 2_500_000)
            return FakeResp(req.full_url, 300_000)

        cache = {}
        with mock.patch.object(
            downloader.urllib.request, "urlopen", side_effect=fake_urlopen
        ) as opened:
            total = downloader._streams_size(ig_selected, probe_cache=cache)
            # Second call hits cache with zero extra requests.
            cached = downloader._streams_size(ig_selected, probe_cache=cache)

        self.assertEqual(total, 2_800_000)
        self.assertEqual(cached, 2_800_000)
        self.assertEqual(opened.call_count, 2)

    def test_image_download_and_gui_image_mode(self):
        img_bytes = b"\x89PNG\r\n\x1a\nfake-png-data"

        class FakeImgResp:
            def __init__(self, url):
                self._url = url
                self.headers = {
                    "Content-Type": "image/png",
                    "Content-Length": str(len(img_bytes)),
                }
                self._pos = 0

            def geturl(self):
                return self._url

            def read(self, n=-1):
                if self._pos >= len(img_bytes):
                    return b""
                chunk = img_bytes[self._pos:] if n < 0 else img_bytes[self._pos:self._pos + n]
                self._pos += len(chunk)
                return chunk

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

        out_dir = Path(self.temporary.name) / "images"
        img_info = downloader._make_image_info(
            "https://i.redd.it/sample_img.png",
            title="Cool Photo",
            ext="png",
            size=len(img_bytes),
            channel="Reddit",
            media_id="img123",
        )
        self.assertTrue(downloader.is_image_only_info(img_info))

        with mock.patch.object(
            downloader.urllib.request,
            "urlopen",
            side_effect=lambda req, timeout=20: FakeImgResp(req.full_url),
        ):
            res = downloader.download_image(
                "https://i.redd.it/sample_img.png",
                fmt="original",
                output_dir=out_dir,
                info=img_info,
            )
            self.assertEqual(res.status, "completed")
            self.assertTrue(res.file_path.is_file())
            self.assertEqual(res.file_path.read_bytes(), img_bytes)

            dup = downloader.download_image(
                "https://i.redd.it/sample_img.png",
                fmt="original",
                output_dir=out_dir,
                info=img_info,
            )
            self.assertEqual(dup.status, "exists")

        # Image format options must omit estimated sizes across all modes,
        # including when given a video info dict with a large video filesize.
        img_opts = downloader.image_format_options(img_info)
        self.assertEqual(len(img_opts), len(config.IMAGE_FORMATS))
        for opt in img_opts:
            self.assertIsNone(opt.size, f"Unexpected estimated size for {opt.value}")

        yt_video_info = {
            "id": "vj_2uNIQZ-A",
            "title": "Sample Video",
            "ext": "mp4",
            "filesize": 804_400_000,
            "width": 1920,
            "height": 1080,
            "thumbnail": "https://i.ytimg.com/vi/vj_2uNIQZ-A/maxresdefault.jpg",
            "formats": [{"format_id": "137", "ext": "mp4", "height": 1080, "vcodec": "avc1"}],
        }
        yt_img_opts = {
            opt.value: opt for opt in downloader.image_format_options(yt_video_info)
        }
        self.assertEqual(yt_img_opts["original"].label, "Original (JPG)")
        for opt in yt_img_opts.values():
            self.assertIsNone(opt.size)

        summary = downloader.summarize_video(img_info)
        self.window.url = "https://www.reddit.com/r/pics/comments/img123/"
        self.window.on_fetched(("image", img_info, summary, img_opts, None))
        self.assertEqual(self.window.type_badge.text(), "REDDIT · IMAGE")
        self.assertTrue(self.window.image_radio.isChecked())
        self.assertFalse(self.window.video_radio.isEnabled())
        self.assertEqual(self.window.format_list.count(), len(config.IMAGE_FORMATS))

        history.add_history_entry(
            "Cool Photo", res.file_path, "image", "Original image", platform="Reddit"
        )
        self.window.show_page("history")
        self.window.set_history_filter("image")
        self.assertEqual(self.window.history_list.count(), 1)

    def test_instagram_photo_uncropped_selection_and_carousel_preview(self):
        # Uncropped candidate must beat cropped square URLs even with same/high area.
        ig_post = {
            "id": "DgISySARxuv",
            "title": "Post by user",
            "thumbnails": [
                {
                    "url": "https://scontent.cdninstagram.com/v/t51.2885-15/s640x640/cropped.jpg",
                    "width": 640,
                    "height": 640,
                },
                {
                    "url": "https://scontent.cdninstagram.com/v/t51.2885-15/c0.135.1080.1080a/sq.jpg",
                    "width": 1080,
                    "height": 1080,
                },
                {
                    "url": "https://scontent.cdninstagram.com/v/t51.2885-15/original_portrait.jpg",
                    "width": 1080,
                    "height": 1350,
                },
            ],
        }
        chosen_url, w, h = downloader.best_image_details(ig_post)
        self.assertIn("original_portrait.jpg", chosen_url)
        self.assertEqual((w, h), (1080, 1350))

        # Carousel normalization (including generic 'Video by ...' titles) and live preview.
        carousel_entries = [
            {
                "id": "slide1",
                "title": "Video by 433",
                "thumbnails": [
                    {
                        "url": "https://scontent.cdninstagram.com/slide1.jpg",
                        "width": 1080,
                        "height": 1350,
                    }
                ],
            },
            {
                "id": "slide2",
                "title": "Post by 433",
                "thumbnails": [
                    {
                        "url": "https://scontent.cdninstagram.com/slide2.jpg",
                        "width": 1440,
                        "height": 1080,
                    }
                ],
            },
        ]
        downloader._normalize_playlist_entries(
            carousel_entries,
            "Post by 433",
            "https://www.instagram.com/p/DgISySARxuv/",
        )
        self.assertEqual(carousel_entries[0]["_zardit_media_kind"], "image")
        self.assertEqual(carousel_entries[1]["_zardit_media_kind"], "image")
        self.assertEqual(carousel_entries[0]["title"], "Photo 1 (1080×1350)")
        self.assertEqual(carousel_entries[1]["title"], "Photo 2 (1440×1080)")

        playlist = SimpleNamespace(title="Post by 433", entries=carousel_entries)
        self.window.url = "https://www.instagram.com/p/DgISySARxuv/"
        with mock.patch.object(
            self.window.preview_loader, "fetch_batch"
        ) as batch_mock:
            self.window.on_fetched(("playlist", playlist, None))
            batch_mock.assert_called_once_with([
                "https://scontent.cdninstagram.com/slide1.jpg",
                "https://scontent.cdninstagram.com/slide2.jpg",
            ])

        self.assertEqual(self.window.type_badge.text(), "INSTAGRAM · GALLERY")
        self.assertTrue(self.window.image_radio.isChecked())
        self.assertEqual(self.window.playlist_list.count(), 2)
        self.assertFalse(
            self.window.playlist_list.item(0).icon().isNull()
        )

        # Format radio buttons must not be clipped even in split wide mode.
        self.window.resize(1280, 800)
        self.app.processEvents()
        self.assertGreaterEqual(
            self.window.image_radio.width(),
            self.window.image_radio.sizeHint().width(),
        )

        with mock.patch.object(
            self.window.preview_loader, "fetch"
        ) as preview_fetch:
            self.window.playlist_list.setCurrentRow(1)
            preview_fetch.assert_called_once_with(
                "https://scontent.cdninstagram.com/slide2.jpg"
            )

        # Lightbox MediaPreviewDialog navigation & selection sync.
        from PySide6.QtCore import QBuffer, QIODevice
        from PySide6.QtGui import QImage

        img = QImage(16, 16, QImage.Format.Format_RGB32)
        img.fill(Qt.GlobalColor.red)
        buf = QBuffer()
        buf.open(QIODevice.OpenModeFlag.WriteOnly)
        img.save(buf, "PNG")
        fake_png = bytes(buf.data())
        self.window.on_preview_loaded(
            "https://scontent.cdninstagram.com/slide1.jpg", fake_png
        )
        self.window._preview_cache["https://scontent.cdninstagram.com/slide2.jpg"] = fake_png

        dlg = gui.MediaPreviewDialog(
            carousel_entries,
            0,
            lambda i: self.window.playlist_list.item(i).checkState() == Qt.CheckState.Checked,
            lambda i, s: self.window.playlist_list.item(i).setCheckState(
                Qt.CheckState.Checked if s else Qt.CheckState.Unchecked
            ),
            self.window._preview_cache,
            self.window.preview_loader,
            parent=self.window,
        )
        self.addCleanup(dlg.close)
        self.assertEqual(dlg.counter_badge.text(), "1 of 2")
        dlg.go(1)
        self.assertEqual(dlg.counter_badge.text(), "2 of 2")
        dlg.include_check.setChecked(False)
        self.assertEqual(
            self.window.playlist_list.item(1).checkState(),
            Qt.CheckState.Unchecked,
        )

    def test_facebook_multi_photo_extractor(self):
        html_body = (
            '<html><head><meta property="og:title" content="Trip Album"></head><body>'
            '{"viewer_image":{"uri":"https:\\/\\/scontent.xx.fbcdn.net\\/p1.jpg","height":1350,"width":1080}}'
            '{"photo_image":{"uri":"https:\\/\\/scontent.xx.fbcdn.net\\/p2.jpg","height":1080,"width":1440}}'
            '{"image":{"uri":"https:\\/\\/scontent.xx.fbcdn.net\\/avatar.jpg","height":80,"width":80}}'
            '</body></html>'
        ).encode("utf-8")

        class FakeFbResp:
            def geturl(self):
                return "https://www.facebook.com/user/posts/123"

            headers = {"Content-Type": "text/html; charset=utf-8"}

            def read(self, _n=-1):
                return html_body

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

        with mock.patch.object(
            downloader.urllib.request, "urlopen", return_value=FakeFbResp()
        ):
            res = downloader._extract_facebook_images(
                "https://www.facebook.com/user/posts/123"
            )

        self.assertEqual(res["_type"], "playlist")
        self.assertEqual(res["title"], "Trip Album")
        self.assertEqual(len(res["entries"]), 2)
        self.assertEqual(res["entries"][0]["width"], 1080)
        self.assertEqual(res["entries"][1]["height"], 1080)

    def test_youtube_flat_playlist_stays_video_playlist_not_image_gallery(self):
        flat_entries = [
            {
                "_type": "url",
                "ie_key": "Youtube",
                "id": "abcdefghijk",
                "title": "Track One",
                "url": "https://www.youtube.com/watch?v=abcdefghijk",
                "duration": 136,
                "thumbnails": [
                    {
                        "url": "https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg",
                        "width": 336,
                        "height": 188,
                    }
                ],
            },
            {
                "_type": "url",
                "ie_key": "Youtube",
                "id": "lmnopqrstuv",
                "title": "Track Two",
                "url": "https://www.youtube.com/watch?v=lmnopqrstuv",
                "duration": 180,
                "thumbnails": [
                    {
                        "url": "https://i.ytimg.com/vi/lmnopqrstuv/hqdefault.jpg",
                        "width": 336,
                        "height": 188,
                    }
                ],
            },
        ]
        downloader._normalize_playlist_entries(
            flat_entries,
            "small playlist",
            "https://www.youtube.com/playlist?list=PL123456",
        )
        for item in flat_entries:
            self.assertNotEqual(item.get("_zardit_media_kind"), "image")

        playlist = SimpleNamespace(title="small playlist", entries=flat_entries)
        self.window.url = "https://www.youtube.com/playlist?list=PL123456"
        with mock.patch.object(
            self.window.preview_loader, "fetch_batch"
        ):
            self.window.on_fetched(("playlist", playlist, None))

        self.assertEqual(self.window.type_badge.text(), "YOUTUBE · PLAYLIST")
        self.assertEqual(self.window.meta_label.text(), "Playlist  ·  2 videos")
        self.assertTrue(self.window.video_radio.isEnabled())
        self.assertTrue(self.window.audio_radio.isEnabled())
        self.assertTrue(self.window.image_radio.isEnabled())

    def test_threads_share_extracts_dash_video_and_audio_formats(self):
        mpd_xml = (
            '<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period>'
            '<AdaptationSet contentType="video">'
            '<Representation id="v720" mimeType="video/mp4" codecs="avc1.64001f" '
            'width="720" height="1280" bandwidth="1200000" FBContentLength="3200000" FBQualityLabel="720p">'
            '<BaseURL>https://scontent.cdninstagram.com/threads_720.mp4</BaseURL>'
            '</Representation></AdaptationSet>'
            '<AdaptationSet contentType="audio">'
            '<Representation id="a1" mimeType="audio/mp4" codecs="mp4a.40.5" '
            'bandwidth="64000" FBContentLength="170000">'
            '<BaseURL>https://scontent.cdninstagram.com/threads_audio.m4a</BaseURL>'
            '</Representation></AdaptationSet>'
            '</Period></MPD>'
        )
        import json as _json

        html_body = (
            '<html><head>'
            '<meta property="og:title" content="Outlook (@outlookindia) on Threads">'
            '<meta property="og:image" content="https://scontent.cdninstagram.com/thumb.jpg">'
            '</head><body><script>'
            + _json.dumps({
                "code": "DgKWddoj2TI",
                "video_dash_manifest": mpd_xml,
                "video_versions": [
                    {"type": 101, "url": "https://scontent.cdninstagram.com/threads_prog.mp4"}
                ],
            })
            + '</script></body></html>'
        ).encode("utf-8")

        class FakeThreadsResp:
            headers = {"Content-Type": "text/html; charset=utf-8"}

            def geturl(self):
                return "https://www.threads.com/@outlookindia/post/DgKWddoj2TI"

            def read(self, _n=-1):
                return html_body

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

        with mock.patch.object(
            downloader.urllib.request, "urlopen", return_value=FakeThreadsResp()
        ):
            info = downloader._extract_threads_media("https://www.threads.com/share/Fm32n67jx")

        self.assertIsNotNone(info)
        self.assertFalse(downloader.is_image_only_info(info))
        self.assertEqual(downloader.available_qualities(info), [720])
        vid_opts = downloader.video_format_options(info)
        self.assertEqual(vid_opts[0].value, 720)
        self.assertEqual(vid_opts[0].size, 3370000)
        audio_opts = downloader.audio_format_options(info)
        self.assertEqual(audio_opts[-1].size, 170000)

    def test_private_instagram_browser_config_share_resolve_and_source_parser(self):
        # 1. Config persistence & _ig_cookie_options
        self.assertIsNone(config.get_ig_browser())
        self.assertEqual(
            downloader._ig_cookie_options("https://www.instagram.com/reel/abc/"),
            {},
        )
        config.save_ig_browser("firefox")
        self.assertEqual(config.get_ig_browser(), "firefox")
        self.assertEqual(
            downloader._ig_cookie_options("https://www.instagram.com/reel/abc/"),
            {"cookiesfrombrowser": ("firefox",)},
        )
        self.assertEqual(
            downloader._ig_cookie_options("https://www.youtube.com/watch?v=abcdefghijk"),
            {},
        )
        self.window.refresh_ig_button()
        self.assertEqual(self.window.ig_button.text(), "IG / FB · Firefox")
        config.save_ig_browser(None)
        self.window.refresh_ig_button()
        self.assertEqual(self.window.ig_button.text(), "IG / FB")

        # 2. Offline JSON (?__a=1) 1080p DASH + carousel extraction
        import json as _json

        mpd_xml = (
            '<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period>'
            '<AdaptationSet contentType="video">'
            '<Representation id="v1080" mimeType="video/mp4" codecs="vp09.00.40.08" '
            'width="1080" height="1920" bandwidth="2300000" FBContentLength="3600000">'
            '<BaseURL>https://scontent.cdninstagram.com/priv_1080.mp4</BaseURL>'
            '</Representation></AdaptationSet>'
            '<AdaptationSet contentType="audio">'
            '<Representation id="a1" mimeType="audio/mp4" codecs="mp4a.40.5" '
            'bandwidth="64000" FBContentLength="180000">'
            '<BaseURL>https://scontent.cdninstagram.com/priv_aud.m4a</BaseURL>'
            '</Representation></AdaptationSet>'
            '</Period></MPD>'
        )
        api_json = _json.dumps({
            "items": [{
                "code": "PrivReel123",
                "caption": {"text": "Private Reel"},
                "user": {"username": "my_private_profile"},
                "video_duration": 13.4,
                "video_dash_manifest": mpd_xml,
                "image_versions2": {
                    "candidates": [{"url": "https://scontent.cdninstagram.com/t.jpg", "width": 1080, "height": 1920}]
                },
            }]
        })
        parsed = downloader.parse_instagram_source(api_json, "https://www.instagram.com/reel/PrivReel123/")
        self.assertEqual(parsed["id"], "PrivReel123")
        self.assertEqual(downloader.available_qualities(parsed), [1080])
        self.assertEqual(downloader.video_format_options(parsed)[0].size, 3780000)

        # 3. Offline view-source HTML carousel extraction via InstagramAccessDialog
        html_src = (
            '<html><head><link rel="canonical" href="https://www.instagram.com/p/PrivCar1/">'
            '<meta property="og:title" content="My Private Post"></head><body>'
            '{"display_url":"https:\\/\\/scontent.cdninstagram.com\\/p1.jpg"}'
            '{"display_url":"https:\\/\\/scontent.cdninstagram.com\\/p2.jpg"}'
            '</body></html>'
        )
        dlg = gui.InstagramAccessDialog(self.window)
        self.addCleanup(dlg.close)
        dlg.source_text.setPlainText(html_src)
        dlg.extract_offline()
        self.assertIsNotNone(dlg.parsed_info)
        self.assertEqual(dlg.parsed_info["_type"], "playlist")
        self.assertEqual(len(dlg.parsed_info["entries"]), 2)

        # 4. /share/ Instagram redirect resolution
        class FakeShareResp:
            def geturl(self):
                return "https://www.instagram.com/p/DbyevnHDzrM/"
            def __enter__(self):
                return self
            def __exit__(self, *args):
                return False

        with mock.patch.object(downloader.urllib.request, "urlopen", return_value=FakeShareResp()):
            resolved = downloader._resolve_instagram_share_url("https://www.instagram.com/share/BAWRAVe3vK")
        self.assertEqual(resolved, "https://www.instagram.com/p/DbyevnHDzrM/")

        # 5. Friendly Instagram guidance (when IG is Off vs Connected)
        msg_off = downloader.friendly_error("ERROR: [Instagram] XYZ: This content is only available for registered users")
        self.assertIn("Click IG / FB", msg_off)
        config.save_ig_browser("firefox")
        msg_on = downloader.friendly_error("ERROR: [Instagram] XYZ: empty media response")
        self.assertIn("Paste Page Source", msg_on)
        config.save_ig_browser(None)

        # 6. Clean title (no hashtag wall bloat), metadata, single image headers, & Option1/2 dedupe
        caption_html = (
            '<html><head>'
            '<meta property="og:description" content="12 likes - sourasish_ain on Aug 6: &quot;Code. Compile. Evolve.\n\n#coding #viral #reels #hotwheels&quot;">'
            '<meta property="og:image" content="https://scontent.cdninstagram.com/cover.jpg">'
            '</head><body>'
            '{"code":"DNRGa-5S8p7","username":"sourasish_ain","full_name":"Sourasish Ain",'
            '"video_duration":28.0,"taken_at":1754470000,'
            '"video_versions":[{"width":720,"height":1280,"url":"https://scontent.cdninstagram.com/v.mp4"}]}'
            '</body></html>'
        )
        parsed_vid = downloader.parse_instagram_source(caption_html)
        self.assertEqual(parsed_vid["title"], "Code. Compile. Evolve")
        self.assertEqual(parsed_vid["uploader"], "Sourasish Ain")
        self.assertEqual(parsed_vid["duration"], 28)
        self.assertTrue(parsed_vid["upload_date"].isdigit())

        # Option 1 saved a 39-char token; Option 2's 11-char shortcode must detect it
        dl_dir = Path(self.temporary.name) / "ig_dedup"
        dl_dir.mkdir(parents=True, exist_ok=True)
        existing_file = dl_dir / "Video by sourasish_ain [720p] [DNRGa-5S8p7c933kqqQH1UK0cmn2PQCh3NlFSk0].mp4"
        existing_file.write_bytes(b"mp4")
        self.assertEqual(
            downloader.find_existing_download(dl_dir, parsed_vid["id"], "mp4", "720p"),
            existing_file,
        )

        # Non-YouTube unlabelled/WebM 1080p stays available alongside 720p labelled MP4
        ig_mixed = {
            "extractor_key": "Instagram",
            "formats": [
                {"ext": "mp4", "vcodec": "avc1", "width": 720, "height": 1280, "format_note": "720p"},
                {"ext": "webm", "vcodec": "vp09", "width": 1080, "height": 1920},
            ],
        }
        self.assertEqual(downloader.available_qualities(ig_mixed), [1080, 720])

        # Single-photo ?__a=1 extraction with http_headers works without TypeError
        single_photo_json = _json.dumps({
            "items": [{
                "code": "PrivPhoto1",
                "caption": {"text": "Quiet view #nature #photo"},
                "user": {"username": "alice"},
                "image_versions2": {
                    "candidates": [{"url": "https://scontent.cdninstagram.com/one.jpg", "width": 1080, "height": 1350}]
                },
            }]
        })
        photo_info = downloader.parse_instagram_source(single_photo_json)
        self.assertEqual(photo_info["_zardit_media_kind"], "image")
        self.assertIn("Referer", photo_info["http_headers"])


class TerminalUiTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)

        for patcher in (
            mock.patch.object(config, "CONFIG_DIR", root / "config"),
            mock.patch.object(
                config, "SETTINGS_FILE", root / "config" / "settings.json"
            ),
            mock.patch.object(
                config, "default_download_dir", lambda: root / "downloads"
            ),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

    def _capture_console(self, width, resolved_theme):
        buffer = io.StringIO()
        return (
            Console(
                file=buffer,
                width=width,
                force_terminal=False,
                no_color=True,
                theme=Theme(theme.TERMINAL_PALETTES[resolved_theme]),
            ),
            buffer,
        )

    def test_menu_renders_cleanly_in_narrow_and_wide_terminals(self):
        for width in (52, 100):
            for mode in ("light", "dark"):
                with self.subTest(width=width, mode=mode):
                    test_console, buffer = self._capture_console(width, mode)
                    with (
                        mock.patch.object(main, "console", test_console),
                        mock.patch.object(main, "theme_mode", mode),
                    ):
                        main.show_menu()
                    output = buffer.getvalue()
                    self.assertIn(config.APP_NAME, output)
                    self.assertIn("Download Video", output)
                    self.assertIn("Switch Theme", output)

    def test_video_card_and_quality_table_render(self):
        test_console, buffer = self._capture_console(80, "light")
        info = {
            "title": "Test Title",
            "uploader": "Test Channel",
            "duration": 95,
            "upload_date": "20260115",
            "formats": [
                {
                    "vcodec": "avc1",
                    "acodec": "none",
                    "ext": "mp4",
                    "height": 720,
                    "filesize": 5_000_000,
                }
            ],
        }
        with mock.patch.object(main, "console", test_console):
            main.show_video_card(info)
            qualities = main.show_quality_table(info, default_quality=720)

        output = buffer.getvalue()
        self.assertIn("Test Title", output)
        self.assertIn("720p", output)
        self.assertIn("default", output)
        self.assertEqual(qualities, [720])

    def test_parse_cli_theme(self):
        self.assertEqual(main.parse_cli_theme(["--theme", "light"]), "light")
        self.assertEqual(main.parse_cli_theme(["--theme=dark"]), "dark")
        self.assertEqual(main.parse_cli_theme(["--theme", "auto"]), "system")
        self.assertIsNone(main.parse_cli_theme(["--diagnostics"]))

    def test_terminal_audio_table_and_clipboard_paste(self):
        test_console, buffer = self._capture_console(90, "dark")
        with (
            mock.patch.object(main, "console", test_console),
            mock.patch.object(
                main,
                "read_clipboard_text",
                return_value="https://youtu.be/dQw4w9WgXcQ",
            ),
            mock.patch.object(main, "prompt_line", return_value="p"),
        ):
            pasted = main.get_url()
            main.show_audio_table(default_bitrate=192, info={"duration": 120})

        output = buffer.getvalue()
        self.assertEqual(pasted, "https://youtu.be/dQw4w9WgXcQ")
        self.assertIn("192 kbps MP3", output)
        self.assertIn("Original Audio", output)
        self.assertIn("Recommended", output)

    def test_history_module_and_terminal_render(self):
        media_dir = Path(self.temporary.name) / "media"
        media_dir.mkdir(parents=True, exist_ok=True)
        first = media_dir / "one.mp4"
        second = media_dir / "two.mp3"
        first.write_bytes(b"111")
        second.write_bytes(b"222")

        e1 = history.add_history_entry("Video One", first, "video", "1080p MP4")
        e2 = history.add_history_entry("Audio Two", second, "audio", "320 kbps MP3")

        entries = history.load_history()
        self.assertEqual(len(entries), 2)
        self.assertEqual(entries[0]["title"], "Audio Two")

        test_console, buffer = self._capture_console(90, "light")
        with mock.patch.object(main, "console", test_console):
            main.render_history_table(entries)
            main.show_menu()

        rendered = buffer.getvalue()
        self.assertIn("Video One", rendered)
        self.assertIn("Audio Two", rendered)
        self.assertIn("Download History", rendered)

        removed, file_deleted = history.remove_history_entry(e1["id"], delete_file=True)
        self.assertIsNotNone(removed)
        self.assertTrue(file_deleted)
        self.assertFalse(first.exists())

        cleared, deleted_count = history.clear_history(delete_files=True)
        self.assertEqual(cleared, 1)
        self.assertEqual(deleted_count, 1)
        self.assertFalse(second.exists())
        self.assertEqual(history.load_history(), [])
        self.assertEqual(e2["kind"], "audio")


class LauncherAndPackagingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def test_resource_path_and_app_icon_load(self):
        svg_path = gui.resource_path("assets/zardit.svg")
        png_path = gui.resource_path("assets/zardit.png")
        ico_path = gui.resource_path("assets/zardit.ico")
        self.assertTrue(svg_path.is_file())
        self.assertTrue(png_path.is_file())
        self.assertTrue(ico_path.is_file())
        icon = gui.load_app_icon()
        self.assertFalse(icon.isNull())

    def test_license_metadata_and_about_dialog(self):
        self.assertEqual(config.APP_LICENSE, "GPL-3.0-or-later")
        license_text = gui.read_license_text()
        self.assertIn("GNU GENERAL PUBLIC LICENSE", license_text)

        dlg = gui.AboutDialog()
        self.addCleanup(dlg.close)
        self.assertEqual(dlg.windowTitle(), f"About {config.APP_NAME}")

        test_console, buffer = TerminalUiTests()._capture_console(90, "dark")
        with mock.patch.object(main, "console", test_console):
            main.show_menu()
        self.assertIn("GPL-3.0-or-later", buffer.getvalue())

    def test_app_update_banner_and_updates_dialog(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with mock.patch.object(config, "CONFIG_DIR", root / "config"), \
                    mock.patch.object(config, "SETTINGS_FILE", root / "config" / "settings.json"), \
                    mock.patch.object(config, "default_download_dir", lambda: root / "downloads"), \
                    mock.patch.object(gui.UpdateNotice, "start", lambda _self: None):
                win = gui.MainWindow()
                self.addCleanup(win.close)

                self.assertEqual(win.update_button.text(), "Updates")
                self.assertFalse(win.app_update_banner.isVisible())

                info = app_updater.AppUpdateInfo(
                    current_version=config.APP_VERSION,
                    latest_version="1.5",
                    tag_name="v1.5",
                    available=True,
                    same_tag_hotfix=False,
                    release_url=app_updater.RELEASES_PAGE_URL,
                    release_notes="Better updater",
                    asset=app_updater.ReleaseAsset(
                        "Zardit-1.5-win64-setup.exe",
                        "https://github.com/Cptmaverick-22/zardit/releases/download/v1.5/Zardit-1.5-win64-setup.exe",
                        "a" * 64,
                        1024,
                        0,
                    ),
                    install_mode="windows_installer",
                )
                win.on_app_update_available(info)
                self.assertFalse(win.app_update_banner.isHidden())
                self.assertEqual(win.update_button.text(), "Updates (1)")

                win.on_update_available("2099.01.01")
                self.assertEqual(win.update_button.text(), "Updates (2)")

                dlg = gui.UpdatesDialog(win, app_info=info)
                self.addCleanup(dlg.close)
                self.assertIn("v1.5", dlg.app_status_lbl.text())

                win.dismiss_app_update_banner()
                self.assertTrue(win.app_update_banner.isHidden())

    def test_configure_bundled_tools_path_for_frozen_builds(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            exe_dir = root / "app"
            bin_dir = exe_dir / "bin"
            bin_dir.mkdir(parents=True)
            exe_path = exe_dir / "Zardit.exe"
            exe_path.write_bytes(b"")

            with mock.patch.object(sys, "frozen", True, create=True), \
                    mock.patch.object(sys, "executable", str(exe_path)), \
                    mock.patch.dict(os.environ, {"PATH": "/usr/bin"}, clear=False):
                zardit_app.configure_bundled_tools_path()
                parts = os.environ["PATH"].split(os.pathsep)
                self.assertEqual(parts[0], str(exe_dir.resolve()))
                self.assertEqual(parts[1], str(bin_dir.resolve()))

    def test_zardit_app_routes_gui_and_cli(self):
        with mock.patch("gui.main", return_value=0) as gui_main, \
                mock.patch("main.run", return_value=0) as cli_run, \
                mock.patch.object(sys, "argv", ["Zardit.exe"]):
            self.assertEqual(zardit_app.main([]), 0)
            gui_main.assert_called_once_with()
            cli_run.assert_not_called()

        with mock.patch("gui.main", return_value=0) as gui_main, \
                mock.patch("main.run", return_value=0) as cli_run, \
                mock.patch.object(sys, "argv", ["zardit-cli.exe"]):
            self.assertEqual(zardit_app.main([]), 0)
            gui_main.assert_not_called()
            cli_run.assert_called_once_with()

        with mock.patch("gui.main", return_value=0) as gui_main, \
                mock.patch("main.run", return_value=0) as cli_run, \
                mock.patch.object(sys, "argv", ["zardit"]):
            self.assertEqual(zardit_app.main(["--cli", "--diagnostics"]), 0)
            gui_main.assert_not_called()
            cli_run.assert_called_once_with()
            self.assertEqual(sys.argv, ["zardit", "--diagnostics"])

        with mock.patch("gui.main", return_value=0) as gui_main, \
                mock.patch("main.run", return_value=0) as cli_run, \
                mock.patch.object(sys, "argv", ["zardit"]):
            self.assertEqual(zardit_app.main(["--diagnostics"]), 0)
            gui_main.assert_not_called()
            cli_run.assert_called_once_with()
            self.assertEqual(sys.argv, ["zardit", "--diagnostics"])


if __name__ == "__main__":
    unittest.main()
