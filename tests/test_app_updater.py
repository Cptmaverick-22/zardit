import hashlib
import io
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config
from zardit_core import app_updater


class _FakeResponse:
    def __init__(self, body: bytes, final_url: str):
        self._buf = io.BytesIO(body)
        self._url = final_url
        self.headers = {"Content-Length": str(len(body))}

    def read(self, n=-1):
        return self._buf.read(n)

    def geturl(self):
        return self._url

    def close(self):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        self.close()


class AppUpdaterTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        for patcher in (
            mock.patch.object(config, "CONFIG_DIR", root / "config"),
            mock.patch.object(config, "SETTINGS_FILE", root / "config" / "settings.json"),
            mock.patch.object(config, "APP_VERSION", "1.4.1"),
            mock.patch.object(
                config,
                "get_build_info",
                lambda: {"version": "1.4.1", "commit": "a" * 40, "built_at": 1700000000},
            ),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_version_and_checksum_parsing(self):
        self.assertGreater(
            app_updater.parse_app_version("1.5.0"),
            app_updater.parse_app_version("v1.4.1"),
        )
        self.assertEqual(
            app_updater.parse_app_version("v1.4"),
            app_updater.parse_app_version("1.4"),
        )
        sums = app_updater.parse_sha256sums(
            f"{'b' * 64}  Zardit-1.4.1-win64-setup.exe\n"
            f"{'c' * 64} *Zardit-1.4.1-android-arm64.apk\n"
            "# comment\n"
        )
        self.assertEqual(sums["Zardit-1.4.1-win64-setup.exe"], "b" * 64)
        self.assertEqual(sums["Zardit-1.4.1-android-arm64.apk"], "c" * 64)

    def test_trusted_host_guard_rejects_untrusted_urls(self):
        app_updater.require_trusted_app_url("https://api.github.com/repos/Cptmaverick-22/zardit/releases/latest")
        app_updater.require_trusted_app_url("https://objects.githubusercontent.com/abc")
        for bad in (
            "http://github.com/Cptmaverick-22/zardit",
            "https://evil.com/Zardit-1.4.1-win64-setup.exe",
            "https://user:pass@github.com/Cptmaverick-22/zardit",
            "https://127.0.0.1/latest",
        ):
            with self.assertRaises(app_updater.AppUpdateError):
                app_updater.require_trusted_app_url(bad)

    def test_detects_newer_semver_release(self):
        sha = "d" * 64
        release_payload = {
            "tag_name": "v1.5",
            "html_url": "https://github.com/Cptmaverick-22/zardit/releases/tag/v1.5",
            "body": "New feature\nSecurity fix",
            "assets": [
                {
                    "name": "SHA256SUMS.txt",
                    "browser_download_url": "https://github.com/Cptmaverick-22/zardit/releases/download/v1.5/SHA256SUMS.txt",
                },
                {
                    "name": "Zardit-1.5-win64-setup.exe",
                    "browser_download_url": "https://github.com/Cptmaverick-22/zardit/releases/download/v1.5/Zardit-1.5-win64-setup.exe",
                    "size": 1024,
                    "updated_at": "2026-11-27T10:00:00Z",
                },
            ],
        }
        with mock.patch.object(app_updater, "_get_json", return_value=release_payload), \
                mock.patch.object(app_updater, "_get_text", return_value=f"{sha}  Zardit-1.5-win64-setup.exe\n"), \
                mock.patch.object(app_updater, "detect_install_mode", return_value="windows_installer"):
            info = app_updater.check_for_app_update()

        self.assertTrue(info.available)
        self.assertFalse(info.same_tag_hotfix)
        self.assertEqual(info.tag_name, "v1.5")
        self.assertIsNotNone(info.asset)
        self.assertEqual(info.asset.sha256, sha)

    def test_detects_same_tag_hotfix_via_commit_and_skips_when_already_installed(self):
        sha = "e" * 64
        release_payload = {
            "tag_name": "v1.4.1",
            "html_url": "https://github.com/Cptmaverick-22/zardit/releases/tag/v1.4.1",
            "body": "Hotfix rebuild",
            "assets": [
                {
                    "name": "SHA256SUMS.txt",
                    "browser_download_url": "https://github.com/Cptmaverick-22/zardit/releases/download/v1.4.1/SHA256SUMS.txt",
                },
                {
                    "name": "Zardit-1.4.1-win64-portable.zip",
                    "browser_download_url": "https://github.com/Cptmaverick-22/zardit/releases/download/v1.4.1/Zardit-1.4.1-win64-portable.zip",
                    "size": 2048,
                    "updated_at": "2023-11-14T22:15:00Z",
                },
            ],
        }

        def fake_json(url, timeout=15):
            if "/commits/" in url:
                return {"sha": "f" * 40}
            return release_payload

        with mock.patch.object(app_updater, "_get_json", side_effect=fake_json), \
                mock.patch.object(app_updater, "_get_text", return_value=f"{sha}  Zardit-1.4.1-win64-portable.zip\n"), \
                mock.patch.object(app_updater, "detect_install_mode", return_value="windows_portable"):
            info = app_updater.check_for_app_update()
            self.assertTrue(info.available)
            self.assertTrue(info.same_tag_hotfix)

            # Once installed SHA matches, hotfix should no longer re-prompt.
            app_updater._write_state(installed_asset_sha256=sha)
            info_after = app_updater.check_for_app_update()
            self.assertFalse(info_after.available)

    def test_detects_same_tag_hotfix_via_asset_timestamp_when_built_earlier(self):
        sha = "1" * 64
        release_payload = {
            "tag_name": "v1.4.1",
            "html_url": "https://github.com/Cptmaverick-22/zardit/releases/tag/v1.4.1",
            "body": "Rebuilt asset",
            "assets": [
                {
                    "name": "SHA256SUMS.txt",
                    "browser_download_url": "https://github.com/Cptmaverick-22/zardit/releases/download/v1.4.1/SHA256SUMS.txt",
                },
                {
                    "name": "Zardit-1.4.1-win64-setup.exe",
                    "browser_download_url": "https://github.com/Cptmaverick-22/zardit/releases/download/v1.4.1/Zardit-1.4.1-win64-setup.exe",
                    "size": 2048,
                    "updated_at": "2023-11-15T00:00:00Z",  # >20m after 1700000000
                },
            ],
        }
        with mock.patch.object(app_updater, "_get_json", return_value=release_payload), \
                mock.patch.object(app_updater, "_get_text", return_value=f"{sha}  Zardit-1.4.1-win64-setup.exe\n"), \
                mock.patch.object(app_updater, "detect_install_mode", return_value="windows_installer"):
            info = app_updater.check_for_app_update(check_same_tag_commit=False)

        self.assertTrue(info.available)
        self.assertTrue(info.same_tag_hotfix)

    def test_download_verifies_sha256_and_rejects_mismatch(self):
        payload = b"zardit-binary-content"
        good_sha = hashlib.sha256(payload).hexdigest()
        dest = Path(self.temp.name) / "dl"

        asset_ok = app_updater.ReleaseAsset(
            name="Zardit-1.4.1-win64-setup.exe",
            url="https://github.com/Cptmaverick-22/zardit/releases/download/v1.4.1/Zardit-1.4.1-win64-setup.exe",
            sha256=good_sha,
            size=len(payload),
            updated_at=0,
        )
        with mock.patch.object(app_updater, "_open_trusted", return_value=_FakeResponse(payload, asset_ok.url)):
            out = app_updater.download_and_verify_asset(asset_ok, dest)
        self.assertTrue(out.is_file())
        self.assertEqual(out.read_bytes(), payload)

        asset_bad = app_updater.ReleaseAsset(
            name="Zardit-1.4.1-win64-setup.exe",
            url=asset_ok.url,
            sha256="0" * 64,
            size=len(payload),
            updated_at=0,
        )
        with mock.patch.object(app_updater, "_open_trusted", return_value=_FakeResponse(payload, asset_bad.url)):
            with self.assertRaises(app_updater.AppUpdateError):
                app_updater.download_and_verify_asset(asset_bad, dest)

    def test_safe_extract_zip_blocks_zip_slip(self):
        bad_zip = Path(self.temp.name) / "evil.zip"
        with zipfile.ZipFile(bad_zip, "w") as zf:
            zf.writestr("../../evil.exe", b"bad")
        with self.assertRaises(app_updater.AppUpdateError):
            app_updater._safe_extract_zip(bad_zip, Path(self.temp.name) / "out")

    def test_background_check_respects_cache_and_dismiss(self):
        info = app_updater.AppUpdateInfo(
            current_version="1.4.1",
            latest_version="1.5",
            tag_name="v1.5",
            available=True,
            same_tag_hotfix=False,
            release_url=app_updater.RELEASES_PAGE_URL,
            release_notes="",
            asset=app_updater.ReleaseAsset("Zardit-1.5-win64-setup.exe", "https://github.com/a", "a" * 64, 10, 0),
            install_mode="windows_installer",
        )
        with mock.patch.object(app_updater, "check_for_app_update", return_value=info) as chk:
            found = app_updater.background_check_app_update(now=1000.0)
            self.assertIsNotNone(found)
            self.assertEqual(chk.call_count, 1)

        app_updater._write_state(
            checked=1000.0,
            available=True,
            latest_version="1.5",
            tag_name="v1.5",
            asset_name="Zardit-1.5-win64-setup.exe",
            asset_url="https://github.com/a",
            asset_sha256="a" * 64,
        )
        # Within 24h returns cached without network call.
        with mock.patch.object(app_updater, "check_for_app_update") as chk2:
            cached = app_updater.background_check_app_update(now=2000.0)
            chk2.assert_not_called()
            self.assertIsNotNone(cached)

        # Dismissing suppresses future background notifications for the same asset.
        app_updater.dismiss_cached_update(asset_sha256="a" * 64, latest_version="1.5")
        self.assertIsNone(app_updater.background_check_app_update(now=2500.0))

    def test_release_notes_markdown_cleanup(self):
        cleaned = app_updater._summarize_release_notes(
            "## Zardit v1.4.2\n"
            "---\n"
            "### What's New\n"
            "- **Private Facebook:** Works with `IG / FB` cookies & [Release](https://github.com/a/b).\n"
            "**Full Changelog:** https://github.com/a/b\n"
        )
        self.assertNotIn("##", cleaned)
        self.assertNotIn("**", cleaned)
        self.assertNotIn("Full Changelog", cleaned)
        self.assertIn("What's New", cleaned)
        self.assertIn("• Private Facebook: Works with IG / FB cookies & Release.", cleaned)

    def test_linux_appimage_self_update_stages_and_launches_swapper(self):
        payload = b"new-appimage-binary"
        sha = hashlib.sha256(payload).hexdigest()
        app_dir = Path(self.temp.name) / "apps"
        app_dir.mkdir(parents=True)
        current_appimage = app_dir / "Zardit-1.4.1-x86_64.AppImage"
        current_appimage.write_bytes(b"old-appimage")

        info = app_updater.AppUpdateInfo(
            current_version="1.4.1",
            latest_version="1.4.2",
            tag_name="v1.4.2",
            available=True,
            same_tag_hotfix=False,
            release_url=app_updater.RELEASES_PAGE_URL,
            release_notes="",
            asset=app_updater.ReleaseAsset(
                "Zardit-1.4.2-x86_64.AppImage",
                "https://github.com/Cptmaverick-22/zardit/releases/download/v1.4.2/Zardit-1.4.2-x86_64.AppImage",
                sha,
                len(payload),
                0,
            ),
            install_mode="linux_appimage",
        )
        with mock.patch.object(app_updater.sys, "platform", "linux"), \
                mock.patch.dict("os.environ", {"APPIMAGE": str(current_appimage)}), \
                mock.patch.object(app_updater, "_open_trusted", return_value=_FakeResponse(payload, info.asset.url)), \
                mock.patch.object(app_updater.subprocess, "Popen") as popen_mock:
            action, target = app_updater.install_app_update(info)

        self.assertEqual(action, "appimage_updating")
        self.assertEqual(target, current_appimage.resolve())
        popen_mock.assert_called_once()
        cmd = popen_mock.call_args.args[0]
        self.assertEqual(cmd[0], "/bin/sh")
        script_path = Path(cmd[1])
        self.assertTrue(script_path.is_file())
        self.assertIn("unset APPDIR APPIMAGE ARGV0 OWD PYTHONHOME PYTHONPATH LD_LIBRARY_PATH", script_path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
