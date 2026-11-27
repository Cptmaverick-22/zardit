import ast
import unittest
from pathlib import Path

try:
    from pyflakes import checker, messages
except ImportError:          # pip install pyflakes
    checker = messages = None

ROOT = Path(__file__).resolve().parent.parent
FILES = [
    "main.py", "gui.py", "downloader.py", "updater.py", "logs.py",
    "config.py", "desktop.py", "theme.py", "history.py", "zardit_app.py",
    "zardit_core/__init__.py", "zardit_core/errors.py", "zardit_core/formats.py",
    "zardit_core/instagram.py", "zardit_core/naming.py", "zardit_core/urls.py",
]


@unittest.skipIf(checker is None, "pyflakes is not installed")
class UndefinedNameTests(unittest.TestCase):
    def test_no_undefined_names(self):
        kinds = (
            messages.UndefinedName,
            messages.UndefinedLocal,
            messages.UndefinedExport,
        )
        problems = []

        for name in FILES:
            path = ROOT / name

            if not path.exists():
                continue

            tree = ast.parse(path.read_text(encoding="utf-8"), str(path))

            for message in checker.Checker(tree, str(path)).messages:
                if isinstance(message, kinds):
                    text = message.message % message.message_args
                    problems.append(f"{name}:{message.lineno}: {text}")

        self.assertEqual(problems, [], "\n".join(problems))