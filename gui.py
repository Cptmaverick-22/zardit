"""Zardit - graphical interface (Qt)."""

from concurrent.futures import ThreadPoolExecutor
import logging
from pathlib import Path
import shutil
import sys
import threading
import time
import urllib.parse
import urllib.request

from PySide6.QtCore import QObject, QSize, Qt, QThread, QUrl, Signal
from PySide6.QtGui import (
    QColor,
    QDesktopServices,
    QFont,
    QIcon,
    QKeySequence,
    QPainter,
    QPainterPath,
    QPalette,
    QPixmap,
    QShortcut,
)
from PySide6.QtWidgets import (
    QAbstractItemView,
    QApplication,
    QBoxLayout,
    QButtonGroup,
    QCheckBox,
    QComboBox,
    QDialog,
    QFileDialog,
    QFrame,
    QGridLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QMessageBox,
    QPlainTextEdit,
    QProgressBar,
    QProgressDialog,
    QPushButton,
    QRadioButton,
    QScrollArea,
    QSizePolicy,
    QStackedWidget,
    QSystemTrayIcon,
    QVBoxLayout,
    QWidget,
)

import updater

updater.activate()   # Must run before anything imports yt-dlp.

from updater import UpdateError
from zardit_core import app_updater
from zardit_core.app_updater import AppUpdateError

from config import (
    APP_LICENSE,
    APP_NAME,
    APP_SLUG,
    APP_TAGLINE,
    APP_VERSION,
    IG_BROWSER_LABELS,
    IG_BROWSERS,
    audio_label,
    get_build_info,
    get_download_dir,
    get_ig_browser,
    image_label,
    load_settings,
    remember_setting,
    save_download_dir,
    save_ig_browser,
)

from desktop import notify

from downloader import (
    VIDEO_ID_PATTERN,
    DownloadError,
    PlaylistInfo,
    PlaylistLinkError,
    Reporter,
    _normalize_playlist_entries,
    audio_format_options,
    best_image_url,
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
    friendly_error,
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

from logs import diagnostics_text, setup_logging
from theme import (
    GUI_PALETTES,
    THEME_LABELS,
    THEME_MODES,
    build_qt_stylesheet,
    get_saved_theme,
    normalize_theme_mode,
    save_theme,
)


THUMBNAIL_MAX_BYTES = 5 * 1024 * 1024
CLOSE_WAIT_MS = 30000
NARROW_BREAKPOINT = 580
WIDE_BREAKPOINT = 960


# --------------------------------------------------------------------------
# Background work
# --------------------------------------------------------------------------

class TaskThread(QThread):
    """Runs a function off the window thread and reports back via signals."""

    done = Signal(object)
    failed = Signal(str)
    message = Signal(str, str)
    stage = Signal(str)
    progress = Signal(object, object, object, object)
    progress_done = Signal()
    playlist_item = Signal(int, int, str)

    def __init__(self, function, parent=None):
        super().__init__(parent)
        self.function = function

    def run(self):
        try:
            self.done.emit(self.function(self))

        except (DownloadError, UpdateError, AppUpdateError) as error:
            self.failed.emit(str(error))

        except Exception as error:
            logging.getLogger("zardit").error(
                "Unexpected error in a background task", exc_info=True
            )
            self.failed.emit(friendly_error(error))

    def detach(self):
        """Stop delivering results to the window (used while closing)."""

        for signal in (
            self.done, self.failed, self.message, self.stage,
            self.progress, self.progress_done, self.playlist_item,
        ):
            try:
                signal.disconnect()
            except (RuntimeError, TypeError):
                pass


class QtReporter(Reporter):
    """Forwards download events to the window through thread signals."""

    def __init__(self, thread):
        self.thread = thread
        self.last_progress = 0.0

    def message(self, text, level="info"):
        self.thread.message.emit(text, level)

    def stage(self, text):
        self.thread.stage.emit(text)

    def playlist_item(self, number, total, title):
        self.thread.playlist_item.emit(number, total, title)

    def progress(self, downloaded, total, speed, eta):
        # Limit updates to about ten per second.
        now = time.monotonic()

        if now - self.last_progress < 0.1:
            return

        self.last_progress = now
        self.thread.progress.emit(downloaded, total, speed, eta)

    def progress_done(self):
        self.last_progress = 0.0
        self.thread.progress_done.emit()


class UpdateNotice(QObject):
    """Looks for newer yt-dlp and Zardit app builds in the background.

    A plain daemon thread is used, so it can never keep the app from closing.
    """

    found = Signal(str)
    app_found = Signal(object)

    def start(self):
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        version = updater.background_check()

        if version:
            try:
                self.found.emit(version)
            except RuntimeError:
                return   # The window was closed in the meantime.

        app_info = app_updater.background_check_app_update()
        if app_info and app_info.available:
            try:
                self.app_found.emit(app_info)
            except RuntimeError:
                pass


class PreviewNotice(QObject):
    """Loads carousel/playlist item preview images in background threads."""

    loaded = Signal(str, object)

    def fetch(self, url):
        threading.Thread(
            target=self._run, args=(url,), daemon=True
        ).start()

    def fetch_batch(self, urls):
        unique = [u for u in dict.fromkeys(urls) if u]
        if not unique:
            return

        def worker():
            with ThreadPoolExecutor(max_workers=4) as pool:
                for url, data in zip(unique, pool.map(load_thumbnail, unique)):
                    if data:
                        try:
                            self.loaded.emit(url, data)
                        except RuntimeError:
                            return

        threading.Thread(target=worker, daemon=True).start()

    def _run(self, url):
        data = load_thumbnail(url)
        try:
            self.loaded.emit(url, data)
        except RuntimeError:
            pass


def load_thumbnail(url):
    """Download thumbnail bytes safely, or return None if unavailable."""

    if not url:
        return None

    try:
        parsed = urllib.parse.urlparse(url)

        # HTTPS only.
        if parsed.scheme != "https" or not parsed.hostname:
            return None

        request = urllib.request.Request(
            url, headers={"User-Agent": "Mozilla/5.0"}
        )

        with urllib.request.urlopen(request, timeout=10) as response:
            # Check the final address after redirects.
            if urllib.parse.urlparse(response.geturl()).scheme != "https":
                return None

            length = response.headers.get("Content-Length")

            if length and int(length) > THUMBNAIL_MAX_BYTES:
                return None

            data = response.read(THUMBNAIL_MAX_BYTES + 1)

            if len(data) > THUMBNAIL_MAX_BYTES:
                return None

            return data

    except Exception:
        return None


def playlist_thumbnail(playlist):
    """Thumbnail bytes for a playlist or carousel (its first item), or None."""

    if not playlist.entries:
        return None

    first = playlist.entries[0]
    thumb = best_image_url(first) or first.get("thumbnail")
    if not thumb and first.get("ie_key") in (None, "Youtube"):
        vid = str(first.get("id") or "")
        if VIDEO_ID_PATTERN.match(vid):
            thumb = f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg"

    return load_thumbnail(thumb)


def _package_single_or_carousel(info):
    """Turn a fetched info or offline-parsed IG dict into an on_fetched tuple."""

    if info.get("_type") == "playlist":
        entries = info.get("entries") or []
        title = info.get("title") or "Instagram Post"
        _normalize_playlist_entries(
            entries,
            title,
            info.get("webpage_url") or "https://www.instagram.com/",
        )
        playlist = PlaylistInfo(title=title, entries=entries)
        return ("playlist", playlist, playlist_thumbnail(playlist))

    summary = summarize_video(info)
    if is_image_only_info(info):
        img_url = best_image_url(info) or summary.thumbnail
        thumb_bytes = load_thumbnail(img_url)
        return (
            "image",
            info,
            summary,
            image_format_options(info),
            thumb_bytes,
        )

    return (
        "video",
        info,
        summary,
        video_format_options(info),
        load_thumbnail(summary.thumbnail),
    )


def fetch_everything(url, prefer_playlist=False):
    """Fetch a single video or a playlist (runs in a thread)."""

    if not prefer_playlist:
        try:
            info = fetch_video_info(url)

        except PlaylistLinkError:
            pass  # A playlist link: fetch it as a playlist below.

        else:
            return _package_single_or_carousel(info)

    playlist = fetch_playlist(url)

    return ("playlist", playlist, playlist_thumbnail(playlist))


# --------------------------------------------------------------------------
# Small helpers
# --------------------------------------------------------------------------

def resource_path(relative):
    """Resolve an asset path in source runs or PyInstaller frozen bundles."""

    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    return base / relative


def load_app_icon():
    """Return a multi-resolution Zardit QIcon for window titlebars and taskbars."""

    png_path = resource_path("assets/zardit.png")
    if png_path.is_file():
        source = QPixmap(str(png_path))
        if not source.isNull():
            icon = QIcon()
            for size in (16, 20, 24, 32, 40, 48, 64, 128, 256):
                icon.addPixmap(
                    source.scaled(
                        size,
                        size,
                        Qt.AspectRatioMode.KeepAspectRatio,
                        Qt.TransformationMode.SmoothTransformation,
                    )
                )
            if not icon.isNull():
                return icon

    candidates = (
        ("assets/zardit.ico", "assets/zardit.svg")
        if sys.platform == "win32"
        else ("assets/zardit.svg", "assets/zardit.ico")
    )
    for candidate in candidates:
        path = resource_path(candidate)
        if path.is_file():
            icon = QIcon(str(path))
            if not icon.isNull():
                return icon
    return QIcon()


def _apply_windows_taskbar_icon(widget):
    """Ensure Windows 10/11 receives explicit native ICON_BIG and ICON_SMALL handles."""

    if sys.platform != "win32" or getattr(widget, "_win_icons_applied", False):
        return
    try:
        import ctypes
        from ctypes import wintypes

        hwnd = int(widget.winId())
        if not hwnd:
            return

        user32 = ctypes.windll.user32
        big_w = user32.GetSystemMetrics(11) or 32
        big_h = user32.GetSystemMetrics(12) or 32
        small_w = user32.GetSystemMetrics(49) or 16
        small_h = user32.GetSystemMetrics(50) or 16

        h_big = None
        h_small = None
        ico_path = resource_path("assets/zardit.ico")
        if ico_path.is_file():
            user32.LoadImageW.restype = wintypes.HANDLE
            user32.LoadImageW.argtypes = [
                wintypes.HINSTANCE,
                wintypes.LPCWSTR,
                wintypes.UINT,
                ctypes.c_int,
                ctypes.c_int,
                wintypes.UINT,
            ]
            h_big = user32.LoadImageW(None, str(ico_path), 1, big_w, big_h, 0x0010)
            h_small = user32.LoadImageW(None, str(ico_path), 1, small_w, small_h, 0x0010)

        if not h_big and getattr(sys, "frozen", False):
            big_arr = (wintypes.HICON * 1)()
            small_arr = (wintypes.HICON * 1)()
            if ctypes.windll.shell32.ExtractIconExW(sys.executable, 0, big_arr, small_arr, 1) > 0:
                h_big = big_arr[0]
                h_small = small_arr[0]

        if h_big:
            user32.SendMessageW(hwnd, 0x0080, 1, h_big)
            set_cls = getattr(user32, "SetClassLongPtrW", None)
            if set_cls is not None:
                set_cls(hwnd, -14, h_big)
        if h_small:
            user32.SendMessageW(hwnd, 0x0080, 0, h_small)
            set_cls = getattr(user32, "SetClassLongPtrW", None)
            if set_cls is not None:
                set_cls(hwnd, -34, h_small)

        widget._win_hicons = (h_big, h_small)
        widget._win_icons_applied = True
    except Exception:
        pass


def plain_label(text="", bold=False, wrap=True, object_name=None):
    label = QLabel(text)
    label.setTextFormat(Qt.TextFormat.PlainText)
    label.setWordWrap(wrap)

    if object_name:
        label.setObjectName(object_name)

    if bold:
        label.setStyleSheet("font-weight: 700;")

    return label


def rounded_pixmap(pixmap, target_size, radius=8):
    """Scale pixmap cleanly and clip to rounded corners."""

    scaled = pixmap.scaled(
        target_size,
        Qt.AspectRatioMode.KeepAspectRatio,
        Qt.TransformationMode.SmoothTransformation,
    )

    if scaled.isNull():
        return scaled

    result = QPixmap(scaled.size())
    result.fill(Qt.GlobalColor.transparent)

    painter = QPainter(result)
    painter.setRenderHint(QPainter.RenderHint.Antialiasing)
    path = QPainterPath()
    path.addRoundedRect(0, 0, scaled.width(), scaled.height(), radius, radius)
    painter.setClipPath(path)
    painter.drawPixmap(0, 0, scaled)
    painter.end()

    return result


def build_qt_palette(theme_name):
    """Build a matching QPalette so native dialogs follow Light/Dark mode."""

    colors = GUI_PALETTES["dark" if theme_name == "dark" else "light"]
    palette = QPalette()
    palette.setColor(QPalette.ColorRole.Window, QColor(colors["bg"]))
    palette.setColor(QPalette.ColorRole.WindowText, QColor(colors["text"]))
    palette.setColor(QPalette.ColorRole.Base, QColor(colors["input_bg"]))
    palette.setColor(QPalette.ColorRole.AlternateBase, QColor(colors["list_alt"]))
    palette.setColor(QPalette.ColorRole.ToolTipBase, QColor(colors["surface"]))
    palette.setColor(QPalette.ColorRole.ToolTipText, QColor(colors["text"]))
    palette.setColor(QPalette.ColorRole.Text, QColor(colors["text"]))
    palette.setColor(QPalette.ColorRole.Button, QColor(colors["secondary_bg"]))
    palette.setColor(QPalette.ColorRole.ButtonText, QColor(colors["secondary_text"]))
    palette.setColor(QPalette.ColorRole.BrightText, QColor(colors["accent_text"]))
    palette.setColor(QPalette.ColorRole.Highlight, QColor(colors["accent"]))
    palette.setColor(QPalette.ColorRole.HighlightedText, QColor(colors["accent_text"]))
    palette.setColor(QPalette.ColorRole.PlaceholderText, QColor(colors["text_muted"]))
    return palette


def repolish(widget):
    """Force Qt to re-evaluate stylesheet rules after dynamic property changes."""

    style = widget.style()
    style.unpolish(widget)
    style.polish(widget)
    widget.update()


class FormatRowWidget(QWidget):
    """Custom list row displaying format title, badge, codec details, and size."""

    def __init__(self, option, parent=None):
        super().__init__(parent)

        layout = QHBoxLayout(self)
        layout.setContentsMargins(10, 8, 12, 8)
        layout.setSpacing(10)

        self.radio_dot = QLabel()
        self.radio_dot.setObjectName("formatRadio")
        self.radio_dot.setProperty("selected", "false")
        layout.addWidget(self.radio_dot, 0, Qt.AlignmentFlag.AlignVCenter)

        text_col = QVBoxLayout()
        text_col.setContentsMargins(0, 0, 0, 0)
        text_col.setSpacing(2)

        top_row = QHBoxLayout()
        top_row.setContentsMargins(0, 0, 0, 0)
        top_row.setSpacing(6)

        self.title_label = plain_label(
            option.label, wrap=False, object_name="formatTitle"
        )
        top_row.addWidget(self.title_label)

        self.badge_label = None
        if option.badge:
            self.badge_label = plain_label(
                option.badge, wrap=False, object_name="formatBadge"
            )
            self.badge_label.setProperty("selected", "false")
            top_row.addWidget(self.badge_label)

        top_row.addStretch(1)
        text_col.addLayout(top_row)

        if option.details:
            detail_label = plain_label(
                option.details, wrap=True, object_name="formatDetail"
            )
            text_col.addWidget(detail_label)

        layout.addLayout(text_col, 1)

        self.size_label = None
        if option.size is not None:
            self.size_label = plain_label(
                f"~{format_file_size(option.size)}",
                wrap=False,
                object_name="formatSize",
            )
            self.size_label.setAlignment(
                Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter
            )
            layout.addWidget(self.size_label, 0, Qt.AlignmentFlag.AlignVCenter)

    def set_selected(self, selected):
        value = "true" if selected else "false"
        self.radio_dot.setProperty("selected", value)
        repolish(self.radio_dot)

        if self.badge_label is not None:
            self.badge_label.setProperty("selected", value)
            repolish(self.badge_label)


class HistoryRowWidget(QWidget):
    """Custom list row for a downloaded video, audio, or image in History."""

    def __init__(self, entry, on_play, on_remove, parent=None):
        super().__init__(parent)
        self.entry = entry

        layout = QHBoxLayout(self)
        layout.setContentsMargins(12, 10, 12, 10)
        layout.setSpacing(12)

        kind = entry.get("kind", "video")
        if kind == "audio":
            badge_text, badge_obj = "AUDIO", "audioBadge"
        elif kind == "image":
            badge_text, badge_obj = "IMAGE", "imageBadge"
        else:
            badge_text, badge_obj = "VIDEO", "typeBadge"

        thumb_label = QLabel()
        thumb_label.setFixedSize(88, 50)
        thumb_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        thumb_path = entry.get("thumbnail_path") or ""
        thumb_pix = QPixmap()
        if thumb_path and Path(thumb_path).is_file() and thumb_pix.load(thumb_path):
            thumb_label.setPixmap(rounded_pixmap(thumb_pix, QSize(88, 50), radius=7))
        else:
            thumb_label.setObjectName("historyThumbFallback")
            thumb_label.setText(badge_text)
        layout.addWidget(thumb_label, 0, Qt.AlignmentFlag.AlignVCenter)

        text_col = QVBoxLayout()
        text_col.setContentsMargins(0, 0, 0, 0)
        text_col.setSpacing(3)

        title_row = QHBoxLayout()
        title_row.setContentsMargins(0, 0, 0, 0)
        title_row.setSpacing(6)

        badge = plain_label(
            badge_text,
            wrap=False,
            object_name=badge_obj,
        )
        title = plain_label(
            entry.get("title") or "Untitled",
            wrap=True,
            object_name="formatTitle",
        )
        title_row.addWidget(badge, 0, Qt.AlignmentFlag.AlignVCenter)
        title_row.addWidget(title, 1)
        text_col.addLayout(title_row)

        meta_parts = []
        if entry.get("platform"):
            meta_parts.append(entry["platform"])
        if entry.get("channel"):
            meta_parts.append(entry["channel"])
        if entry.get("quality"):
            meta_parts.append(entry["quality"])
        if entry.get("size"):
            meta_parts.append(format_file_size(entry["size"]))
        if entry.get("downloaded_at"):
            meta_parts.append(entry["downloaded_at"].replace("T", " ")[:16])

        meta_row = QHBoxLayout()
        meta_row.setContentsMargins(0, 0, 0, 0)
        meta_row.setSpacing(8)

        meta_label = plain_label(
            "  ·  ".join(meta_parts),
            wrap=True,
            object_name="formatDetail",
        )
        meta_row.addWidget(meta_label, 1)

        file_exists = Path(entry.get("file_path", "")).is_file()
        if not file_exists:
            missing = plain_label(
                "File missing", wrap=False, object_name="missingTag"
            )
            meta_row.addWidget(missing)

        text_col.addLayout(meta_row)
        layout.addLayout(text_col, 1)

        actions = QHBoxLayout()
        actions.setContentsMargins(0, 0, 0, 0)
        actions.setSpacing(6)

        play_btn = QPushButton("Play")
        play_btn.setObjectName("accentSoftBtn" if file_exists else "compactBtn")
        play_btn.setEnabled(file_exists)
        play_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        play_btn.setToolTip(
            "Play this media file" if file_exists else "File no longer exists on disk"
        )
        play_btn.clicked.connect(lambda _checked=False: on_play(self.entry))

        remove_btn = QPushButton("Remove")
        remove_btn.setObjectName("compactBtn")
        remove_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        remove_btn.setToolTip("Remove from history (optionally delete file)")
        remove_btn.clicked.connect(lambda _checked=False: on_remove(self.entry))

        actions.addWidget(play_btn)
        actions.addWidget(remove_btn)
        layout.addLayout(actions)


class MediaPreviewDialog(QDialog):
    """Lightbox dialog to preview gallery/carousel photos before downloading."""

    def __init__(self, entries, start_index, checked_getter, checked_setter, preview_cache, preview_loader, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Preview Media")
        self.setMinimumSize(520, 460)
        self.resize(760, 620)

        self.entries = entries
        self.index = max(0, min(start_index, len(entries) - 1))
        self.checked_getter = checked_getter
        self.checked_setter = checked_setter
        self.preview_cache = preview_cache
        self.preview_loader = preview_loader
        self._current_url = ""
        self._current_pixmap = QPixmap()

        layout = QVBoxLayout(self)
        layout.setContentsMargins(18, 16, 18, 16)
        layout.setSpacing(12)

        top_bar = QHBoxLayout()
        top_bar.setSpacing(10)
        self.counter_badge = plain_label("", wrap=False, object_name="countBadge")
        self.title_label = plain_label("", bold=True, wrap=True, object_name="mediaTitle")
        top_bar.addWidget(self.counter_badge)
        top_bar.addWidget(self.title_label, 1)
        layout.addLayout(top_bar)

        self.image_frame = QFrame()
        self.image_frame.setObjectName("insetBar")
        frame_layout = QVBoxLayout(self.image_frame)
        frame_layout.setContentsMargins(12, 12, 12, 12)
        self.image_label = QLabel("Loading preview…")
        self.image_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.image_label.setSizePolicy(
            QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding
        )
        frame_layout.addWidget(self.image_label)
        layout.addWidget(self.image_frame, 1)

        footer = QHBoxLayout()
        footer.setSpacing(10)

        self.include_check = QCheckBox("Include in download")
        self.include_check.setCursor(Qt.CursorShape.PointingHandCursor)
        self.include_check.toggled.connect(self._on_include_toggled)

        self.prev_btn = QPushButton("◀ Previous")
        self.prev_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.prev_btn.clicked.connect(lambda: self.go(-1))

        self.next_btn = QPushButton("Next ▶")
        self.next_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.next_btn.clicked.connect(lambda: self.go(1))

        close_btn = QPushButton("Close")
        close_btn.setObjectName("primaryBtn")
        close_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        close_btn.clicked.connect(self.accept)

        footer.addWidget(self.include_check)
        footer.addStretch(1)
        footer.addWidget(self.prev_btn)
        footer.addWidget(self.next_btn)
        footer.addWidget(close_btn)
        layout.addLayout(footer)

        QShortcut(QKeySequence(Qt.Key.Key_Left), self, activated=lambda: self.go(-1))
        QShortcut(QKeySequence(Qt.Key.Key_Right), self, activated=lambda: self.go(1))

        self.preview_loader.loaded.connect(self._on_loaded)
        self._render_current()

    def closeEvent(self, event):
        try:
            self.preview_loader.loaded.disconnect(self._on_loaded)
        except (RuntimeError, TypeError):
            pass
        super().closeEvent(event)

    def _entry_url(self, entry):
        url = best_image_url(entry) or entry.get("thumbnail")
        if not url and entry.get("ie_key") in (None, "Youtube"):
            vid = str(entry.get("id") or "")
            if VIDEO_ID_PATTERN.match(vid):
                url = f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg"
        return url or ""

    def _render_current(self):
        entry = self.entries[self.index]
        total = len(self.entries)
        self.counter_badge.setText(f"{self.index + 1} of {total}")
        self.title_label.setText(entry.get("title") or f"Item {self.index + 1}")

        self.prev_btn.setEnabled(self.index > 0)
        self.next_btn.setEnabled(self.index < total - 1)

        self.include_check.blockSignals(True)
        self.include_check.setChecked(bool(self.checked_getter(self.index)))
        self.include_check.blockSignals(False)

        self._current_url = self._entry_url(entry)
        self._current_pixmap = QPixmap()

        if self._current_url and self._current_url in self.preview_cache:
            self._apply_bytes(self.preview_cache[self._current_url])
        elif self._current_url:
            self.image_label.setPixmap(QPixmap())
            self.image_label.setText("Loading preview…")
            self.preview_loader.fetch(self._current_url)
        else:
            self.image_label.setPixmap(QPixmap())
            self.image_label.setText("No preview available")

    def _on_loaded(self, url, data):
        if url == self._current_url and data:
            self._apply_bytes(data)

    def _apply_bytes(self, data):
        pix = QPixmap()
        if data and pix.loadFromData(data):
            self._current_pixmap = pix
            self._rescale_pixmap()
        else:
            self.image_label.setText("Could not load preview")

    def _rescale_pixmap(self):
        if self._current_pixmap.isNull():
            return
        target = self.image_label.size()
        if target.width() < 20 or target.height() < 20:
            target = QSize(680, 440)
        self.image_label.setPixmap(
            self._current_pixmap.scaled(
                target,
                Qt.AspectRatioMode.KeepAspectRatio,
                Qt.TransformationMode.SmoothTransformation,
            )
        )

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self._rescale_pixmap()

    def _on_include_toggled(self, checked):
        self.checked_setter(self.index, checked)

    def go(self, delta):
        nxt = self.index + delta
        if 0 <= nxt < len(self.entries):
            self.index = nxt
            self._render_current()


def read_license_text():
    """Return the bundled MIT License text, or a concise fallback."""

    license_path = resource_path("LICENSE")
    if license_path.is_file():
        try:
            return license_path.read_text(encoding="utf-8").strip()
        except OSError:
            pass
    return (
        f"{APP_NAME} v{APP_VERSION} is licensed under the {APP_LICENSE} License.\n"
        "Copyright (c) 2026 Cptmaverick-22."
    )


class AboutDialog(QDialog):
    """Displays version, third-party tools, and license text."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle(f"About {APP_NAME}")
        self.resize(520, 440)
        icon = load_app_icon()
        if not icon.isNull():
            self.setWindowIcon(icon)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(20, 18, 20, 18)
        layout.setSpacing(12)

        header = QHBoxLayout()
        header.setSpacing(12)

        if not icon.isNull():
            icon_lbl = QLabel()
            icon_lbl.setPixmap(icon.pixmap(48, 48))
            header.addWidget(icon_lbl, 0, Qt.AlignmentFlag.AlignTop)

        title_col = QVBoxLayout()
        title_col.setSpacing(2)
        title_lbl = QLabel(f"{APP_NAME} v{APP_VERSION}")
        title_lbl.setObjectName("appTitle")
        tag_lbl = QLabel(APP_TAGLINE)
        tag_lbl.setObjectName("mutedText")
        tag_lbl.setWordWrap(True)
        build = get_build_info()
        commit = (build.get("commit") or "")[:7]
        build_bit = f"  ·  Build {commit}" if commit else ""
        lic_lbl = QLabel(
            f"License: {APP_LICENSE}{build_bit}  ·  Built with yt-dlp, FFmpeg, Deno, PySide6 & Rich"
        )
        lic_lbl.setObjectName("mutedText")
        lic_lbl.setWordWrap(True)
        title_col.addWidget(title_lbl)
        title_col.addWidget(tag_lbl)
        title_col.addWidget(lic_lbl)
        header.addLayout(title_col, 1)
        layout.addLayout(header)

        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setObjectName("card")
        license_label = QLabel(read_license_text())
        license_label.setTextFormat(Qt.TextFormat.PlainText)
        license_label.setWordWrap(True)
        license_label.setTextInteractionFlags(
            Qt.TextInteractionFlag.TextSelectableByMouse
        )
        license_label.setContentsMargins(12, 12, 12, 12)
        scroll.setWidget(license_label)
        layout.addWidget(scroll, 1)

        buttons = QHBoxLayout()
        if hasattr(parent, "open_updates_dialog"):
            updates_btn = QPushButton("Check for updates")
            updates_btn.setObjectName("secondaryBtn")
            updates_btn.setCursor(Qt.CursorShape.PointingHandCursor)
            updates_btn.clicked.connect(lambda: (self.accept(), parent.open_updates_dialog()))
            buttons.addWidget(updates_btn)
        buttons.addStretch(1)
        close_btn = QPushButton("Close")
        close_btn.setObjectName("primaryBtn")
        close_btn.clicked.connect(self.accept)
        buttons.addWidget(close_btn)
        layout.addLayout(buttons)


class UpdatesDialog(QDialog):
    """Minimal unified updater for both Zardit App and the yt-dlp engine."""

    def __init__(self, parent=None, app_info=None):
        super().__init__(parent)
        self.setWindowTitle(f"{APP_NAME} Updates")
        self.resize(520, 420)
        icon = load_app_icon()
        if not icon.isNull():
            self.setWindowIcon(icon)

        self.main_window = parent
        self.app_info = app_info

        layout = QVBoxLayout(self)
        layout.setContentsMargins(20, 18, 20, 18)
        layout.setSpacing(12)

        # Card 1: Zardit Application
        app_card = QFrame()
        app_card.setObjectName("card")
        app_col = QVBoxLayout(app_card)
        app_col.setContentsMargins(14, 12, 14, 12)
        app_col.setSpacing(8)

        mode_labels = {
            "windows_installer": "Windows Installer",
            "windows_portable": "Windows Portable",
            "linux_appimage": "Linux AppImage",
            "windows_source": "Source (Windows)",
            "source": "Source Install",
        }
        mode_text = mode_labels.get(app_updater.detect_install_mode(), "Source")
        build = get_build_info()
        short_sha = (build.get("commit") or "")[:7]
        ver_meta = f"v{APP_VERSION} ({short_sha}) · {mode_text}" if short_sha else f"v{APP_VERSION} · {mode_text}"

        app_top = QHBoxLayout()
        app_title = QLabel(f"{APP_NAME} App")
        app_title.setObjectName("sectionTitle")
        self.app_ver_badge = QLabel(ver_meta)
        self.app_ver_badge.setObjectName("mutedText")
        app_top.addWidget(app_title)
        app_top.addStretch(1)
        app_top.addWidget(self.app_ver_badge)
        app_col.addLayout(app_top)

        self.app_status_lbl = plain_label(
            "Check GitHub Releases for new versions or refreshed builds.",
            object_name="mutedText",
        )
        app_col.addWidget(self.app_status_lbl)

        self.app_notes_box = QPlainTextEdit()
        self.app_notes_box.setReadOnly(True)
        self.app_notes_box.setMaximumHeight(96)
        self.app_notes_box.hide()
        app_col.addWidget(self.app_notes_box)

        app_btns = QHBoxLayout()
        app_btns.setSpacing(8)
        self.app_check_btn = QPushButton("Check for App Update")
        self.app_check_btn.setObjectName("secondaryBtn")
        self.app_check_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.app_check_btn.clicked.connect(self.check_app)

        self.app_install_btn = QPushButton("Update & Install")
        self.app_install_btn.setObjectName("primaryBtn")
        self.app_install_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.app_install_btn.clicked.connect(self.install_app)
        self.app_install_btn.hide()

        self.app_release_btn = QPushButton("View Release")
        self.app_release_btn.setObjectName("compactBtn")
        self.app_release_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.app_release_btn.clicked.connect(self.open_release_page)
        self.app_release_btn.hide()

        app_btns.addWidget(self.app_check_btn)
        app_btns.addWidget(self.app_install_btn)
        app_btns.addStretch(1)
        app_btns.addWidget(self.app_release_btn)
        app_col.addLayout(app_btns)
        layout.addWidget(app_card)

        # Card 2: yt-dlp Extraction Engine
        engine_card = QFrame()
        engine_card.setObjectName("card")
        eng_col = QVBoxLayout(engine_card)
        eng_col.setContentsMargins(14, 12, 14, 12)
        eng_col.setSpacing(8)

        eng_top = QHBoxLayout()
        eng_title = QLabel("yt-dlp Engine")
        eng_title.setObjectName("sectionTitle")
        self.eng_ver_lbl = QLabel(updater.current_version())
        self.eng_ver_lbl.setObjectName("mutedText")
        eng_top.addWidget(eng_title)
        eng_top.addStretch(1)
        eng_top.addWidget(self.eng_ver_lbl)
        eng_col.addLayout(eng_top)

        self.eng_status_lbl = plain_label(
            f"Active: {updater.describe()}",
            object_name="mutedText",
        )
        eng_col.addWidget(self.eng_status_lbl)

        eng_btns = QHBoxLayout()
        eng_btns.setSpacing(8)
        self.eng_update_btn = QPushButton("Check & Update yt-dlp")
        self.eng_update_btn.setObjectName("secondaryBtn")
        self.eng_update_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.eng_update_btn.clicked.connect(self.run_ytdlp_check)
        eng_btns.addWidget(self.eng_update_btn)
        eng_btns.addStretch(1)
        eng_col.addLayout(eng_btns)
        layout.addWidget(engine_card)

        # Bottom controls
        footer = QHBoxLayout()
        self.daily_check = QCheckBox("Check automatically once a day (App & yt-dlp)")
        self.daily_check.setChecked(
            updater.auto_check_enabled() and app_updater.auto_check_enabled()
        )
        self.daily_check.toggled.connect(self._on_daily_toggled)
        footer.addWidget(self.daily_check, 1)

        close_btn = QPushButton("Close")
        close_btn.setObjectName("secondaryBtn")
        close_btn.clicked.connect(self.accept)
        footer.addWidget(close_btn)
        layout.addLayout(footer)

        if app_info is not None:
            self._apply_app_info(app_info)

    def _on_daily_toggled(self, checked):
        updater.set_auto_check(checked)
        app_updater.set_auto_check(checked)

    def _apply_app_info(self, info):
        self.app_info = info
        if info.available:
            if info.same_tag_hotfix:
                self.app_status_lbl.setText(
                    f"A refreshed build of Zardit {info.tag_name} is available (SHA-256 verified)."
                )
            else:
                self.app_status_lbl.setText(
                    f"Zardit {info.tag_name} is available (you have v{info.current_version})."
                )
            if info.release_notes:
                self.app_notes_box.setPlainText(info.release_notes)
                self.app_notes_box.show()
            else:
                self.app_notes_box.hide()

            if info.asset:
                if info.install_mode == "linux_appimage" and not app_updater.can_self_update_appimage():
                    self.app_install_btn.setText("Download AppImage")
                elif info.install_mode == "source":
                    self.app_install_btn.setText("Download Package")
                else:
                    self.app_install_btn.setText("Update & Install")
                self.app_install_btn.show()
            else:
                self.app_install_btn.hide()
            self.app_release_btn.show()
        else:
            self.app_status_lbl.setText(
                f"Zardit v{info.current_version} is up to date."
            )
            self.app_notes_box.hide()
            self.app_install_btn.hide()
            self.app_release_btn.show()

    def check_app(self):
        self.app_check_btn.setEnabled(False)
        self.app_check_btn.setText("Checking…")
        if hasattr(self.main_window, "start_task"):
            task = self.main_window.start_task(lambda _t: app_updater.check_for_app_update())
            task.done.connect(self._on_app_checked)
            task.failed.connect(self._on_app_check_failed)
            task.start()
        else:
            try:
                info = app_updater.check_for_app_update()
                self._on_app_checked(info)
            except Exception as err:
                self._on_app_check_failed(str(err))

    def _on_app_checked(self, info):
        self.app_check_btn.setEnabled(True)
        self.app_check_btn.setText("Check for App Update")
        self._apply_app_info(info)
        if hasattr(self.main_window, "sync_app_update_state"):
            self.main_window.sync_app_update_state(info)

    def _on_app_check_failed(self, msg):
        self.app_check_btn.setEnabled(True)
        self.app_check_btn.setText("Check for App Update")
        self.app_status_lbl.setText(msg)

    def install_app(self):
        if not self.app_info:
            return
        self.accept()
        if hasattr(self.main_window, "install_app_update_flow"):
            self.main_window.install_app_update_flow(self.app_info)

    def open_release_page(self):
        url = self.app_info.release_url if self.app_info else app_updater.RELEASES_PAGE_URL
        QDesktopServices.openUrl(QUrl(url))

    def run_ytdlp_check(self):
        self.accept()
        if hasattr(self.main_window, "update_ytdlp"):
            self.main_window.update_ytdlp()


class InstagramAccessDialog(QDialog):
    """Configure local browser cookies or extract offline from private IG/FB source."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Private Instagram & Facebook Access")
        self.resize(580, 560)
        icon = load_app_icon()
        if not icon.isNull():
            self.setWindowIcon(icon)

        self.parsed_info = None
        self.parsed_url = ""
        self.browser_changed = False

        layout = QVBoxLayout(self)
        layout.setContentsMargins(20, 18, 20, 18)
        layout.setSpacing(14)

        # Card 1: Browser cookies (local yt-dlp --cookies-from-browser)
        browser_card = QFrame()
        browser_card.setObjectName("card")
        b_layout = QVBoxLayout(browser_card)
        b_layout.setContentsMargins(14, 12, 14, 12)
        b_layout.setSpacing(8)

        b_title = QLabel("Option 1 · Use Signed-In Browser Cookies (Automatic)")
        b_title.setObjectName("sectionTitle")
        b_hint = plain_label(
            "Reads your existing Instagram & Facebook sessions directly from a browser "
            "on this PC (never uploaded anywhere). Tip: Firefox works on all OSes; Chrome/"
            "Edge on Windows v130+ encrypts local cookies — use Option 2 if locked.",
            object_name="mutedText",
        )
        b_row = QHBoxLayout()
        b_row.setSpacing(8)
        self.browser_combo = QComboBox()
        self.browser_combo.addItem("Off (Public media works without login)", None)
        for key in IG_BROWSERS:
            self.browser_combo.addItem(IG_BROWSER_LABELS.get(key, key.title()), key)

        current_browser = get_ig_browser()
        if current_browser:
            idx = self.browser_combo.findData(current_browser)
            if idx >= 0:
                self.browser_combo.setCurrentIndex(idx)

        save_browser_btn = QPushButton("Save")
        save_browser_btn.setObjectName("primaryBtn")
        save_browser_btn.clicked.connect(self.save_browser_choice)

        clear_browser_btn = QPushButton("Turn Off")
        clear_browser_btn.setObjectName("secondaryBtn")
        clear_browser_btn.clicked.connect(self.disconnect_browser)

        b_row.addWidget(self.browser_combo, 1)
        b_row.addWidget(save_browser_btn)
        b_row.addWidget(clear_browser_btn)

        self.browser_status = plain_label("", object_name="mutedText")
        b_layout.addWidget(b_title)
        b_layout.addWidget(b_hint)
        b_layout.addLayout(b_row)
        b_layout.addWidget(self.browser_status)
        layout.addWidget(browser_card)

        # Card 2: Offline Page Source / ?__a=1 JSON extractor (SaveIG-style, 100% local)
        source_card = QFrame()
        source_card.setObjectName("card")
        s_layout = QVBoxLayout(source_card)
        s_layout.setContentsMargins(14, 12, 14, 12)
        s_layout.setSpacing(8)

        s_title = QLabel("Option 2 · Paste Page Source / JSON (100% Offline)")
        s_title.setObjectName("sectionTitle")
        s_steps = plain_label(
            "1. Open the private Instagram or Facebook post/reel in your logged-in browser.\n"
            "2. Press Ctrl+U (View Page Source) or append ?__a=1&__d=dis (IG), then Ctrl+A -> Ctrl+C.\n"
            "3. Paste below — extracted completely offline on your PC.",
            object_name="mutedText",
        )
        self.source_url_input = QLineEdit()
        self.source_url_input.setPlaceholderText(
            "Optional: post link (https://www.instagram.com/… or https://www.facebook.com/…)"
        )
        self.source_text = QPlainTextEdit()
        self.source_text.setPlaceholderText(
            "Paste copied HTML page source or ?__a=1 JSON here…"
        )
        self.source_text.setMinimumHeight(130)

        self.error_label = plain_label("", object_name="statusError")
        self.error_label.hide()

        s_btns = QHBoxLayout()
        s_btns.setSpacing(8)
        paste_btn = QPushButton("Paste Clipboard")
        paste_btn.setObjectName("secondaryBtn")
        paste_btn.clicked.connect(self.paste_clipboard)

        extract_btn = QPushButton("Extract Media (Offline)")
        extract_btn.setObjectName("primaryBtn")
        extract_btn.clicked.connect(self.extract_offline)

        s_btns.addWidget(paste_btn)
        s_btns.addStretch(1)
        s_btns.addWidget(extract_btn)

        s_layout.addWidget(s_title)
        s_layout.addWidget(s_steps)
        s_layout.addWidget(self.source_url_input)
        s_layout.addWidget(self.source_text, 1)
        s_layout.addWidget(self.error_label)
        s_layout.addLayout(s_btns)
        layout.addWidget(source_card, 1)

        bottom = QHBoxLayout()
        bottom.addStretch(1)
        close_btn = QPushButton("Close")
        close_btn.setObjectName("secondaryBtn")
        close_btn.clicked.connect(self.reject)
        bottom.addWidget(close_btn)
        layout.addLayout(bottom)

    def save_browser_choice(self):
        chosen = self.browser_combo.currentData()
        saved = save_ig_browser(chosen)
        self.browser_changed = True
        self.browser_status.setText(
            f"Connected locally to {IG_BROWSER_LABELS.get(saved, saved)}."
            if saved
            else "Browser cookies turned off."
        )

    def disconnect_browser(self):
        self.browser_combo.setCurrentIndex(0)
        save_ig_browser(None)
        self.browser_changed = True
        self.browser_status.setText("Browser cookies turned off.")

    def paste_clipboard(self):
        clip = QApplication.clipboard().text() or ""
        if clip.strip():
            self.source_text.setPlainText(clip)
            self.error_label.hide()

    def extract_offline(self):
        raw = self.source_text.toPlainText().strip()
        src_url = self.source_url_input.text().strip()
        try:
            self.parsed_info = parse_instagram_source(raw, src_url or None)
        except DownloadError as error:
            self.error_label.setText(str(error))
            self.error_label.show()
            return
        self.parsed_url = (
            self.parsed_info.get("webpage_url")
            or src_url
            or "https://www.instagram.com/"
        )
        self.accept()


# --------------------------------------------------------------------------
# Main window
# --------------------------------------------------------------------------

class MainWindow(QWidget):
    def __init__(self):
        super().__init__()

        self.setObjectName("mainWindow")
        self.setWindowTitle(f"{APP_NAME} v{APP_VERSION}")
        icon = load_app_icon()
        if not icon.isNull():
            self.setWindowIcon(icon)
        self.setMinimumSize(480, 420)
        self.resize(760, 520)

        self.state = "idle"      # idle, fetching, ready, downloading, done
        self.mode = "video"      # "video" or "playlist"
        self.task = None
        self.cancel_event = None
        self.url = ""
        self.info = None
        self.summary = None
        self.video_options = []
        self.image_options = []
        self.playlist = None
        self.failed_items = []
        self.last_folder = None
        self.download_kind = "video"
        self.download_setting = None
        self._syncing_checks = False
        self._status_text = ""
        self._status_level = "info"
        self._thumb_data = None
        self._thumb_placeholder = ""
        self._preview_cache = {}
        self._preview_url = ""
        self._is_narrow = False
        self._is_wide = False
        self._active_page = "downloader"
        self._history_filter = "all"
        self._available_ytdlp_version = None
        self._available_app_update = None

        self.theme_mode = get_saved_theme()
        self.active_theme = self.resolve_theme(self.theme_mode)

        self.build_ui()
        self.apply_theme()
        self.refresh_history()
        self.set_state("idle")

        app = QApplication.instance()
        if app is not None:
            app.styleHints().colorSchemeChanged.connect(
                self.on_system_theme_changed
            )

        self.update_notice = UpdateNotice(self)
        self.update_notice.found.connect(self.on_update_available)
        self.update_notice.app_found.connect(self.on_app_update_available)
        self.update_notice.start()

        self.preview_loader = PreviewNotice(self)
        self.preview_loader.loaded.connect(self.on_preview_loaded)

        if not shutil.which("ffmpeg"):
            self.show_status(
                "FFmpeg was not found. Downloads will not work until "
                "it is installed.",
                "warning",
            )

    # ----- theme ----------------------------------------------------------

    def resolve_theme(self, mode):
        """Return 'light' or 'dark' for a theme mode ('system'/'light'/'dark')."""

        normalized = normalize_theme_mode(mode)

        if normalized in ("light", "dark"):
            return normalized

        app = QApplication.instance()
        if app is not None:
            scheme = app.styleHints().colorScheme()

            if scheme == Qt.ColorScheme.Dark:
                return "dark"

            if scheme == Qt.ColorScheme.Light:
                return "light"

            window_color = app.palette().color(QPalette.ColorRole.Window)
            return "dark" if window_color.lightness() < 128 else "light"

        return "light"

    def set_theme_mode(self, mode):
        self.theme_mode = save_theme(mode)
        self.apply_theme()

    def on_system_theme_changed(self, _scheme):
        if self.theme_mode == "system":
            self.apply_theme()

    def apply_theme(self):
        self.active_theme = self.resolve_theme(self.theme_mode)
        stylesheet = build_qt_stylesheet(self.active_theme)
        palette = build_qt_palette(self.active_theme)

        app = QApplication.instance()
        if app is not None:
            app.setPalette(palette)
            app.setStyleSheet(stylesheet)
        else:
            self.setPalette(palette)
            self.setStyleSheet(stylesheet)

        for mode, button in self.theme_buttons.items():
            button.blockSignals(True)
            button.setChecked(mode == self.theme_mode)
            button.blockSignals(False)

        self.show_status(self._status_text, self._status_level)

        if self._thumb_data is not None or self._thumb_placeholder:
            self.show_thumbnail(self._thumb_data, self._thumb_placeholder)

    # ----- layout ---------------------------------------------------------

    def build_ui(self):
        root_layout = QVBoxLayout(self)
        root_layout.setContentsMargins(0, 0, 0, 0)
        root_layout.setSpacing(0)

        # Persistent top header (stretches full window width)
        self.header_widget = QWidget()
        self.header_widget.setObjectName("scrollContent")
        self.header_outer_layout = QVBoxLayout(self.header_widget)
        self.header_outer_layout.setContentsMargins(24, 16, 24, 8)
        self.header_outer_layout.setSpacing(0)

        self.header_layout = QBoxLayout(QBoxLayout.Direction.LeftToRight)
        self.header_layout.setSpacing(12)

        brand_col = QVBoxLayout()
        brand_col.setSpacing(2)

        title_row = QHBoxLayout()
        title_row.setSpacing(8)

        heading = QLabel(APP_NAME)
        heading.setObjectName("appTitle")
        version_badge = QLabel(f"v{APP_VERSION}")
        version_badge.setObjectName("versionBadge")

        title_row.addWidget(heading)
        title_row.addWidget(version_badge, 0, Qt.AlignmentFlag.AlignVCenter)
        title_row.addStretch(1)

        subtitle = QLabel(APP_TAGLINE)
        subtitle.setObjectName("appSubtitle")
        subtitle.setWordWrap(True)

        brand_col.addLayout(title_row)
        brand_col.addWidget(subtitle)

        controls_row = QHBoxLayout()
        controls_row.setSpacing(8)

        nav_frame = QFrame()
        nav_frame.setObjectName("segmentGroup")
        nav_layout = QHBoxLayout(nav_frame)
        nav_layout.setContentsMargins(3, 3, 3, 3)
        nav_layout.setSpacing(2)

        self.nav_downloader_btn = QPushButton("Downloader")
        self.nav_downloader_btn.setObjectName("segmentBtn")
        self.nav_downloader_btn.setCheckable(True)
        self.nav_downloader_btn.setChecked(True)
        self.nav_downloader_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.nav_downloader_btn.clicked.connect(
            lambda: self.show_page("downloader")
        )

        self.nav_history_btn = QPushButton("History")
        self.nav_history_btn.setObjectName("segmentBtn")
        self.nav_history_btn.setCheckable(True)
        self.nav_history_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.nav_history_btn.clicked.connect(
            lambda: self.show_page("history")
        )

        nav_group = QButtonGroup(self)
        nav_group.setExclusive(True)
        nav_group.addButton(self.nav_downloader_btn)
        nav_group.addButton(self.nav_history_btn)

        nav_layout.addWidget(self.nav_downloader_btn)
        nav_layout.addWidget(self.nav_history_btn)

        theme_frame = QFrame()
        theme_frame.setObjectName("segmentGroup")
        theme_layout = QHBoxLayout(theme_frame)
        theme_layout.setContentsMargins(3, 3, 3, 3)
        theme_layout.setSpacing(2)

        self.theme_buttons = {}
        theme_group = QButtonGroup(self)
        theme_group.setExclusive(True)

        for mode in THEME_MODES:
            btn = QPushButton(THEME_LABELS[mode])
            btn.setObjectName("segmentBtn")
            btn.setCheckable(True)
            btn.setCursor(Qt.CursorShape.PointingHandCursor)
            btn.setToolTip(f"Use {THEME_LABELS[mode].lower()} appearance")
            btn.clicked.connect(
                lambda checked=False, m=mode: self.set_theme_mode(m)
            )
            theme_group.addButton(btn)
            theme_layout.addWidget(btn)
            self.theme_buttons[mode] = btn

        self.ig_button = QPushButton("IG / FB")
        self.ig_button.setCursor(Qt.CursorShape.PointingHandCursor)
        self.ig_button.setToolTip(
            "Configure private Instagram & Facebook browser cookies or extract offline from page source"
        )
        self.ig_button.clicked.connect(self.open_instagram_access)
        self.refresh_ig_button()

        self.update_button = QPushButton("Updates")
        self.update_button.setObjectName("updateBtn")
        self.update_button.setProperty("updateAvailable", "false")
        self.update_button.setCursor(Qt.CursorShape.PointingHandCursor)
        self.update_button.setToolTip(
            "Check for Zardit app and yt-dlp engine updates"
        )
        self.update_button.clicked.connect(self.open_updates_dialog)

        self.about_button = QPushButton("About")
        self.about_button.setObjectName("secondaryBtn")
        self.about_button.setCursor(Qt.CursorShape.PointingHandCursor)
        self.about_button.setToolTip(f"Version and {APP_LICENSE} license")
        self.about_button.clicked.connect(self.show_about)

        controls_row.addWidget(nav_frame)
        controls_row.addWidget(theme_frame)
        controls_row.addWidget(self.ig_button)
        controls_row.addWidget(self.update_button)
        controls_row.addWidget(self.about_button)
        controls_row.addStretch(1)

        self.header_controls_container = QWidget()
        controls_wrap = QHBoxLayout(self.header_controls_container)
        controls_wrap.setContentsMargins(0, 0, 0, 0)
        controls_wrap.addLayout(controls_row)

        self.header_layout.addLayout(brand_col, 1)
        self.header_layout.addWidget(
            self.header_controls_container, 0, Qt.AlignmentFlag.AlignVCenter
        )
        self.header_outer_layout.addLayout(self.header_layout)

        # Non-intrusive app update banner (hidden until a new release or hotfix arrives)
        self.app_update_banner = QFrame()
        self.app_update_banner.setObjectName("statusBanner")
        self.app_update_banner.setProperty("statusLevel", "info")
        app_upd_row = QHBoxLayout(self.app_update_banner)
        app_upd_row.setContentsMargins(14, 8, 14, 8)
        app_upd_row.setSpacing(10)

        self.app_update_banner_label = plain_label(object_name="statusText")
        self.app_update_banner_label.setProperty("statusLevel", "info")

        self.app_update_action_btn = QPushButton("Update")
        self.app_update_action_btn.setObjectName("compactBtn")
        self.app_update_action_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.app_update_action_btn.clicked.connect(self._on_banner_update_clicked)

        self.app_update_dismiss_btn = QPushButton("Later")
        self.app_update_dismiss_btn.setObjectName("compactBtn")
        self.app_update_dismiss_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.app_update_dismiss_btn.clicked.connect(self.dismiss_app_update_banner)

        app_upd_row.addWidget(self.app_update_banner_label, 1)
        app_upd_row.addWidget(self.app_update_action_btn, 0, Qt.AlignmentFlag.AlignVCenter)
        app_upd_row.addWidget(self.app_update_dismiss_btn, 0, Qt.AlignmentFlag.AlignVCenter)
        self.app_update_banner.hide()
        self.header_outer_layout.addWidget(self.app_update_banner)

        root_layout.addWidget(self.header_widget)

        # Main stacked pages (Downloader + History)
        self.pages = QStackedWidget()
        root_layout.addWidget(self.pages, 1)

        self.scroll_area = QScrollArea()
        self.scroll_area.setObjectName("mainScroll")
        self.scroll_area.setWidgetResizable(True)
        self.scroll_area.setFrameShape(QFrame.Shape.NoFrame)
        self.scroll_area.setHorizontalScrollBarPolicy(
            Qt.ScrollBarPolicy.ScrollBarAlwaysOff
        )

        self.scroll_content = QWidget()
        self.scroll_content.setObjectName("scrollContent")
        self.content_layout = QVBoxLayout(self.scroll_content)
        self.content_layout.setContentsMargins(24, 10, 24, 24)
        self.content_layout.setSpacing(14)

        self.scroll_area.setWidget(self.scroll_content)
        self.pages.addWidget(self.scroll_area)

        # URL card
        url_card = QFrame()
        url_card.setObjectName("card")
        url_card_layout = QVBoxLayout(url_card)
        url_card_layout.setContentsMargins(16, 14, 16, 14)
        url_card_layout.setSpacing(10)

        url_header = QHBoxLayout()
        url_header.setSpacing(8)
        url_label = QLabel("Media link")
        url_label.setObjectName("sectionTitle")
        url_hint = QLabel("YouTube · Instagram · Facebook · Reddit · X · Threads")
        url_hint.setObjectName("mutedText")
        url_header.addWidget(url_label)
        url_header.addStretch(1)
        url_header.addWidget(url_hint)
        url_card_layout.addLayout(url_header)

        self.url_inputs_layout = QGridLayout()
        self.url_inputs_layout.setHorizontalSpacing(8)
        self.url_inputs_layout.setVerticalSpacing(8)

        self.url_input = QLineEdit()
        self.url_input.setPlaceholderText(
            "Paste a link from YouTube, Instagram, Facebook, Reddit, X, or Threads…"
        )
        self.url_input.setClearButtonEnabled(True)
        self.url_input.returnPressed.connect(self.fetch)
        self.url_input.textEdited.connect(self.on_url_edited)

        self.paste_button = QPushButton("Paste && Fetch")
        self.paste_button.setObjectName("accentSoftBtn")
        self.paste_button.setCursor(Qt.CursorShape.PointingHandCursor)
        self.paste_button.setToolTip(
            "Paste a link from the clipboard and fetch it automatically"
        )
        self.paste_button.clicked.connect(self.paste_and_fetch)

        self.fetch_button = QPushButton("Fetch")
        self.fetch_button.setObjectName("primaryBtn")
        self.fetch_button.setMinimumWidth(96)
        self.fetch_button.setCursor(Qt.CursorShape.PointingHandCursor)
        self.fetch_button.clicked.connect(self.fetch)

        self._layout_url_inputs(narrow=False)
        url_card_layout.addLayout(self.url_inputs_layout)
        self.content_layout.addWidget(url_card)

        # Idle welcome / quick-start card
        self.hero_card = QFrame()
        self.hero_card.setObjectName("heroCard")
        hero_layout = QVBoxLayout(self.hero_card)
        hero_layout.setContentsMargins(20, 20, 20, 20)
        hero_layout.setSpacing(10)

        hero_title = QLabel("Copy any media link and paste it in one tap")
        hero_title.setObjectName("heroTitle")
        hero_title.setWordWrap(True)

        hero_body = QLabel(
            "Use Paste & Fetch above to grab video resolutions, audio tracks, "
            "or multi-video posts across YouTube, Instagram, Facebook, Reddit, "
            "X / Twitter, Threads, and more."
        )
        hero_body.setObjectName("heroBody")
        hero_body.setWordWrap(True)

        pills_grid = QGridLayout()
        pills_grid.setSpacing(8)
        platforms = (
            "YouTube",
            "Instagram",
            "Facebook",
            "Reddit",
            "X / Twitter",
            "Threads",
        )
        for idx, platform in enumerate(platforms):
            pill = QLabel(platform)
            pill.setObjectName("featurePill")
            pill.setAlignment(Qt.AlignmentFlag.AlignCenter)
            pills_grid.addWidget(pill, idx // 3, idx % 3)

        hero_layout.addWidget(hero_title)
        hero_layout.addWidget(hero_body)
        hero_layout.addLayout(pills_grid)

        self.recent_section = QWidget()
        recent_layout = QVBoxLayout(self.recent_section)
        recent_layout.setContentsMargins(0, 6, 0, 0)
        recent_layout.setSpacing(8)
        recent_hdr = QHBoxLayout()
        recent_title = QLabel("Recent downloads")
        recent_title.setObjectName("sectionTitle")
        recent_all_btn = QPushButton("View all")
        recent_all_btn.setObjectName("compactBtn")
        recent_all_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        recent_all_btn.clicked.connect(lambda: self.show_page("history"))
        recent_hdr.addWidget(recent_title)
        recent_hdr.addStretch(1)
        recent_hdr.addWidget(recent_all_btn)
        recent_layout.addLayout(recent_hdr)
        self.recent_rows_layout = QVBoxLayout()
        self.recent_rows_layout.setSpacing(6)
        recent_layout.addLayout(self.recent_rows_layout)
        self.recent_section.hide()
        hero_layout.addWidget(self.recent_section)

        self.content_layout.addWidget(self.hero_card)

        # Video / playlist card
        self.card = QFrame()
        self.card.setObjectName("mediaCard")
        self.card_layout = QBoxLayout(QBoxLayout.Direction.LeftToRight, self.card)
        self.card_layout.setContentsMargins(16, 16, 16, 16)
        self.card_layout.setSpacing(16)

        self.thumb = QLabel()
        self.thumb.setFixedSize(192, 108)
        self.thumb.setAlignment(Qt.AlignmentFlag.AlignCenter)

        text_column = QVBoxLayout()
        text_column.setSpacing(6)

        self.type_badge = QLabel("VIDEO")
        self.type_badge.setObjectName("typeBadge")
        self.type_badge.setSizePolicy(
            QSizePolicy.Policy.Maximum, QSizePolicy.Policy.Fixed
        )

        self.title_label = plain_label(object_name="mediaTitle")
        self.meta_label = plain_label(object_name="mediaMeta")

        text_column.addWidget(self.type_badge, 0, Qt.AlignmentFlag.AlignLeft)
        text_column.addWidget(self.title_label)
        text_column.addWidget(self.meta_label)
        text_column.addStretch(1)

        self.card_layout.addWidget(
            self.thumb, 0, Qt.AlignmentFlag.AlignTop | Qt.AlignmentFlag.AlignLeft
        )
        self.card_layout.addLayout(text_column, 1)
        self.content_layout.addWidget(self.card)

        # Format, quality, and playlist-specific options card
        self.options = QFrame()
        self.options.setObjectName("card")
        self.options_grid = QGridLayout(self.options)
        self.options_grid.setContentsMargins(16, 16, 16, 16)
        self.options_grid.setHorizontalSpacing(18)
        self.options_grid.setVerticalSpacing(14)

        self.format_section = QWidget()
        format_section_layout = QVBoxLayout(self.format_section)
        format_section_layout.setContentsMargins(0, 0, 0, 0)
        format_section_layout.setSpacing(12)

        kind_row = QHBoxLayout()
        kind_row.setSpacing(8)
        self.video_radio = QRadioButton("Video (MP4)")
        self.audio_radio = QRadioButton("Audio")
        self.image_radio = QRadioButton("Image")
        for btn in (self.video_radio, self.audio_radio, self.image_radio):
            btn.setObjectName("pillRadio")
            btn.setSizePolicy(
                QSizePolicy.Policy.Minimum, QSizePolicy.Policy.Fixed
            )
            btn.setCursor(Qt.CursorShape.PointingHandCursor)
            pill_font = btn.font()
            pill_font.setWeight(QFont.Weight.DemiBold)
            btn.setFont(pill_font)
        self.video_radio.setChecked(True)

        group = QButtonGroup(self)
        group.addButton(self.video_radio)
        group.addButton(self.audio_radio)
        group.addButton(self.image_radio)
        self.video_radio.toggled.connect(self.populate_quality)
        self.audio_radio.toggled.connect(self.populate_quality)
        self.image_radio.toggled.connect(self.populate_quality)

        kind_row.addWidget(self.video_radio)
        kind_row.addWidget(self.audio_radio)
        kind_row.addWidget(self.image_radio)
        kind_row.addStretch(1)

        self.quality_label = QLabel("Video formats")
        self.quality_label.setObjectName("sectionTitle")
        self.format_count_label = plain_label(
            "", wrap=False, object_name="countBadge"
        )
        self.format_count_label.setSizePolicy(
            QSizePolicy.Policy.Maximum, QSizePolicy.Policy.Fixed
        )

        label_pair = QHBoxLayout()
        label_pair.setSpacing(8)
        label_pair.addWidget(self.quality_label)
        label_pair.addWidget(self.format_count_label)
        label_pair.addStretch(1)

        format_section_layout.addLayout(kind_row)
        format_section_layout.addLayout(label_pair)

        self.format_list = QListWidget()
        self.format_list.setObjectName("formatList")
        self.format_list.setSelectionMode(
            QAbstractItemView.SelectionMode.SingleSelection
        )
        self.format_list.setHorizontalScrollBarPolicy(
            Qt.ScrollBarPolicy.ScrollBarAlwaysOff
        )
        self.format_list.setVerticalScrollMode(
            QAbstractItemView.ScrollMode.ScrollPerPixel
        )
        self.format_list.setMinimumHeight(140)
        self.format_list.currentRowChanged.connect(self.on_format_row_changed)
        format_section_layout.addWidget(self.format_list, 1)

        # Playlist-only options
        self.playlist_options = QWidget()
        playlist_layout = QVBoxLayout(self.playlist_options)
        playlist_layout.setContentsMargins(0, 0, 0, 0)
        playlist_layout.setSpacing(12)

        self.playlist_divider = QFrame()
        self.playlist_divider.setObjectName("divider")
        self.playlist_divider.setFrameShape(QFrame.Shape.HLine)
        playlist_layout.addWidget(self.playlist_divider)

        playlist_header = QHBoxLayout()
        playlist_header.setSpacing(8)

        playlist_title = QLabel("Playlist items")
        playlist_title.setObjectName("sectionTitle")

        self.selection_count_label = plain_label(
            wrap=False, object_name="countBadge"
        )

        self.select_all_button = QPushButton("Select all")
        self.select_all_button.setObjectName("compactBtn")
        self.select_all_button.setCursor(Qt.CursorShape.PointingHandCursor)
        self.select_all_button.setToolTip(
            "Check all videos (or all shown videos when filtered)"
        )
        self.select_all_button.clicked.connect(self.select_all_videos)

        self.select_none_button = QPushButton("Clear")
        self.select_none_button.setObjectName("compactBtn")
        self.select_none_button.setCursor(Qt.CursorShape.PointingHandCursor)
        self.select_none_button.setToolTip(
            "Uncheck all videos (or all shown videos when filtered)"
        )
        self.select_none_button.clicked.connect(self.select_no_videos)

        self.preview_button = QPushButton("Preview")
        self.preview_button.setObjectName("compactBtn")
        self.preview_button.setCursor(Qt.CursorShape.PointingHandCursor)
        self.preview_button.setToolTip(
            "Open larger visual preview to browse and select pictures"
        )
        self.preview_button.clicked.connect(self.open_preview_dialog)

        playlist_header.addWidget(playlist_title)
        playlist_header.addWidget(self.selection_count_label)
        playlist_header.addStretch(1)
        playlist_header.addWidget(self.preview_button)
        playlist_header.addWidget(self.select_all_button)
        playlist_header.addWidget(self.select_none_button)
        playlist_layout.addLayout(playlist_header)

        self.playlist_inputs_layout = QGridLayout()
        self.playlist_inputs_layout.setHorizontalSpacing(10)
        self.playlist_inputs_layout.setVerticalSpacing(8)

        self.range_field_label = QLabel("Range")
        self.range_field_label.setObjectName("fieldLabel")
        self.range_input = QLineEdit()
        self.range_input.setClearButtonEnabled(True)
        self.range_input.textEdited.connect(self.on_range_edited)

        self.filter_field_label = QLabel("Filter")
        self.filter_field_label.setObjectName("fieldLabel")
        self.search_input = QLineEdit()
        self.search_input.setPlaceholderText("Filter videos by title…")
        self.search_input.setClearButtonEnabled(True)
        self.search_input.textChanged.connect(self.on_search_changed)

        self._layout_playlist_inputs(narrow=False)
        playlist_layout.addLayout(self.playlist_inputs_layout)

        self.playlist_list = QListWidget()
        self.playlist_list.setIconSize(QSize(78, 52))
        self.playlist_list.setMinimumHeight(210)
        self.playlist_list.setSizePolicy(
            QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding
        )
        self.playlist_list.itemChanged.connect(self.on_checklist_item_changed)
        self.playlist_list.currentItemChanged.connect(
            self.on_playlist_item_preview
        )
        self.playlist_list.itemDoubleClicked.connect(
            lambda _item: self.open_preview_dialog()
        )
        playlist_layout.addWidget(self.playlist_list, 1)

        subfolder_row = QHBoxLayout()
        subfolder_row.setSpacing(10)
        subfolder_label = QLabel("Subfolder")
        subfolder_label.setObjectName("fieldLabel")
        self.subfolder_input = QLineEdit()
        self.subfolder_input.setClearButtonEnabled(True)
        subfolder_row.addWidget(subfolder_label)
        subfolder_row.addWidget(self.subfolder_input, 1)
        playlist_layout.addLayout(subfolder_row)

        self._layout_options_sections(wide=False)
        self.content_layout.addWidget(self.options, 1)

        # Save folder bar
        folder_bar = QFrame()
        folder_bar.setObjectName("insetBar")
        folder_row = QHBoxLayout(folder_bar)
        folder_row.setContentsMargins(14, 10, 14, 10)
        folder_row.setSpacing(10)

        save_to_label = QLabel("Save to")
        save_to_label.setObjectName("fieldLabel")
        self.folder_label = plain_label(
            str(get_download_dir()), object_name="mutedText"
        )
        self.folder_button = QPushButton("Change…")
        self.folder_button.setObjectName("compactBtn")
        self.folder_button.setCursor(Qt.CursorShape.PointingHandCursor)
        self.folder_button.clicked.connect(self.choose_folder)

        folder_row.addWidget(save_to_label)
        folder_row.addWidget(self.folder_label, 1)
        folder_row.addWidget(self.folder_button)
        self.content_layout.addWidget(folder_bar)

        # Action buttons
        button_row = QHBoxLayout()
        button_row.setSpacing(10)

        self.download_button = QPushButton("Download")
        self.download_button.setObjectName("primaryBtn")
        self.download_button.setCursor(Qt.CursorShape.PointingHandCursor)
        self.download_button.clicked.connect(self.start_download)

        self.cancel_button = QPushButton("Cancel")
        self.cancel_button.setObjectName("dangerBtn")
        self.cancel_button.setCursor(Qt.CursorShape.PointingHandCursor)
        self.cancel_button.clicked.connect(self.cancel_download)

        self.open_button = QPushButton("Open folder")
        self.open_button.setCursor(Qt.CursorShape.PointingHandCursor)
        self.open_button.clicked.connect(self.open_folder)

        self.failures_button = QPushButton("View failed")
        self.failures_button.setCursor(Qt.CursorShape.PointingHandCursor)
        self.failures_button.clicked.connect(self.show_failures)

        button_row.addWidget(self.download_button)
        button_row.addWidget(self.cancel_button)
        button_row.addWidget(self.open_button)
        button_row.addWidget(self.failures_button)
        button_row.addStretch(1)
        self.content_layout.addLayout(button_row)

        # Progress card
        self.progress_box = QFrame()
        self.progress_box.setObjectName("card")
        progress_layout = QVBoxLayout(self.progress_box)
        progress_layout.setContentsMargins(16, 14, 16, 14)
        progress_layout.setSpacing(8)

        self.item_label = plain_label(bold=True)
        self.progress_bar = QProgressBar()
        self.progress_bar.setTextVisible(False)
        self.progress_bar.setFixedHeight(10)
        self.progress_label = plain_label(object_name="progressDetail")

        progress_layout.addWidget(self.item_label)
        progress_layout.addWidget(self.progress_bar)
        progress_layout.addWidget(self.progress_label)
        self.content_layout.addWidget(self.progress_box)

        # Status banner
        self.status_banner = QFrame()
        self.status_banner.setObjectName("statusBanner")
        self.status_banner.setProperty("statusLevel", "info")
        status_row = QHBoxLayout(self.status_banner)
        status_row.setContentsMargins(14, 10, 14, 10)
        status_row.setSpacing(12)

        self.status_label = plain_label(object_name="statusText")
        self.status_label.setProperty("statusLevel", "info")

        self.diagnostics_button = QPushButton("Copy diagnostics")
        self.diagnostics_button.setObjectName("compactBtn")
        self.diagnostics_button.setCursor(Qt.CursorShape.PointingHandCursor)
        self.diagnostics_button.setToolTip(
            "Copy technical details to share when reporting a problem"
        )
        self.diagnostics_button.clicked.connect(self.copy_diagnostics)
        self.diagnostics_button.hide()

        status_row.addWidget(self.status_label, 1)
        status_row.addWidget(
            self.diagnostics_button, 0, Qt.AlignmentFlag.AlignVCenter
        )
        self.status_banner.hide()
        self.content_layout.addWidget(self.status_banner)

        self.content_layout.addStretch(1)

        # History page (stretches full window width and height)
        self._build_history_page()
        self.pages.addWidget(self.history_page)

    def _build_history_page(self):
        self.history_page = QWidget()
        self.history_page.setObjectName("scrollContent")
        self.history_page_layout = QVBoxLayout(self.history_page)
        self.history_page_layout.setContentsMargins(24, 10, 24, 24)
        self.history_page_layout.setSpacing(14)

        # History toolbar card
        toolbar = QFrame()
        toolbar.setObjectName("card")
        toolbar_layout = QVBoxLayout(toolbar)
        toolbar_layout.setContentsMargins(16, 14, 16, 14)
        toolbar_layout.setSpacing(12)

        top_row = QHBoxLayout()
        top_row.setSpacing(10)

        history_title = QLabel("Download History")
        history_title.setObjectName("heroTitle")
        self.history_count_label = plain_label(
            "0 items", wrap=False, object_name="countBadge"
        )
        self.clear_history_button = QPushButton("Clear All")
        self.clear_history_button.setObjectName("dangerBtn")
        self.clear_history_button.setCursor(Qt.CursorShape.PointingHandCursor)
        self.clear_history_button.setToolTip(
            "Clear all entries from history (optionally delete files)"
        )
        self.clear_history_button.clicked.connect(self.clear_all_history)

        top_row.addWidget(history_title)
        top_row.addWidget(self.history_count_label)
        top_row.addStretch(1)
        top_row.addWidget(self.clear_history_button)
        toolbar_layout.addLayout(top_row)

        self.history_filter_layout = QBoxLayout(QBoxLayout.Direction.LeftToRight)
        self.history_filter_layout.setSpacing(10)

        self.history_search = QLineEdit()
        self.history_search.setPlaceholderText(
            "Search downloaded videos or audio…"
        )
        self.history_search.setClearButtonEnabled(True)
        self.history_search.textChanged.connect(self.refresh_history)

        filter_frame = QFrame()
        filter_frame.setObjectName("segmentGroup")
        filter_seg_layout = QHBoxLayout(filter_frame)
        filter_seg_layout.setContentsMargins(3, 3, 3, 3)
        filter_seg_layout.setSpacing(2)

        self.history_filter_buttons = {}
        filter_group = QButtonGroup(self)
        filter_group.setExclusive(True)

        for key, label in (
            ("all", "All"),
            ("video", "Video"),
            ("audio", "Audio"),
            ("image", "Image"),
        ):
            btn = QPushButton(label)
            btn.setObjectName("segmentBtn")
            btn.setCheckable(True)
            btn.setChecked(key == "all")
            btn.setCursor(Qt.CursorShape.PointingHandCursor)
            btn.clicked.connect(
                lambda _checked=False, k=key: self.set_history_filter(k)
            )
            filter_group.addButton(btn)
            filter_seg_layout.addWidget(btn)
            self.history_filter_buttons[key] = btn

        self.history_filter_layout.addWidget(self.history_search, 1)
        self.history_filter_layout.addWidget(filter_frame)
        toolbar_layout.addLayout(self.history_filter_layout)
        self.history_page_layout.addWidget(toolbar)

        # History list (full-width & full-height)
        self.history_list = QListWidget()
        self.history_list.setObjectName("historyList")
        self.history_list.setSelectionMode(
            QAbstractItemView.SelectionMode.SingleSelection
        )
        self.history_list.setHorizontalScrollBarPolicy(
            Qt.ScrollBarPolicy.ScrollBarAlwaysOff
        )
        self.history_list.setVerticalScrollMode(
            QAbstractItemView.ScrollMode.ScrollPerPixel
        )
        self.history_list.setToolTip("Click an item to play it")
        self.history_list.itemClicked.connect(self.on_history_item_activated)
        self.history_list.itemActivated.connect(self.on_history_item_activated)
        self.history_page_layout.addWidget(self.history_list, 1)

        # Empty history card
        self.history_empty_card = QFrame()
        self.history_empty_card.setObjectName("heroCard")
        empty_layout = QVBoxLayout(self.history_empty_card)
        empty_layout.setContentsMargins(24, 28, 24, 28)
        empty_layout.setSpacing(10)

        self.history_empty_title = QLabel("No downloads yet")
        self.history_empty_title.setObjectName("heroTitle")
        self.history_empty_body = QLabel(
            "Videos and audio tracks you download will appear here so you can "
            "play or manage them anytime."
        )
        self.history_empty_body.setObjectName("heroBody")
        self.history_empty_body.setWordWrap(True)

        go_btn = QPushButton("Go to Downloader")
        go_btn.setObjectName("accentSoftBtn")
        go_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        go_btn.clicked.connect(lambda: self.show_page("downloader"))

        empty_layout.addWidget(self.history_empty_title)
        empty_layout.addWidget(self.history_empty_body)
        empty_layout.addWidget(go_btn, 0, Qt.AlignmentFlag.AlignLeft)
        self.history_page_layout.addWidget(self.history_empty_card)
        self.history_page_layout.addStretch(0)

    def _layout_options_sections(self, wide):
        """Place Format and Playlist side-by-side on wide screens, stacked otherwise."""

        while self.options_grid.count():
            self.options_grid.takeAt(0)

        split = wide and self.mode == "playlist"
        self.playlist_divider.setVisible(not split)

        if split:
            self.options_grid.addWidget(self.format_section, 0, 0)
            self.options_grid.addWidget(self.playlist_options, 0, 1)
            self.options_grid.setColumnStretch(0, 2)
            self.options_grid.setColumnStretch(1, 3)
        else:
            self.options_grid.addWidget(self.format_section, 0, 0)
            self.options_grid.addWidget(self.playlist_options, 1, 0)
            self.options_grid.setColumnStretch(0, 1)
            self.options_grid.setColumnStretch(1, 0)

    def _layout_url_inputs(self, narrow):
        """Place URL box + Paste & Fetch + Fetch on 1 row (wide) or 2 rows (narrow)."""

        while self.url_inputs_layout.count():
            self.url_inputs_layout.takeAt(0)

        if narrow:
            self.url_inputs_layout.addWidget(self.url_input, 0, 0, 1, 2)
            self.url_inputs_layout.addWidget(self.paste_button, 1, 0)
            self.url_inputs_layout.addWidget(self.fetch_button, 1, 1)
            self.url_inputs_layout.setColumnStretch(0, 1)
            self.url_inputs_layout.setColumnStretch(1, 1)
            self.url_inputs_layout.setColumnStretch(2, 0)
        else:
            self.url_inputs_layout.addWidget(self.url_input, 0, 0)
            self.url_inputs_layout.addWidget(self.paste_button, 0, 1)
            self.url_inputs_layout.addWidget(self.fetch_button, 0, 2)
            self.url_inputs_layout.setColumnStretch(0, 1)
            self.url_inputs_layout.setColumnStretch(1, 0)
            self.url_inputs_layout.setColumnStretch(2, 0)

    def _layout_playlist_inputs(self, narrow):
        """Arrange Range and Filter controls in 2 columns (wide) or 1 column (narrow)."""

        while self.playlist_inputs_layout.count():
            self.playlist_inputs_layout.takeAt(0)

        if narrow:
            self.playlist_inputs_layout.addWidget(self.range_field_label, 0, 0)
            self.playlist_inputs_layout.addWidget(self.range_input, 0, 1)
            self.playlist_inputs_layout.addWidget(self.filter_field_label, 1, 0)
            self.playlist_inputs_layout.addWidget(self.search_input, 1, 1)
            self.playlist_inputs_layout.setColumnStretch(1, 1)
            self.playlist_inputs_layout.setColumnStretch(3, 0)
        else:
            self.playlist_inputs_layout.addWidget(self.range_field_label, 0, 0)
            self.playlist_inputs_layout.addWidget(self.range_input, 0, 1)
            self.playlist_inputs_layout.addWidget(self.filter_field_label, 0, 2)
            self.playlist_inputs_layout.addWidget(self.search_input, 0, 3)
            self.playlist_inputs_layout.setColumnStretch(1, 1)
            self.playlist_inputs_layout.setColumnStretch(3, 1)

    def update_responsive_layout(self, width):
        """Stretch layout cleanly across full window width at every breakpoint."""

        narrow = width < NARROW_BREAKPOINT
        wide = width >= WIDE_BREAKPOINT
        side_margin = 16 if narrow else 24

        self.header_outer_layout.setContentsMargins(side_margin, 16, side_margin, 8)
        self.content_layout.setContentsMargins(side_margin, 10, side_margin, 24)
        self.history_page_layout.setContentsMargins(side_margin, 10, side_margin, 24)

        if narrow != self._is_narrow:
            self._is_narrow = narrow
            direction = (
                QBoxLayout.Direction.TopToBottom
                if narrow
                else QBoxLayout.Direction.LeftToRight
            )
            self.header_layout.setDirection(direction)
            self.card_layout.setDirection(direction)
            self.history_filter_layout.setDirection(direction)
            self._layout_url_inputs(narrow)
            self._layout_playlist_inputs(narrow)

        if wide != self._is_wide:
            self._is_wide = wide
            self._layout_options_sections(wide)

    def showEvent(self, event):
        super().showEvent(event)
        _apply_windows_taskbar_icon(self)

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self.update_responsive_layout(event.size().width())

    # ----- state ----------------------------------------------------------

    def set_state(self, state):
        self.state = state

        busy = state in ("fetching", "downloading")
        has_media = state in ("ready", "downloading", "done")
        editable = state in ("ready", "done")
        is_playlist = self.mode == "playlist"

        self.url_input.setEnabled(not busy)
        self.paste_button.setEnabled(not busy)
        self.fetch_button.setEnabled(not busy)
        self.fetch_button.setText(
            "Fetching…" if state == "fetching" else "Fetch"
        )
        self.fetch_button.setObjectName(
            "secondaryBtn" if has_media else "primaryBtn"
        )
        repolish(self.fetch_button)

        self.folder_button.setEnabled(not busy)
        self.update_button.setEnabled(not busy)

        self.hero_card.setVisible(state == "idle")
        self.card.setVisible(has_media)
        self.options.setVisible(has_media)
        self.playlist_options.setVisible(has_media and is_playlist)
        self._layout_options_sections(self._is_wide)

        has_video_streams = self.mode != "image" and not (
            self.mode == "playlist"
            and self.playlist is not None
            and self.playlist.entries
            and all(
                e.get("_zardit_media_kind") == "image"
                for e in self.playlist.entries
            )
        )
        self.video_radio.setEnabled(editable and has_video_streams)
        self.audio_radio.setEnabled(editable and has_video_streams)
        self.image_radio.setEnabled(editable)
        self.format_list.setEnabled(editable)
        self.range_input.setEnabled(editable)
        self.search_input.setEnabled(editable)
        self.preview_button.setEnabled(editable)
        self.select_all_button.setEnabled(editable)
        self.select_none_button.setEnabled(editable)
        self.playlist_list.setEnabled(editable)
        self.subfolder_input.setEnabled(editable)

        self.download_button.setVisible(editable)
        self.cancel_button.setVisible(state == "downloading")
        self.cancel_button.setEnabled(True)
        self.cancel_button.setText("Cancel")
        self.open_button.setVisible(
            state == "done" and self.last_folder is not None
        )
        self.failures_button.setVisible(
            state == "done" and bool(self.failed_items)
        )

        self.progress_box.setVisible(busy)
        self.item_label.setVisible(
            state == "downloading"
            and is_playlist
            and bool(self.item_label.text())
        )

        self.update_download_button()
        self.fit_window(shrink=(state == "idle"))

    def fit_window(self, shrink=False):
        """Grow the window when content needs room; never override maximized windows."""

        if (
            self.isMaximized()
            or self.isFullScreen()
            or self._active_page != "downloader"
        ):
            return

        self.content_layout.activate()
        self.scroll_content.adjustSize()

        header_h = self.header_widget.sizeHint().height()
        needed = max(
            self.minimumHeight(),
            header_h + self.scroll_content.sizeHint().height() + 4,
        )

        screen = self.screen()
        if screen is not None:
            max_height = int(screen.availableGeometry().height() * 0.88)
            needed = min(needed, max_height)

        if shrink or self.height() < needed:
            self.resize(self.width(), needed)

    def update_download_button(self):
        if self.mode == "playlist" and self.playlist is not None:
            count = self.checked_count()
            self.download_button.setText(
                f"Download {count} item{'s' if count != 1 else ''}"
            )
            self.download_button.setEnabled(count > 0)
        else:
            self.download_button.setText("Download")
            self.download_button.setEnabled(True)

    def show_status(self, text, level="info"):
        self._status_text = text or ""
        self._status_level = level if level in ("info", "success", "warning", "error") else "info"

        self.status_label.setText(self._status_text)
        self.status_banner.setProperty("statusLevel", self._status_level)
        self.status_label.setProperty("statusLevel", self._status_level)
        repolish(self.status_banner)
        repolish(self.status_label)

        self.diagnostics_button.setVisible(
            self._status_level == "error" and bool(self._status_text)
        )
        self.status_banner.setVisible(bool(self._status_text))

    def copy_diagnostics(self):
        QApplication.clipboard().setText(diagnostics_text())
        self.show_status(
            "Diagnostics copied to the clipboard. "
            "Paste them when you report the problem.",
            "success",
        )

    # ----- background tasks -----------------------------------------------

    def start_task(self, function):
        """Create a background thread that cleans itself up when finished."""

        task = TaskThread(function, self)
        task.finished.connect(self._on_task_finished)
        task.finished.connect(task.deleteLater)

        self.task = task

        return task

    def _on_task_finished(self):
        if self.sender() is self.task:
            self.task = None

    # ----- thumbnails -----------------------------------------------------

    def show_thumbnail(self, data, placeholder=""):
        self._thumb_data = data
        self._thumb_placeholder = placeholder

        try:
            pixmap = QPixmap()
            self.thumb.setObjectName("")
            repolish(self.thumb)

            if data and pixmap.loadFromData(data):
                self.thumb.setPixmap(
                    rounded_pixmap(pixmap, QSize(192, 108), radius=8)
                )
                return

            self.thumb.clear()

            if placeholder:
                self.thumb.setText(placeholder)
                self.thumb.setObjectName("thumbPlaceholder")
                repolish(self.thumb)

        except Exception:
            logging.getLogger("zardit").warning(
                "Could not show the thumbnail", exc_info=True
            )
            self.thumb.clear()

    # ----- playlist checklist ---------------------------------------------

    def _entry_preview_url(self, entry):
        url = best_image_url(entry) or entry.get("thumbnail")
        if not url and entry.get("ie_key") in (None, "Youtube"):
            vid = str(entry.get("id") or "")
            if VIDEO_ID_PATTERN.match(vid):
                url = f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg"
        return url or ""

    def _icon_from_bytes(self, data):
        pix = QPixmap()
        if data and pix.loadFromData(data):
            return QIcon(rounded_pixmap(pix, QSize(78, 52), radius=6))
        return self._placeholder_icon()

    def _placeholder_icon(self):
        pix = QPixmap(78, 52)
        pix.fill(Qt.GlobalColor.transparent)
        painter = QPainter(pix)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        fill = QColor(GUI_PALETTES[self.active_theme]["thumb_bg"])
        painter.setBrush(fill)
        painter.setPen(Qt.PenStyle.NoPen)
        painter.drawRoundedRect(0, 0, 78, 52, 6, 6)
        painter.end()
        return QIcon(pix)

    def populate_playlist_checklist(self):
        """Fill the checklist with inline picture previews for all items."""

        self._syncing_checks = True
        self.playlist_list.clear()

        urls_to_fetch = []
        if self.playlist:
            kinds = {
                "image" if e.get("_zardit_media_kind") == "image" else "video"
                for e in self.playlist.entries
            }
            mixed = len(kinds) > 1
            placeholder = self._placeholder_icon()

            for index, entry in enumerate(self.playlist.entries, start=1):
                title = entry.get("title") or entry.get("id") or "Untitled"
                if mixed:
                    tag = "[Image]" if entry.get("_zardit_media_kind") == "image" else "[Video]"
                    label = f"{index}.  {tag} {title}"
                else:
                    label = f"{index}.  {title}"
                item = QListWidgetItem(label)
                item.setData(Qt.ItemDataRole.UserRole, entry)
                preview_url = self._entry_preview_url(entry)
                item.setData(Qt.ItemDataRole.UserRole + 1, preview_url)
                if preview_url in self._preview_cache:
                    item.setIcon(
                        self._icon_from_bytes(self._preview_cache[preview_url])
                    )
                else:
                    item.setIcon(placeholder)
                    if preview_url:
                        urls_to_fetch.append(preview_url)
                item.setSizeHint(QSize(0, 62))
                item.setFlags(item.flags() | Qt.ItemFlag.ItemIsUserCheckable)
                item.setCheckState(Qt.CheckState.Checked)
                self.playlist_list.addItem(item)

        self._syncing_checks = False
        self.update_selection_count()
        if urls_to_fetch:
            self.preview_loader.fetch_batch(urls_to_fetch)

    def on_playlist_item_preview(self, current, _previous=None):
        if current is None or self.mode != "playlist":
            return

        entry = current.data(Qt.ItemDataRole.UserRole)
        if not isinstance(entry, dict):
            return

        thumb_url = self._entry_preview_url(entry)
        if not thumb_url:
            return

        self._preview_url = thumb_url
        if thumb_url in self._preview_cache:
            self.show_thumbnail(self._preview_cache[thumb_url])
            return

        self.preview_loader.fetch(thumb_url)

    def on_preview_loaded(self, url, data):
        if not data:
            return

        self._preview_cache[url] = data
        if url == self._preview_url and self.mode == "playlist":
            self.show_thumbnail(data)

        if self.mode == "playlist":
            icon = None
            for i in range(self.playlist_list.count()):
                item = self.playlist_list.item(i)
                if item.data(Qt.ItemDataRole.UserRole + 1) == url:
                    if icon is None:
                        icon = self._icon_from_bytes(data)
                    item.setIcon(icon)

    def open_preview_dialog(self):
        if not self.playlist or not self.playlist.entries:
            return

        start = max(0, self.playlist_list.currentRow())

        def is_checked(idx):
            item = self.playlist_list.item(idx)
            return item is not None and item.checkState() == Qt.CheckState.Checked

        def set_checked(idx, state):
            item = self.playlist_list.item(idx)
            if item is not None:
                item.setCheckState(
                    Qt.CheckState.Checked if state else Qt.CheckState.Unchecked
                )

        dlg = MediaPreviewDialog(
            self.playlist.entries,
            start,
            is_checked,
            set_checked,
            self._preview_cache,
            self.preview_loader,
            parent=self,
        )
        dlg.exec()
        self.playlist_list.setCurrentRow(dlg.index)

    def checked_count(self):
        return sum(
            self.playlist_list.item(i).checkState() == Qt.CheckState.Checked
            for i in range(self.playlist_list.count())
        )

    def on_search_changed(self, text):
        """Hide or show checklist items based on the title filter."""

        query = text.strip().lower()

        for i in range(self.playlist_list.count()):
            item = self.playlist_list.item(i)
            item.setHidden(bool(query) and query not in item.text().lower())

        self.update_selection_count()

    def update_selection_count(self):
        total = self.playlist_list.count()
        filtering = bool(self.search_input.text().strip())

        checked = shown = hidden_checked = 0

        for i in range(total):
            item = self.playlist_list.item(i)
            is_checked = item.checkState() == Qt.CheckState.Checked

            checked += is_checked
            shown += not item.isHidden()
            hidden_checked += is_checked and item.isHidden()

        text = f"{checked} of {total} selected"

        if filtering:
            text += f"  ·  {shown} shown"

            if hidden_checked:
                text += f"  ·  {hidden_checked} hidden"

        self.selection_count_label.setText(text)

        if checked == 0:
            hint = "Nothing selected"
        elif checked == total:
            hint = f"All (1-{total}), or e.g. 1-3, 5, 8-10"
        else:
            hint = ""

        self.range_input.setPlaceholderText(hint)
        self.update_download_button()

    @staticmethod
    def format_ranges(indices):
        """Format numbers like [1, 2, 3, 5] as '1-3, 5'."""

        if not indices:
            return ""

        numbers = sorted(indices)
        parts = []
        start = end = numbers[0]

        for number in numbers[1:]:
            if number == end + 1:
                end = number
            else:
                parts.append(str(start) if start == end else f"{start}-{end}")
                start = end = number

        parts.append(str(start) if start == end else f"{start}-{end}")

        return ", ".join(parts)

    def sync_range_from_checks(self):
        """Make the range box describe the currently checked items."""

        total = self.playlist_list.count()
        checked = [
            i + 1
            for i in range(total)
            if self.playlist_list.item(i).checkState()
            == Qt.CheckState.Checked
        ]

        if not checked or len(checked) == total:
            self.range_input.clear()
        else:
            self.range_input.setText(self.format_ranges(checked))

    def on_range_edited(self, text):
        """Update the checkboxes to match the typed range."""

        if not self.playlist:
            return

        total = len(self.playlist.entries)
        indices = parse_range(text, total)

        if indices is None:
            return

        self._syncing_checks = True

        for index in range(total):
            self.playlist_list.item(index).setCheckState(
                Qt.CheckState.Checked
                if (index + 1) in indices
                else Qt.CheckState.Unchecked
            )

        self._syncing_checks = False
        self.update_selection_count()

    def on_checklist_item_changed(self, _item):
        if self._syncing_checks or not self.playlist:
            return

        self.sync_range_from_checks()
        self.update_selection_count()

    def set_shown_checks(self, state):
        """Check or uncheck every item that is currently shown."""

        self._syncing_checks = True

        for i in range(self.playlist_list.count()):
            item = self.playlist_list.item(i)

            if not item.isHidden():
                item.setCheckState(state)

        self._syncing_checks = False

        self.sync_range_from_checks()
        self.update_selection_count()

    def select_all_videos(self):
        self.set_shown_checks(Qt.CheckState.Checked)

    def select_no_videos(self):
        self.set_shown_checks(Qt.CheckState.Unchecked)

    def get_checked_entries(self):
        """The playlist entries whose checkboxes are checked."""

        if not self.playlist:
            return []

        return [
            entry
            for index, entry in enumerate(self.playlist.entries)
            if self.playlist_list.item(index).checkState()
            == Qt.CheckState.Checked
        ]

    # ----- fetching -------------------------------------------------------

    def on_url_edited(self):
        if self.state in ("ready", "done"):
            self.info = None
            self.playlist = None
            self.failed_items = []
            self.set_state("idle")
            self.show_status("")

    def ask_mixed_url_choice(self):
        """Ask whether a video-inside-a-playlist link means one or all.

        Returns "video", "playlist", or None if cancelled.
        """

        dialog = QMessageBox(self)
        dialog.setWindowTitle(APP_NAME)
        dialog.setIcon(QMessageBox.Icon.Question)
        dialog.setText("This link is a video inside a playlist.")
        dialog.setInformativeText(
            "Would you like just this video, or the whole playlist?"
        )

        video_button = dialog.addButton(
            "Single Video", QMessageBox.ButtonRole.AcceptRole
        )
        playlist_button = dialog.addButton(
            "Whole Playlist", QMessageBox.ButtonRole.ActionRole
        )
        dialog.addButton(QMessageBox.StandardButton.Cancel)
        dialog.setDefaultButton(video_button)

        dialog.exec()
        clicked = dialog.clickedButton()

        if clicked == video_button:
            return "video"

        if clicked == playlist_button:
            return "playlist"

        return None

    def paste_and_fetch(self):
        """Paste a media link from the clipboard and start fetching right away."""

        if self.state in ("fetching", "downloading"):
            return

        clipboard_text = QApplication.clipboard().text().strip()
        if not clipboard_text:
            self.show_status(
                "Clipboard is empty. Copy a video or post link first.", "warning"
            )
            return

        extracted = extract_media_url(clipboard_text)
        if not extracted:
            self.url_input.setText(clipboard_text)
            self.on_url_edited()
            self.show_status(
                "Clipboard does not contain a valid media URL.", "error"
            )
            return

        self.url_input.setText(extracted)
        self.on_url_edited()
        self.fetch()

    def fetch(self):
        if self.state in ("fetching", "downloading"):
            return

        url = self.url_input.text().strip()

        if not url:
            self.show_status("Paste a media link first.", "error")
            return

        prefer_playlist = False

        if has_playlist_and_video(url):
            choice = self.ask_mixed_url_choice()

            if choice is None:
                return

            prefer_playlist = choice == "playlist"

        self.url = url
        self.failed_items = []
        self.show_status(
            "Fetching playlist information…"
            if prefer_playlist
            else "Fetching information…"
        )
        self.item_label.setText("")
        self.progress_bar.setRange(0, 0)
        self.progress_label.setText("")
        self.set_state("fetching")

        task = self.start_task(
            lambda thread: fetch_everything(url, prefer_playlist)
        )
        task.done.connect(self.on_fetched)
        task.failed.connect(self.on_fetch_failed)
        task.start()

    def on_fetched(self, result):
        self.mode = result[0]
        platform = detect_platform(self.url) if self.url else ""
        badge_prefix = f"{platform.upper()} · " if platform else ""

        if self.mode == "video":
            _, self.info, self.summary, self.video_options, thumbnail = result
            self.image_options = []
            self.playlist = None
            self.search_input.clear()
            self.playlist_list.clear()

            self.type_badge.setText(f"{badge_prefix}VIDEO")
            self.title_label.setText(self.summary.title)
            self.title_label.setToolTip(self.summary.title)
            meta_bits = []
            if self.summary.channel and self.summary.channel != "Unknown channel":
                meta_bits.append(self.summary.channel)
            dur_text = format_duration(self.summary.duration)
            if dur_text and dur_text != "Unknown":
                meta_bits.append(dur_text)
            if self.summary.upload_date and self.summary.upload_date != "Unknown":
                meta_bits.append(self.summary.upload_date)
            self.meta_label.setText("  ·  ".join(meta_bits) if meta_bits else "Instagram / Web Media")
            self.show_thumbnail(thumbnail)
            if self.image_radio.isChecked():
                self.video_radio.setChecked(True)

        elif self.mode == "image":
            _, self.info, self.summary, self.image_options, thumbnail = result
            self.video_options = []
            self.playlist = None
            self.search_input.clear()
            self.playlist_list.clear()

            self.type_badge.setText(f"{badge_prefix}IMAGE")
            self.title_label.setText(self.summary.title)
            meta_bits = [self.summary.channel]
            if self.summary.upload_date and self.summary.upload_date != "Unknown":
                meta_bits.append(self.summary.upload_date)
            self.meta_label.setText("  ·  ".join(meta_bits))
            self.show_thumbnail(thumbnail, placeholder="IMAGE")
            self.image_radio.setChecked(True)

        else:
            _, self.playlist, thumbnail = result
            self.info = None
            self.summary = None
            self.video_options = []
            self.image_options = []

            count = len(self.playlist.entries)
            is_image_gallery = bool(
                self.playlist.entries
                and all(
                    e.get("_zardit_media_kind") == "image"
                    for e in self.playlist.entries
                )
            )
            if thumbnail and self.playlist.entries:
                first_url = best_image_url(self.playlist.entries[0]) or self.playlist.entries[0].get("thumbnail")
                if first_url:
                    self._preview_cache[first_url] = thumbnail
            self.type_badge.setText(
                f"{badge_prefix}{'GALLERY' if is_image_gallery else 'PLAYLIST'}"
            )
            self.title_label.setText(self.playlist.title)
            item_word = "image" if is_image_gallery else "video"
            self.meta_label.setText(
                f"{'Gallery' if is_image_gallery else 'Playlist'}  ·  "
                f"{count} {item_word if count == 1 else item_word + 's'}"
            )
            self.show_thumbnail(
                thumbnail,
                placeholder="GALLERY" if is_image_gallery else "PLAYLIST",
            )

            self.range_input.clear()
            self.search_input.clear()
            self.subfolder_input.setText(self.playlist.title)
            self.subfolder_input.setPlaceholderText(self.playlist.title)
            self.populate_playlist_checklist()
            if is_image_gallery:
                self.image_radio.setChecked(True)
            elif self.image_radio.isChecked():
                self.video_radio.setChecked(True)

        self.populate_quality()
        self.show_status("")
        self.set_state("ready")

    def on_fetch_failed(self, message):
        self.show_status(message, "error")
        self.set_state("idle")

    def populate_quality(self):
        if self.mode in ("video", "image") and self.info is None:
            return

        if self.mode == "playlist" and self.playlist is None:
            return

        settings = load_settings()
        self.format_list.blockSignals(True)
        self.format_list.clear()

        if self.video_radio.isChecked():
            if self.mode == "video":
                self.quality_label.setText("Video formats")
                options = list(self.video_options)
            else:
                self.quality_label.setText("Max video quality")
                options = playlist_video_format_options()

            saved = settings.get("video_quality")

        elif self.audio_radio.isChecked():
            self.quality_label.setText("Audio formats")
            options = audio_format_options(
                self.info if self.mode == "video" else None
            )
            saved = settings.get("audio_bitrate")

        else:
            self.quality_label.setText("Image formats")
            if self.mode == "image" and self.image_options:
                options = list(self.image_options)
            else:
                options = image_format_options(
                    self.info if self.mode == "video" else None
                )
            saved = settings.get("image_format")

        count = len(options)
        self.format_count_label.setText(
            f"{count} {'format' if count == 1 else 'formats'}"
        )

        selected_row = 0
        total_height = 0
        for row, option in enumerate(options):
            item = QListWidgetItem()
            item.setData(Qt.ItemDataRole.UserRole, option.value)
            widget = FormatRowWidget(option)
            row_height = max(54, widget.sizeHint().height() + 4)
            item.setSizeHint(QSize(0, row_height))
            total_height += row_height + 4
            self.format_list.addItem(item)
            self.format_list.setItemWidget(item, widget)

            if option.value == saved:
                selected_row = row

        list_height = min(max(total_height + 12, 120), 310)
        self.format_list.setMinimumHeight(list_height)
        self.format_list.setMaximumHeight(list_height)

        self.format_list.blockSignals(False)

        if count > 0:
            self.format_list.setCurrentRow(selected_row)
            self.on_format_row_changed(selected_row)

        self.fit_window()

    def on_format_row_changed(self, current_row):
        for row in range(self.format_list.count()):
            item = self.format_list.item(row)
            widget = self.format_list.itemWidget(item)
            if widget is not None:
                widget.set_selected(row == current_row)

    def selected_quality(self):
        item = self.format_list.currentItem()
        if item is None:
            return None
        return item.data(Qt.ItemDataRole.UserRole)

    # ----- downloading ----------------------------------------------------

    def start_download(self):
        setting = self.selected_quality()

        if setting is None:
            self.show_status(
                "No supported MP4 resolutions for this item. "
                "Try Audio or Image instead.",
                "warning",
            )
            return

        if self.video_radio.isChecked():
            kind = "video"
        elif self.audio_radio.isChecked():
            kind = "audio"
        else:
            kind = "image"

        self.cancel_event = threading.Event()
        cancel = self.cancel_event

        if self.mode == "playlist":
            total = len(self.playlist.entries)
            range_text = self.range_input.text().strip()

            if range_text and parse_range(range_text, total) is None:
                self.show_status(
                    f"Invalid range. Use numbers between 1 and {total}, "
                    "for example 1-3, 5, 8-10, or use the checkboxes.",
                    "error",
                )
                return

            selected = self.get_checked_entries()

            if not selected:
                self.show_status(
                    "Select at least one item to download.", "error"
                )
                return

            folder_name = (
                self.subfolder_input.text().strip() or self.playlist.title
            )
            workers = max(1, min(int(load_settings().get("playlist_concurrency", 2) or 2), 4))

            def job(thread):
                return download_playlist(
                    selected, kind, setting, folder_name,
                    reporter=QtReporter(thread), cancel=cancel,
                    max_workers=workers,
                )

            done_handler = self.on_playlist_done

        else:
            if kind == "video":
                function = download_video
            elif kind == "audio":
                function = download_audio
            else:
                function = download_image
            url, info = self.url, self.info

            def job(thread):
                return function(
                    url, setting,
                    info=info, reporter=QtReporter(thread), cancel=cancel,
                )

            done_handler = self.on_download_done

        self.download_kind = kind
        self.download_setting = setting
        self.failed_items = []

        self.show_status("")
        self.item_label.setText("")
        self.progress_bar.setRange(0, 0)
        self.progress_label.setText("")
        self.set_state("downloading")

        task = self.start_task(job)
        task.message.connect(self.show_status)
        task.stage.connect(self.on_stage)
        task.playlist_item.connect(self.on_playlist_item)
        task.progress.connect(self.on_progress)
        task.progress_done.connect(self.on_progress_done)
        task.done.connect(done_handler)
        task.failed.connect(self.on_download_failed)
        task.start()

    def cancel_download(self):
        if self.cancel_event is not None:
            self.cancel_event.set()

        self.cancel_button.setEnabled(False)
        self.cancel_button.setText("Cancelling…")

    def on_playlist_item(self, number, total, title):
        self.item_label.setText(f"({number}/{total})  {title}")
        self.item_label.setVisible(True)
        self.progress_bar.setRange(0, 0)
        self.progress_label.setText("")
        self.cancel_button.setEnabled(
            self.cancel_event is None or not self.cancel_event.is_set()
        )
        self.fit_window()

    def on_stage(self, text):
        # Merging and converting can't be interrupted.
        self.show_status(text)
        self.progress_bar.setRange(0, 0)
        self.progress_label.setText("")
        self.cancel_button.setEnabled(False)

    def on_progress(self, downloaded, total, speed, eta):
        if total:
            self.progress_bar.setRange(0, 1000)
            self.progress_bar.setValue(
                min(1000, int(downloaded * 1000 / total))
            )
            parts = [
                f"{format_file_size(downloaded)} / {format_file_size(total)}"
            ]
        else:
            self.progress_bar.setRange(0, 0)
            parts = [format_file_size(downloaded)]

        if speed:
            parts.append(f"{format_file_size(speed)}/s")

        if eta is not None:
            parts.append(f"ETA {format_duration(eta)}")

        self.progress_label.setText("    ·    ".join(parts))

    def on_progress_done(self):
        self.progress_bar.setRange(0, 1000)
        self.progress_bar.setValue(0)
        self.progress_label.clear()

    def remember_choice(self):
        if self.download_kind == "video":
            key = "video_quality"
        elif self.download_kind == "audio":
            key = "audio_bitrate"
        else:
            key = "image_format"
        remember_setting(key, self.download_setting)

    def _quality_description(self):
        if self.download_kind == "video":
            return f"{self.download_setting}p MP4"
        if self.download_kind == "image":
            return image_label(self.download_setting)
        return audio_label(self.download_setting)

    def _record_single_history(self, result):
        if result.file_path is None:
            return

        if result.status == "exists":
            existing = {item.get("file_path") for item in load_history()}
            if str(Path(result.file_path).resolve()) in existing:
                return

        title = self.summary.title if self.summary else (result.filename or "Download")
        channel = self.summary.channel if self.summary else ""
        duration = self.summary.duration if self.summary else None
        video_id = (self.info or {}).get("id", "")

        add_history_entry(
            title=title,
            file_path=result.file_path,
            kind=self.download_kind,
            quality=self._quality_description(),
            channel=channel,
            video_id=video_id,
            duration=duration,
            platform=detect_platform(self.url) if self.url else "",
            thumbnail_bytes=self._thumb_data,
        )
        self.refresh_history()

    def on_download_done(self, result):
        self.last_folder = result.folder

        if result.status == "completed":
            self.show_status("✔ Download complete", "success")
            notify("Download complete", self.summary.title)
            self.remember_choice()
            self._record_single_history(result)

        elif result.status == "exists":
            self.show_status(
                f"Already downloaded: {result.filename}", "warning"
            )
            self._record_single_history(result)

        else:
            self.show_status("Download cancelled.", "warning")

        self.set_state("done")

    def on_playlist_done(self, result):
        self.last_folder = result.folder
        self.failed_items = result.failed

        for item in getattr(result, "completed_items", ()):
            entry_thumb_bytes = None
            vid = item.get("video_id")
            if self.playlist is not None and vid:
                for p_entry in self.playlist.entries:
                    if p_entry.get("id") == vid:
                        t_url = self._entry_preview_url(p_entry)
                        if t_url and t_url in self._preview_cache:
                            entry_thumb_bytes = self._preview_cache[t_url]
                        break
            add_history_entry(
                title=item.get("title"),
                file_path=item.get("file_path"),
                kind=item.get("kind"),
                quality=item.get("quality", ""),
                video_id=vid or "",
                duration=item.get("duration"),
                platform=detect_platform(self.url) if self.url else "",
                thumbnail_bytes=entry_thumb_bytes or self._thumb_data,
            )
        if getattr(result, "completed_items", None):
            self.refresh_history()

        summary_text = (
            f"{result.completed} completed, "
            f"{result.skipped} already downloaded, "
            f"{len(result.failed)} failed."
        )

        if result.cancelled:
            self.show_status(f"Playlist cancelled — {summary_text}", "warning")
        elif result.failed and result.completed == 0:
            self.show_status(f"Playlist failed — {summary_text}", "error")
        elif result.failed:
            self.show_status(
                f"Playlist finished with errors — {summary_text}", "warning"
            )
        else:
            self.show_status(f"✔ Playlist complete — {summary_text}", "success")

        if result.completed:
            notify(
                "Playlist finished",
                f"{result.completed} of {result.total} downloaded",
            )
            self.remember_choice()

        if result.failed:
            self.diagnostics_button.show()

        self.set_state("done")

    def on_download_failed(self, message):
        self.show_status(message, "error")
        self.set_state("ready")

    def show_failures(self):
        if not self.failed_items:
            return

        lines = [
            f"• {title}\n   {reason}"
            for title, reason in self.failed_items
        ]

        QMessageBox.warning(
            self, "Failed Playlist Items", "\n\n".join(lines)
        )

    # ----- folder ---------------------------------------------------------

    def choose_folder(self):
        chosen = QFileDialog.getExistingDirectory(
            self, "Choose download folder", str(get_download_dir())
        )

        if not chosen:
            return

        try:
            folder = save_download_dir(chosen)
        except (OSError, ValueError) as error:
            self.show_status(f"Could not use that folder: {error}", "error")
            return

        self.folder_label.setText(str(folder))

    def open_folder(self):
        if self.last_folder is not None:
            QDesktopServices.openUrl(
                QUrl.fromLocalFile(str(self.last_folder))
            )

    # ----- history --------------------------------------------------------

    def show_page(self, page):
        self._active_page = page
        is_history = page == "history"
        self.pages.setCurrentWidget(
            self.history_page if is_history else self.scroll_area
        )

        self.nav_downloader_btn.blockSignals(True)
        self.nav_history_btn.blockSignals(True)
        self.nav_downloader_btn.setChecked(not is_history)
        self.nav_history_btn.setChecked(is_history)
        self.nav_downloader_btn.blockSignals(False)
        self.nav_history_btn.blockSignals(False)

        if is_history:
            self.refresh_history()

    def set_history_filter(self, kind):
        self._history_filter = kind
        for key, btn in self.history_filter_buttons.items():
            btn.blockSignals(True)
            btn.setChecked(key == kind)
            btn.blockSignals(False)
        self.refresh_history()

    def _refresh_recent_tiles(self, all_entries):
        while self.recent_rows_layout.count():
            child = self.recent_rows_layout.takeAt(0)
            widget = child.widget()
            if widget is not None:
                widget.deleteLater()

        recent = all_entries[:3]
        self.recent_section.setVisible(bool(recent))
        for entry in recent:
            tile = QFrame()
            tile.setObjectName("recentTile")
            row = QHBoxLayout(tile)
            row.setContentsMargins(10, 8, 10, 8)
            row.setSpacing(10)

            thumb_lbl = QLabel()
            thumb_lbl.setFixedSize(56, 36)
            thumb_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
            t_path = entry.get("thumbnail_path") or ""
            pix = QPixmap()
            if t_path and Path(t_path).is_file() and pix.load(t_path):
                thumb_lbl.setPixmap(rounded_pixmap(pix, QSize(56, 36), radius=5))
            else:
                thumb_lbl.setObjectName("historyThumbFallback")
                thumb_lbl.setText(str(entry.get("kind", "video")).upper()[:3])
            row.addWidget(thumb_lbl)

            text_col = QVBoxLayout()
            text_col.setSpacing(1)
            t_lbl = plain_label(entry.get("title") or "Untitled", wrap=False, object_name="formatTitle")
            meta = " · ".join(p for p in (entry.get("platform"), entry.get("quality")) if p)
            m_lbl = plain_label(meta, wrap=False, object_name="formatDetail")
            text_col.addWidget(t_lbl)
            text_col.addWidget(m_lbl)
            row.addLayout(text_col, 1)

            play_btn = QPushButton("Play")
            play_btn.setObjectName("compactBtn")
            play_btn.setCursor(Qt.CursorShape.PointingHandCursor)
            play_btn.setEnabled(Path(entry.get("file_path", "")).is_file())
            play_btn.clicked.connect(lambda _c=False, e=entry: self.play_history_entry(e))
            row.addWidget(play_btn)
            self.recent_rows_layout.addWidget(tile)

    def refresh_history(self):
        all_entries = load_history()
        self._refresh_recent_tiles(all_entries)
        total_count = len(all_entries)
        self.nav_history_btn.setText(
            f"History ({total_count})" if total_count else "History"
        )
        self.clear_history_button.setEnabled(total_count > 0)

        query = self.history_search.text().strip().lower()
        filtered = []
        for entry in all_entries:
            if (
                self._history_filter != "all"
                and entry.get("kind") != self._history_filter
            ):
                continue
            if query:
                haystack = " ".join([
                    entry.get("title", ""),
                    entry.get("channel", ""),
                    entry.get("quality", ""),
                ]).lower()
                if query not in haystack:
                    continue
            filtered.append(entry)

        shown = len(filtered)
        self.history_count_label.setText(
            f"{shown} of {total_count} items"
            if (query or self._history_filter != "all") and total_count
            else f"{total_count} {'item' if total_count == 1 else 'items'}"
        )

        self.history_list.clear()
        for entry in filtered:
            item = QListWidgetItem()
            item.setData(Qt.ItemDataRole.UserRole, entry)
            row_widget = HistoryRowWidget(
                entry,
                on_play=self.play_history_entry,
                on_remove=self.remove_history_item,
            )
            item.setSizeHint(
                QSize(0, max(64, row_widget.sizeHint().height() + 4))
            )
            self.history_list.addItem(item)
            self.history_list.setItemWidget(item, row_widget)

        has_rows = shown > 0
        self.history_list.setVisible(has_rows)
        self.history_empty_card.setVisible(not has_rows)

        if not total_count:
            self.history_empty_title.setText("No downloads yet")
            self.history_empty_body.setText(
                "Videos and audio tracks you download will appear here so you "
                "can play or manage them anytime."
            )
        elif not has_rows:
            self.history_empty_title.setText("No matching items")
            self.history_empty_body.setText(
                "Try clearing your search or switching the format filter above."
            )

    def on_history_item_activated(self, item):
        entry = item.data(Qt.ItemDataRole.UserRole)
        if entry:
            self.play_history_entry(entry)

    def play_history_entry(self, entry):
        path = Path(entry.get("file_path", ""))
        if not path.is_file():
            QMessageBox.warning(
                self,
                "File Not Found",
                f"The file is no longer at:\n{path}\n\n"
                "You can remove this entry from your history.",
            )
            self.refresh_history()
            return

        QDesktopServices.openUrl(QUrl.fromLocalFile(str(path)))

    def remove_history_item(self, entry):
        title = entry.get("title") or "this item"
        path = Path(entry.get("file_path", ""))
        file_exists = path.is_file()

        box = QMessageBox(self)
        box.setWindowTitle("Remove from History")
        box.setIcon(QMessageBox.Icon.Question)
        box.setText(f"Remove \"{title}\" from download history?")

        delete_check = None
        if file_exists:
            box.setInformativeText(f"File: {path.name}")
            delete_check = QCheckBox("Also delete the downloaded file from disk")
            box.setCheckBox(delete_check)

        remove_btn = box.addButton("Remove", QMessageBox.ButtonRole.AcceptRole)
        box.addButton(QMessageBox.StandardButton.Cancel)
        box.setDefaultButton(remove_btn)
        box.exec()

        if box.clickedButton() is not remove_btn:
            return

        delete_file = bool(delete_check and delete_check.isChecked())
        remove_history_entry(entry.get("id"), delete_file=delete_file)
        self.refresh_history()

    def refresh_ig_button(self):
        browser = get_ig_browser()
        if browser:
            self.ig_button.setText(f"IG / FB · {browser.title()}")
            self.ig_button.setObjectName("accentSoftBtn")
        else:
            self.ig_button.setText("IG / FB")
            self.ig_button.setObjectName("secondaryBtn")
        repolish(self.ig_button)

    def open_instagram_access(self):
        if self.state in ("fetching", "downloading"):
            return
        dlg = InstagramAccessDialog(self)
        accepted = dlg.exec() == QDialog.DialogCode.Accepted
        self.refresh_ig_button()
        if accepted and dlg.parsed_info is not None:
            parsed_info = dlg.parsed_info
            self.url = dlg.parsed_url
            self.url_input.setText(self.url)
            self.failed_items = []
            self.show_status("Preparing extracted media…")
            self.set_state("fetching")
            task = self.start_task(
                lambda _thread: _package_single_or_carousel(parsed_info)
            )
            task.done.connect(self.on_fetched)
            task.failed.connect(self.on_fetch_failed)
            task.start()

    def clear_all_history(self):
        entries = load_history()
        if not entries:
            return

        box = QMessageBox(self)
        box.setWindowTitle("Clear History")
        box.setIcon(QMessageBox.Icon.Warning)
        box.setText(
            f"Clear all {len(entries)} item{'s' if len(entries) != 1 else ''} "
            "from download history?"
        )

        any_files = any(
            Path(item.get("file_path", "")).is_file() for item in entries
        )
        delete_check = None
        if any_files:
            box.setInformativeText(
                "You can choose whether to keep or delete the downloaded media files."
            )
            delete_check = QCheckBox(
                "Also delete all downloaded videos and audio files from disk"
            )
            box.setCheckBox(delete_check)

        clear_btn = box.addButton(
            "Clear All", QMessageBox.ButtonRole.DestructiveRole
        )
        box.addButton(QMessageBox.StandardButton.Cancel)
        box.setDefaultButton(QMessageBox.StandardButton.Cancel)
        box.exec()

        if box.clickedButton() is not clear_btn:
            return

        delete_files = bool(delete_check and delete_check.isChecked())
        clear_history(delete_files=delete_files)
        self.refresh_history()

    # ----- yt-dlp & app updates -------------------------------------------

    def refresh_update_badge(self):
        count = (1 if self._available_ytdlp_version else 0) + (
            1 if (self._available_app_update and self._available_app_update.available) else 0
        )
        if count > 0:
            self.update_button.setText(f"Updates ({count})")
            self.update_button.setProperty("updateAvailable", "true")
            tips = []
            if self._available_app_update and self._available_app_update.available:
                tips.append(f"Zardit {self._available_app_update.tag_name}")
            if self._available_ytdlp_version:
                tips.append(f"yt-dlp {self._available_ytdlp_version}")
            self.update_button.setToolTip("Updates available: " + " · ".join(tips))
        else:
            self.update_button.setText("Updates")
            self.update_button.setProperty("updateAvailable", "false")
            self.update_button.setToolTip("Check for Zardit app and yt-dlp engine updates")
        repolish(self.update_button)

    def _notify_app_update_os(self, info):
        app = QApplication.instance()
        if app is not None and app.platformName() == "offscreen":
            return
        title = (
            f"{APP_NAME} {info.tag_name} update ready"
            if info.same_tag_hotfix
            else f"{APP_NAME} {info.tag_name} is available"
        )
        msg = "Click Updates inside Zardit to verify and install."
        notify(title, msg)
        if sys.platform == "win32" and QSystemTrayIcon.isSystemTrayAvailable():
            try:
                if not hasattr(self, "_update_tray"):
                    self._update_tray = QSystemTrayIcon(load_app_icon(), self)
                    self._update_tray.show()
                self._update_tray.showMessage(title, msg, QSystemTrayIcon.MessageIcon.Information, 6000)
            except Exception:
                pass

    def sync_app_update_state(self, info):
        if info and info.available:
            self._available_app_update = info
            if info.same_tag_hotfix:
                label = f"Updated build available for {APP_NAME} {info.tag_name} (SHA-256 verified)."
            else:
                label = f"{APP_NAME} {info.tag_name} is available (currently v{info.current_version})."
            self.app_update_banner_label.setText(label)
            self.app_update_action_btn.setText(
                "Download" if info.install_mode in ("linux_appimage", "source") else "Update"
            )
            self.app_update_banner.show()
        else:
            self._available_app_update = None
            self.app_update_banner.hide()
        self.refresh_update_badge()

    def on_app_update_available(self, info):
        self.sync_app_update_state(info)
        if info and info.available:
            self._notify_app_update_os(info)

    def _on_banner_update_clicked(self):
        if self._available_app_update:
            self.open_updates_dialog()

    def dismiss_app_update_banner(self):
        info = self._available_app_update
        if info:
            app_updater.dismiss_cached_update(
                asset_sha256=info.asset.sha256 if info.asset else "",
                latest_version=info.latest_version,
            )
        self.app_update_banner.hide()

    def open_updates_dialog(self):
        dlg = UpdatesDialog(self, app_info=self._available_app_update)
        dlg.exec()

    def install_app_update_flow(self, info):
        if self.state in ("fetching", "downloading"):
            self.show_status("Wait for the current download to finish before updating.", "warning")
            return
        progress = self.open_progress(f"Downloading {APP_NAME} {info.tag_name}…")
        progress.setWindowTitle(f"Update {APP_NAME}")

        def job(thread):
            return app_updater.install_app_update(
                info=info,
                report=lambda text: thread.message.emit(text, "info"),
            )

        task = self.start_task(job)
        task.message.connect(lambda text, _level: progress.setLabelText(text))
        task.done.connect(lambda result: self._on_app_update_installed(progress, info, result))
        task.failed.connect(lambda msg: self.on_update_failed(progress, msg))
        task.start()

    def _on_app_update_installed(self, progress, info, result):
        progress.close()
        action, path = result
        self._available_app_update = None
        self.app_update_banner.hide()
        self.refresh_update_badge()

        if action == "installer_launched":
            self.show_status("✔ Verified installer launched. Completing upgrade…", "success")
            self.close()
            app = QApplication.instance()
            if app is not None:
                app.quit()
        elif action == "portable_updating":
            self.show_status("✔ Verified portable update staged. Restarting…", "success")
            self.close()
            app = QApplication.instance()
            if app is not None:
                app.quit()
        elif action == "appimage_updating":
            self.show_status("✔ Verified AppImage update staged. Restarting…", "success")
            self.close()
            app = QApplication.instance()
            if app is not None:
                app.quit()
        else:
            QMessageBox.information(
                self,
                f"{APP_NAME} Update Downloaded",
                f"Verified package saved to:\n{path}\n\n"
                f"Release: {info.tag_name}",
            )
            self.show_status(f"✔ Verified update saved: {path}", "success")

    def on_update_available(self, version):
        self._available_ytdlp_version = version
        self.refresh_update_badge()

    def open_progress(self, text):
        dialog = QProgressDialog(text, "", 0, 0, self)
        dialog.setWindowTitle("Update yt-dlp")
        dialog.setCancelButton(None)
        dialog.setWindowModality(Qt.WindowModality.WindowModal)
        dialog.setMinimumDuration(0)
        dialog.setAutoClose(False)
        dialog.setAutoReset(False)
        dialog.show()

        return dialog

    def update_ytdlp(self):
        if self.state in ("fetching", "downloading"):
            return

        progress = self.open_progress("Checking for updates…")

        task = self.start_task(lambda thread: updater.check_for_update())
        task.done.connect(lambda info: self.on_update_checked(progress, info))
        task.failed.connect(
            lambda message: self.on_update_failed(progress, message)
        )
        task.start()

    def on_update_failed(self, progress, message):
        progress.close()
        self.show_status(message, "error")

    def on_update_checked(self, progress, info):
        progress.close()

        if not info.available:
            self._available_ytdlp_version = None
            self.refresh_update_badge()

        box = QMessageBox(self)
        box.setWindowTitle("Update yt-dlp")

        daily = QCheckBox("Check for updates once a day")
        daily.setChecked(updater.auto_check_enabled())
        box.setCheckBox(daily)

        install_button = restart_button = builtin_button = None

        if info.available:
            box.setIcon(QMessageBox.Icon.Question)
            box.setText(f"yt-dlp {info.latest} is available.")
            box.setInformativeText(
                f"You have {info.current}. Download and install the update?"
            )
            install_button = box.addButton(
                "Update", QMessageBox.ButtonRole.AcceptRole
            )
            box.addButton("Not now", QMessageBox.ButtonRole.RejectRole)

        elif info.pending_restart:
            box.setIcon(QMessageBox.Icon.Information)
            box.setText("An update is already installed.")
            box.setInformativeText(
                f"yt-dlp {info.installed} will be used after the app restarts."
            )
            restart_button = box.addButton(
                "Restart now", QMessageBox.ButtonRole.AcceptRole
            )
            box.addButton("Later", QMessageBox.ButtonRole.RejectRole)

        else:
            box.setIcon(QMessageBox.Icon.Information)
            box.setText("yt-dlp is up to date.")
            box.setInformativeText(f"Version {info.current}")
            box.addButton(QMessageBox.StandardButton.Ok)

            if updater.update_active():
                builtin_button = box.addButton(
                    "Use the built-in version",
                    QMessageBox.ButtonRole.ActionRole,
                )

        box.exec()
        updater.set_auto_check(daily.isChecked())

        clicked = box.clickedButton()

        if install_button is not None and clicked is install_button:
            self.install_update(info)

        elif restart_button is not None and clicked is restart_button:
            self.restart_app()

        elif builtin_button is not None and clicked is builtin_button:
            updater.remove_update()
            self.show_status(
                "The built-in yt-dlp will be used after the app restarts."
            )

    def install_update(self, info):
        progress = self.open_progress("Starting the update…")

        def job(thread):
            return updater.install_update(
                report=lambda text: thread.message.emit(text, "info"),
                info=info,
            )

        task = self.start_task(job)
        task.message.connect(lambda text, level: progress.setLabelText(text))
        task.done.connect(
            lambda version: self.on_update_installed(progress, version)
        )
        task.failed.connect(
            lambda message: self.on_update_failed(progress, message)
        )
        task.start()

    def on_update_installed(self, progress, version):
        progress.close()
        self._available_ytdlp_version = None
        self.refresh_update_badge()
        self.show_status(
            f"✔ yt-dlp {version} is installed. Restart the app to use it.",
            "success",
        )

        answer = QMessageBox.question(
            self,
            "Update yt-dlp",
            f"yt-dlp {version} is installed.\n\n"
            "Restart the app now to use it?",
        )

        if answer == QMessageBox.StandardButton.Yes:
            self.restart_app()

    def show_about(self):
        dialog = AboutDialog(self)
        dialog.exec()

    def restart_app(self):
        if updater.restart():
            self.close()
        else:
            self.show_status(
                "Couldn't restart automatically. "
                "Please start the app again yourself.",
                "warning",
            )

    # ----- closing --------------------------------------------------------

    def closeEvent(self, event):
        app = QApplication.instance()
        if app is not None:
            try:
                app.styleHints().colorSchemeChanged.disconnect(
                    self.on_system_theme_changed
                )
            except (RuntimeError, TypeError):
                pass

        task = self.task

        if task is not None and task.isRunning():
            if self.state == "downloading":
                answer = QMessageBox.question(
                    self,
                    APP_NAME,
                    "A download is in progress. Cancel it and quit?",
                )

                if answer != QMessageBox.StandardButton.Yes:
                    event.ignore()
                    return

                if self.cancel_event is not None:
                    self.cancel_event.set()

            # Fetching can't be cancelled, so stop listening to it, hide the
            # window and let the task finish in the background.
            task.detach()
            self.hide()

            if not task.wait(CLOSE_WAIT_MS):
                # Stuck (for example, no network). Stopping it abruptly is
                # acceptable because the program is exiting.
                task.terminate()
                task.wait(2000)

        if hasattr(self, "_update_tray"):
            try:
                self._update_tray.hide()
            except Exception:
                pass
        event.accept()


def _set_windows_app_id():
    """Give Zardit its own Windows taskbar identity and icon."""

    if sys.platform != "win32":
        return
    try:
        import ctypes

        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(
            f"Cptmaverick22.{APP_NAME}.{APP_VERSION}"
        )
    except (AttributeError, OSError):
        pass


def main():
    setup_logging()
    _set_windows_app_id()
    app = QApplication(sys.argv)
    app.setStyle("Fusion")
    app.setApplicationName(APP_NAME)
    app.setDesktopFileName(APP_SLUG)
    icon = load_app_icon()
    if not icon.isNull():
        app.setWindowIcon(icon)

    window = MainWindow()
    window.show()

    sys.exit(app.exec())


if __name__ == "__main__":
    main()
