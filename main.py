"""Zardit - modern terminal interface (Rich)."""

from pathlib import Path
import shutil
import subprocess
import sys
import threading

import updater

updater.activate()   # Must run before anything imports yt-dlp.

from updater import UpdateError

from rich import box
from rich.console import Console
from rich.markup import escape
from rich.padding import Padding
from rich.panel import Panel
from rich.progress import (
    BarColumn,
    DownloadColumn,
    Progress,
    SpinnerColumn,
    TextColumn,
    TimeRemainingColumn,
    TransferSpeedColumn,
)
from rich.rule import Rule
from rich.table import Table
from rich.theme import Theme

from config import (
    APP_LICENSE,
    APP_NAME,
    APP_TAGLINE,
    APP_VERSION,
    AUDIO_CHOICES,
    IG_BROWSER_LABELS,
    IG_BROWSERS,
    IMAGE_FORMATS,
    PLAYLIST_QUALITIES,
    audio_label,
    get_download_dir,
    get_ig_browser,
    image_label,
    load_settings,
    remember_setting,
    save_download_dir,
    save_ig_browser,
)

from desktop import notify, open_file, open_folder

from downloader import (
    DownloadError,
    PlaylistInfo,
    PlaylistLinkError,
    Reporter,
    _normalize_playlist_entries,
    audio_format_options,
    detect_platform,
    download_audio,
    download_image,
    download_playlist,
    download_video,
    extract_media_url,
    fetch_playlist,
    fetch_video_info,
    format_duration,
    format_file_size,
    has_playlist_and_video,
    image_format_options,
    is_image_only_info,
    parse_instagram_source,
    parse_range,
    playlist_video_format_options,
    summarize_video,
    video_format_options,
)

from history import (
    add_history_entry,
    clear_history,
    load_history,
    remove_history_entry,
)
from logs import LOG_FILE, diagnostics_text, setup_logging
from theme import (
    TERMINAL_PALETTES,
    THEME_LABELS,
    THEME_MODES,
    cycle_theme_mode,
    get_saved_theme,
    normalize_theme_mode,
    resolve_terminal_theme,
    save_theme,
)


MIN_UI_WIDTH = 46
MAX_UI_WIDTH = 88
SIDE_MARGIN = 2


def build_console(theme_mode=None):
    """Create a Rich Console styled for the resolved light or dark theme."""

    resolved = resolve_terminal_theme(theme_mode)
    return Console(theme=Theme(TERMINAL_PALETTES[resolved]))


theme_mode = get_saved_theme()
console = build_console(theme_mode)


def refresh_console(new_mode=None):
    """Update the active console when the theme preference changes."""

    global theme_mode, console
    if new_mode is not None:
        theme_mode = save_theme(new_mode)
    console = build_console(theme_mode)
    return console


# --------------------------------------------------------------------------
# Responsive layout & output helpers
# --------------------------------------------------------------------------

def ui_width():
    """Responsive content width that respects terminal margins."""

    available = max(MIN_UI_WIDTH, console.width - (SIDE_MARGIN * 2))
    return min(available, MAX_UI_WIDTH)


def is_compact():
    """True when the terminal is narrow and needs compact layouts."""

    return ui_width() < 62


def truncate_middle(text, limit):
    """Shorten long text (such as paths) cleanly from the middle."""

    text = str(text)
    if len(text) <= limit or limit < 10:
        return text

    keep = (limit - 1) // 2
    return f"{text[:keep]}…{text[-keep:]}"


def print_block(renderable, top=1, bottom=0):
    """Print a renderable with consistent horizontal and vertical margins."""

    console.print(Padding(renderable, (top, SIDE_MARGIN, bottom, SIDE_MARGIN)))


def print_line(markup="", highlight=False):
    """Print styled text aligned with the outer left margin."""

    console.print(Padding(markup, (0, SIDE_MARGIN)), highlight=highlight)


def print_section(title):
    """Print a clean horizontal divider for a workflow step."""

    rule = Rule(
        f"[accent.bold]{escape(title)}[/]",
        style="border",
        align="left",
    )
    print_block(Padding(rule, (0, 0)), top=1, bottom=0)


def status_panel(message, title, border_style="card.border", icon=""):
    """Render a compact rounded status banner."""

    prefix = f"{icon}  " if icon else ""
    return Panel(
        f"{prefix}{message}",
        title=f"[{border_style}]{escape(title)}[/]",
        title_align="left",
        border_style=border_style,
        box=box.ROUNDED,
        width=ui_width(),
        padding=(0, 2),
    )


def prompt_line(label, hint=None):
    """Prompt the user with consistent indentation and styling."""

    hint_text = f" [muted]({escape(hint)})[/]" if hint else ""
    return console.input(
        f"\n  [accent.bold]›[/] [prompt]{escape(label)}[/]{hint_text}: "
    ).strip()


# --------------------------------------------------------------------------
# Terminal display of download events
# --------------------------------------------------------------------------

class RichReporter(Reporter):
    """Shows download events using Rich."""

    STYLES = {"info": "info", "warning": "warning", "error": "error"}

    def __init__(self):
        self.bar = None
        self.task = None

    def message(self, text, level="info"):
        style = self.STYLES.get(level)
        if style:
            print_line(f"[{style}]{escape(text)}[/]")
        else:
            print_line(escape(text))

    def stage(self, text):
        print_line(f"[warning]• {escape(text)}[/]")

    def playlist_item(self, number, total, title):
        console.print()
        print_line(
            f"[accent.bold]({number}/{total})[/] [heading]{escape(title)}[/]"
        )

    def progress(self, downloaded, total, speed, eta):
        if self.bar is None:
            columns = [
                TextColumn("  "),
                SpinnerColumn(style="accent"),
                TextColumn("[accent.bold]Downloading[/]"),
                BarColumn(
                    bar_width=None,
                    style="border",
                    complete_style="progress.bar",
                    finished_style="progress.complete",
                ),
                TextColumn("[heading]{task.percentage:>5.1f}%[/]"),
            ]

            if not is_compact():
                columns.extend([
                    DownloadColumn(),
                    TransferSpeedColumn(),
                    TimeRemainingColumn(),
                ])
            else:
                columns.append(DownloadColumn())

            self.bar = Progress(
                *columns,
                console=console,
                transient=True,
                expand=False,
            )
            self.bar.start()
            self.task = self.bar.add_task("download", total=total)

        self.bar.update(self.task, completed=downloaded, total=total)

    def progress_done(self):
        if self.bar is not None:
            self.bar.stop()
            self.bar = None


# --------------------------------------------------------------------------
# Small helpers
# --------------------------------------------------------------------------

def confirm(prompt):
    answer = console.input(
        f"\n  [accent.bold]?[/] [prompt]{escape(prompt)}[/] [muted](y/n)[/]: "
    ).strip().lower()
    return answer in ("y", "yes")


def show_error(error):
    print_block(
        status_panel(
            f"[error]{escape(str(error))}[/]",
            "Error",
            border_style="error",
            icon="[error]✖[/]",
        ),
        top=1,
        bottom=0,
    )


def missing_tools():
    """Return a list of external helper binaries that are missing."""

    return [tool for tool in ("ffmpeg", "deno") if not shutil.which(tool)]


def check_requirements():
    """Warn about missing tools cleanly."""

    missing = missing_tools()
    if missing:
        names = ", ".join(missing)
        print_block(
            status_panel(
                f"[warning]Missing required helper(s): {escape(names)}. "
                "Some downloads or merges may fail until installed.[/]",
                "Environment Check",
                border_style="warning",
                icon="[warning]![/]",
            ),
            top=1,
            bottom=0,
        )


def offer_open_folder(folder=None):
    if not confirm("Open download folder?"):
        return

    if not open_folder(folder or get_download_dir()):
        print_line("[warning]Could not open the folder automatically.[/]")


def select_option(options, label, default=None):
    hint = f"Enter = {default}, Q = cancel" if default in options else "Q = cancel"

    while True:
        choice = prompt_line(f"Select {label}", hint=hint).lower()

        if choice == "q":
            return None

        if choice == "" and default in options:
            return default

        if choice.isdigit():
            index = int(choice)

            if 1 <= index <= len(options):
                return options[index - 1]

        print_line("[error]Invalid selection. Enter a listed number or Q.[/]")


def read_clipboard_text():
    """Read text from the system clipboard if a platform tool is available."""

    commands = []
    if sys.platform == "darwin":
        commands = [["pbpaste"]]
    elif sys.platform == "win32":
        commands = [["powershell", "-NoProfile", "-Command", "Get-Clipboard"]]
    else:
        commands = [
            ["wl-paste", "--no-newline"],
            ["xclip", "-selection", "clipboard", "-o"],
            ["xsel", "--clipboard", "--output"],
        ]

    for cmd in commands:
        if not shutil.which(cmd[0]):
            continue

        try:
            res = subprocess.run(
                cmd, capture_output=True, text=True, timeout=2
            )
            if res.returncode == 0:
                return res.stdout.strip()
        except (OSError, subprocess.SubprocessError):
            continue

    return ""


def get_url():
    raw = prompt_line(
        "Media URL",
        hint="YouTube/IG/FB/Reddit/X/Threads, P = paste, Q = cancel",
    )

    if not raw or raw.lower() == "q":
        if not raw:
            print_line("[error]URL cannot be empty.[/]")
        return None

    if raw.lower() == "p":
        clip = read_clipboard_text()
        if not clip:
            print_line("[warning]Clipboard is empty or unavailable.[/]")
            return None

        extracted = extract_media_url(clip)
        if not extracted:
            print_line("[error]Clipboard does not contain a valid media URL.[/]")
            return None

        print_line(f"[muted]Pasted:[/] [accent]{escape(extracted)}[/]")
        return extracted

    return raw


update_notice = {"version": None}


def start_update_check():
    """Look for a newer yt-dlp in the background (a request at most once a day)."""

    def work():
        update_notice["version"] = updater.background_check()

    threading.Thread(target=work, daemon=True).start()


def show_menu_notice():
    if update_notice["version"]:
        print_block(
            status_panel(
                f"yt-dlp [accent.bold]{escape(update_notice['version'])}[/] "
                "is available. Choose [accent.bold]5[/] to update.",
                "Update Available",
                border_style="warning",
                icon="[warning]↑[/]",
            ),
            top=1,
            bottom=0,
        )


def offer_daily_check_setting():
    enabled = updater.auto_check_enabled()
    state, other = ("on", "off") if enabled else ("off", "on")

    if confirm(f"The daily update check is {state}. Turn it {other}?"):
        updater.set_auto_check(not enabled)


def update_ytdlp():
    """Check for a newer yt-dlp and install it if the user agrees."""

    print_section("Update yt-dlp")

    try:
        with console.status("  [accent]Checking for updates…[/]"):
            info = updater.check_for_update()
    except UpdateError as error:
        show_error(error)
        return

    update_notice["version"] = info.latest if info.available else None

    details = Table.grid(padding=(0, 2))
    details.add_column(style="muted")
    details.add_column(style="accent.bold")
    details.add_row("Installed", escape(info.current))

    if info.available:
        details.add_row("Latest", escape(info.latest))

    print_block(
        Panel(
            details,
            title="[heading]yt-dlp Version[/]",
            title_align="left",
            border_style="card.border",
            box=box.ROUNDED,
            width=ui_width(),
            padding=(1, 2),
        ),
        top=1,
    )

    if info.available:
        if not confirm("Download and install this update?"):
            return

        try:
            with console.status("  [accent]Updating yt-dlp…[/]") as status:
                version = updater.install_update(
                    report=status.update, info=info
                )
        except UpdateError as error:
            show_error(error)
            return

        print_block(
            status_panel(
                f"yt-dlp [heading]{escape(str(version))}[/] is installed. "
                "Restart this app to use it.",
                "Updated",
                border_style="success",
                icon="[success]✔[/]",
            ),
            top=1,
        )
        update_notice["version"] = None
        return

    if info.pending_restart:
        print_block(
            status_panel(
                f"yt-dlp [heading]{escape(str(info.installed))}[/] is already "
                "installed. Restart this app to use it.",
                "Restart Required",
                border_style="warning",
                icon="[warning]![/]",
            ),
            top=1,
        )
    else:
        print_block(
            status_panel(
                "yt-dlp is already up to date.",
                "Up to Date",
                border_style="success",
                icon="[success]✔[/]",
            ),
            top=1,
        )

    if updater.update_active() and confirm(
        "Go back to the yt-dlp that came with the app?"
    ):
        updater.remove_update()
        print_line("The built-in version will be used after a restart.")

    offer_daily_check_setting()


# --------------------------------------------------------------------------
# Theme switching & header / menu
# --------------------------------------------------------------------------

def theme_summary():
    resolved = resolve_terminal_theme(theme_mode)
    label = THEME_LABELS[theme_mode]
    if theme_mode == "system":
        return f"{label} ({THEME_LABELS[resolved]})"
    return label


def change_theme():
    """Let the user choose Auto, Light, or Dark theme or cycle quickly."""

    print_section("Appearance")

    table = Table(
        box=box.ROUNDED,
        width=ui_width(),
        border_style="card.border",
        header_style="accent.bold",
        padding=(0, 2),
    )
    table.add_column("#", justify="right", style="accent.bold", width=4)
    table.add_column("Theme", style="heading")
    table.add_column("Description", style="muted")

    descriptions = {
        "system": "Detect light/dark terminal automatically",
        "light": "High-contrast colors for light backgrounds",
        "dark": "Crisp vibrant colors for dark backgrounds",
    }

    for index, mode in enumerate(THEME_MODES, start=1):
        active_tag = " [success]● active[/]" if mode == theme_mode else ""
        table.add_row(
            str(index),
            f"{THEME_LABELS[mode]}{active_tag}",
            descriptions[mode],
        )

    print_block(table, top=1)

    chosen = select_option(list(THEME_MODES), "theme", default=theme_mode)
    if chosen is None:
        print_line("[warning]Cancelled.[/]")
        return

    refresh_console(chosen)
    print_block(
        status_panel(
            f"Terminal theme set to [heading]{escape(theme_summary())}[/].",
            "Theme Updated",
            border_style="success",
            icon="[success]✔[/]",
        ),
        top=1,
    )


def show_menu():
    width = ui_width()
    compact = is_compact()
    download_dir = str(get_download_dir(create=False))
    ffmpeg_ok = bool(shutil.which("ffmpeg"))
    deno_ok = bool(shutil.which("deno"))

    # Status strip above menu options
    meta = Table.grid(expand=True, padding=(0, 2))
    meta.add_column(style="muted", no_wrap=True)
    meta.add_column(ratio=1)

    path_limit = max(20, width - 22)
    meta.add_row("Tagline", f"[muted]{escape(APP_TAGLINE)}[/]")
    meta.add_row("Save to", f"[heading]{escape(truncate_middle(download_dir, path_limit))}[/]")
    meta.add_row("Theme", f"[accent.bold]{escape(theme_summary())}[/]")
    ig_browser = get_ig_browser()
    ig_status = IG_BROWSER_LABELS.get(ig_browser, ig_browser.title()) if ig_browser else "Off"
    meta.add_row("IG Auth", f"[muted]{escape(ig_status)}[/]")
    meta.add_row("License", f"[muted]{escape(APP_LICENSE)}[/]")
    if not compact:
        meta.add_row("Sites", "[muted]YouTube · Instagram · Facebook · Reddit · X · Threads[/]")

    ffmpeg_pill = "[pill.ok]FFmpeg ✔[/]" if ffmpeg_ok else "[pill.warn]FFmpeg missing[/]"
    deno_pill = "[pill.ok]Deno ✔[/]" if deno_ok else "[pill.warn]Deno missing[/]"
    meta.add_row("Tools", f"{ffmpeg_pill}   {deno_pill}")

    # Menu options grid
    menu_table = Table.grid(expand=True, padding=(0, 2))
    menu_table.add_column(width=4, justify="center")
    menu_table.add_column(style="heading", no_wrap=True)
    if not compact:
        menu_table.add_column(style="muted", justify="right")

    history_count = len(load_history())
    history_desc = (
        f"View, play & manage ({history_count})"
        if history_count
        else "View, play & manage"
    )

    items = [
        ("1", "Download Video", "Single video as MP4"),
        ("2", "Download Audio", "MP3 or original audio"),
        ("3", "Download Playlist", "Batch select & range"),
        ("I", "Download Image", "Photo, gallery, or thumbnail"),
        ("4", "Download Location", "Change output folder"),
        ("5", "Update yt-dlp", "Check or install updates"),
        ("H", "Download History", history_desc),
        ("G", "Instagram Access", "Private IG cookies or source"),
        ("T", "Switch Theme", f"Current: {theme_summary()}"),
        ("6", "Exit", "Quit application"),
    ]

    for key, label, desc in items:
        key_cell = f"[accent.bold][{key}][/]"
        if compact:
            menu_table.add_row(key_cell, label)
        else:
            menu_table.add_row(key_cell, label, desc)

    body = Table.grid(expand=True, padding=(0, 0))
    body.add_row(meta)
    body.add_row(Rule(style="border"))
    body.add_row(menu_table)

    panel = Panel(
        body,
        title=f"[brand]▶[/] [heading]{APP_NAME}[/] [muted]v{APP_VERSION}[/]",
        title_align="left",
        subtitle="[muted]Enter a choice below[/]",
        subtitle_align="right",
        border_style="card.border",
        box=box.ROUNDED,
        width=width,
        padding=(1, 2),
    )

    print_block(panel, top=1, bottom=0)
    show_menu_notice()


# --------------------------------------------------------------------------
# Single video and audio
# --------------------------------------------------------------------------

def show_video_card(info):
    """Display a video's details in a responsive rounded card."""

    summary = summarize_video(info)
    width = ui_width()

    grid = Table.grid(expand=True, padding=(0, 2))
    grid.add_column()

    grid.add_row(f"[badge] VIDEO [/]  [heading]{escape(summary.title)}[/]")
    grid.add_row(f"[muted]{escape(summary.channel)}[/]")
    grid.add_row("")

    if is_compact():
        grid.add_row(
            f"[muted]Duration:[/] {format_duration(summary.duration)}"
        )
        grid.add_row(f"[muted]Uploaded:[/] {summary.upload_date}")
    else:
        meta_line = Table.grid(padding=(0, 4))
        meta_line.add_column()
        meta_line.add_column()
        meta_line.add_row(
            f"[muted]Duration:[/] [heading]{format_duration(summary.duration)}[/]",
            f"[muted]Uploaded:[/] [heading]{summary.upload_date}[/]",
        )
        grid.add_row(meta_line)

    print_block(
        Panel(
            grid,
            border_style="card.border",
            box=box.ROUNDED,
            width=width,
            padding=(1, 2),
        ),
        top=1,
    )


def _render_format_table(options, title_col, default_value=None):
    """Display a responsive Rich table for video or audio format options."""

    compact = is_compact()
    table = Table(
        box=box.ROUNDED,
        width=ui_width(),
        border_style="card.border",
        header_style="accent.bold",
        padding=(0, 2),
    )
    table.add_column("#", justify="right", style="accent.bold", width=4)
    table.add_column(title_col, style="heading")

    if not compact:
        table.add_column("Details", style="muted")

    has_sizes = any(opt.size is not None for opt in options)
    if has_sizes:
        table.add_column("Est. size", justify="right", style="muted")

    for index, opt in enumerate(options, start=1):
        badge = f" [badge] {escape(opt.badge)} [/]" if opt.badge else ""
        default_tag = (
            "  [success]★ default[/]" if opt.value == default_value else ""
        )
        name_cell = f"{escape(opt.label)}{badge}{default_tag}"

        row = [str(index), name_cell]
        if not compact:
            row.append(escape(opt.details))
        if has_sizes:
            row.append(
                f"~{format_file_size(opt.size)}" if opt.size is not None else "--"
            )

        table.add_row(*row)

    print_block(table, top=1)


def show_quality_table(info, default_quality=None):
    """Display available resolutions with codec details and estimated sizes."""

    with console.status("  [accent]Calculating available formats…[/]"):
        options = video_format_options(info)

    if not options:
        print_block(
            status_panel(
                "No supported MP4 resolutions found for this video. "
                "Try Audio mode instead.",
                "No MP4 Formats",
                border_style="warning",
                icon="[warning]![/]",
            ),
            top=1,
        )
        return []

    _render_format_table(options, "Video Quality", default_quality)
    return [opt.value for opt in options]


def show_audio_table(default_bitrate=None, info=None):
    options = audio_format_options(info)
    _render_format_table(options, "Audio Format", default_bitrate)


def show_image_table(default_format=None, info=None):
    options = image_format_options(info)
    _render_format_table(options, "Image Format", default_format)
    return [opt.value for opt in options]


def choice_text(kind, value, ceiling=False):
    """How a quality choice reads on screen."""

    if kind == "audio":
        return audio_label(value)

    if kind == "image":
        return image_label(value)

    return f"up to {value}p MP4" if ceiling else f"{value}p MP4"


def run_download(function, url, setting, info, kind="video"):
    """Run one download and print the outcome.

    Returns True only when a new file was saved.
    """

    print_line(
        f"[muted]Saving to:[/] {escape(truncate_middle(get_download_dir(), ui_width() - 14))}"
    )
    console.print()

    try:
        result = function(url, setting, info=info, reporter=RichReporter())
    except DownloadError as error:
        show_error(error)
        print_line(f"[muted]Log details: {escape(str(LOG_FILE))}[/]")
        return False

    if result.status == "completed":
        if result.file_path is not None:
            summary = summarize_video(info)
            add_history_entry(
                title=summary.title,
                file_path=result.file_path,
                kind=kind,
                quality=choice_text(kind, setting),
                channel=summary.channel,
                video_id=info.get("id", ""),
                duration=summary.duration,
                platform=detect_platform(url),
            )
        print_block(
            status_panel(
                "Download completed successfully!",
                "Done",
                border_style="success",
                icon="[success]✔[/]",
            ),
            top=1,
        )
        notify("Download complete", info.get("title", "Download"))
        return True

    if result.status == "exists":
        print_block(
            status_panel(
                f"This file has already been downloaded:\n[heading]{escape(result.filename)}[/]",
                "Already Exists",
                border_style="warning",
                icon="[warning]•[/]",
            ),
            top=1,
        )
    else:
        print_line("[warning]Download cancelled.[/]")

    return False


def inspect_single(kind):
    """Download one video ("video"), track ("audio"), or image ("image")."""

    titles = {
        "video": "Download Video",
        "audio": "Download Audio",
        "image": "Download Image",
    }
    print_section(titles.get(kind, "Download"))

    url = get_url()
    if not url:
        return

    if has_playlist_and_video(url):
        print_line("\n[warning]This link is a video inside a playlist.[/]")

        if confirm("Download the whole playlist instead of just this video?"):
            inspect_playlist(url=url, preset_kind=kind)
            return

    try:
        with console.status("  [accent]Fetching media information…[/]"):
            info = fetch_video_info(url)

    except PlaylistLinkError:
        print_line("\n[warning]This is a playlist or gallery link.[/]")

        if confirm("Switch to batch download?"):
            inspect_playlist(url=url, preset_kind=kind)

        return

    except DownloadError as error:
        show_error(error)
        return

    _handle_single_info(kind, url, info)


def _handle_single_info(kind, url, info):
    """Present fetched or offline-parsed single video/audio/image options."""

    show_video_card(info)
    settings = load_settings()

    if kind != "image" and is_image_only_info(info):
        print_line("[info]Detected an image post — switching to Image mode.[/]")
        kind = "image"

    if kind == "video":
        default_val = settings.get("video_quality")
        choices = show_quality_table(info, default_quality=default_val)

        if not choices:
            return

        label, key = "resolution", "video_quality"
        function = download_video

    elif kind == "audio":
        default_val = settings.get("audio_bitrate")
        show_audio_table(default_bitrate=default_val, info=info)

        choices = AUDIO_CHOICES
        label, key = "audio quality", "audio_bitrate"
        function = download_audio

    else:
        default_val = settings.get("image_format")
        choices = show_image_table(default_format=default_val, info=info)
        label, key = "image format", "image_format"
        function = download_image

    setting = select_option(choices, label, default=default_val)

    if setting is None:
        print_line("[warning]Cancelled.[/]")
        return

    print_line(f"\n[muted]Selected:[/] [accent.bold]{choice_text(kind, setting)}[/]")

    if not confirm("Start download?"):
        print_line("[warning]Cancelled.[/]")
        return

    if run_download(function, url, setting, info, kind=kind):
        remember_setting(key, setting)
        offer_open_folder()


def configure_instagram_access():
    """Configure local browser cookies or extract a private IG post offline."""

    print_section("Private Instagram Access")
    current = get_ig_browser()
    print_line(
        f"[muted]Current browser cookies:[/] [heading]{escape(IG_BROWSER_LABELS.get(current, 'Off (Public posts only)'))}[/]"
    )

    menu = Table(
        box=box.ROUNDED,
        width=ui_width(),
        border_style="card.border",
        header_style="accent.bold",
        padding=(0, 2),
    )
    menu.add_column("#", justify="right", style="accent.bold", width=4)
    menu.add_column("Method", style="heading")
    menu.add_column("How it works", style="muted")
    menu.add_row("1", "Connect Browser Cookies", "Use Chrome/Firefox/Edge session locally")
    menu.add_row("2", "Extract from Page Source (Offline)", "Paste view-source HTML / ?__a=1 JSON or file")
    print_block(menu, top=1)

    mode = select_option(["browser", "source"], "option")
    if mode is None:
        print_line("[warning]Cancelled.[/]")
        return

    if mode == "browser":
        b_table = Table(
            box=box.ROUNDED,
            width=ui_width(),
            border_style="card.border",
            header_style="accent.bold",
            padding=(0, 2),
        )
        b_table.add_column("#", justify="right", style="accent.bold", width=4)
        b_table.add_column("Browser", style="heading")
        opts = [None, *IG_BROWSERS]
        for idx, b in enumerate(opts, start=1):
            label = "Off (Public posts only)" if b is None else IG_BROWSER_LABELS.get(b, b.title())
            tag = " [success]● active[/]" if b == current else ""
            b_table.add_row(str(idx), f"{label}{tag}")
        print_block(b_table, top=1)
        chosen = select_option(opts, "browser", default=current)
        if chosen is None and current is None:
            print_line("[muted]Instagram cookies remain Off.[/]")
            return
        saved = save_ig_browser(chosen)
        print_block(
            status_panel(
                f"Instagram browser cookies set to [heading]{escape(IG_BROWSER_LABELS.get(saved, 'Off'))}[/].",
                "Instagram Access Updated",
                border_style="success",
                icon="[success]✔[/]",
            ),
            top=1,
        )
        return

    src_url = prompt_line(
        "Optional post URL",
        hint="Enter = skip, Q = cancel",
    )
    if src_url.lower() == "q":
        print_line("[warning]Cancelled.[/]")
        return

    file_or_paste = prompt_line(
        "Path to saved HTML/JSON file, or press Enter to paste text",
        hint="Q = cancel",
    )
    if file_or_paste.lower() == "q":
        print_line("[warning]Cancelled.[/]")
        return

    raw_text = ""
    if file_or_paste:
        try:
            raw_text = Path(file_or_paste).expanduser().read_text(encoding="utf-8", errors="replace")
        except OSError as err:
            show_error(DownloadError(f"Could not read file: {err}"))
            return
    else:
        print_line("[muted]Paste view-source HTML or ?__a=1 JSON below, then type END on a new line:[/]")
        lines = []
        while True:
            try:
                line = input()
            except EOFError:
                break
            if line.strip() == "END":
                break
            lines.append(line)
        raw_text = "\n".join(lines)

    try:
        parsed = parse_instagram_source(raw_text, src_url or None)
    except DownloadError as error:
        show_error(error)
        return

    resolved_url = parsed.get("webpage_url") or src_url or "https://www.instagram.com/"
    if parsed.get("_type") == "playlist":
        entries = parsed.get("entries") or []
        title = parsed.get("title") or "Instagram Post"
        _normalize_playlist_entries(entries, title, resolved_url)
        pl = PlaylistInfo(title=title, entries=entries)
        _inspect_loaded_playlist(pl, resolved_url)
        return

    _handle_single_info("video", resolved_url, parsed)


def inspect_playlist(url=None, preset_kind=None):
    if url is None:
        print_section("Download Playlist")
        url = get_url()

    if not url:
        return

    try:
        with console.status("  [accent]Fetching playlist…[/]"):
            playlist = fetch_playlist(url)
    except DownloadError as error:
        show_error(error)
        return

    _inspect_loaded_playlist(playlist, url, preset_kind=preset_kind)


def _inspect_loaded_playlist(playlist, url, preset_kind=None):
    entries = playlist.entries
    count = len(entries)

    print_block(
        Panel(
            f"[badge] PLAYLIST [/]  [heading]{escape(playlist.title)}[/]\n"
            f"[muted]{count} {'video' if count == 1 else 'videos'}[/]",
            border_style="card.border",
            box=box.ROUNDED,
            width=ui_width(),
            padding=(1, 2),
        ),
        top=1,
    )

    is_image_gallery = bool(
        entries
        and all(e.get("_zardit_media_kind") == "image" for e in entries)
    )

    if is_image_gallery:
        kind = "image"
    elif preset_kind in ("video", "audio", "image"):
        kind = preset_kind
    else:
        fmt_table = Table(
            box=box.ROUNDED,
            width=ui_width(),
            border_style="card.border",
            header_style="accent.bold",
            padding=(0, 2),
        )
        fmt_table.add_column("#", justify="right", style="accent.bold", width=4)
        fmt_table.add_column("Format", style="heading")
        fmt_table.add_row("1", "Video (MP4)")
        fmt_table.add_row("2", "Audio")
        fmt_table.add_row("3", "Image")
        print_block(fmt_table, top=1)
        kind = select_option(["video", "audio", "image"], "format")

    if kind is None:
        print_line("[warning]Cancelled.[/]")
        return

    if kind == "video":
        choices = PLAYLIST_QUALITIES
        label, key = "maximum resolution", "video_quality"
        format_opts = playlist_video_format_options()
        title_col = "Max Video Quality"
    elif kind == "audio":
        choices = AUDIO_CHOICES
        label, key = "audio quality", "audio_bitrate"
        format_opts = audio_format_options()
        title_col = "Audio Format"
    else:
        choices = IMAGE_FORMATS
        label, key = "image format", "image_format"
        format_opts = image_format_options()
        title_col = "Image Format"

    default_val = load_settings().get(key)
    _render_format_table(format_opts, title_col, default_val)

    setting = select_option(choices, label, default=default_val)

    if setting is None:
        print_line("[warning]Cancelled.[/]")
        return

    while True:
        text = prompt_line(
            "Range, e.g. 1-3, 5, 8-10",
            hint=f"Enter = all {count}, Q = cancel",
        )

        if text.lower() == "q":
            print_line("[warning]Cancelled.[/]")
            return

        indices = parse_range(text, count)

        if indices is not None:
            break

        print_line("[error]Invalid range. Try again.[/]")

    selected = [entries[i - 1] for i in sorted(indices)]

    folder_name = prompt_line(
        "Subfolder name",
        hint=f"Enter = {truncate_middle(playlist.title, 28)}",
    ) or playlist.title

    if not confirm(
        f"Download {len(selected)} item(s) as "
        f"{choice_text(kind, setting, ceiling=True)}?"
    ):
        print_line("[warning]Cancelled.[/]")
        return

    console.print()

    try:
        result = download_playlist(
            selected, kind, setting, folder_name, reporter=RichReporter()
        )
    except DownloadError as error:
        show_error(error)
        return

    for item in getattr(result, "completed_items", ()):
        add_history_entry(
            title=item.get("title"),
            file_path=item.get("file_path"),
            kind=item.get("kind"),
            quality=item.get("quality", ""),
            video_id=item.get("video_id", ""),
            duration=item.get("duration"),
            platform=detect_platform(url),
        )

    summary = (
        f"[success]{result.completed} completed[/], "
        f"[muted]{result.skipped} already downloaded[/], "
        f"[{'error' if result.failed else 'muted'}]{len(result.failed)} failed[/]."
    )

    if result.cancelled:
        title, style = "Playlist Cancelled", "warning"
    elif result.failed and result.completed == 0:
        title, style = "Playlist Failed", "error"
    elif result.failed:
        title, style = "Finished With Errors", "warning"
    else:
        title, style = "Playlist Complete", "success"

    print_block(
        status_panel(summary, title, border_style=style),
        top=1,
    )

    if result.failed:
        print_line(f"[muted]Log details: {escape(str(LOG_FILE))}[/]")

    if result.completed:
        notify(
            "Playlist finished",
            f"{result.completed} of {result.total} downloaded",
        )
        remember_setting(key, setting)
        offer_open_folder(result.folder)


# --------------------------------------------------------------------------
# History
# --------------------------------------------------------------------------

def render_history_table(entries):
    """Display downloaded videos and audio in a responsive Rich table."""

    compact = is_compact()
    table = Table(
        box=box.ROUNDED,
        width=ui_width(),
        border_style="card.border",
        header_style="accent.bold",
        padding=(0, 2),
    )
    table.add_column("#", justify="right", style="accent.bold", width=4)
    table.add_column("Type", width=7)
    table.add_column("Title", style="heading")
    if not compact:
        table.add_column("Quality / Size", style="muted")
    table.add_column("Status", justify="right", width=9)

    for index, entry in enumerate(entries, start=1):
        kind = entry.get("kind", "video")
        if kind == "audio":
            type_cell = "[info]AUDIO[/]"
        elif kind == "image":
            type_cell = "[success]IMAGE[/]"
        else:
            type_cell = "[brand]VIDEO[/]"
        title_cell = escape(truncate_middle(entry.get("title") or "Untitled", 44 if not compact else 24))
        exists = Path(entry.get("file_path", "")).is_file()
        status_cell = "[pill.ok]Ready[/]" if exists else "[warning]Missing[/]"

        row = [str(index), type_cell, title_cell]
        if not compact:
            parts = []
            if entry.get("quality"):
                parts.append(entry["quality"])
            if entry.get("size"):
                parts.append(format_file_size(entry["size"]))
            row.append(escape(" · ".join(parts) or "--"))
        row.append(status_cell)
        table.add_row(*row)

    print_block(table, top=1)


def show_history():
    """Browse, play, remove, or clear downloaded videos and audio."""

    print_section("Download History")

    while True:
        entries = load_history()
        if not entries:
            print_block(
                status_panel(
                    "No downloaded videos or audio in history yet.",
                    "History Empty",
                    border_style="card.border",
                ),
                top=1,
            )
            return

        render_history_table(entries)
        print_line(
            "[muted]Enter a number to play, [heading]R <num>[/] to remove, "
            "[heading]C[/] to clear all, or [heading]Q[/] to return.[/]"
        )

        action = prompt_line(
            "History action",
            hint=f"1-{len(entries)}, R <#>, C, Q",
        ).lower()

        if not action or action == "q":
            return

        if action == "c":
            if confirm(f"Clear all {len(entries)} item(s) from history?"):
                delete_files = confirm(
                    "Also delete downloaded video/audio files from disk?"
                )
                cleared, deleted = clear_history(delete_files=delete_files)
                msg = f"Cleared {cleared} history item(s)."
                if delete_files:
                    msg += f" Deleted {deleted} file(s) from disk."
                print_block(
                    status_panel(msg, "History Cleared", border_style="success", icon="[success]✔[/]"),
                    top=1,
                )
            continue

        if action.startswith("r"):
            target_str = action[1:].strip() or prompt_line(
                "Item number to remove", hint=f"1-{len(entries)}, Q = cancel"
            ).lower()
            if not target_str or target_str == "q":
                continue
            if not target_str.isdigit() or not (1 <= int(target_str) <= len(entries)):
                print_line("[error]Invalid item number.[/]")
                continue

            entry = entries[int(target_str) - 1]
            delete_file = False
            if Path(entry.get("file_path", "")).is_file():
                delete_file = confirm("Also delete the file from disk?")

            removed, file_deleted = remove_history_entry(
                entry.get("id"), delete_file=delete_file
            )
            if removed:
                msg = f"Removed \"{escape(removed.get('title', 'item'))}\"."
                if file_deleted:
                    msg += " File deleted from disk."
                print_line(f"[success]{msg}[/]")
            continue

        if action.isdigit() and 1 <= int(action) <= len(entries):
            entry = entries[int(action) - 1]
            path = Path(entry.get("file_path", ""))
            if not path.is_file():
                print_line(
                    f"[warning]File no longer exists: {escape(str(path))}[/]"
                )
                continue
            if open_file(path):
                print_line(f"[success]Playing:[/] {escape(path.name)}")
            else:
                print_line("[warning]Could not launch media player.[/]")
            continue

        print_line("[error]Invalid command.[/]")


# --------------------------------------------------------------------------
# Settings and main loop
# --------------------------------------------------------------------------

def change_download_location():
    print_section("Download Location")

    try:
        current = str(get_download_dir())
        print_block(
            status_panel(
                f"[accent.bold]{escape(current)}[/]",
                "Current Folder",
                border_style="card.border",
            ),
            top=1,
        )

        new_path = prompt_line("New download directory", hint="Q = cancel")

        if new_path.lower() == "q":
            print_line("[warning]Cancelled.[/]")
            return

        if not new_path:
            print_line("[error]Directory cannot be empty.[/]")
            return

        new_dir = save_download_dir(new_path)

        print_block(
            status_panel(
                f"[heading]{escape(str(new_dir))}[/]",
                "Location Updated",
                border_style="success",
                icon="[success]✔[/]",
            ),
            top=1,
        )

    except (OSError, ValueError) as error:
        show_error(f"Unable to change download location: {error}")


def parse_cli_theme(argv):
    """Extract optional --theme <auto|system|light|dark> from argv."""

    for index, arg in enumerate(argv):
        if arg == "--theme" and index + 1 < len(argv):
            return normalize_theme_mode(argv[index + 1])

        if arg.startswith("--theme="):
            return normalize_theme_mode(arg.split("=", 1)[1])

    return None


def main():
    check_requirements()
    start_update_check()

    while True:
        show_menu()

        choice = prompt_line("Choose an option", hint="1-6, I = image, H = history, G = IG, T = theme").lower()

        if choice == "1":
            inspect_single("video")

        elif choice == "2":
            inspect_single("audio")

        elif choice == "3":
            inspect_playlist()

        elif choice == "i":
            inspect_single("image")

        elif choice == "4":
            change_download_location()

        elif choice == "5":
            update_ytdlp()

        elif choice == "h":
            show_history()

        elif choice == "g":
            configure_instagram_access()

        elif choice == "t":
            change_theme()

        elif choice == "tc":
            refresh_console(cycle_theme_mode(theme_mode))
            print_line(f"[success]Theme switched to {escape(theme_summary())}.[/]")
            continue

        elif choice in ("6", "q", "exit"):
            print_line("\n[muted]Goodbye![/]\n")
            break

        else:
            print_line("[error]Invalid option. Enter 1-6, I, H, G, or T.[/]")

        console.input("\n  [muted]Press Enter to return to the menu…[/]")


def run():
    setup_logging()

    if "--diagnostics" in sys.argv:
        print(diagnostics_text())
        return

    cli_theme = parse_cli_theme(sys.argv[1:])
    if cli_theme is not None:
        refresh_console(cli_theme)

    try:
        main()

    except (KeyboardInterrupt, EOFError):
        print("\n\nApplication interrupted. Goodbye!")


if __name__ == "__main__":
    run()
