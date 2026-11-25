<div align="center">

<img src="assets/zardit.png" width="120" height="120" alt="Zardit Logo" />

# Zardit

***Blaze through videos, audio & playlists across every platform.***

[![Latest Release](https://img.shields.io/github/v/release/Cptmaverick-22/zardit?style=for-the-badge&color=e63946)](https://github.com/Cptmaverick-22/zardit/releases/latest)
[![Linux CI](https://img.shields.io/github/actions/workflow/status/Cptmaverick-22/zardit/ci-linux.yml?branch=main&label=Linux%20CI&style=for-the-badge)](https://github.com/Cptmaverick-22/zardit/actions/workflows/ci-linux.yml)
[![Windows CI](https://img.shields.io/github/actions/workflow/status/Cptmaverick-22/zardit/ci-windows.yml?branch=main&label=Windows%20CI&style=for-the-badge)](https://github.com/Cptmaverick-22/zardit/actions/workflows/ci-windows.yml)
[![Python](https://img.shields.io/badge/Python-3.11%E2%80%933.15-3776AB?style=for-the-badge&logo=python&logoColor=white)](https://www.python.org/)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue?style=for-the-badge)](LICENSE)

Download **MP4 videos**, **MP3 / original audio** with embedded cover art, **images** (Original, PNG, JPG, WebP), and **multi-item playlists & galleries** from **YouTube, Instagram, Facebook, Reddit, X (Twitter), Threads**, and more — via a modern Qt desktop app or a Rich terminal interface.

[Downloads](#-installation) · [Features](#-features) · [Usage](#-using-the-window-app) · [Formats](#-quality-and-formats) · [Troubleshooting](#-troubleshooting) · [Developers](#-for-developers)

</div>

---

> Only download content that you own or have permission to download. See [Legal and responsible use](#-legal-and-responsible-use).

## Supported Platforms & Interfaces

| Platform | Videos | Audio | Images / Galleries |
|---|:---:|:---:|:---:|
| **YouTube** | MP4 (144p–2160p) + Playlists | MP3 / M4A | — |
| **Instagram** | Reels & Posts | MP3 / Original | Single & Multi-photo Carousels |
| **Facebook** | Videos & Reels | MP3 / Original | Single & Multi-photo Posts |
| **Reddit** | Videos | MP3 / Original | Images |
| **X (Twitter)** | Videos | MP3 / Original | Images |
| **Threads** | Videos | MP3 / Original | Images & Carousels |

Zardit includes **two interfaces** that share the same settings, download history, and download folder:

- **Desktop App (`gui.py` / `Zardit`)** — Qt (PySide6) interface with **Auto / Light / Dark** themes, one-tap **Paste & Fetch**, estimated file sizes, interactive gallery/carousel previews, and built-in **History** playback & management.
- **Terminal App (`main.py` / `zardit-cli`)** — Clean, themed Rich menu-driven CLI with clipboard paste and history playback.

---

## ✨ Features

### Downloading
- **Video as MP4** — Structured resolution list with estimated file sizes per stream (including probed estimates across platforms).
- **Audio as MP3 or Original** — 128, 192, 256, or 320 kbps MP3, or original audio without re-encoding, with embedded cover art and metadata tags.
- **Images & Carousels** — Save in **Original** format or convert to **PNG**, **JPG**, or **WebP** with uncropped aspect ratios, preview thumbnails/lightbox, and per-item selection for multi-photo Instagram/Facebook posts.
- **Playlists & Smart Links** — Select everything, enter a range like `1-3, 5, 8-10`, filter by title, or pick individual items. Pasting a video link inside a playlist prompts whether to download the single item or the whole playlist.

### Reliability & Convenience
- **Live Progress & Clean Cancel** — Speed, downloaded bytes, ETA, and automatic cleanup of partial temporary files on cancellation.
- **Resume & Deduplication** — Already-downloaded items at the same quality are skipped automatically.
- **Automatic Retries & Disk Checks** — Retries transient HTTP `403` / `429` / `5xx` errors and verifies free disk space before downloading.
- **In-app `yt-dlp` Updater** — One-click PyPI update with SHA-256 verification and safe rollback (no manual pip commands required).
- **Desktop Integration** — Linux desktop notifications (`notify-send`), Windows taskbar/Start Menu integration, **Open folder** button, and **Copy diagnostics** for bug reports.

---

## 📦 Installation

For most users on **Linux** and **Windows**, prebuilt releases on [GitHub Releases](https://github.com/Cptmaverick-22/zardit/releases/latest) bundle Python, PySide6, `yt-dlp`, **FFmpeg**, and **Deno** — no extra setup needed.

| OS | Status | Recommended Package |
|---|---|---|
| **Linux (x86_64)** | Tested (Fedora & Ubuntu CI) | `Zardit-<version>-x86_64.AppImage` |
| **Windows 10 / 11 (x64)** | Tested (Windows CI) | `Zardit-<version>-win64-setup.exe` or `portable.zip` |
| **macOS** | Source install supported | Run from source (`brew` + `.venv`) |

### Linux (AppImage — Recommended)

Download `Zardit-<version>-x86_64.AppImage` (and optional `SHA256SUMS.txt`) from [GitHub Releases](https://github.com/Cptmaverick-22/zardit/releases/latest):

```bash
chmod +x Zardit-*-x86_64.AppImage
./Zardit-*-x86_64.AppImage
```

- Launch Terminal UI: `./Zardit-*-x86_64.AppImage --cli`
- Print diagnostics: `./Zardit-*-x86_64.AppImage --diagnostics`
- Install app-menu icon & `zardit` / `zardit-cli` commands locally:
  ```bash
  ./packaging/linux/install_local.sh ~/Applications/Zardit-1.2-x86_64.AppImage
  ```
  *(Run `./packaging/linux/install_local.sh` without arguments to create desktop/CLI launchers backed by your local `.venv`.)*

### Windows (Installer & Portable ZIP — Recommended)

Download either package from [GitHub Releases](https://github.com/Cptmaverick-22/zardit/releases/latest):

- **Installer (`Zardit-<version>-win64-setup.exe`)** — Installs Zardit without requiring administrator rights, adds Start Menu / optional Desktop shortcuts, and optionally adds `zardit-cli` to your user `PATH`.
- **Portable (`Zardit-<version>-win64-portable.zip`)** — Extract anywhere and run `Zardit.exe` (GUI) or `zardit-cli.exe` in **Windows Terminal**.

---

<details>
<summary><strong>Install from Source (Linux, Windows, macOS)</strong></summary>

#### Requirements
- **Python 3.11 – 3.15**
- **FFmpeg** (merges video/audio, converts MP3/images, embeds cover art)
- **Deno** (solves YouTube JavaScript extraction challenges)

#### Linux (from source)
*Fedora (with full FFmpeg from RPM Fusion for MP3 encoding):*
```bash
sudo dnf install python3 git curl unzip
sudo dnf install https://mirrors.rpmfusion.org/free/fedora/rpmfusion-free-release-$(rpm -E %fedora).noarch.rpm
sudo dnf swap ffmpeg-free ffmpeg --allowerasing
```

*Ubuntu / Debian / Linux Mint (Ubuntu 24.04+):*
```bash
sudo apt update
sudo apt install python3 python3-venv python3-pip git curl unzip ffmpeg libxcb-cursor0
```

*Install Deno, clone, and run:*
```bash
curl -fsSL https://deno.land/install.sh | sh
export PATH="$HOME/.deno/bin:$PATH"

git clone https://github.com/Cptmaverick-22/zardit.git
cd zardit
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python zardit_app.py
```

#### Windows (from source)
Use **Windows Terminal** (recommended over `cmd.exe`):
```powershell
winget install Gyan.FFmpeg
winget install DenoLand.Deno
# Reopen terminal after installing, then:
git clone https://github.com/Cptmaverick-22/zardit.git
cd zardit
py -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python zardit_app.py
```
*(If PowerShell blocks activation scripts, run `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` once.)*

#### macOS (from source)
```bash
brew install python ffmpeg deno git
git clone https://github.com/Cptmaverick-22/zardit.git
cd zardit
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python zardit_app.py
```

</details>

---

## 🚀 Running the App

| Action | Linux / macOS | Windows |
|---|---|---|
| Activate `.venv` | `source .venv/bin/activate` | `.venv\Scripts\Activate.ps1` |
| Unified launcher (GUI) | `python zardit_app.py` | `python zardit_app.py` |
| Unified launcher (CLI) | `python zardit_app.py --cli` | `python zardit_app.py --cli` |
| Window app directly | `python gui.py` | `python gui.py` |
| Terminal app directly | `python main.py` | `python main.py` |
| Diagnostics report | `python zardit_app.py --diagnostics` | `python zardit_app.py --diagnostics` |

---

## 🖥️ Using the Window App

1. **Paste a link** (YouTube, Instagram, Facebook, Reddit, X, or Threads) and click **Paste & Fetch** or **Fetch**.
2. Review the preview card (thumbnail, platform badge, title, author, duration/date, or gallery count).
3. Choose **Video (MP4)**, **Audio (MP3 / M4A)**, or **Image** (when available).
4. Pick your **quality or format** — your last choice is remembered automatically.
   - For playlists or multi-photo galleries, select items via checkboxes, ranges (`1-3, 5, 8-10`), title filter, or the image preview modal.
5. Check **Save to** (click **Change…** to pick another folder) and click **Download**.
6. Switch to the **History** tab at any time to play, open, or clean up past downloads, or toggle **Auto / Light / Dark** themes in the top bar.

---

## 💻 Using the Terminal App

Start it with `zardit-cli`, `./Zardit-*-x86_64.AppImage --cli`, or `python main.py`:

```text
╭────────── Zardit v1.2 ──────────╮
│                                 │
│  [1] Download Video             │
│  [2] Download Audio             │
│  [3] Download Playlist          │
│  [I] Download Image             │
│  [4] Download Location          │
│  [5] Update yt-dlp              │
│  [H] Download History           │
│  [T] Switch Theme               │
│  [6] Exit                       │
│                                 │
╰─────────────────────────────────╯
```

Type **Q** at prompts to go back, or press **Ctrl+C** during a download to cancel cleanly.

---

## 🎞️ Quality and Formats

- **Video (MP4):** Resolutions from `144p` to `2160p` (whichever the source provides).
  - Up to **1080p**, Zardit prefers **H.264** for maximum device compatibility.
  - **1440p and 2160p** use **AV1** on YouTube (plays in modern browsers, VLC, and mpv; choose 1080p or lower for older TVs/phones).
- **Audio:**
  - **MP3** (`128`, `192`, `256`, `320 kbps`) with embedded cover art and tags.
  - **Original audio (no conversion)** saved as `.m4a` with cover art and tags — faster and lossless relative to the source stream.
- **Images:**
  - **Original** (native resolution without re-encoding) or converted to **PNG** (lossless), **JPG**, or **WebP** while preserving original aspect ratios.

> **Note:** Live streams, upcoming premieres, and private/age-restricted/members-only/region-blocked posts requiring sign-in are not supported.

---

## 🔄 Updating `yt-dlp`

Platforms change frequently, and an outdated `yt-dlp` is the most common cause of sudden fetch or download errors.

- **In-app:** Click **Update yt-dlp** in the window (or choose **Update yt-dlp** in the CLI). Zardit checks PyPI, verifies SHA-256 checksums, tests the package (for source installs), and activates it on restart.
- **Rollback:** Open the update dialog again and choose **"Use the built-in version"** to revert at any time.
- **Daily check:** Zardit checks PyPI at most once per day for a notice banner and never installs updates silently. Disable it in the update dialog or by setting `"check_updates": false` in `settings.json`.

---

## 📁 Where Files and Settings Are Stored

Downloads go to **`Zardit`** inside your system `Downloads` folder by default (respecting XDG `user-dirs.dirs` on Linux). Playlists and galleries are saved into subfolders.

Per-user config, history, logs, and `yt-dlp` updates live in:

| System | Config Directory |
|---|---|
| **Linux** | `~/.config/zardit/` |
| **Windows** | `%APPDATA%\zardit\` |
| **macOS** | `~/Library/Application Support/zardit/` |

| File / Folder | Purpose |
|---|---|
| `settings.json` | Saved download folder, quality/format defaults, theme, and update check preference. |
| `history.json` | Download history entries shared between GUI and CLI. |
| `zardit.log` | Rotating application log (`.1`, `.2`). |
| `updates/` | Downloaded PyPI `yt-dlp` updates. |

---

## 🏷️ File Names

```text
<Title> [<quality>] [<media id>].<extension>
```

Examples:
```text
Loose Emotions EP 12 [720p] [ZG98zgaVIGg].mp4
Loose Emotions EP 12 [192kbps] [ZG98zgaVIGg].mp3
Loose Emotions EP 12 [original] [ZG98zgaVIGg].m4a
```

Titles are sanitized across Linux, Windows, and macOS (`:` -> ` -`, `/ \ |` -> `-`, `"` -> `'`, `? * < >` removed, Windows reserved names like `CON`/`NUL` prefixed with `_`, multi-byte UTF-8 truncation to 150 bytes or Windows path-length safe limits).

---

## 🔒 Privacy

- No accounts, telemetry, or usage tracking.
- Network requests go only to the media source (via `yt-dlp` / thumbnail previews) and PyPI for optional `yt-dlp` version checks.
- Share-tracking query parameters (such as `si=`, `utm_source=`) are stripped in logs, and diagnostics replace your home directory with `~`.

---

## 🛠️ Troubleshooting

- **"Could not load the Qt platform plugin" (Linux):** Install `xcb-util-cursor` (Fedora) or `libxcb-cursor0` (Ubuntu/Debian).
- **Wayland terminal messages (`qt.qpa.wayland`):** Harmless desktop-integration notices; the app works normally.
- **FFmpeg or Deno not found:** Check `ffmpeg -version` and `deno --version` (or use the AppImage / Windows release bundles which include both).
- **MP3 conversion or cover art fails on Fedora:** Swap `ffmpeg-free` for full `ffmpeg` from RPM Fusion.
- **HTTP 403 / 429 or missing formats:** Click **Update yt-dlp** and retry later if rate-limited.
- **Garbled characters in Windows CLI:** Run `zardit-cli.exe` in **Windows Terminal** instead of legacy `cmd.exe`.

### Reporting a Problem
Click **Copy diagnostics** in the GUI (or run `zardit_app.py --diagnostics` / `zardit-cli.exe --diagnostics`), check the **Recent log** section for anything private, and attach it to a [GitHub Issue](https://github.com/Cptmaverick-22/zardit/issues).

---

## 👨‍💻 For Developers

```text
zardit/
├── zardit_app.py          # Unified GUI/CLI launcher (used by AppImage & Windows builds)
├── gui.py                 # Desktop app (PySide6)
├── main.py                # Terminal app (Rich)
├── downloader.py          # Core extraction, downloads, playlists, galleries & image conversion
├── updater.py             # PyPI yt-dlp updater (stdlib only)
├── config.py              # Constants, defaults, and settings persistence
├── theme.py               # Light / Dark / Auto palettes and QSS
├── history.py             # Shared download history
├── logs.py                # Rotating logs & diagnostics
├── desktop.py             # Notifications & file/folder openers
├── assets/                # SVG, PNG, and multi-resolution ICO icons
├── packaging/
│   ├── linux/             # AppImage build script, AppRun, .desktop, AppStream, local installer
│   └── windows/           # Windows PyInstaller spec, PowerShell build script, Inno Setup (.iss)
├── .github/workflows/     # Path-filtered Linux/Windows CI & release workflows
├── requirements.txt
├── requirements-dev.txt
└── tests/
```

> **Important:** `updater.activate()` must run before anything imports `yt_dlp`. Keep it at the top of any new entry point.

### Run Checks Locally
```bash
pip install -r requirements-dev.txt
QT_QPA_PLATFORM=offscreen python -m unittest discover -s tests -v
python -m pyflakes config.py desktop.py downloader.py gui.py history.py logs.py main.py theme.py updater.py zardit_app.py tests/test_ui.py tests/test_names.py
```

### Build Packages Locally
- **Linux AppImage:**
  ```bash
  pip install pyinstaller
  ./packaging/linux/build_appimage.sh   # or pass --use-system-tools
  ```
- **Windows x64 Installer & Portable ZIP:**
  ```powershell
  pip install pyinstaller
  powershell -ExecutionPolicy Bypass -File packaging\windows\build_windows.ps1
  ```

---

## 📜 Legal and Responsible Use

This software is intended only for downloading content that you own, have permission to download, or are otherwise legally authorized to download. Respect copyright laws and platform Terms of Service.

## 🗺️ Roadmap

- Android APK companion app with Share-sheet support
- Faster playlists by preparing the next item while one downloads
- Subtitles and time-range clip downloads
- WebM / MKV format options for resolutions above 1080p

## License

Licensed under the [GNU General Public License v3.0 or later (GPL-3.0-or-later)](LICENSE). Copyright © 2026 Cptmaverick-22.
