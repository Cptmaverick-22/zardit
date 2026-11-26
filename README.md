<div align="center">

<img src="assets/zardit.png" width="120" height="120" alt="Zardit Logo" />

# Zardit

***Blaze through videos, audio & playlists across every platform.***

[![Latest Release](https://img.shields.io/github/v/release/Cptmaverick-22/zardit?style=for-the-badge&color=e63946)](https://github.com/Cptmaverick-22/zardit/releases/latest)
[![Linux CI](https://img.shields.io/github/actions/workflow/status/Cptmaverick-22/zardit/ci-linux.yml?branch=main&label=Linux%20CI&style=for-the-badge)](https://github.com/Cptmaverick-22/zardit/actions/workflows/ci-linux.yml)
[![Windows CI](https://img.shields.io/github/actions/workflow/status/Cptmaverick-22/zardit/ci-windows.yml?branch=main&label=Windows%20CI&style=for-the-badge)](https://github.com/Cptmaverick-22/zardit/actions/workflows/ci-windows.yml)
[![Android CI](https://img.shields.io/github/actions/workflow/status/Cptmaverick-22/zardit/ci-android.yml?branch=main&label=Android%20CI&style=for-the-badge)](https://github.com/Cptmaverick-22/zardit/actions/workflows/ci-android.yml)
[![Python](https://img.shields.io/badge/Python-3.11%E2%80%933.15-3776AB?style=for-the-badge&logo=python&logoColor=white)](https://www.python.org/)
[![Android](https://img.shields.io/badge/Android-7.0%2B%20(arm64)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Cptmaverick-22/zardit/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue?style=for-the-badge)](LICENSE)

Download **MP4 videos**, **MP3 / original M4A audio**, **images** (Original, PNG, JPG, WebP), and **multi-item playlists & carousels** from **YouTube, Instagram, Facebook, Reddit, X (Twitter), Threads**, and more — via a Qt desktop app, a Rich terminal interface, or a Material 3 Android app.

[Downloads](#-installation) · [Features](#-features) · [Desktop](#-using-the-window-app) · [Terminal](#-using-the-terminal-app) · [Android](#-using-the-android-app) · [Formats](#-quality-and-formats) · [Troubleshooting](#-troubleshooting) · [Developers](#-for-developers)

</div>

---

> Only download content that you own or have permission to download. See [Legal and responsible use](#-legal-and-responsible-use).

## Supported Platforms & Interfaces

| Platform | Videos | Audio | Images / Galleries |
|---|:---:|:---:|:---:|
| **YouTube** | MP4 (144p–2160p desktop / up to 1080p Android) + Playlists | MP3 (desktop) / M4A | — |
| **Instagram** | Reels & Posts (uncropped preview + optional IG sign-in on Android) | MP3 (desktop) / Original | Single & Multi-photo Carousels |
| **Facebook** | Videos & Reels | MP3 (desktop) / Original | Single & Multi-photo Posts |
| **Reddit** | Videos | MP3 (desktop) / Original | Images |
| **X (Twitter)** | Videos | MP3 (desktop) / Original | Images |
| **Threads** | Videos (including `/share/` links) | MP3 (desktop) / Original | Images & Carousels |

Zardit includes **three interfaces** across desktop and mobile:

- **Desktop App (`gui.py` / `Zardit`)** — Qt (PySide6) interface with **Auto / Light / Dark** themes, one-tap **Paste & Fetch**, estimated file sizes, interactive gallery/carousel previews, and built-in **History** playback & management.
- **Terminal App (`main.py` / `zardit-cli`)** — Clean, themed Rich menu-driven CLI sharing settings and download history with the desktop app.
- **Android App (`android/`)** — Lightweight Material 3 Jetpack Compose companion app (`arm64-v8a`) with Android **Share-sheet** support, **Auto / Light / Dark** themes, foreground download service & notifications, native `MediaMuxer` MP4 assembly, Storage Access Framework (`Downloads/Zardit`) saving, optional local AES-256-GCM encrypted Instagram login, and **History** management.

---

## ✨ Features

### Downloading
- **Video as MP4** — Structured, deduplicated resolution list with estimated file sizes per stream (including probed stream estimates across platforms).
- **Audio** — On desktop: **128, 192, 256, or 320 kbps MP3** or original audio with embedded cover art and metadata tags. On Android: lightweight **Original / M4A** audio without bundled FFmpeg.
- **Images & Carousels** — Save in **Original** format or convert to **PNG**, **JPG**, or **WebP** with uncropped aspect ratios, preview thumbnails/lightbox, and per-item selection for multi-photo Instagram/Facebook carousels.
- **Playlists & Smart Links** — Select everything, enter a range like `1-3, 5, 8-10`, filter by title (desktop), or pick individual items. Pasting a video link inside a playlist prompts whether to download the single item or the whole playlist.

### Reliability & Convenience
- **Live Progress & Clean Cancel** — Speed, downloaded bytes, ETA (desktop), status updates, and automatic cleanup of partial temporary files on cancellation.
- **Resume & Deduplication** — Already-downloaded items at the same quality are skipped cleanly.
- **Automatic Retries & Disk Checks** — Retries transient HTTP `403` / `429` / `5xx` errors and verifies free disk space before downloading.
- **In-app Engine / `yt-dlp` Updater** — One-click PyPI update with SHA-256 verification and rollback on desktop; in-app **Engine** update button with live version tracking on Android.
- **Platform Integration & Diagnostics** — Linux desktop notifications (`notify-send`), Windows Start Menu/taskbar integration, Android **Share** menu intent & foreground notifications, plus **Copy diagnostics** / **Copy Diagnostics** (`--diagnostics` on desktop) for fast bug reports.

---

## 📦 Installation

On **Linux** and **Windows**, prebuilt releases on [GitHub Releases](https://github.com/Cptmaverick-22/zardit/releases/latest) bundle Python, PySide6, `yt-dlp`, **FFmpeg**, and **Deno** — no extra setup needed. The **Android APK** ships a slim `arm64-v8a` build minified with R8 and uses Android's native `MediaMuxer` instead of bundling FFmpeg.

| OS | Status | Recommended Package |
|---|---|---|
| **Linux (x86_64)** | Tested (Fedora & Ubuntu CI) | `Zardit-<version>-x86_64.AppImage` |
| **Windows 10 / 11 (x64)** | Tested (Windows CI) | `Zardit-<version>-win64-setup.exe` or `portable.zip` |
| **Android 7.0+ (`arm64-v8a`)** | Tested (Android CI) | `Zardit-<version>-android-arm64.apk` |
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

### Android (APK — `arm64-v8a`)

Download `Zardit-<version>-android-arm64.apk` from [GitHub Releases](https://github.com/Cptmaverick-22/zardit/releases/latest):

1. Open the downloaded `.apk` on your Android 7.0+ (`arm64-v8a`) device and allow **"Install unknown apps"** if prompted.
2. Grant notification permission when asked (used for background foreground-service progress notifications).
3. Verify `SHA256SUMS.txt` if desired before installing.

> **Note:** Android releases target `arm64-v8a` devices and currently use the standard Android release build without bundling FFmpeg (~30–40 MB install).

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
| Force CLI theme | `python main.py --theme dark` | `python main.py --theme dark` |
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
  ╭─ ▶ Zardit v1.2 ────────────────────────────────────────────────────────────╮
  │                                                                            │
  │  Tagline  Blaze through videos, audio & playlists across every platform.   │
  │  Save to  ~/Downloads/Zardit                                               │
  │  Theme    Auto (Dark)                                                      │
  │  License  GPL-3.0-or-later                                                 │
  │  Sites    YouTube · Instagram · Facebook · Reddit · X · Threads            │
  │  Tools    FFmpeg ✔   Deno ✔                                                │
  │  ────────────────────────────────────────────────────────────────────────  │
  │    [1]    Download Video                              Single video as MP4  │
  │    [2]    Download Audio                            MP3 or original audio  │
  │    [3]    Download Playlist                          Batch select & range  │
  │    [I]    Download Image                     Photo, gallery, or thumbnail  │
  │    [4]    Download Location                          Change output folder  │
  │    [5]    Update yt-dlp                          Check or install updates  │
  │    [H]    Download History                        View, play & manage (3)  │
  │    [T]    Switch Theme                               Current: Auto (Dark)  │
  │    [6]    Exit                                           Quit application  │
  │                                                                            │
  ╰───────────────────────────────────────────────────── Enter a choice below ─╯

  › Choose an option (1-6, I = image, H = history, T = theme): 
```

- Enter **`1`–`6`**, **`I`**, **`H`**, or **`T`** (`tc` to quick-cycle **Auto / Light / Dark**, or launch with `--theme auto|light|dark`).
- Type **`Q`** at prompts to go back, or press **`Ctrl+C`** during a download to cancel cleanly.

---

## 📱 Using the Android App

1. **Paste or Share a link:** Paste any supported URL and tap **Paste & Fetch** (or **Fetch**), or use Android's **Share** menu from YouTube, Instagram, Threads, Facebook, Reddit, or X directly into **Zardit**.
2. **Pick your mode & quality:** Switch between **Video (MP4)**, **Audio** (shown when audio tracks are exposed), or **Image** (**Original**, **PNG**, **JPG**, or **WebP**). For playlists and multi-photo carousels, toggle items or use range syntax like `1-3, 5`.
3. **Choose where to save:** Defaults to `Downloads/Zardit` (tap **Change…** to pick another folder with Android's native folder picker).
4. **Download & Manage History:** Track progress in-app or from the notification bar, cancel cleanly any time, and open the **History** tab to **Open**, **Share**, or delete items (choose **History only** or **Delete file too**).
5. **Optional Instagram sign-in (`IG`):** Tap **IG** in the top bar only if you need to fetch login-required or age-restricted Instagram posts. Your session stays local and encrypted with **Android Keystore (AES-256-GCM)**.
6. **In-app Engine updater (`Engine`):** Tap **Engine** in the top bar to update the built-in extraction engine directly on-device.

---

## 🎞️ Quality and Formats

- **Video (MP4):**
  - **Desktop:** Resolutions from `144p` to `2160p` (whichever the source provides). Up to **1080p** prefers **H.264**; **1440p and 2160p** use **AV1** + FFmpeg muxing.
  - **Android:** Prioritizes **H.264 + AAC** streams merged directly on-device with Android's native `MediaMuxer` so Shorts, ultrawide videos, Reels, Threads, and Facebook videos play reliably in standard Android gallery/video players without bundling FFmpeg.
- **Audio:**
  - **Desktop:** **MP3** (`128`, `192`, `256`, `320 kbps`) with embedded cover art and metadata tags, or **Original audio** (`.m4a`).
  - **Android:** **Original audio** (`.m4a` / AAC) saved directly without transcoding.
- **Images:**
  - **Original** (native resolution without re-encoding) or converted to **PNG** (lossless), **JPG**, or **WebP** while preserving original aspect ratios.

> **Note:** Live streams, upcoming premieres, and DRM-protected or subscriber-only posts are not supported. On Android, login-required Instagram posts can optionally use local **IG** sign-in.

---

## 🔄 Updating `yt-dlp` / Extraction Engine

Platforms change frequently, and an outdated extractor is the most common cause of sudden fetch or download errors.

- **Desktop (In-app):** Click **Update yt-dlp** in the window (or choose **`[5] Update yt-dlp`** in the CLI). Zardit checks PyPI, verifies SHA-256 checksums, tests the package (for source installs), and activates it on restart. Open the dialog again and choose **"Use the built-in version"** to roll back at any time. Zardit checks PyPI at most once per day (`"check_updates": false` in `settings.json` to disable).
- **Android (In-app):** Tap **Engine** in the top bar to update the embedded extraction engine directly on-device.

---

## 📁 Where Files and Settings Are Stored

Downloads go to **`Zardit`** inside your system `Downloads` folder by default (respecting XDG `user-dirs.dirs` on Linux and `Downloads/Zardit` via MediaStore/SAF on Android). Playlists and galleries are saved into subfolders.

Desktop config, history, logs, and `yt-dlp` updates live in:

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

On **Android**, preferences, download history, and optional AES-GCM encrypted Instagram cookies are stored inside the app's private data directory (`filesDir` / `SharedPreferences`), while downloaded media is saved to `Downloads/Zardit` or any folder you grant via Android's Storage Access Framework (`Change…`).

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

Titles are sanitized across Linux, Windows, macOS, and Android SAF (`:` -> ` -`, `/ \ |` -> `-`, `"` -> `'`, `? * < >` removed, Windows reserved names like `CON`/`NUL` prefixed with `_`, and byte-length safe truncation).

---

## 🔒 Privacy

- No Zardit accounts, analytics, telemetry, or usage tracking.
- Network requests go only to the media source and official update endpoints (PyPI for desktop `yt-dlp` checks; GitHub/`youtubedl-android` updater endpoints when manually updating the Android engine).
- On Android, optional Instagram sign-in stores only minimal session cookies (`sessionid`, `csrftoken`, `ds_user_id`) encrypted locally using **Android Keystore (`AES/GCM/NoPadding`)** — credentials never leave your device except to Instagram itself, and can be cleared with one tap.
- Share-tracking query parameters (such as `si=`, `utm_source=`) are stripped in logs, and desktop diagnostics replace your home directory with `~`.

---

## 🛠️ Troubleshooting

- **"Could not load the Qt platform plugin" (Linux):** Install `xcb-util-cursor` (Fedora) or `libxcb-cursor0` (Ubuntu/Debian).
- **Wayland terminal messages (`qt.qpa.wayland`):** Harmless desktop-integration notices; the app works normally.
- **FFmpeg or Deno not found:** Check `ffmpeg -version` and `deno --version` (or use the AppImage / Windows release bundles which include both).
- **MP3 conversion or cover art fails on Fedora:** Swap `ffmpeg-free` for full `ffmpeg` from RPM Fusion.
- **HTTP 403 / 429 or missing formats:** Update `yt-dlp` (Desktop) or tap **Engine** (Android) and retry later if rate-limited.
- **Garbled characters in Windows CLI:** Run `zardit-cli.exe` in **Windows Terminal** instead of legacy `cmd.exe`.

### Reporting a Problem
- **Desktop:** Click **Copy diagnostics** in the GUI (or run `zardit_app.py --diagnostics` / `zardit-cli.exe --diagnostics`).
- **Android:** Tap **Copy Diagnostics** directly on the error card to copy sanitized device/error logs.

Review the copied output for anything personal and attach it to a [GitHub Issue](https://github.com/Cptmaverick-22/zardit/issues).

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
├── android/               # Android companion app (Kotlin, Jetpack Compose, Material 3)
│   └── app/src/main/kotlin/io/github/cptmaverick_22/zardit/
│       ├── MainActivity.kt        # Compose entry point & ACTION_SEND Share-sheet handler
│       ├── ZarditApp.kt           # Engine initialization
│       ├── data/                  # Prefs, HistoryStore, InstagramAuth (AES-GCM), DiagnosticLog
│       ├── downloader/            # ZarditEngine, NativeMuxer, MediaModels, ZarditFlavor
│       ├── service/               # DownloadService foreground notification worker
│       └── ui/                    # Material 3 screens, ViewModel, IG login dialog & theme
├── assets/                # SVG, PNG, and multi-resolution ICO icons
├── packaging/
│   ├── linux/             # AppImage build script, AppRun, .desktop, AppStream, local installer
│   └── windows/           # Windows PyInstaller spec, PowerShell build script, Inno Setup (.iss)
├── .github/workflows/     # Path-filtered Linux, Windows & Android CI + multi-platform release
├── requirements.txt
├── requirements-dev.txt
└── tests/
```

> **Important:** `updater.activate()` must run before anything imports `yt_dlp`. Keep it at the top of any new desktop entry point.

### Run Desktop Checks Locally
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
- **Android APK (`arm64-v8a`):**
  ```bash
  cd android
  ./gradlew assembleRelease --no-daemon
  ```

---

## 📜 Legal and Responsible Use

This software is intended only for downloading content that you own, have permission to download, or are otherwise legally authorized to download. Respect copyright laws and platform Terms of Service.

## 🗺️ Roadmap

- **v1.3:** Optional on-demand minimal FFmpeg add-on for Android (MP3 conversion, Instagram high-res VP9/DASH, and 1440p+/AV1 streams without bloating the base APK), release-signed Android builds, and synced desktop + Android parity
- Faster playlists by preparing the next item while one downloads
- Subtitles and time-range clip downloads
- WebM / MKV format options for resolutions above 1080p

## License

Licensed under the [GNU General Public License v3.0 or later (GPL-3.0-or-later)](LICENSE). Copyright © 2026 Cptmaverick-22.
