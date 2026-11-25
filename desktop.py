"""Desktop integration: notifications and opening folders."""

import os
import shutil
import subprocess
import sys

MAX_NOTIFICATION_CHARS = 200

def notify(title, message):
    """Show a desktop notification if the system supports it (Linux)."""

    if shutil.which("notify-send"):
        message = str(message)

        if len(message) > MAX_NOTIFICATION_CHARS:
            message = message[:MAX_NOTIFICATION_CHARS - 1] + "…"

        # "--" makes sure a video title that starts with "-" is not read as
        # an option.
        subprocess.run(["notify-send", "--", title, message], check=False)

def open_file(path):
    """Open a media file in the user's default application."""

    return open_folder(path)


def open_folder(path):
    """Open a folder in the file manager. Returns True if it was launched."""

    path = str(path)

    try:
        if sys.platform == "win32":
            os.startfile(path)
        elif sys.platform == "darwin":
            subprocess.Popen(["open", path])
        elif shutil.which("xdg-open"):
            subprocess.Popen(
                ["xdg-open", path],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        else:
            return False

    except OSError:
        return False

    return True