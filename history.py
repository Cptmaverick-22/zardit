"""Download history storage and management."""

from datetime import datetime
import json
import uuid
from pathlib import Path

import config

MAX_HISTORY_ENTRIES = 500


def history_file():
    """Path to the user's download history JSON file."""

    return config.CONFIG_DIR / "history.json"


def load_history():
    """Return saved download history entries (newest first)."""

    path = history_file()
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []

    if not isinstance(data, list):
        return []

    return [entry for entry in data if isinstance(entry, dict) and entry.get("file_path")]


def _write_history(entries):
    """Persist history entries atomically."""

    path = history_file()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(entries[:MAX_HISTORY_ENTRIES], indent=2), encoding="utf-8")
    tmp.replace(path)


def add_history_entry(
    title,
    file_path,
    kind,
    quality="",
    channel="",
    video_id="",
    duration=None,
    size=None,
    platform="",
):
    """Record a downloaded video or audio file at the top of history."""

    if not file_path:
        return None

    resolved = str(Path(file_path).expanduser().resolve())
    file_obj = Path(resolved)

    if size is None and file_obj.is_file():
        try:
            size = file_obj.stat().st_size
        except OSError:
            size = None

    entry = {
        "id": uuid.uuid4().hex[:12],
        "video_id": str(video_id or ""),
        "title": str(title or file_obj.stem or "Untitled"),
        "channel": str(channel or ""),
        "platform": str(platform or ""),
        "kind": kind if kind in ("video", "audio", "image") else "video",
        "quality": str(quality or ""),
        "file_path": resolved,
        "folder": str(file_obj.parent),
        "duration": duration,
        "size": size,
        "downloaded_at": datetime.now().isoformat(timespec="seconds"),
    }

    entries = [
        item for item in load_history() if item.get("file_path") != resolved
    ]
    entries.insert(0, entry)

    try:
        _write_history(entries)
    except OSError:
        return None

    return entry


def _delete_disk_file(file_path):
    """Best-effort delete of a downloaded file on disk."""

    if not file_path:
        return False

    try:
        target = Path(file_path)
        if target.is_file():
            target.unlink()
            return True
    except OSError:
        pass

    return False


def remove_history_entry(entry_id, delete_file=False):
    """Remove one entry by ID, optionally deleting its media file.

    Returns (removed_entry_or_None, file_deleted_bool).
    """

    entries = load_history()
    target = None
    remaining = []

    for item in entries:
        if item.get("id") == entry_id and target is None:
            target = item
        else:
            remaining.append(item)

    if target is None:
        return None, False

    deleted = _delete_disk_file(target.get("file_path")) if delete_file else False

    try:
        _write_history(remaining)
    except OSError:
        pass

    return target, deleted


def clear_history(delete_files=False):
    """Clear all history entries, optionally deleting downloaded files.

    Returns (cleared_count, deleted_files_count).
    """

    entries = load_history()
    deleted_count = 0

    if delete_files:
        for item in entries:
            if _delete_disk_file(item.get("file_path")):
                deleted_count += 1

    try:
        _write_history([])
    except OSError:
        pass

    return len(entries), deleted_count
