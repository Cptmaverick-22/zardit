<div align="center">

<img src="assets/zardit.png" width="120" height="120" alt="Zardit Logo" />

# Zardit

**A privacy-conscious, cross-platform media downloader.**

*Blaze through videos, audio, images, playlists & carousels via a Desktop GUI, a Terminal UI, or an Android app.*

[![Latest Release](https://img.shields.io/github/v/release/Cptmaverick-22/zardit?style=for-the-badge&color=e63946)](https://github.com/Cptmaverick-22/zardit/releases/latest)
[![Linux CI](https://img.shields.io/github/actions/workflow/status/Cptmaverick-22/zardit/ci-linux.yml?branch=main&label=Linux%20CI&style=for-the-badge)](https://github.com/Cptmaverick-22/zardit/actions/workflows/ci-linux.yml)
[![Windows CI](https://img.shields.io/github/actions/workflow/status/Cptmaverick-22/zardit/ci-windows.yml?branch=main&label=Windows%20CI&style=for-the-badge)](https://github.com/Cptmaverick-22/zardit/actions/workflows/ci-windows.yml)
[![Android CI](https://img.shields.io/github/actions/workflow/status/Cptmaverick-22/zardit/ci-android.yml?branch=main&label=Android%20CI&style=for-the-badge)](https://github.com/Cptmaverick-22/zardit/actions/workflows/ci-android.yml)
[![Python](https://img.shields.io/badge/Python-3.11%E2%80%933.15-3776AB?style=for-the-badge&logo=python&logoColor=white)](https://www.python.org/)
[![Android](https://img.shields.io/badge/Android-7.0%2B%20(arm64)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Cptmaverick-22/zardit/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue?style=for-the-badge)](LICENSE)

[Downloads](#-installation) · [Compatibility](#supported-platforms--interfaces) · [Features](#-features) · [Desktop](#-using-the-window-app) · [Terminal](#-using-the-terminal-app) · [Android](#-using-the-android-app) · [Privacy](#-privacy) · [Developers](#-for-developers)

</div>

---

> **Responsible use:** Only download content that you own, have permission to download, or are otherwise legally authorized to save. See [Legal and responsible use](#-legal-and-responsible-use).

## Supported Platforms & Interfaces

Zardit builds a complete application layer on top of [`yt-dlp`](https://github.com/yt-dlp/yt-dlp) across **three interfaces**:

- **Desktop App (`gui.py` / `Zardit`)** — PySide6 interface with **Auto / Light / Dark** themes, one-tap **Paste & Fetch**, **IG** private Instagram access (local browser cookies or 100% offline source parser), interactive gallery/carousel previews, and built-in **History** playback & management.
- **Terminal App (`main.py` / `zardit-cli`)** — Themed Rich menu-driven CLI sharing settings, Instagram access, and download history with the desktop app.
- **Android App (`android/`)** — Slim Material 3 Jetpack Compose companion app (`arm64-v8a`) with Android **Share-sheet** integration, **Auto / Light / Dark** themes, foreground download service & notifications, native `MediaMuxer` + Media3 `Mp4Muxer` assembly, Storage Access Framework (`Downloads/Zardit`), optional local AES-256-GCM encrypted **IG** sign-in, and **History** management with device-file cleanup.

### Platform compatibility

Media extraction depends on upstream platforms and the extractors shipped in your active `yt-dlp` / Android Engine version. Platform layouts, authentication gates, regional rules, and rate limits can change without notice — so compatibility below is **not a guarantee that every URL, account, or post type will work at all times**.

| Platform | Videos | Audio | Images / Galleries | Notes |
|---|---|---|---|---|
| **YouTube** | MP4 (`144p`–`2160p` desktop / up to `1080p+` Android) + Playlists | MP3 (desktop) / M4A | Thumbnails (`Original` / PNG / JPG / WebP) | Deno recommended on desktop for JS challenges |
| **Instagram** | Reels & Posts | MP3 (desktop) / Original | Single photos, thumbnails & carousels | Private/follow-only posts require local **IG** setup |
| **Facebook** | Videos & Reels | MP3 (desktop) / Original | Single & Multi-photo posts | Availability varies on login-gated posts |
| **Reddit** | Videos | MP3 (desktop) / Original | Single & Gallery images | — |
| **X (Twitter)** | Videos | MP3 (desktop) / Original | Images | Some posts require session/upstream updates |
| **Threads** | Videos (including `/share/` links) | MP3 (desktop) / Original | Images & Carousels | Higher DASH tiers shown when exposed |

Other websites supported by `yt-dlp` may also work, but are **best-effort** and not actively regression-tested.

---

## ✨ Features

### Downloading
- **Video as MP4** — Clean, deduplicated resolution list with estimated file sizes per stream (including probed direct-stream size estimates).
- **Audio** — Desktop supports **128, 192, 256, or 320 kbps MP3** (with embedded cover art and metadata tags) or original audio. Android saves **Original / M4A** audio directly without bundling FFmpeg.
- **Images & Carousels** — Download native **Original** images or convert to **PNG**, **JPG**, or **WebP** across videos (thumbnails), playlists (Android & desktop), and multi-photo carousels/galleries with uncropped aspect ratios.
- **Playlists & Smart Ranges** — Download everything, enter ranges like `1-3, 5, 8-10`, filter by title (desktop), or toggle individual items. Pasting a single video URL from inside a playlist lets you choose between downloading just that item or the full playlist.

### Reliability & Convenience
- **Live Progress & Clean Cancel** — Real-time progress, speed, downloaded bytes, ETA (desktop), and automatic cleanup of leftover temporary files when cancelled.
- **Duplicate Prevention** — Skips already-downloaded items at the same quality (including cross-matching Instagram share tokens vs. shortcodes across Desktop and Android).
- **Retries & Disk-Space Safety** — Retries selected transient HTTP `403` / `429` / `5xx` errors and checks free storage before starting desktop downloads.
- **In-App Engine / `yt-dlp` Updater** — Desktop checks PyPI with SHA-256 verification and automatic rollback if activation fails; Android includes an in-app **Engine** update button.
- **OS Integration & Diagnostics** — Linux notifications (`notify-send`), Windows Start Menu/taskbar integration, Android Share-sheet + notifications, and redacted **Copy diagnostics** / `--diagnostics` reports.

---

## 📦 Installation

Prebuilt packages on [GitHub Releases](https://github.com/Cptmaverick-22/zardit/releases/latest) are recommended: Linux and Windows bundles include Python, PySide6, `yt-dlp`, **FFmpeg**, and **Deno**; the Android APK targets `arm64-v8a` with R8 shrinking and native media muxing (no bundled FFmpeg).

| OS | Package | Build & Verification Status |
|---|---|---|
| **Linux (x86_64)** | `Zardit-<version>-x86_64.AppImage` | Release build + Linux CI (Pyflakes & unit suite) |
| **Windows 10 / 11 (x64)** | `Zardit-<version>-win64-setup.exe` or `portable.zip` | Release build + Windows CI (Pyflakes & unit suite) |
| **Android 7.0+ (`arm64-v8a`)** | `Zardit-<version>-android-arm64.apk` | Release APK built cleanly in Android CI |
| **macOS** | Source install (`brew` + `.venv`) | Source path provided; no prebuilt `.dmg` published |

> **Note:** CI builds confirm compilation and automated desktop checks pass; they do not replace live testing on every target device and OS configuration. Check each release page for exact attached assets.

### Verify Release Downloads (Recommended)
When a release includes `SHA256SUMS.txt`, verify your download before installing:

- **Linux:**
  ```bash
  sha256sum -c SHA256SUMS.txt --ignore-missing
  ```
- **Windows PowerShell:**
  ```powershell
  Get-FileHash .\Zardit-<version>-win64-setup.exe -Algorithm SHA256
  ```
  Compare the printed hash with the matching line in `SHA256SUMS.txt`.

### Linux (AppImage — Recommended)
```bash
chmod +x Zardit-*-x86_64.AppImage
./Zardit-*-x86_64.AppImage
```
- Launch Terminal UI: `./Zardit-*-x86_64.AppImage --cli`
- Print diagnostics: `./Zardit-*-x86_64.AppImage --diagnostics`
- Register desktop app-menu icon & `zardit` / `zardit-cli` commands locally:
  ```bash
  ./packaging/linux/install_local.sh ~/Applications/Zardit-1.4-x86_64.AppImage
  ```
  *(Run `./packaging/linux/install_local.sh` without arguments to create desktop/CLI launchers backed by your local `.venv`.)*

### Windows (Installer & Portable ZIP)
- **Installer (`Zardit-<version>-win64-setup.exe`):** Per-user install (no admin rights required), Start Menu / optional Desktop shortcuts, and optional `zardit-cli` `PATH` entry.
- **Portable (`Zardit-<version>-win64-portable.zip`):** Extract anywhere and run `Zardit.exe` (GUI) or `zardit-cli.exe` in **Windows Terminal**.

### Android (`arm64-v8a` APK)
1. Open `Zardit-<version>-android-arm64.apk` on an Android 7.0+ (`arm64-v8a`) device and allow **"Install unknown apps"** if prompted.
2. Grant notification permission when asked (required for background download-service progress).

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
python -m pip install --upgrade pip
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
python -m pip install --upgrade pip
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
python -m pip install --upgrade pip
pip install -r requirements.txt
python zardit_app.py
```

</details>

---

## 🚀 Running from Source

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

1. **Paste a link** and click **Paste & Fetch** or **Fetch**.
2. Review the preview card (thumbnail, platform badge, title, creator, duration/date, or gallery count).
3. Choose **Video (MP4)**, **Audio (MP3 / M4A)**, or **Image**.
4. Pick your **quality or format** — your last choice is remembered automatically.
   - For playlists or multi-photo galleries, select items via checkboxes, ranges (`1-3, 5, 8-10`), title filter, or the image preview modal.
5. Check **Save to** (**Change…** to pick another folder) and click **Download**.
6. Use the **History** tab to play, open, or remove past downloads, or switch **Auto / Light / Dark** in the top bar.
7. **Optional Private Instagram (`IG`):** Click **IG** in the top bar for two completely local options:
   - **Option 1 — Browser Cookies:** Pick a signed-in browser (**Chrome, Firefox, Edge, Brave, Chromium, Opera, or Vivaldi**). Zardit saves only the browser name in `settings.json` and lets `yt-dlp` read that browser's cookie store in memory during Instagram requests.
   - **Option 2 — Paste Page Source (100% Offline):** Open the post in your browser, press **`Ctrl+U`** (or open `?__a=1&__d=dis`), copy the source, and extract video, audio, or carousels completely offline inside Zardit.

---

## 💻 Using the Terminal App

Start it with `zardit-cli`, `./Zardit-*-x86_64.AppImage --cli`, or `python main.py`:

```text
  ╭─ ▶ Zardit v1.4 ────────────────────────────────────────────────────────────╮
  │                                                                            │
  │  Tagline  Blaze through videos, audio & playlists across every platform.   │
  │  Save to  ~/Downloads/Zardit                                               │
  │  Theme    Auto (Dark)                                                      │
  │  IG Auth  Mozilla Firefox                                                  │
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
  │    [G]    Instagram Access                   Private IG cookies or source  │
  │    [T]    Switch Theme                               Current: Auto (Dark)  │
  │    [6]    Exit                                           Quit application  │
  │                                                                            │
  ╰───────────────────────────────────────────────────── Enter a choice below ─╯

  › Choose an option (1-6, I = image, H = history, G = IG, T = theme): 
```

- Enter **`1`–`6`**, **`I`**, **`H`**, **`G`**, or **`T`** (`tc` to quick-cycle **Auto / Light / Dark**, or launch with `--theme auto|light|dark`).
- Choose **`[G] Instagram Access`** to pick a signed-in browser or paste/load saved page HTML/JSON offline.
- Type **`Q`** at prompts to go back, or press **`Ctrl+C`** during a download to cancel cleanly.

---

## 📱 Using the Android App

1. **Paste or Share a link:** Paste a URL and tap **Paste & Fetch** / **Fetch**, or share links directly into **Zardit** from any app's Android **Share** menu.
2. **Choose Video, Audio, or Image:** Switch between **Video (MP4)**, **Audio** (when exposed), and **Image** (**Original**, **PNG**, **JPG**, or **WebP** — available for standalone photos, video thumbnails, and playlists). Toggle playlist/carousel items or select ranges like `1-3, 5`.
3. **Output folder:** Saves to `Downloads/Zardit` by default, or any folder picked with **Change…** via Android Storage Access Framework.
4. **History & File Cleanup:** Track progress in the app or notification shade, then use **History** to **Open**, **Share**, remove single entries, or tap **Clear all** and choose **History only** vs. **Delete files too**.
5. **Optional Instagram Sign-In (`IG`):** Tap **IG** at the top to sign in locally (`Android Keystore AES-256-GCM`) for private, follow-only, or `/share/` Instagram posts. Wait for notifications & battery optimization settings to allow background downloads cleanly.
6. **In-App Engine Updater (`Engine`):** Tap **Engine** to update the embedded extraction engine on-device.

---

## 🎞️ Quality and Formats

| Media Type | Desktop (Linux / Windows / macOS) | Android (`arm64-v8a`) |
|---|---|---|
| **Video (MP4)** | `144p`–`2160p` (source-dependent). Prefers H.264 up to `1080p`; uses AV1 / VP9 + FFmpeg muxing at `1440p` / `2160p`. | Native MP4 muxing (H.264 / HEVC / VP9 / AV1) via Jetpack Media3 `Mp4Muxer` + Android `MediaMuxer` without bundling FFmpeg. |
| **Audio** | MP3 (`128`, `192`, `256`, `320 kbps`) with cover art & metadata tags, or Original audio (`.m4a`). | Original audio (`.m4a` / AAC) saved directly without FFmpeg transcoding. |
| **Images** | **Original** or converted to **PNG** (lossless), **JPG**, or **WebP** while keeping native aspect ratio. | **Original** or converted to **PNG**, **JPG**, or **WebP** across photos, video thumbnails & playlists. |

> **Limitations:** Live streams, upcoming premieres, and DRM-protected or subscriber-only media are not supported. On Instagram, even with local **IG** auth enabled, access depends on your account's permissions and Instagram's web APIs.

---

## 🔄 Updating `yt-dlp` / Extraction Engine

Outdated extractors are the #1 reason fetches suddenly fail when platforms update their pages.

- **Desktop:** Click **Update yt-dlp** in the window (or **`[5]`** in the CLI). Zardit checks PyPI (`check_updates: false` in `settings.json` to disable daily checks), verifies **SHA-256** checksums, smoke-tests source updates, activates on restart, and lets you roll back to **"Use the built-in version"** any time.
- **Android:** Tap **Engine** in the top bar to check for and install engine updates directly on-device.

---

## 📁 Where Files and Settings Are Stored

Downloads go into **`Zardit`** inside your Downloads folder by default (respecting XDG `user-dirs.dirs` on Linux and `Downloads/Zardit` via MediaStore/SAF on Android). Playlists and galleries are organized into subfolders.

| System | Config Directory |
|---|---|
| **Linux** | `~/.config/zardit/` (or `$XDG_CONFIG_HOME/zardit/`) |
| **Windows** | `%APPDATA%\zardit\` |
| **macOS** | `~/Library/Application Support/zardit/` |

| File / Folder | Purpose |
|---|---|
| `settings.json` | Saved download folder, quality/format defaults, theme, `ig_browser` name (never cookies), and update preference. |
| `history.json` | Download history shared between GUI and CLI. |
| `zardit.log` | Rotating application log (`.1`, `.2`). |
| `updates/` | Downloaded PyPI `yt-dlp` wheel updates and activation state. |

On **Android**, preferences, history, and optional AES-GCM encrypted Instagram cookies stay in the app's private data directory (`filesDir` / `SharedPreferences`), while downloads go to `Downloads/Zardit` or your selected SAF folder.

### File Naming
```text
<Title> [<quality>] [<media id>].<extension>
```
Examples: `Loose Emotions EP 12 [720p] [ZG98zgaVIGg].mp4`, `Loose Emotions EP 12 [192kbps] [ZG98zgaVIGg].mp3`.
Titles are sanitized for Linux, Windows, macOS, and Android SAF (`:` -> ` -`, `/ \ |` -> `-`, `"` -> `'`, `? * < >` stripped, Windows reserved names like `CON` / `NUL` prefixed with `_`, and byte-length safe truncation).

---

## 🔒 Privacy

- **Zero accounts or telemetry:** No analytics, tracking SDKs, or Zardit servers. Network calls go only to the media source and update endpoints (PyPI for desktop `yt-dlp` updates, or when triggering an Android Engine update).
- **Local-only private Instagram handling:**
  - On Desktop, **Option 1** saves only the selected browser name in `settings.json` (`null` when turned off) and reads your browser's cookies in memory; **Option 2** parses pasted source text offline in memory.
  - On Android, optional Instagram sign-in stores only minimal session cookies (`sessionid`, `csrftoken`, `ds_user_id`) encrypted locally with **Android Keystore (`AES/GCM/NoPadding`)**, clears temporary WebView state after extraction, and can be wiped with one tap.
- **Sanitized diagnostics:** Desktop logs strip share-tracking parameters (`si=`, `utm_*`) and desktop diagnostics mask your home directory with `~`; Android diagnostics redact session cookie values and tracking parameters. **Always review diagnostic output before sharing it publicly.**

---

## 🛠️ Troubleshooting

| Problem | What to try |
|---|---|
| Fetch fails or formats missing | Update `yt-dlp` (Desktop) or tap **Engine** (Android), confirm the post is public (or connect **IG** locally if private), and retry. |
| HTTP `403` or `429` | Rate limit or temporary block — wait a few minutes instead of retrying rapidly. |
| Private IG failed in Chrome/Edge (Windows) | Windows Chrome/Edge (`v130+`) often lock or encrypt cookie databases while open; use **Firefox** or **IG → Paste Page Source (100% Offline)**. |
| "Could not load Qt platform plugin" (Linux) | Install `xcb-util-cursor` (Fedora) or `libxcb-cursor0` (Ubuntu/Debian). |
| MP3 / cover art fails on Fedora | Replace `ffmpeg-free` with full `ffmpeg` from RPM Fusion (`dnf swap ffmpeg-free ffmpeg --allowerasing`). |
| Garbled text in Windows CLI | Launch `zardit-cli.exe` in **Windows Terminal** instead of legacy `cmd.exe`. |

### Reporting a Problem
1. Update Zardit and `yt-dlp` / **Engine** first.
2. Click **Copy diagnostics** (Desktop GUI), run `zardit-cli --diagnostics` / `python zardit_app.py --diagnostics`, or tap **Copy Diagnostics** on the Android error card.
3. **Review and strip anything personal** (usernames, private URLs, paths, or tokens — never attach cookie databases or credentials) and open a [GitHub Issue](https://github.com/Cptmaverick-22/zardit/issues).

---

## 👨‍💻 For Developers

```text
zardit/
├── zardit_app.py          # Unified GUI/CLI launcher (activates updater first)
├── gui.py                 # Desktop app (PySide6)
├── main.py                # Terminal app (Rich)
├── downloader.py          # Extraction, downloads, playlists, galleries & image conversion
├── updater.py             # PyPI yt-dlp updater (stdlib only)
├── config.py              # Constants, defaults & settings persistence
├── theme.py               # Auto / Light / Dark palettes and QSS
├── history.py             # Shared download history
├── logs.py                # Rotating logs & diagnostics
├── desktop.py             # Notifications & file/folder openers
├── android/               # Android app (Kotlin, Jetpack Compose, Material 3)
│   └── app/src/main/kotlin/io/github/cptmaverick_22/zardit/
│       ├── MainActivity.kt        # Compose entry point & ACTION_SEND handler
│       ├── ZarditApp.kt           # Engine initialization
│       ├── data/                  # Prefs, HistoryStore, InstagramAuth (AES-GCM), DiagnosticLog
│       ├── downloader/            # ZarditEngine, NativeMuxer, MediaModels, ZarditFlavor
│       ├── service/               # DownloadService foreground notification worker
│       └── ui/                    # Material 3 screens, ViewModel, IG login dialog & theme
├── assets/                # SVG, PNG & multi-resolution ICO icons
├── packaging/
│   ├── linux/             # AppImage build script, AppRun, .desktop, AppStream & install_local.sh
│   └── windows/           # Windows PyInstaller spec, PowerShell build script & Inno Setup (.iss)
├── .github/workflows/     # Linux CI, Windows CI, Android CI & tagged Release workflow
├── requirements.txt
├── requirements-dev.txt
└── tests/                 # Desktop, updater, privacy & GUI/CLI test suites
```

> **Important:** `updater.activate()` must execute before anything imports `yt_dlp`. Keep it at the top of any desktop entry point.

### Run Desktop Tests & Lint Locally
```bash
pip install -r requirements-dev.txt
QT_QPA_PLATFORM=offscreen python -m unittest discover -s tests -v
python -m pyflakes config.py desktop.py downloader.py gui.py history.py logs.py main.py theme.py updater.py zardit_app.py tests/test_ui.py tests/test_names.py tests/test_resolution.py
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

### Contributing
- Keep changes focused, add or update tests for changed behavior (parsing, format selection, cleanup, updater, or privacy rules), and report which local checks you ran.
- Never commit real credentials, browser cookie databases, session tokens, or private media fixtures.
- Open an issue before large refactors or new platform additions so maintenance trade-offs can be discussed first.

---

## 🗺️ Roadmap

Priorities may shift as upstream platforms change; listed items are goals rather than commitments:

- Security & reliability hardening (stricter URL validation, Android backup exclusions, expanded secret redaction, and Android JVM unit tests in CI)
- Gradual modularization of `downloader.py` and `ZarditEngine.kt` for safer per-platform maintenance
- Optional on-demand FFmpeg add-on for Android (MP3 conversion without inflating default APK size)
- Faster playlists by preparing the next item while one downloads
- Subtitles and time-range clip workflows

## 📜 Legal and Responsible Use

Zardit is intended only for downloading media you own, have permission to download, or are legally authorized to save. Respect copyright laws and platform Terms of Service. Zardit is not affiliated with, endorsed by, or sponsored by YouTube, Instagram, Facebook, Reddit, X, or Threads.

## License

Licensed under the [GNU General Public License v3.0 or later (GPL-3.0-or-later)](LICENSE). Copyright © 2026 Cptmaverick-22.
