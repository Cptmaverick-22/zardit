import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config


class DownloadFolderTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.default = self.root / "default"

        for patcher in (
            mock.patch.object(config, "CONFIG_DIR", self.root / "config"),
            mock.patch.object(
                config, "SETTINGS_FILE", self.root / "config" / "settings.json"
            ),
            mock.patch.object(
                config, "default_download_dir", lambda: self.default
            ),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

    def save(self, **settings):
        config.CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        config.SETTINGS_FILE.write_text(json.dumps(settings))

    def test_saved_folder_is_used_and_created(self):
        mine = self.root / "mine"
        self.save(download_dir=str(mine))

        self.assertEqual(config.get_download_dir(), mine.resolve())
        self.assertTrue(mine.is_dir())

    def test_without_a_saved_folder_the_default_is_used(self):
        self.assertEqual(config.get_download_dir(), self.default.resolve())
        self.assertTrue(self.default.is_dir())

    def test_looking_does_not_create_anything(self):
        mine = self.root / "mine"
        self.save(download_dir=str(mine))

        self.assertEqual(config.get_download_dir(create=False), mine.resolve())
        self.assertFalse(mine.exists())

        config.SETTINGS_FILE.unlink()

        self.assertEqual(
            config.get_download_dir(create=False), self.default.resolve()
        )
        self.assertFalse(self.default.exists())

    def test_a_saved_path_that_is_a_file_falls_back_and_says_so(self):
        broken = self.root / "a_file"
        broken.write_text("x")
        self.save(download_dir=str(broken))

        with self.assertLogs("zardit", level="WARNING") as logged:
            folder = config.get_download_dir()

        self.assertEqual(folder, self.default.resolve())
        self.assertIn("can't be used", logged.output[0])
        self.assertIn(str(broken), logged.output[0])

    def test_a_folder_that_cannot_be_created_falls_back_and_says_so(self):
        blocker = self.root / "a_file"
        blocker.write_text("x")
        self.save(download_dir=str(blocker / "inside"))

        with self.assertLogs("zardit", level="WARNING"):
            folder = config.get_download_dir()

        self.assertEqual(folder, self.default.resolve())

    def test_nonsense_settings_are_ignored_quietly(self):
        for value in (None, 5, "", "   ", ["x"]):
            with self.subTest(value=value):
                self.save(download_dir=value)

                with self.assertNoLogs("zardit", level="WARNING"):
                    folder = config.get_download_dir(create=False)

                self.assertEqual(folder, self.default.resolve())


if __name__ == "__main__":
    unittest.main()