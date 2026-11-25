# Zardit

> *Blaze through videos, audio & playlists across every platform.*

Download videos as MP4, audio as MP3 (or the original audio), images (Original, PNG, JPG, WebP), and playlists/galleries from **YouTube, Instagram, Facebook, Reddit, X (Twitter), Threads**, and more. Built with Python, [yt-dlp](https://github.com/yt-dlp/yt-dlp), FFmpeg, Rich, and Qt.

It has two interfaces that share the same settings, download history, and download folder:

- **Window app** (`gui.py`): modern Light & Dark themed Qt app with one-tap **Paste & Fetch**, structured format lists, full-width responsive layout, and built-in **History** player/manager.
- **Terminal app** (`main.py`): a clean, themed Rich menu-driven interface with clipboard paste and history playback.

> Only download content that you own or have permission to download. See [Legal and responsible use](#legal-and-responsible-use).

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Running the app](#running-the-app)
- [Using the window app](#using-the-window-app)
- [Using the terminal app](#using-the-terminal-app)
- [Playlists](#playlists)
- [Quality and formats](#quality-and-formats)
- [Updating yt-dlp](#updating-yt-dlp)
- [Where files and settings are stored](#where-files-and-settings-are-stored)
- [File names](#file-names)
- [Privacy](#privacy)
- [Troubleshooting](#troubleshooting)
- [Reporting a problem](#reporting-a-problem)
- [For developers](#for-developers)
- [Legal and responsible use](#legal-and-responsible-use)

## Features

**Downloading**

- Video as **MP4**, with an **estimated file size** shown for every resolution.
- Audio as **MP3** (128, 192, 256 or 320 kbps) or as the **original audio** without conversion. Both get embedded **cover art and tags**.
- **Playlists**: everything, a range such as `1-3, 5, 8-10`, or just the videos you tick. Each playlist goes into its own folder.
- **Smart links**: paste a video link that belongs to a playlist and the app asks whether you want the single video or the whole playlist.

**Reliability**

- One progress bar per download, with speed and time remaining.
- Cancel at any time. Leftover temporary files are cleaned up.
- Files you already downloaded are skipped, so re-running a playlist picks up where it stopped.
- Temporary YouTube errors (HTTP 403, 429, 5xx) are retried automatically.
- Checks the free disk space before a download starts.
- Readable error messages, and a log file for the rest.

**Convenience**

- Remembers your last quality choices and your download folder.
- Built-in **Update yt-dlp** button, because YouTube changes often. See [Updating yt-dlp](#updating-yt-dlp).
- Desktop notification when a download finishes (Linux) and an "Open folder" button.
- **Copy diagnostics** button for bug reports.

## Requirements

| Needs | Why |
|---|---|
| **Python 3.11 to 3.15** | yt-dlp needs 3.11 or newer, and PySide6 currently supports up to 3.15. 3.12 or 3.13 is a safe choice. |
| **FFmpeg** | Merges video and audio, converts MP3, embeds cover art. |
| **Deno** | Lets yt-dlp solve YouTube's JavaScript checks. |
| Python packages `yt-dlp[default]`, `rich`, `PySide6` | Installed from `requirements.txt`. |
| An internet connection | To reach YouTube. |

| System | Status |
|---|---|
| Linux | Developed and tested on Fedora. |
| Windows 10 and 11 | Written to work, **not tested yet**. |
| macOS | Written to work, **not tested yet**. |

Desktop notifications work on Linux only (they need `notify-send`, which most desktops include).

## Installation

Pick your system. For Linux desktop users, the portable **AppImage** is the fastest way to get started.

### Linux (AppImage — Recommended)

Download the latest `Zardit-<version>-x86_64.AppImage` (and optional `SHA256SUMS.txt`) from **GitHub Releases**, then make it executable and run it:

```bash
chmod +x Zardit-*-x86_64.AppImage
./Zardit-*-x86_64.AppImage
```

The AppImage bundles Python, PySide6, yt-dlp, **FFmpeg**, and **Deno**, so you do not need to install those separately.

- Run `./Zardit-*-x86_64.AppImage --cli` to launch the terminal interface.
- Run `./Zardit-*-x86_64.AppImage --diagnostics` to print a diagnostics report.
- Want an app menu icon and `zardit` / `zardit-cli` terminal commands? Run `./packaging/linux/install_local.sh ~/Applications/Zardit-1.2-x86_64.AppImage` (or without arguments to install a launcher using your local `.venv`).

### Linux (from source)

**1. Install the system tools**

*Fedora*

```bash
sudo dnf install python3 git curl unzip
```

Fedora's default FFmpeg may lack MP3 encoding. Install the full version from [RPM Fusion](https://rpmfusion.org/):

```bash
sudo dnf install https://mirrors.rpmfusion.org/free/fedora/rpmfusion-free-release-$(rpm -E %fedora).noarch.rpm
sudo dnf swap ffmpeg-free ffmpeg --allowerasing
```

*Ubuntu, Debian and Linux Mint*

```bash
sudo apt update
sudo apt install python3 python3-venv python3-pip git curl unzip ffmpeg libxcb-cursor0
```

Ubuntu 22.04 ships Python 3.10, which is too old. Use Ubuntu 24.04 or newer, or install a newer Python yourself.

*Other distributions:* install Python 3.11 or newer, `git`, `curl`, `unzip` and FFmpeg with your package manager.

**2. Install Deno**

```bash
curl -fsSL https://deno.land/install.sh | sh
```

If the installer doesn't update your shell configuration, add Deno to your PATH yourself:

```bash
echo 'export PATH="$HOME/.deno/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc
```

**3. Check the tools**

```bash
python3 --version
ffmpeg -version
deno --version
```

Each command should print a version. `python3 --version` must show 3.11 or newer.

**4. Get the project**

```bash
git clone https://github.com/Cptmaverick-22/zardit.git
cd zardit
```

No git? Download the ZIP from the GitHub page, extract it, and open a terminal in that folder.

**5. Create a virtual environment and install the packages**

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

**6. Start the app**

```bash
python gui.py
```

### Windows

Use **Windows Terminal** (the default on Windows 11). The old `cmd.exe` window can show garbled symbols in the terminal app.

**1. Install Python 3.11 to 3.15** from [python.org](https://www.python.org/downloads/). In the installer, tick **"Add python.exe to PATH"**.

**2. Install FFmpeg and Deno.** Open PowerShell and run:

```powershell
winget install Gyan.FFmpeg
winget install DenoLand.Deno
```

Close and reopen the terminal, then check:

```powershell
python --version
ffmpeg -version
deno --version
```

**3. Get the project**

```powershell
git clone https://github.com/Cptmaverick-22/zardit.git
cd zardit
```

No git? Download the ZIP from the GitHub page, extract it, and open PowerShell in that folder.

**4. Create a virtual environment and install the packages**

```powershell
py -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

If PowerShell refuses to run the activation script, run this once and try again:

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
```

**5. Start the app**

```powershell
python gui.py
```

### macOS

```bash
brew install python ffmpeg deno git
git clone https://github.com/Cptmaverick-22/zardit.git
cd zardit
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python gui.py
```

## Running the app

Open a terminal in the project folder and activate the virtual environment first (or use the AppImage / `./packaging/linux/install_local.sh`):

| | Linux and macOS | Windows |
|---|---|---|
| Activate | `source .venv/bin/activate` | `.venv\Scripts\Activate.ps1` |
| Unified launcher (GUI) | `python zardit_app.py` | `python zardit_app.py` |
| Unified launcher (CLI) | `python zardit_app.py --cli` | `python zardit_app.py --cli` |
| Window app | `python gui.py` | `python gui.py` |
| Terminal app | `python main.py` | `python main.py` |
| Diagnostics report | `python zardit_app.py --diagnostics` | `python main.py --diagnostics` |

You can skip the activation by calling the environment's Python directly, for example `.venv/bin/python gui.py` (Linux and macOS) or `.venv\Scripts\python.exe gui.py` (Windows).

## Using the window app

1. **Paste a YouTube link** and press **Fetch** (or Enter).
2. The thumbnail, title, channel, duration and date appear.
3. Choose **Video (MP4)** or **Audio**.
4. Pick the **quality**. Videos show an estimated size per resolution, and your last choice is pre-selected.
5. Check **Save to** (press **Change…** to pick another folder).
6. Press **Download**. A progress bar shows size, speed and time remaining. **Cancel** stops cleanly.
7. When it finishes you get a notification (Linux) and an **Open folder** button.

If a link points at a video inside a playlist, a dialog asks whether you want just the video or the whole playlist.

After an error, a **Copy diagnostics** button appears. See [Reporting a problem](#reporting-a-problem).

## Using the terminal app

Start it with `python main.py`:

```text
╭────── YT Downloader v1.1 ───────╮
│                                 │
│   1  Download Video             │
│   2  Download Audio             │
│   3  Download Playlist          │
│   4  Change Download Location   │
│   5  Update yt-dlp              │
│   6  Exit                       │
│                                 │
╰─────────────────────────────────╯
```

| Option | What happens |
|---|---|
| **1. Download Video** | Paste a link, check the info, pick a resolution from the table (Enter reuses your last choice), confirm. |
| **2. Download Audio** | Paste a link, pick an MP3 bitrate or "Original audio", confirm. |
| **3. Download Playlist** | See [Playlists](#playlists). |
| **4. Change Download Location** | Enter a new folder. It is saved for next time. |
| **5. Update yt-dlp** | See [Updating yt-dlp](#updating-yt-dlp). |
| **6. Exit** | Quit. |

Type **Q** at most prompts to go back. Press **Ctrl+C** during a download to cancel it. Leftover temporary files are removed.

## Playlists

**Window app:** paste a playlist link and press Fetch. You get a checklist of every video.

- Untick videos you don't want, or use **All** and **None**.
- Type a **range** such as `1-3, 5, 8-10`. The checkboxes follow, and the other way round.
- Use **Filter videos by title** to find videos in a long playlist.
- Edit the **Subfolder** name, or keep the playlist's title.
- Press **Download N items**. If some videos fail, **View failed** shows why.

**Terminal app:** choose option 3, pick Video or Audio, choose a quality, enter a range (Enter means everything) and a folder name (Enter means the playlist's title), and confirm.

Good to know:

- **For playlists the resolution is a ceiling.** "Up to 1080p" means the best available up to 1080p for each video, because the videos in a playlist rarely offer the same resolutions. Playlist options run from 2160p down to 360p.
- **Finished videos are skipped**, whatever resolution they were saved in. An MP3 at a different bitrate counts as a different download.
- To resume an interrupted playlist, run it again with the same folder name.
- Private or deleted videos are reported and skipped, and the rest continues.
- **Not supported as playlists:** YouTube Mix lists (the personalised ones), Watch Later, Liked videos (these need you to be signed in) and channel pages. For a channel, open one of its playlists and paste that link.

## Quality and formats

**Video** is saved as MP4. Resolutions are labelled the way YouTube labels them (144p up to 2160p, whichever the video offers as MP4).

- Up to **1080p** the app prefers **H.264**, which plays almost everywhere.
- **1440p and 2160p** are **AV1**, because YouTube has no H.264 above 1080p. They play in browsers, VLC and mpv, but not on some older TVs, phones and editors. Choose 1080p or lower if a device can't play a file.

**Audio** has two kinds:

- **MP3** at 128, 192, 256 or 320 kbps, with cover art and tags. YouTube's audio is typically around 128 kbps, so a higher bitrate doesn't add quality. It only makes the file bigger.
- **Original audio (no conversion):** YouTube's own audio saved as an `.m4a` file, with cover art and tags. It's faster and smaller than converting, and nothing is re-encoded.

Live streams and upcoming premieres can't be downloaded. Private, age-restricted, members-only and region-blocked videos aren't supported either, because the app has no sign-in.

## Updating yt-dlp

YouTube changes often, and an outdated yt-dlp is the most common reason downloads suddenly fail.

**In the app:** click **Update yt-dlp** in the window, or choose option **5** in the terminal app. It shows your version and the newest one and installs only if you agree. The new version is used **after a restart** (the window offers to restart for you).

How it works:

- The new yt-dlp (and the small helper it needs) is downloaded from PyPI into the app's settings folder. The files are checked against PyPI's checksums, tested with your Python, and only then installed.
- It is loaded ahead of the installed copy. If it ever fails to load, it is deleted and the installed copy is used, so a bad update can't break the app.
- If you later update yt-dlp with pip to a version that is at least as new, the downloaded copy is removed automatically.
- To go back to the installed version, open the update screen again and choose **"Use the built-in version"** (window) or answer **y** to "Go back to the yt-dlp that came with the app?" (terminal).

**Daily check:** once a day the app asks PyPI whether a newer yt-dlp exists, and only shows a notice. It never installs anything by itself. You can turn the check off with the checkbox in the window's update dialog, with the question in the terminal's update screen, or by setting `"check_updates": false` in `settings.json`.

**With pip:** inside your virtual environment, run `pip install -U "yt-dlp[default]"`.

## Where files and settings are stored

**Downloads** go to a folder called **Zardit** inside your Downloads folder (on Linux the app follows your desktop's Downloads folder, even if it is translated). Change it any time. Playlists get a subfolder.

**Settings, history, log and updates** live in a per-user folder, so they survive reinstalling the app:

| System | Folder |
|---|---|
| Linux | `~/.config/zardit/` |
| Windows | `%APPDATA%\zardit\` |
| macOS | `~/Library/Application Support/zardit/` |

| Inside that folder | What it is |
|---|---|
| `settings.json` | Your saved choices (folder, quality, theme), shared by both interfaces. |
| `history.json` | Your downloaded videos and audio history. |
| `zardit.log` | The log (older copies are kept as `.1` and `.2`). |
| `updates/` | Downloaded yt-dlp updates. |

```json
{
    "download_dir": "/home/you/Videos/YouTube",
    "video_quality": 1080,
    "audio_bitrate": "original",
    "check_updates": true
}
```

| Key | Meaning |
|---|---|
| `download_dir` | Where files are saved. |
| `video_quality` | Last resolution used. |
| `audio_bitrate` | Last audio choice (a bitrate such as `192`, or `"original"`). |
| `check_updates` | Set to `false` to turn off the daily update check. |

You can edit or delete the file at any time. If it is missing or damaged, the app uses its defaults. If your saved download folder can't be used (for example an unplugged drive), the app uses the default folder instead and writes the reason to the log.

## File names

```text
<Title> [<quality>] [<video id>].<extension>
```

```text
Loose Emotions EP 12 [720p] [ZG98zgaVIGg].mp4
Loose Emotions EP 12 [192kbps] [ZG98zgaVIGg].mp3
Loose Emotions EP 12 [original] [ZG98zgaVIGg].m4a
```

The quality and the YouTube video ID always stay at the end of the name. The app uses them to recognise files it already downloaded, so the same video at the same quality is never downloaded twice, while different qualities can sit side by side.

Titles are cleaned so the names work on Linux, Windows and macOS:

| In the title | In the file or folder name |
|---|---|
| `:` | ` -` |
| `/` `\` `\|` | `-` |
| `"` | `'` |
| `?` `*` `<` `>` | removed |
| Control and invisible characters | removed |
| Repeated spaces | one space |
| Leading or trailing dots and spaces | removed |
| Windows reserved names (`CON`, `NUL`, `COM1` and so on) | prefixed with `_` |

Other rules:

- Titles in any script (Hindi, Japanese, Arabic and so on) are kept and never cut in the middle of a character.
- Titles are limited to 150 bytes. On Windows they are shortened further when the folder path is long, so the whole path stays under Windows' 260-character limit.
- Playlist folder names follow the same rules and are limited to 80 bytes.
- If a title is empty after cleaning, the video ID is used instead.

## Privacy

- The app has no accounts and sends no usage statistics.
- It talks to YouTube (through yt-dlp, and for thumbnails), and to PyPI for the yt-dlp update check (at most once a day, and you can turn it off).
- The log records links and titles of what you download, with share-tracking parameters such as `si=` removed.
- The diagnostics report replaces your home folder with `~`. Read its "Recent log" section before posting it publicly.

## Troubleshooting

**The window doesn't open: "Could not load the Qt platform plugin".** A system library is missing.

```bash
# Fedora
sudo dnf install xcb-util-cursor

# Ubuntu, Debian, Mint
sudo apt install libxcb-cursor0
```

**Lines starting with `qt.qpa.wayland` or "Could not register app ID" appear in the terminal.** These are harmless desktop-integration messages from Wayland. The app works normally.

**FFmpeg or Deno not found.** Check `ffmpeg -version` and `deno --version`. On Linux and macOS make sure `~/.deno/bin` is on your PATH. On Windows, close and reopen the terminal after installing so the PATH updates.

**Downloads suddenly fail, or formats are missing.** Update yt-dlp (see [Updating yt-dlp](#updating-yt-dlp)). This fixes most sudden failures.

**"HTTP Error 403" or "429".** Usually temporary. The app retries up to two more times. If it keeps happening, update yt-dlp and try again later. Downloading many videos quickly can trigger YouTube's rate limits.

**"Requires authentication", "age-restricted", "members only", "unavailable" or "not available in your country".** The video needs a signed-in account, was removed, or is blocked in your region. The app can't sign in.

**MP3 conversion fails, or there is no cover art.** On Fedora, install the full FFmpeg from RPM Fusion (see [Installation](#installation)). Fedora's default build may lack MP3 encoding.

**A 1440p or 2160p file won't play on my TV or phone.** Those resolutions are AV1. Download 1080p or lower for H.264.

**The estimated size is slightly off.** Sizes come from the information YouTube provides. When it is only approximate, the final file can differ by a few percent.

**"Not enough free disk space".** The app estimates the space a download needs (a video needs room for its parts and the merged file at the same time) and stops early. Free some space or choose another folder.

**Files end up in a different folder than the one I chose.** If the saved folder can't be used, the app falls back to the default and logs why. Press **Copy diagnostics** or run `python main.py --diagnostics` to see the reason.

**No notification, or the folder doesn't open.** Notifications work on Linux only and need `notify-send`. If a folder doesn't open, open the download folder by hand. Downloads work either way.

**Strange symbols in the terminal app on Windows.** Use Windows Terminal instead of the old `cmd.exe` window.

**"File not found" on very long titles (Windows).** Windows limits paths to 260 characters by default. Use a shorter playlist folder name, or a download location closer to the drive root, such as `C:\Downloads`.

**A download was interrupted.** Press **Cancel** (or Ctrl+C) to stop cleanly. Run the same download again to start over. Finished files are skipped.

## Reporting a problem

1. In the window, press **Copy diagnostics** (it appears after an error), or run `python main.py --diagnostics`.
2. The report lists the app, Python, yt-dlp, FFmpeg and Deno versions, which yt-dlp copy is in use, and the end of the log.
3. Check the "Recent log" part for anything private, then paste the report into your bug report together with what you did and what you expected.

## For developers

```text
zardit/
├── zardit_app.py          # Unified GUI/CLI launcher (used by AppImage)
├── gui.py                 # Window app (Qt)
├── main.py                # Terminal app (Rich)
├── downloader.py          # Core logic: fetching, downloading, playlists, images
├── updater.py             # In-app yt-dlp updates (standard library only)
├── config.py              # Constants and settings
├── theme.py               # Light / Dark / Auto themes
├── history.py             # Persistent download history
├── logs.py                # Log file and diagnostics report
├── desktop.py             # Notifications and opening folders
├── assets/                # Zardit SVG/PNG application icons
├── packaging/linux/       # AppImage build script, AppRun, .desktop, local installer
├── .github/workflows/     # GitHub Actions CI and tag-triggered AppImage releases
├── requirements.txt
├── requirements-dev.txt
├── README.md
└── tests/
```

| File | Purpose |
|---|---|
| `zardit_app.py` | Single entry point for packaged builds (`--cli` / `--diagnostics` route to `main.py`). |
| `downloader.py` | The core. It never prints or asks questions: interfaces pass in a `Reporter` to receive progress and a cancel event to stop a download. |
| `gui.py` and `main.py` | Thin interfaces over the same core, so a change to downloading lands in both. |
| `updater.py` | Downloads yt-dlp from PyPI and loads it first. It must not import yt-dlp itself. |
| `config.py` | Version, quality lists, and loading and saving the settings file. |

One rule matters: **`updater.activate()` must run before anything imports yt-dlp.** `zardit_app.py`, `gui.py`, and `main.py` do this at the very top. Keep it first when you add an entry point.

**Build the Linux AppImage locally**

```bash
pip install pyinstaller
./packaging/linux/build_appimage.sh
```

Or pass `--use-system-tools` to reuse system `ffmpeg` and `deno` during local builds. Tagging a release (`git tag v1.2 && git push origin v1.2`) triggers `.github/workflows/release-appimage.yml` to build and publish `Zardit-<version>-x86_64.AppImage` and `SHA256SUMS.txt` on GitHub Releases.

**Run the checks**

```bash
pip install -r requirements-dev.txt
python -m unittest discover tests -v
python -m pyflakes *.py
```

The tests need no network access. `tests/test_names.py` runs pyflakes automatically and is skipped when pyflakes isn't installed. The window itself isn't covered by tests, so run pyflakes after editing `gui.py`.

## Legal and responsible use

This application is intended for downloading content that you own, have permission to download, or are otherwise legally authorized to download.

Respect copyright laws, YouTube's Terms of Service and the rights of content creators. Do not use this software to infringe copyright or bypass access restrictions.

## Roadmap

Ideas, not promises:

- Ready-to-run Windows installer alongside the Linux AppImage
- Faster playlists by preparing the next video while one downloads
- Subtitles, and downloading a clip by start and end time
- An option for WebM or MKV to offer more formats above 1080p
- Windows desktop notifications

## License

No license has been specified yet. All rights reserved by the project author unless a license is added.