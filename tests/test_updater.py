import hashlib
import io
import os
import sys
import tempfile
import threading
import time
import types
import unittest
import urllib.error
import zipfile
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import updater
from updater import UpdateError

YTDLP_URL = "https://files.pythonhosted.org/packages/ab/yt_dlp-2099.01.01-py3-none-any.whl"
EJS_URL = "https://files.pythonhosted.org/packages/cd/yt_dlp_ejs-0.3.2-py3-none-any.whl"


def make_wheel(files):
    buffer = io.BytesIO()

    with zipfile.ZipFile(buffer, "w") as bundle:
        for name, content in files.items():
            info = zipfile.ZipInfo(name)
            info.filename = name
            bundle.writestr(info, content)

    return buffer.getvalue()


def ytdlp_wheel(version="2099.01.01"):
    return make_wheel({
        "yt_dlp/__init__.py": "",
        "yt_dlp/version.py": f"__version__ = '{version}'\n",
        f"yt_dlp-{version}.dist-info/METADATA": "Name: yt-dlp\n",
        f"yt_dlp-{version}.data/data/share/man/yt-dlp.1": "manual",
    })


def ejs_wheel():
    return make_wheel({"yt_dlp_ejs/__init__.py": "", "yt_dlp_ejs/core.js": "x"})


def sha(data):
    return hashlib.sha256(data).hexdigest()


def wheel_for(data, name="yt-dlp", version="2099.01.01", url=YTDLP_URL):
    return updater.Wheel(name, version, url, sha(data), len(data))


def pypi_json(version, url=YTDLP_URL, digest="a" * 64, requires_python=">=3.9",
              yanked=False, requires_dist=None, extra_files=()):
    return {
        "info": {
            "version": version,
            "requires_python": requires_python,
            "requires_dist": requires_dist,
        },
        "urls": [
            *extra_files,
            {
                "filename": f"pkg-{version}-py3-none-any.whl",
                "packagetype": "bdist_wheel",
                "url": url,
                "digests": {"sha256": digest},
                "requires_python": requires_python,
                "yanked": yanked,
                "size": 1234,
            },
        ],
    }


class FakeResponse:
    def __init__(self, data, url=YTDLP_URL, fail_after=None):
        self.stream = io.BytesIO(data)
        self.url = url
        self.fail_after = fail_after
        self.closed = False

    def read(self, size=-1):
        if self.fail_after is not None and self.stream.tell() >= self.fail_after:
            raise ConnectionResetError("connection lost")

        return self.stream.read(size)

    def geturl(self):
        return self.url

    def close(self):
        self.closed = True

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
        return False


class UpdaterCase(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.update_dir = self.root / "updates"
        self.settings = {}

        for patcher in (
            mock.patch.object(updater, "UPDATE_DIR", self.update_dir),
            mock.patch.object(updater, "_status", updater._new_status()),
            mock.patch.object(updater, "load_settings",
                              lambda: dict(self.settings)),
            mock.patch.object(updater, "remember_setting",
                              lambda key, value: self.settings.update({key: value})),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

    def make_update(self, version, init_code=""):
        folder = self.update_dir / f"v{version}"
        (folder / "yt_dlp").mkdir(parents=True)
        (folder / "yt_dlp" / "__init__.py").write_text(init_code)
        (folder / "yt_dlp" / "version.py").write_text(
            f"__version__ = '{version}'\n"
        )
        updater._write_state(active=version)

        return folder


class VersionTests(unittest.TestCase):
    def test_parse_version(self):
        self.assertEqual(updater.parse_version("2026.10.26"), (2026, 10, 26))
        self.assertEqual(
            updater.parse_version("2026.10.26.232815"), (2026, 10, 26, 232815)
        )
        self.assertEqual(updater.parse_version("2026.10.26.dev1"), (2026, 10, 26))
        self.assertEqual(updater.parse_version(""), ())
        self.assertEqual(updater.parse_version(None), ())
        self.assertEqual(updater.parse_version("junk"), ())

    def test_newer_versions_compare_higher(self):
        parse = updater.parse_version

        self.assertGreater(parse("2026.11.01"), parse("2026.10.26"))
        self.assertGreater(parse("2026.10.26.1"), parse("2026.10.26"))
        self.assertGreater(parse("2026.10.26"), parse(None))

    def test_python_requirements(self):
        here = (3, 12, 3)
        cases = [
            (">=3.10", True), (">=3.13", False), (">=3.9,<4", True),
            ("<3.12", False), ("<=3.12", False), ("<=3.13", True),
            ("!=3.12.*", False), ("!=3.11.*", True), ("==3.12.*", True),
            ("==3.11.*", False), (">3.12", True), ("", True),
            (None, True), ("garbage", True),
        ]

        for requires, expected in cases:
            with self.subTest(requires=requires):
                self.assertEqual(
                    updater.python_supported(requires, here), expected
                )


class RequirementTests(unittest.TestCase):
    def test_ejs_requirement(self):
        cases = [
            (['yt-dlp-ejs==0.3.2; extra == "default"'], (True, "0.3.2")),
            (["yt_dlp_ejs==1.0"], (True, "1.0")),
            (["yt-dlp-ejs[x]==1.0"], (True, "1.0")),
            (["yt-dlp-ejs>=0.3"], (True, None)),
            (["yt-dlp-ejs"], (True, None)),
            (["requests", "brotli"], (False, None)),
            ([], (False, None)),
            (None, (False, None)),
        ]

        for lines, expected in cases:
            with self.subTest(lines=lines):
                self.assertEqual(updater.ejs_requirement(lines), expected)


class TrustTests(unittest.TestCase):
    def test_trusted_addresses(self):
        updater._require_trusted("https://pypi.org/pypi/yt-dlp/json")
        updater._require_trusted(YTDLP_URL)

    def test_untrusted_addresses(self):
        for url in (
            "http://pypi.org/pypi/yt-dlp/json",
            "https://example.com/x.whl",
            "https://files.pythonhosted.org.evil.com/x.whl",
            "https://evil.com/files.pythonhosted.org/x.whl",
            "ftp://pypi.org/x",
            "",
        ):
            with self.subTest(url=url):
                with self.assertRaises(UpdateError):
                    updater._require_trusted(url)

    def test_redirects_to_other_hosts_are_refused(self):
        response = FakeResponse(b"", url="https://evil.com/x.whl")

        with mock.patch.object(
            updater.urllib.request, "urlopen", return_value=response
        ):
            with self.assertRaises(UpdateError):
                updater._open(YTDLP_URL)

        self.assertTrue(response.closed)


class PickWheelTests(unittest.TestCase):
    def test_picks_the_wheel(self):
        wheel = updater._pick_wheel(pypi_json("2099.01.01"), "yt-dlp")

        self.assertEqual(wheel.version, "2099.01.01")
        self.assertEqual(wheel.url, YTDLP_URL)
        self.assertEqual(wheel.sha256, "a" * 64)

    def test_skips_source_archives_yanked_and_unchecked_files(self):
        extras = [
            {"filename": "pkg-1.tar.gz", "packagetype": "sdist",
             "url": "https://files.pythonhosted.org/s.tar.gz",
             "digests": {"sha256": "b" * 64}},
            {"filename": "pkg-1-py3-none-any.whl", "packagetype": "bdist_wheel",
             "url": "https://files.pythonhosted.org/y.whl",
             "digests": {"sha256": "c" * 64}, "yanked": True},
            {"filename": "pkg-1-py3-none-any.whl", "packagetype": "bdist_wheel",
             "url": "https://files.pythonhosted.org/n.whl", "digests": {}},
        ]
        wheel = updater._pick_wheel(
            pypi_json("2099.01.01", extra_files=extras), "yt-dlp"
        )

        self.assertEqual(wheel.url, YTDLP_URL)

    def test_no_wheel(self):
        with self.assertRaises(UpdateError):
            updater._pick_wheel({"info": {"version": "1"}, "urls": []}, "x")

    def test_unsupported_python(self):
        with self.assertRaises(UpdateError) as caught:
            updater._pick_wheel(
                pypi_json("2099.01.01", requires_python=">=99"), "yt-dlp"
            )

        self.assertIn("doesn't support this Python", str(caught.exception))


class ExtractTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def write(self, data):
        path = self.root / "pkg.whl"
        path.write_bytes(data)

        return path

    def test_extracts_files_and_skips_data_folders(self):
        target = self.root / "out"
        updater._extract_wheel(self.write(ytdlp_wheel()), target)

        self.assertTrue((target / "yt_dlp" / "version.py").is_file())
        self.assertTrue((target / "yt_dlp-2099.01.01.dist-info").is_dir())
        self.assertFalse(list(target.glob("*.data")))

    def test_unsafe_names_are_refused(self):
        for name in (
            "../evil.py", "a/../../evil.py", "/etc/evil.py", "a\\b.py",
            "C:/evil.py", "a/C:evil.py",
        ):
            with self.subTest(name=name):
                target = self.root / "out"
                data = make_wheel({"ok.py": "1", name: "x"})

                with self.assertRaises(UpdateError):
                    updater._extract_wheel(self.write(data), target)

                self.assertFalse((self.root / "evil.py").exists())

    def test_oversized_packages_are_refused(self):
        data = make_wheel({"big.bin": "x" * 100})

        with mock.patch.object(updater, "MAX_UNPACKED_BYTES", 10):
            with self.assertRaises(UpdateError):
                updater._extract_wheel(self.write(data), self.root / "out")

    def test_not_a_zip(self):
        with self.assertRaises(UpdateError):
            updater._extract_wheel(self.write(b"not a zip"), self.root / "out")


class DownloadTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)
        self.data = ytdlp_wheel()
        self.reports = []

    def run_download(self, response=None, wheel=None, error=None):
        wheel = wheel or wheel_for(self.data)
        patch = (
            mock.patch.object(updater, "_open", side_effect=error)
            if error else
            mock.patch.object(
                updater, "_open",
                return_value=response or FakeResponse(self.data),
            )
        )

        with patch:
            return updater._download(wheel, self.folder, self.reports.append)

    def test_download_and_checksum(self):
        path = self.run_download()

        self.assertEqual(path.read_bytes(), self.data)
        self.assertTrue(self.reports)

    def test_checksum_mismatch(self):
        wheel = wheel_for(self.data)
        wheel.sha256 = "0" * 64

        with self.assertRaises(UpdateError) as caught:
            self.run_download(wheel=wheel)

        self.assertIn("checksum", str(caught.exception))

    def test_download_larger_than_allowed(self):
        with mock.patch.object(updater, "MAX_WHEEL_BYTES", 5):
            with self.assertRaises(UpdateError) as caught:
                self.run_download()

        self.assertIn("larger than expected", str(caught.exception))

    def test_connection_lost_mid_download(self):
        response = FakeResponse(self.data, fail_after=10)

        with mock.patch.object(updater, "CHUNK", 8):
            with self.assertRaises(UpdateError) as caught:
                self.run_download(response=response)

        self.assertEqual(str(caught.exception), updater.NETWORK_ERROR)

    def test_cannot_connect(self):
        with self.assertRaises(UpdateError) as caught:
            self.run_download(error=urllib.error.URLError("no network"))

        self.assertEqual(str(caught.exception), updater.NETWORK_ERROR)


class CheckTests(UpdaterCase):
    def check(self, data, running="2026.10.26"):
        with mock.patch.object(updater, "_get_json", return_value=data), \
                mock.patch.object(updater, "current_version",
                                  return_value=running):
            return updater.check_for_update()

    def test_update_available(self):
        info = self.check(pypi_json(
            "2099.01.01", requires_dist=['yt-dlp-ejs==0.3.2; extra == "default"']
        ))

        self.assertTrue(info.available)
        self.assertEqual(info.latest, "2099.01.01")
        self.assertEqual(info.current, "2026.10.26")
        self.assertEqual(info.wheel.url, YTDLP_URL)
        self.assertEqual(info.ejs, (True, "0.3.2"))
        self.assertFalse(info.pending_restart)

        state = updater._read_state()
        self.assertEqual(state["latest"], "2099.01.01")
        self.assertIn("checked", state)

    def test_up_to_date(self):
        info = self.check(pypi_json("2026.10.26"))

        self.assertFalse(info.available)
        self.assertIsNone(info.wheel)

    def test_installed_update_waiting_for_a_restart(self):
        self.make_update("2099.01.01")

        info = self.check(pypi_json("2099.01.01"))

        self.assertFalse(info.available)
        self.assertTrue(info.pending_restart)
        self.assertEqual(info.installed, "2099.01.01")

    def test_unexpected_answers(self):
        for data in (pypi_json("junk"), pypi_json("../../x"), {"info": {}}):
            with self.subTest(data=data):
                with self.assertRaises(UpdateError):
                    self.check(data)

    def test_network_problems(self):
        for error in (urllib.error.URLError("down"), OSError("reset")):
            with self.subTest(error=error):
                with mock.patch.object(updater, "_open", side_effect=error):
                    with self.assertRaises(UpdateError) as caught:
                        updater._get_json("https://pypi.org/pypi/yt-dlp/json")

                self.assertIn("Couldn't reach PyPI", str(caught.exception))

    def test_http_errors(self):
        error = urllib.error.HTTPError("u", 503, "x", {}, None)
        self.addCleanup(error.close)

        with mock.patch.object(updater, "_open", side_effect=error):
            with self.assertRaises(UpdateError) as caught:
                updater._get_json("https://pypi.org/pypi/yt-dlp/json")

        self.assertIn("503", str(caught.exception))

    def test_bad_json(self):
        with mock.patch.object(updater, "_open",
                               return_value=FakeResponse(b"<html>")):
            with self.assertRaises(UpdateError):
                updater._get_json("https://pypi.org/pypi/yt-dlp/json")


class InstallTests(UpdaterCase):
    def setUp(self):
        super().setUp()
        self.ytdlp = ytdlp_wheel()
        self.ejs = ejs_wheel()
        self.reports = []

        patcher = mock.patch.object(updater, "_smoke_test")
        self.smoke = patcher.start()
        self.addCleanup(patcher.stop)

    def info(self, ytdlp=None, ejs_required=True, version="2099.01.01"):
        ytdlp = ytdlp or self.ytdlp

        return updater.UpdateInfo(
            current="2026.10.26", latest=version, available=True,
            pending_restart=False, installed=None,
            wheel=wheel_for(ytdlp, version=version),
            ejs=(ejs_required, "0.3.2" if ejs_required else None),
        )

    def serve(self, ytdlp=None, ejs=None):
        files = {YTDLP_URL: ytdlp or self.ytdlp, EJS_URL: ejs or self.ejs}

        def opener(url, accept="*/*", timeout=updater.TIMEOUT):
            return FakeResponse(files[url], url=url)

        ejs_json = pypi_json(
            "0.3.2", url=EJS_URL, digest=sha(ejs or self.ejs)
        )

        return (
            mock.patch.object(updater, "_open", side_effect=opener),
            mock.patch.object(updater, "_get_json", return_value=ejs_json),
        )

    def install(self, info=None, **serve_options):
        opener, getter = self.serve(**serve_options)

        with opener, getter:
            return updater.install_update(
                report=self.reports.append, info=info or self.info()
            )

    def test_installs_both_packages(self):
        self.assertEqual(self.install(), "2099.01.01")

        folder = self.update_dir / "v2099.01.01"

        self.assertTrue((folder / "yt_dlp" / "version.py").is_file())
        self.assertTrue((folder / "yt_dlp_ejs" / "core.js").is_file())
        self.assertEqual(updater._read_state()["active"], "2099.01.01")
        self.assertFalse((self.update_dir / "staging").exists())
        self.smoke.assert_called_once()
        self.assertTrue(any("Unpacking" in text for text in self.reports))

    def test_ejs_is_skipped_when_not_required(self):
        self.install(info=self.info(ejs_required=False))

        folder = self.update_dir / "v2099.01.01"

        self.assertTrue((folder / "yt_dlp").is_dir())
        self.assertFalse((folder / "yt_dlp_ejs").exists())

    def test_nothing_to_do_when_not_available(self):
        info = self.info()
        info.available = False

        self.assertIsNone(self.install(info=info))
        self.assertFalse(self.update_dir.exists())

    def test_wrong_version_inside_the_package_is_refused(self):
        wrong = ytdlp_wheel("2050.01.01")

        with self.assertRaises(UpdateError) as caught:
            self.install(info=self.info(ytdlp=wrong), ytdlp=wrong)

        self.assertIn("2050.01.01", str(caught.exception))
        self.assertEqual(updater._read_state().get("active"), None)
        self.assertFalse((self.update_dir / "v2099.01.01").exists())
        self.assertFalse((self.update_dir / "staging").exists())

    def test_failed_test_installs_nothing_and_keeps_the_old_update(self):
        self.make_update("2098.01.01")
        self.smoke.side_effect = UpdateError("does not work")

        with self.assertRaises(UpdateError):
            self.install()

        self.assertEqual(updater._read_state()["active"], "2098.01.01")
        self.assertFalse((self.update_dir / "v2099.01.01").exists())
        self.assertTrue((self.update_dir / "v2098.01.01").is_dir())

    def test_damaged_download_installs_nothing(self):
        damaged = bytearray(self.ytdlp)
        damaged[-1] ^= 0xFF

        with self.assertRaises(UpdateError) as caught:
            self.install(ytdlp=bytes(damaged))

        self.assertIn("checksum", str(caught.exception))
        self.assertFalse((self.update_dir / "v2099.01.01").exists())

    def test_old_versions_are_cleaned_but_the_running_one_stays(self):
        self.make_update("2096.01.01")
        self.make_update("2097.01.01")
        updater._status["dir"] = "2097.01.01"     # running right now

        self.install()

        self.assertFalse((self.update_dir / "v2096.01.01").exists())
        self.assertTrue((self.update_dir / "v2097.01.01").is_dir())
        self.assertTrue((self.update_dir / "v2099.01.01").is_dir())
        self.assertEqual(updater._read_state()["active"], "2099.01.01")

    def test_unexpected_ejs_release_is_refused(self):
        opener, _ = self.serve()
        wrong = pypi_json("9.9.9", url=EJS_URL, digest=sha(self.ejs))

        with opener, mock.patch.object(updater, "_get_json", return_value=wrong):
            with self.assertRaises(UpdateError):
                updater.install_update(info=self.info())


class SmokeTestTests(unittest.TestCase):
    """Runs the real pre-install check in a separate Python process."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)
        (self.folder / "yt_dlp").mkdir()

    def write(self, init="", version="__version__ = '2099.01.01'\n"):
        (self.folder / "yt_dlp" / "__init__.py").write_text(init)
        (self.folder / "yt_dlp" / "version.py").write_text(version)

    def test_a_working_copy_passes(self):
        self.write()
        updater._smoke_test(self.folder)

    def test_a_copy_that_cannot_be_imported_fails(self):
        self.write(init="raise ImportError('boom')")

        with self.assertRaises(UpdateError) as caught:
            updater._smoke_test(self.folder)

        self.assertIn("doesn't work", str(caught.exception))

    def test_a_copy_without_a_version_fails(self):
        (self.folder / "yt_dlp" / "__init__.py").write_text("")

        with self.assertRaises(UpdateError):
            updater._smoke_test(self.folder)

    def test_skipped_in_a_packaged_app(self):
        self.write(init="raise ImportError('boom')")

        with mock.patch.object(sys, "frozen", True, create=True):
            updater._smoke_test(self.folder)


class ActivateTests(UpdaterCase):
    def setUp(self):
        super().setUp()

        for patcher in (
            mock.patch.dict(sys.modules),
            mock.patch.object(sys, "path", list(sys.path)),
            mock.patch.object(updater, "_installed_version",
                              return_value="2000.01.01"),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

        for name in list(sys.modules):
            if name.split(".")[0] in ("yt_dlp", "yt_dlp_ejs"):
                del sys.modules[name]

    def test_a_second_call_does_nothing(self):
        folder = self.make_update("2099.01.01")

        updater.activate()

        with self.assertNoLogs("zardit", level="WARNING"):
            updater.activate()

        self.assertEqual(sys.path.count(str(folder)), 1)
        self.assertTrue(updater.update_active())

    def test_nothing_to_do_without_an_update(self):
        updater.activate()

        self.assertFalse(updater.update_active())
        self.assertNotIn("yt_dlp", sys.modules)

    def test_loads_the_downloaded_copy_first(self):
        folder = self.make_update("2099.01.01")

        updater.activate()

        import yt_dlp
        import yt_dlp.version

        self.assertTrue(updater.update_active())
        self.assertEqual(sys.path[0], str(folder))
        self.assertEqual(yt_dlp.version.__version__, "2099.01.01")
        self.assertIn(str(folder.resolve()), str(Path(yt_dlp.__file__).resolve()))
        self.assertIn("downloaded update", updater.describe())

    def test_a_broken_copy_is_removed_and_forgotten(self):
        folder = self.make_update("2099.01.01", init_code="raise ImportError('boom')")

        updater.activate()

        self.assertFalse(updater.update_active())
        self.assertNotIn(str(folder), sys.path)
        self.assertNotIn("yt_dlp", sys.modules)
        self.assertFalse(folder.exists())

        state = updater._read_state()
        self.assertNotIn("active", state)
        self.assertIn("boom", state["failed"]["reason"])
        self.assertIn("boom", updater._status["failed"])
        self.assertIn("removed", updater.describe())

    def test_a_syntax_error_is_survived_too(self):
        self.make_update("2099.01.01", init_code="def broken(:\n")

        updater.activate()

        self.assertFalse(updater.update_active())
        self.assertIn("SyntaxError", updater._status["failed"])

    def test_a_folder_that_only_looks_like_yt_dlp_is_removed(self):
        folder = self.update_dir / "v2099.01.01"
        (folder / "yt_dlp").mkdir(parents=True)
        (folder / "yt_dlp" / "version.py").write_text("__version__ = '2099.01.01'\n")
        updater._write_state(active="2099.01.01")

        updater.activate()

        self.assertFalse(updater.update_active())
        self.assertNotIn(str(folder), sys.path)
        self.assertFalse(folder.exists())
        self.assertIsNotNone(updater._status["failed"])

    def test_missing_files_are_forgotten(self):
        updater._write_state(active="2099.01.01")

        updater.activate()

        self.assertNotIn("active", updater._read_state())
        self.assertIn("missing", updater._status["failed"])

    def test_damaged_record_is_forgotten(self):
        updater._write_state(active="../../etc")

        updater.activate()

        self.assertNotIn("active", updater._read_state())

    def test_an_older_update_than_the_installed_copy_is_dropped(self):
        folder = self.make_update("2099.01.01")

        with mock.patch.object(updater, "_installed_version",
                               return_value="2100.01.01"):
            updater.activate()

        self.assertFalse(updater.update_active())
        self.assertTrue(updater._status["superseded"])
        self.assertFalse(folder.exists())
        self.assertNotIn("active", updater._read_state())
        self.assertNotIn(str(folder), sys.path)
        self.assertIn("at least as new", updater.describe())

    def test_does_nothing_if_yt_dlp_is_already_imported(self):
        folder = self.make_update("2099.01.01")
        sys.modules["yt_dlp"] = types.ModuleType("yt_dlp")

        updater.activate()

        self.assertNotIn(str(folder), sys.path)
        self.assertTrue(folder.exists())
        self.assertEqual(updater._read_state()["active"], "2099.01.01")


class RemoveTests(UpdaterCase):
    def test_remove_update(self):
        self.make_update("2098.01.01")
        self.make_update("2099.01.01")
        updater._status["dir"] = "2098.01.01"

        self.assertTrue(updater.remove_update())
        self.assertIsNone(updater.update_version())
        self.assertTrue((self.update_dir / "v2098.01.01").is_dir())
        self.assertFalse((self.update_dir / "v2099.01.01").exists())

    def test_nothing_to_remove(self):
        self.assertFalse(updater.remove_update())

class StateTests(UpdaterCase):
    def test_concurrent_writes_do_not_lose_keys(self):
        def worker(number):
            for index in range(25):
                updater._write_state(**{f"key_{number}_{index}": index})

        threads = [
            threading.Thread(target=worker, args=(number,))
            for number in range(8)
        ]

        for thread in threads:
            thread.start()

        for thread in threads:
            thread.join()

        self.assertEqual(len(updater._read_state()), 8 * 25)

class BackgroundCheckTests(UpdaterCase):
    def setUp(self):
        super().setUp()
        patcher = mock.patch.object(
            updater, "current_version", return_value="2026.10.26"
        )
        patcher.start()
        self.addCleanup(patcher.stop)

    def info(self, available=True):
        return updater.UpdateInfo(
            "2026.10.26", "2099.01.01", available, False, None, None,
            (False, None),
        )

    def test_setting_defaults_to_on_and_can_be_turned_off(self):
        self.assertTrue(updater.auto_check_enabled())

        updater.set_auto_check(False)
        self.assertFalse(updater.auto_check_enabled())

        updater.set_auto_check(True)
        self.assertTrue(updater.auto_check_enabled())

    def test_off_means_no_network(self):
        self.settings["check_updates"] = False

        with mock.patch.object(updater, "check_for_update",
                               side_effect=AssertionError("no network")):
            self.assertIsNone(updater.background_check())

    def test_due_check_reports_a_newer_version(self):
        with mock.patch.object(updater, "check_for_update",
                               return_value=self.info()) as check:
            self.assertEqual(updater.background_check(), "2099.01.01")

        check.assert_called_once_with(updater.QUIET_TIMEOUT)

    def test_due_check_with_nothing_new(self):
        with mock.patch.object(updater, "check_for_update",
                               return_value=self.info(available=False)):
            self.assertIsNone(updater.background_check())

    def test_check_due(self):
        self.assertTrue(updater.check_due())

        updater._write_state(checked=time.time())
        self.assertFalse(updater.check_due())

        updater._write_state(checked=time.time() - 2 * 24 * 3600)
        self.assertTrue(updater.check_due())

        self.assertTrue(updater.check_due(now=time.time() + 25 * 3600))

    def test_between_checks_the_cached_answer_is_used(self):
        updater._write_state(checked=time.time(), latest="2099.01.01")

        with mock.patch.object(updater, "check_for_update",
                               side_effect=AssertionError("no network")):
            self.assertEqual(updater.background_check(), "2099.01.01")

    def test_cached_answer_is_ignored_once_it_is_installed(self):
        updater._write_state(checked=time.time(), latest="2099.01.01")
        self.make_update("2099.01.01")

        self.assertIsNone(updater.cached_update())

    def test_cached_answer_that_is_not_newer(self):
        updater._write_state(latest="2026.10.26")

        self.assertIsNone(updater.cached_update())

    def test_failures_are_quiet_and_not_retried_at_once(self):
        with mock.patch.object(updater, "check_for_update",
                               side_effect=UpdateError("offline")):
            self.assertIsNone(updater.background_check())

        self.assertFalse(updater.check_due())


class RestartTests(unittest.TestCase):
    def test_command_from_source(self):
        with mock.patch.object(sys, "argv", ["gui.py", "--x"]), \
                mock.patch.object(sys, "frozen", False, create=True):
            self.assertEqual(
                updater.restart_command(), [sys.executable, "gui.py", "--x"]
            )

    def test_command_in_a_packaged_app(self):
        with mock.patch.object(sys, "argv", ["/app/ytdl", "--x"]), \
                mock.patch.object(sys, "frozen", True, create=True), \
                mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("APPIMAGE", None)
            self.assertEqual(
                updater.restart_command(), [sys.executable, "--x"]
            )

    def test_command_in_an_appimage(self):
        with mock.patch.object(sys, "argv", ["/tmp/.mount_Zardit/usr/bin/zardit", "--x"]), \
                mock.patch.object(sys, "frozen", True, create=True), \
                mock.patch.dict(os.environ, {"APPIMAGE": "/home/user/Zardit-1.2-x86_64.AppImage"}):
            self.assertEqual(
                updater.restart_command(),
                ["/home/user/Zardit-1.2-x86_64.AppImage", "--x"],
            )

    def test_restart_reports_failure(self):
        with mock.patch.object(updater.subprocess, "Popen",
                               side_effect=OSError("no")):
            self.assertFalse(updater.restart())

    def test_restart_launches_detached(self):
        with mock.patch.object(updater.subprocess, "Popen") as popen:
            self.assertTrue(updater.restart())

        popen.assert_called_once()


class DescribeTests(UpdaterCase):
    def test_built_in_copy(self):
        with mock.patch.object(updater, "current_version",
                               return_value="2026.10.26"):
            self.assertEqual(
                updater.describe(), "2026.10.26 (installed or bundled copy)"
            )

    def test_pending_update_is_mentioned(self):
        self.make_update("2099.01.01")

        with mock.patch.object(updater, "current_version",
                               return_value="2026.10.26"):
            self.assertIn("2099.01.01 will be used after a restart",
                          updater.describe())


if __name__ == "__main__":
    unittest.main()