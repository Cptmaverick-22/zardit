import logging
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import logs
from logs import _PrivacyFilter, diagnostics_text, redact_sensitive, strip_tracking


class StripTrackingTests(unittest.TestCase):
    def test_removes_tracking_parameters(self):
        cases = [
            ("https://youtu.be/abc?si=XYZ", "https://youtu.be/abc"),
            ("https://youtu.be/abc?si=XYZ&t=30", "https://youtu.be/abc?t=30"),
            ("https://youtu.be/abc?t=30&si=XYZ", "https://youtu.be/abc?t=30"),
            ("https://www.youtube.com/watch?v=abc&list=PL1&si=XYZ",
             "https://www.youtube.com/watch?v=abc&list=PL1"),
            ("https://www.youtube.com/watch?v=abc&feature=share&pp=ygU",
             "https://www.youtube.com/watch?v=abc"),
            ("https://x.com/?si=a&utm_source=b&v=1", "https://x.com/?v=1"),
            ("https://x.com/?utm_medium=a&utm_campaign=b", "https://x.com/"),
        ]

        for text, expected in cases:
            with self.subTest(text=text):
                self.assertEqual(strip_tracking(text), expected)

    def test_other_text_is_left_alone(self):
        for text in (
            "https://www.youtube.com/watch?v=abc&list=PL1&t=30",
            "Downloading 1 format(s): 251",
            "si=5 and pp=3 are not parameters here",
            "https://x.com/?app=1&support=2",
            "",
        ):
            with self.subTest(text=text):
                self.assertEqual(strip_tracking(text), text)

    def test_links_inside_a_sentence(self):
        self.assertEqual(
            strip_tracking("Extracting URL: https://youtu.be/abc?si=XYZ done"),
            "Extracting URL: https://youtu.be/abc done",
        )


class FilterTests(unittest.TestCase):
    def record(self, message, *args):
        return logging.LogRecord("zardit", logging.INFO, "f", 1, message, args, None)

    def test_message_is_cleaned(self):
        record = self.record("Fetched %s", "https://youtu.be/abc?si=XYZ")

        self.assertTrue(_PrivacyFilter().filter(record))
        self.assertEqual(record.getMessage(), "Fetched https://youtu.be/abc")

    def test_clean_messages_keep_their_formatting(self):
        record = self.record("%d items, 100%% done", 5)

        _PrivacyFilter().filter(record)

        self.assertEqual(record.getMessage(), "5 items, 100% done")

    def test_percent_signs_survive_cleaning(self):
        # A literal % in a message without arguments must not break it.
        plain = self.record("100% done https://youtu.be/abc?si=X")
        _PrivacyFilter().filter(plain)
        self.assertEqual(plain.getMessage(), "100% done https://youtu.be/abc")

        # A % that only appears after formatting must not be formatted twice.
        formatted = self.record("%s at %d%%", "https://youtu.be/abc?si=X", 50)
        _PrivacyFilter().filter(formatted)
        self.assertEqual(formatted.getMessage(), "https://youtu.be/abc at 50%")

    def test_redacts_session_cookies_auth_headers_and_ig_tokens(self):
        raw = (
            "GET https://www.instagram.com/p/abc/?igsh=SECRET123&xmt=X9 "
            "Cookie: sessionid=supersecret; csrftoken=csrf123; ds_user_id=42\n"
            "Authorization: Bearer tok_999"
        )
        cleaned = redact_sensitive(raw)
        self.assertNotIn("SECRET123", cleaned)
        self.assertNotIn("supersecret", cleaned)
        self.assertNotIn("csrf123", cleaned)
        self.assertNotIn("tok_999", cleaned)
        self.assertIn("<redacted>", cleaned)

        rec = self.record("sessionid=my_secret_cookie https://instagram.com/p/x/?ig_rid=trace1")
        _PrivacyFilter().filter(rec)
        msg = rec.getMessage()
        self.assertNotIn("my_secret_cookie", msg)
        self.assertNotIn("trace1", msg)

    def test_diagnostics_text_redacts_cookies_and_home_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            fake_log = Path(tmp) / "zardit.log"
            fake_log.write_text(
                f"{Path.home()}/Downloads cookie sessionid=leak123 csrftoken=leak456\n",
                encoding="utf-8",
            )
            with mock.patch.object(logs, "LOG_FILE", fake_log):
                report = diagnostics_text(10)
            self.assertNotIn("leak123", report)
            self.assertNotIn("leak456", report)
            self.assertNotIn(str(Path.home()), report)


if __name__ == "__main__":
    unittest.main()