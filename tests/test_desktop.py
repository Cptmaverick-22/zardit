import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import desktop


class NotifyTests(unittest.TestCase):
    def notify(self, title, message, available=True):
        with mock.patch.object(
            desktop.shutil, "which",
            return_value="/usr/bin/notify-send" if available else None,
        ), mock.patch.object(desktop.subprocess, "run") as run:
            desktop.notify(title, message)

        return run

    def test_a_title_starting_with_a_dash_is_not_read_as_an_option(self):
        run = self.notify("Download complete", "-1 hour loop")

        run.assert_called_once_with(
            ["notify-send", "--", "Download complete", "-1 hour loop"],
            check=False,
        )

    def test_long_messages_are_shortened(self):
        run = self.notify("Done", "x" * 500)

        message = run.call_args.args[0][-1]

        self.assertEqual(len(message), desktop.MAX_NOTIFICATION_CHARS)
        self.assertTrue(message.endswith("…"))

    def test_short_messages_are_unchanged(self):
        run = self.notify("Done", "short")

        self.assertEqual(run.call_args.args[0][-1], "short")

    def test_non_text_messages_are_accepted(self):
        run = self.notify("Done", None)

        self.assertEqual(run.call_args.args[0][-1], "None")

    def test_nothing_runs_without_notify_send(self):
        run = self.notify("Done", "message", available=False)

        run.assert_not_called()


if __name__ == "__main__":
    unittest.main()