# YT Downloader — Architecture & Development Handoff Document

## 1. Project Overview
**YT Downloader** (currently `v1.1`) is a cross-platform (Linux, Windows, macOS) Python application for downloading YouTube videos (MP4), audio tracks (MP3 with embedded metadata and cover art), and playlists. It features a clean separation of concerns between core business logic and user interfaces, providing both a Rich-powered CLI (`main.py`) and a PySide6 Qt GUI (`gui.py`).

### Tech Stack & Dependencies
* **Language:** Python 3.10+
* **Core Engine:** `yt-dlp`
* **CLI Framework:** `rich`
* **GUI Framework:** `PySide6` (Qt6)
* **External System Binaries:** `ffmpeg` (required for stream merging and MP3 conversion), `deno` (optional/warned in CLI for JS challenge solving), `notify-send` (optional on Linux for desktop notifications).

---

## 2. File Structure & Module Responsibilities

| File | Responsibility |
| :--- | :--- |
| `config.py` | Defines application constants (`APP_NAME`, `APP_VERSION`, `AUDIO_BITRATES`, `PLAYLIST_QUALITIES`), OS-aware configuration directory resolution (`XDG_CONFIG_HOME` on Linux, `APPDATA` on Windows, `Application Support` on macOS), atomic JSON settings persistence, and XDG user-dirs download path detection. |
| `downloader.py` | Pure backend logic (never prints or prompts). Wraps `yt-dlp`, validates URLs, sanitizes filenames across platforms, estimates stream sizes, tracks progress across multi-stream downloads (`ProgressTracker`), supports thread-safe cancellation (`threading.Event`), and communicates back to callers via a subclassable `Reporter` interface. |
| `main.py` | Interactive terminal UI built with `rich`. Implements `RichReporter` to display progress bars and status panels. |
| `gui.py` | Desktop GUI built with `PySide6`. Implements `TaskThread` (`QThread`) and `QtReporter` (`Signal`-based) to keep network/disk operations off the main Qt event loop. |

---

## 3. Cross-Platform & Windows Compatibility Audit (Verified)
An initial audit confirmed that the codebase is fully compatible with both **Linux** and **Windows**:
* **Filename & Path Safety (`downloader.py`):** `sanitize_name()` strips NTFS-forbidden characters (`: / \ | " ? * < >`), normalizes Unicode (`NFC`), strips trailing periods/spaces, prefixes Windows reserved device names (`CON`, `PRN`, `AUX`, `NUL`, `COM1-9`, `LPT1-9`), and dynamically truncates titles via `max_title_bytes()` on `win32` to stay under the 259-character `WINDOWS_PATH_LIMIT`. Also passes `"windowsfilenames": True` to `yt-dlp`.
* **Settings Storage (`config.py`):** Writes UTF-8 JSON atomically using `.tmp` + `Path.replace()` inside per-user config directories (`~/.config/yt-downloader` on Linux, `%APPDATA%\yt-downloader` on Windows).
* **OS Integration (`main.py`, `gui.py`):** Folder opening uses `os.startfile` on Windows, `open` on macOS, `xdg-open` on Linux (CLI), and `QDesktopServices.openUrl(QUrl.fromLocalFile(...))` in Qt (GUI). Desktop notifications safely guard `notify-send` behind `shutil.which()`.

---

## 4. Changelog of Completed Enhancements

### A. Core Backend (`downloader.py`)
1. **Mixed Video + Playlist URL Detection (`has_playlist_and_video`):**
   * Added `parse_qs` from `urllib.parse`.
   * Implemented `has_playlist_and_video(url)` to detect URLs that reference both a specific video (`v=`, `youtu.be/<id>`, `/shorts/`, `/live/`) and a playlist (`list=`).
2. **Forced Playlist Extraction on Mixed URLs (`fetch_playlist`):**
   * Added `"yes_playlist": True` to the `yt_dlp.YoutubeDL` options inside `fetch_playlist(url)` so that passing a mixed `watch?v=...&list=...` link extracts the playlist entries rather than defaulting to the single video.

### B. Graphical Interface (`gui.py`)
1. **Full Playlist Download Support:**
   * Updated `fetch_everything(url, prefer_playlist=False)` to return a tagged tuple: `("video", info, summary, options, thumbnail)` or `("playlist", playlist)`. Automatically falls back to `fetch_playlist(url)` if `fetch_video_info(url)` raises a playlist-link error.
   * Added `self.mode` (`"video"` or `"playlist"`) to `MainWindow`.
   * Added playlist-specific UI state: displays `"PLAYLIST"` badge in the thumbnail frame, shows video count, and switches the quality dropdown between exact video sizes (`video_options`) and ceiling resolutions (`PLAYLIST_QUALITIES`: `"Up to 1080p"`, etc.).
   * Added `playlist_item = Signal(int, int, str)` to `TaskThread` and wired `QtReporter.playlist_item` to `MainWindow.on_playlist_item`, displaying `(X/Y) Video Title` above the progress bar during playlist downloads.
   * Added `on_playlist_done()` summary status and a **"View failed"** button (`show_failures()`) that opens a `QMessageBox` listing any failed videos and their error reasons.
2. **Mixed URL Choice Prompt (`ask_mixed_url_choice`):**
   * When a user pastes a URL where `has_playlist_and_video(url)` is `True`, a `QMessageBox` prompts them to choose between **"Single Video"**, **"Whole Playlist"**, or **"Cancel"** before starting the fetch thread.
3. **Interactive Scrollable Playlist Checklist (`QListWidget`):**
   * Added `self.playlist_list` (`QListWidget`) displaying all playlist videos with checkboxes (`1. Video Title`), all checked by default.
   * Added **All** (`select_all_videos`) and **None** (`select_no_videos`) quick-toggle buttons.
   * Implemented two-way synchronization (`self._syncing_checks` guard) between the **Range** `QLineEdit` (`on_range_edited`) and the checklist checkboxes (`on_checklist_item_changed` / `sync_range_from_checks`).
   * Updated `start_download()` to pass `self.get_checked_entries()` to `download_playlist()`, allowing arbitrary non-contiguous video selections.
4. **Live Playlist Search Filter (`self.search_input`):**
   * Added a live search `QLineEdit` (`"Filter videos by title…"`) above the checklist connected to `on_search_changed()`.
   * Toggles item visibility via `item.setHidden(not matches)` so hidden items preserve their checked state.
   * Made the **All** and **None** buttons filter-aware: when a filter is active, they only check/uncheck visible (matching) items.
   * Updated `update_selection_count()` to show `Selected: X of Y (Z shown)` when a filter is active.

### C. Terminal Interface (`main.py`)
1. **Mixed & Pure Playlist Routing in Video/Audio Flows:**
   * Updated `show_video_info(url)` to return `(info, is_pure_playlist)`.
   * In `inspect_video()` and `inspect_audio()`, if `has_playlist_and_video(url)` is `True`, the CLI prompts the user if they want to download the whole playlist instead and routes directly to `inspect_playlist(url=url, preset_kind="video"|"audio")`.
   * If a user pastes a pure playlist link into Option 1 (Video) or Option 2 (Audio), it catches the flag and offers to switch directly to `inspect_playlist()` with the format pre-selected.

---

## 5. Current API Signatures Across Modules (Quick Reference for AI)

* **`config.py`:**
  * `APP_NAME: str`, `APP_VERSION: str`, `AUDIO_BITRATES: list[int]`, `PLAYLIST_QUALITIES: list[int]`
  * `load_settings() -> dict`
  * `save_setting(key: str, value: Any) -> None`
  * `get_download_dir() -> Path`
  * `save_download_dir(path: str | Path) -> Path`
* **`downloader.py`:**
  * `validate_youtube_url(url: str) -> bool`
  * `has_playlist_and_video(url: str) -> bool`
  * `fetch_video_info(url: str) -> dict`
  * `summarize_video(info: dict) -> VideoSummary`
  * `fetch_playlist(url: str) -> PlaylistInfo`
  * `parse_range(text: str, total: int) -> tuple[int, int] | None`
  * `quality_options(info: dict) -> list[tuple[int, int | None]]`
  * `download_video(url, quality, output_dir=None, info=None, ceiling=False, reporter=None, cancel=None) -> DownloadResult`
  * `download_audio(url, bitrate, output_dir=None, info=None, reporter=None, cancel=None) -> DownloadResult`
  * `download_playlist(entries, kind, setting, folder_name, reporter=None, cancel=None) -> PlaylistResult`

---

## 6. Roadmap & Next Development Opportunities

The following features and refinements are prime candidates for the next development iterations:

### Priority 1: Playlist & Selection Enhancements
1. **Multi-Range / Comma-Separated Range Parsing:**
   * Upgrade `parse_range()` (or add `parse_selection()`) in `downloader.py` to support complex range expressions like `1-3, 5, 8-10, 15-` in both `main.py` and `gui.py`, syncing seamlessly with the GUI checklist.
2. **Playlist Track Numbering Prefix:**
   * Add an optional toggle (`"Number files in playlist order"`, e.g., `01 - Title [720p] [id].mp4`) in `download_playlist()`, `gui.py` (checkbox), and `main.py` (prompt).
3. **Overall Playlist Progress Bar in GUI:**
   * Add a secondary progress bar or combined indicator in `gui.py` showing overall playlist completion (`Item 3 of 10`) distinct from the current video's byte-level progress bar.

### Priority 2: Format, Subtitles & Media Features
4. **Subtitle / Closed Caption Downloading:**
   * Allow users to download or embed subtitles (`.srt` / embedded subs) with language selection via `yt-dlp`'s `writesubtitles` / `subtitleslangs` / `FFmpegEmbedSubtitle`.
5. **Additional Containers & Audio Formats:**
   * Support `.mkv` / `.webm` (allowing VP9/AV1 4K streams when MP4 H.264 is only available up to 1080p) and `.m4a` / `.opus` / `.flac` for audio.
6. **SponsorBlock / Chapter Splitting:**
   * Optional integration with `yt-dlp`'s SponsorBlock postprocessor to skip/mark sponsor segments or embed YouTube chapter markers.

### Priority 3: OS Packaging, Polish & Reliability
7. **Windows File-Lock Retry on Cancellation (`downloader.py`):**
   * In `cleanup_incomplete_files()`, add a brief retry loop (`time.sleep(0.15)`) on `PermissionError` to ensure `ffmpeg` / `yt-dlp` child processes on Windows have released `.part` file handles before unlinking.
8. **Cross-Platform Desktop Notifications:**
   * Extend `notify()` in `main.py` and `gui.py` to support macOS (`osascript`) and Windows (or Qt's `QSystemTrayIcon.showMessage()` in `gui.py`).
9. **Linux Desktop Integration & Packaging:**
   * Create a `.desktop` launcher file, application icon, `requirements.txt` / `pyproject.toml`, and an install script (`install.sh`) or AppImage/Flatpak build setup for Linux, plus a PyInstaller spec for Windows `.exe` distribution.