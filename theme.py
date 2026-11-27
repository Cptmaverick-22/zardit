"""Shared Dark/Light/System theme tokens, QSS builder, and Rich themes."""

import os
from config import load_settings, remember_setting

THEME_MODES = ("system", "light", "dark")

THEME_LABELS = {
    "system": "Auto",
    "light": "Light",
    "dark": "Dark",
}


def normalize_theme_mode(value):
    """Return a valid theme mode ('system', 'light', or 'dark')."""

    if isinstance(value, str):
        cleaned = value.strip().lower()

        if cleaned == "auto":
            return "system"

        if cleaned in THEME_MODES:
            return cleaned

    return "system"


def get_saved_theme():
    """Load the persisted theme preference, defaulting to 'system'."""

    return normalize_theme_mode(load_settings().get("theme"))


def save_theme(mode):
    """Persist the chosen theme mode and return the normalized value."""

    normalized = normalize_theme_mode(mode)
    remember_setting("theme", normalized)
    return normalized


def cycle_theme_mode(current):
    """Return the next theme mode in sequence: system -> light -> dark."""

    normalized = normalize_theme_mode(current)
    index = THEME_MODES.index(normalized)
    return THEME_MODES[(index + 1) % len(THEME_MODES)]


def detect_terminal_theme():
    """Best-effort detection of 'light' or 'dark' terminal background.

    Inspects COLORFGBG (used by many terminal emulators as 'fg;bg') and
    returns 'light' for bright background indices, otherwise 'dark'.
    """

    colorfgbg = os.environ.get("COLORFGBG", "").strip()

    if colorfgbg:
        parts = [p for p in colorfgbg.replace(":", ";").split(";") if p.isdigit()]

        if parts:
            bg = int(parts[-1])

            if bg in (7, 15) or bg >= 248:
                return "light"

            return "dark"

    return "dark"


def resolve_terminal_theme(mode=None):
    """Resolve 'system'/None into 'light' or 'dark' for terminal rendering."""

    normalized = normalize_theme_mode(mode if mode is not None else get_saved_theme())

    if normalized == "system":
        return detect_terminal_theme()

    return normalized


# --------------------------------------------------------------------------
# GUI palettes & stylesheet
# --------------------------------------------------------------------------

GUI_PALETTES = {
    "light": {
        "bg": "#F4F6F9",
        "surface": "#FFFFFF",
        "surface_alt": "#F8FAFC",
        "surface_hover": "#F1F5F9",
        "input_bg": "#FFFFFF",
        "input_focus_bg": "#FFFFFF",
        "border": "#E2E8F0",
        "border_strong": "#CBD5E1",
        "divider": "#E8EDF3",
        "text": "#0F172A",
        "text_secondary": "#475569",
        "text_muted": "#64748B",
        "accent": "#DC2626",
        "accent_hover": "#B91C1C",
        "accent_pressed": "#991B1B",
        "accent_soft": "#FEF2F2",
        "accent_soft_border": "#FECACA",
        "accent_text": "#FFFFFF",
        "secondary_bg": "#FFFFFF",
        "secondary_hover": "#F8FAFC",
        "secondary_pressed": "#F1F5F9",
        "secondary_text": "#1E293B",
        "danger_bg": "#FEF2F2",
        "danger_hover": "#FEE2E2",
        "danger_border": "#FECACA",
        "danger_text": "#B91C1C",
        "thumb_bg": "#E2E8F0",
        "list_alt": "#F8FAFC",
        "list_selected": "#FEE2E2",
        "list_selected_text": "#7F1D1D",
        "progress_track": "#E2E8F0",
        "badge_bg": "#EEF2F6",
        "badge_text": "#334155",
        "segment_bg": "#E8EDF3",
        "segment_active_bg": "#FFFFFF",
        "segment_active_text": "#0F172A",
        "scrollbar": "#CBD5E1",
        "scrollbar_hover": "#94A3B8",
        "info_bg": "#EFF6FF",
        "info_border": "#BFDBFE",
        "info_text": "#1D4ED8",
        "success_bg": "#ECFDF5",
        "success_border": "#A7F3D0",
        "success_text": "#047857",
        "warning_bg": "#FFFBEB",
        "warning_border": "#FDE68A",
        "warning_text": "#B45309",
        "error_bg": "#FEF2F2",
        "error_border": "#FECACA",
        "error_text": "#B91C1C",
        "disabled_bg": "#E2E8F0",
        "disabled_text": "#94A3B8",
    },
    "dark": {
        "bg": "#0D1017",
        "surface": "#161B24",
        "surface_alt": "#1C222E",
        "surface_hover": "#232A38",
        "input_bg": "#11151D",
        "input_focus_bg": "#141923",
        "border": "#262D3B",
        "border_strong": "#374151",
        "divider": "#232936",
        "text": "#F1F5F9",
        "text_secondary": "#94A3B8",
        "text_muted": "#64748B",
        "accent": "#EF4444",
        "accent_hover": "#DC2626",
        "accent_pressed": "#B91C1C",
        "accent_soft": "#2A161B",
        "accent_soft_border": "#7F1D1D",
        "accent_text": "#FFFFFF",
        "secondary_bg": "#1E2532",
        "secondary_hover": "#273041",
        "secondary_pressed": "#181E29",
        "secondary_text": "#E2E8F0",
        "danger_bg": "#2B161B",
        "danger_hover": "#3B1C23",
        "danger_border": "#7F1D1D",
        "danger_text": "#FCA5A5",
        "thumb_bg": "#1E2532",
        "list_alt": "#191F2A",
        "list_selected": "#3A1C22",
        "list_selected_text": "#FECACA",
        "progress_track": "#252C3A",
        "badge_bg": "#222938",
        "badge_text": "#CBD5E1",
        "segment_bg": "#12161F",
        "segment_active_bg": "#252D3D",
        "segment_active_text": "#F8FAFC",
        "scrollbar": "#333C4D",
        "scrollbar_hover": "#4B5568",
        "info_bg": "#142038",
        "info_border": "#1E3A8A",
        "info_text": "#93C5FD",
        "success_bg": "#102822",
        "success_border": "#065F46",
        "success_text": "#6EE7B7",
        "warning_bg": "#2B2110",
        "warning_border": "#78350F",
        "warning_text": "#FCD34D",
        "error_bg": "#2D1519",
        "error_border": "#7F1D1D",
        "error_text": "#FCA5A5",
        "disabled_bg": "#1E2430",
        "disabled_text": "#64748B",
    },
}


def build_qt_stylesheet(theme_name):
    """Generate a modern QSS stylesheet for 'light' or 'dark' theme."""

    c = GUI_PALETTES["dark" if theme_name == "dark" else "light"]

    return f"""
    QWidget {{
        color: {c["text"]};
        font-size: 13px;
    }}

    QWidget#mainWindow, QWidget#scrollContent {{
        background-color: {c["bg"]};
    }}

    QScrollArea#mainScroll {{
        background-color: {c["bg"]};
        border: none;
    }}

    QLabel {{
        background-color: transparent;
    }}

    QLabel#appTitle {{
        font-size: 21px;
        font-weight: 700;
        color: {c["text"]};
    }}

    QLabel#appSubtitle {{
        font-size: 12px;
        color: {c["text_secondary"]};
    }}

    QLabel#versionBadge {{
        background-color: {c["badge_bg"]};
        color: {c["badge_text"]};
        border: 1px solid {c["border"]};
        border-radius: 9px;
        padding: 2px 8px;
        font-size: 11px;
        font-weight: 600;
    }}

    QLabel#sectionTitle {{
        font-size: 12px;
        font-weight: 600;
        color: {c["text_secondary"]};
    }}

    QLabel#fieldLabel {{
        font-size: 12px;
        font-weight: 600;
        color: {c["text_secondary"]};
    }}

    QLabel#mediaTitle {{
        font-size: 15px;
        font-weight: 700;
        color: {c["text"]};
    }}

    QLabel#mediaMeta {{
        font-size: 12.5px;
        color: {c["text_secondary"]};
    }}

    QLabel#typeBadge {{
        background-color: {c["accent_soft"]};
        color: {c["accent"]};
        border: 1px solid {c["accent_soft_border"]};
        border-radius: 6px;
        padding: 2px 8px;
        font-size: 10.5px;
        font-weight: 700;
        letter-spacing: 0.6px;
    }}

    QLabel#audioBadge {{
        background-color: {c["info_bg"]};
        color: {c["info_text"]};
        border: 1px solid {c["info_border"]};
        border-radius: 6px;
        padding: 2px 8px;
        font-size: 10.5px;
        font-weight: 700;
        letter-spacing: 0.6px;
    }}

    QLabel#imageBadge {{
        background-color: {c["success_bg"]};
        color: {c["success_text"]};
        border: 1px solid {c["success_border"]};
        border-radius: 6px;
        padding: 2px 8px;
        font-size: 10.5px;
        font-weight: 700;
        letter-spacing: 0.6px;
    }}

    QLabel#missingTag {{
        color: {c["warning_text"]};
        font-size: 11.5px;
        font-weight: 600;
    }}

    QLabel#countBadge {{
        background-color: {c["badge_bg"]};
        color: {c["badge_text"]};
        border-radius: 8px;
        padding: 3px 10px;
        font-size: 12px;
        font-weight: 600;
    }}

    QLabel#mutedText {{
        color: {c["text_secondary"]};
        font-size: 12.5px;
    }}

    QLabel#progressDetail {{
        color: {c["text_secondary"]};
        font-size: 12px;
    }}

    QLabel#thumbPlaceholder {{
        background-color: {c["thumb_bg"]};
        color: {c["text_secondary"]};
        border: 1px solid {c["border"]};
        border-radius: 8px;
        font-size: 11px;
        font-weight: 700;
        letter-spacing: 2px;
    }}

    QLabel#historyThumbFallback {{
        background-color: {c["surface_alt"]};
        color: {c["text_secondary"]};
        border: 1px solid {c["border"]};
        border-radius: 7px;
        font-size: 10px;
        font-weight: 700;
        letter-spacing: 0.8px;
    }}

    QFrame#recentTile {{
        background-color: {c["surface_alt"]};
        border: 1px solid {c["border"]};
        border-radius: 10px;
    }}

    QFrame#card, QFrame#mediaCard, QFrame#heroCard {{
        background-color: {c["surface"]};
        border: 1px solid {c["border"]};
        border-radius: 12px;
    }}

    QLabel#heroTitle {{
        font-size: 16px;
        font-weight: 700;
        color: {c["text"]};
    }}

    QLabel#heroBody {{
        font-size: 13px;
        color: {c["text_secondary"]};
    }}

    QLabel#featurePill {{
        background-color: {c["surface_alt"]};
        color: {c["text_secondary"]};
        border: 1px solid {c["border"]};
        border-radius: 8px;
        padding: 5px 10px;
        font-size: 11.5px;
        font-weight: 600;
    }}

    QFrame#insetBar {{
        background-color: {c["surface_alt"]};
        border: 1px solid {c["border"]};
        border-radius: 10px;
    }}

    QFrame#divider {{
        background-color: {c["divider"]};
        border: none;
        max-height: 1px;
        min-height: 1px;
    }}

    QFrame#segmentGroup {{
        background-color: {c["segment_bg"]};
        border: 1px solid {c["border"]};
        border-radius: 9px;
    }}

    QPushButton#segmentBtn {{
        background-color: transparent;
        color: {c["text_secondary"]};
        border: none;
        border-radius: 7px;
        padding: 5px 10px;
        font-size: 12px;
        font-weight: 600;
    }}

    QPushButton#segmentBtn:hover {{
        color: {c["text"]};
        background-color: {c["surface_hover"]};
    }}

    QPushButton#segmentBtn:checked {{
        background-color: {c["segment_active_bg"]};
        color: {c["segment_active_text"]};
        border: 1px solid {c["border"]};
    }}

    QLineEdit {{
        background-color: {c["input_bg"]};
        color: {c["text"]};
        border: 1px solid {c["border_strong"]};
        border-radius: 8px;
        padding: 7px 12px;
        min-height: 22px;
        selection-background-color: {c["accent"]};
        selection-color: {c["accent_text"]};
    }}

    QLineEdit:hover {{
        border-color: {c["text_muted"]};
    }}

    QLineEdit:focus {{
        background-color: {c["input_focus_bg"]};
        border: 1.5px solid {c["accent"]};
    }}

    QLineEdit:disabled {{
        background-color: {c["disabled_bg"]};
        color: {c["disabled_text"]};
        border-color: {c["border"]};
    }}

    QComboBox {{
        background-color: {c["input_bg"]};
        color: {c["text"]};
        border: 1px solid {c["border_strong"]};
        border-radius: 8px;
        padding: 7px 12px;
        min-height: 22px;
    }}

    QComboBox:hover {{
        border-color: {c["text_muted"]};
    }}

    QComboBox:focus {{
        border: 1.5px solid {c["accent"]};
    }}

    QComboBox:disabled {{
        background-color: {c["disabled_bg"]};
        color: {c["disabled_text"]};
        border-color: {c["border"]};
    }}

    QComboBox::drop-down {{
        subcontrol-origin: padding;
        subcontrol-position: top right;
        width: 28px;
        border: none;
    }}

    QComboBox QAbstractItemView {{
        background-color: {c["surface"]};
        color: {c["text"]};
        border: 1px solid {c["border_strong"]};
        border-radius: 8px;
        padding: 4px;
        selection-background-color: {c["accent_soft"]};
        selection-color: {c["text"]};
        outline: none;
    }}

    QRadioButton#pillRadio {{
        background-color: {c["surface_alt"]};
        color: {c["text_secondary"]};
        border: 1px solid {c["border"]};
        border-radius: 8px;
        padding: 7px 16px;
        font-weight: 600;
        spacing: 8px;
    }}

    QRadioButton#pillRadio:hover {{
        background-color: {c["surface_hover"]};
        color: {c["text"]};
    }}

    QRadioButton#pillRadio:checked {{
        background-color: {c["accent_soft"]};
        color: {c["text"]};
        border: 1.5px solid {c["accent"]};
    }}

    QRadioButton#pillRadio:disabled {{
        background-color: {c["disabled_bg"]};
        color: {c["disabled_text"]};
        border-color: {c["border"]};
    }}

    QRadioButton#pillRadio::indicator {{
        width: 14px;
        height: 14px;
        border-radius: 8px;
        border: 1.5px solid {c["border_strong"]};
        background-color: {c["input_bg"]};
    }}

    QRadioButton#pillRadio::indicator:checked {{
        border: 4px solid {c["accent"]};
        background-color: {c["accent_text"]};
    }}

    QPushButton {{
        background-color: {c["secondary_bg"]};
        color: {c["secondary_text"]};
        border: 1px solid {c["border_strong"]};
        border-radius: 8px;
        padding: 7px 14px;
        font-weight: 600;
    }}

    QPushButton:hover {{
        background-color: {c["secondary_hover"]};
        border-color: {c["text_muted"]};
    }}

    QPushButton:pressed {{
        background-color: {c["secondary_pressed"]};
    }}

    QPushButton:disabled {{
        background-color: {c["disabled_bg"]};
        color: {c["disabled_text"]};
        border-color: {c["border"]};
    }}

    QPushButton#primaryBtn {{
        background-color: {c["accent"]};
        color: {c["accent_text"]};
        border: 1px solid {c["accent"]};
        border-radius: 8px;
        padding: 9px 22px;
        font-weight: 700;
    }}

    QPushButton#primaryBtn:hover {{
        background-color: {c["accent_hover"]};
        border-color: {c["accent_hover"]};
    }}

    QPushButton#primaryBtn:pressed {{
        background-color: {c["accent_pressed"]};
        border-color: {c["accent_pressed"]};
    }}

    QPushButton#primaryBtn:disabled {{
        background-color: {c["disabled_bg"]};
        color: {c["disabled_text"]};
        border-color: {c["border"]};
    }}

    QPushButton#accentSoftBtn {{
        background-color: {c["accent_soft"]};
        color: {c["accent"]};
        border: 1px solid {c["accent_soft_border"]};
        border-radius: 8px;
        padding: 7px 14px;
        font-weight: 700;
    }}

    QPushButton#accentSoftBtn:hover {{
        background-color: {c["accent"]};
        color: {c["accent_text"]};
        border-color: {c["accent"]};
    }}

    QPushButton#accentSoftBtn:pressed {{
        background-color: {c["accent_pressed"]};
        color: {c["accent_text"]};
        border-color: {c["accent_pressed"]};
    }}

    QPushButton#accentSoftBtn:disabled {{
        background-color: {c["disabled_bg"]};
        color: {c["disabled_text"]};
        border-color: {c["border"]};
    }}

    QPushButton#dangerBtn {{
        background-color: {c["danger_bg"]};
        color: {c["danger_text"]};
        border: 1px solid {c["danger_border"]};
        border-radius: 8px;
        padding: 9px 18px;
        font-weight: 700;
    }}

    QPushButton#dangerBtn:hover {{
        background-color: {c["danger_hover"]};
    }}

    QPushButton#compactBtn {{
        background-color: {c["secondary_bg"]};
        color: {c["secondary_text"]};
        border: 1px solid {c["border"]};
        border-radius: 7px;
        padding: 5px 11px;
        font-size: 12px;
        font-weight: 600;
    }}

    QPushButton#compactBtn:hover {{
        background-color: {c["surface_hover"]};
    }}

    QPushButton#updateBtn {{
        background-color: {c["surface_alt"]};
        color: {c["text_secondary"]};
        border: 1px solid {c["border"]};
        border-radius: 8px;
        padding: 6px 12px;
        font-size: 12px;
        font-weight: 600;
    }}

    QPushButton#updateBtn:hover {{
        background-color: {c["surface_hover"]};
        color: {c["text"]};
    }}

    QPushButton#updateBtn[updateAvailable="true"] {{
        background-color: {c["warning_bg"]};
        color: {c["warning_text"]};
        border: 1px solid {c["warning_border"]};
    }}

    QListWidget {{
        background-color: {c["input_bg"]};
        color: {c["text"]};
        border: 1px solid {c["border"]};
        border-radius: 9px;
        padding: 4px;
        outline: none;
    }}

    QListWidget::item {{
        padding: 6px 8px;
        border-radius: 6px;
    }}

    QListWidget::item:hover {{
        background-color: {c["surface_hover"]};
    }}

    QListWidget::item:selected {{
        background-color: {c["list_selected"]};
        color: {c["list_selected_text"]};
    }}

    QListWidget#formatList, QListWidget#historyList {{
        background-color: {c["surface_alt"]};
        border: 1px solid {c["border"]};
        border-radius: 10px;
        padding: 4px;
        outline: none;
    }}

    QListWidget#formatList::item, QListWidget#historyList::item {{
        background-color: transparent;
        border: 1px solid transparent;
        border-radius: 8px;
        margin: 2px 0px;
        padding: 0px;
    }}

    QListWidget#formatList::item:hover, QListWidget#historyList::item:hover {{
        background-color: {c["surface_hover"]};
    }}

    QListWidget#formatList::item:selected {{
        background-color: {c["accent_soft"]};
        border: 1px solid {c["accent"]};
    }}

    QListWidget#historyList::item:selected {{
        background-color: {c["surface_hover"]};
        border: 1px solid {c["border_strong"]};
    }}

    QLabel#formatRadio {{
        min-width: 14px;
        max-width: 14px;
        min-height: 14px;
        max-height: 14px;
        border-radius: 8px;
        border: 1.5px solid {c["border_strong"]};
        background-color: {c["input_bg"]};
    }}

    QLabel#formatRadio[selected="true"] {{
        border: 4px solid {c["accent"]};
        background-color: {c["accent_text"]};
    }}

    QLabel#formatTitle {{
        font-size: 13.5px;
        font-weight: 700;
        color: {c["text"]};
    }}

    QLabel#formatDetail {{
        font-size: 11.5px;
        color: {c["text_secondary"]};
    }}

    QLabel#formatBadge {{
        background-color: {c["badge_bg"]};
        color: {c["badge_text"]};
        border-radius: 6px;
        padding: 2px 7px;
        font-size: 10.5px;
        font-weight: 700;
    }}

    QLabel#formatBadge[selected="true"] {{
        background-color: {c["accent"]};
        color: {c["accent_text"]};
    }}

    QLabel#formatSize {{
        font-size: 12.5px;
        font-weight: 600;
        color: {c["text_secondary"]};
    }}

    QProgressBar {{
        background-color: {c["progress_track"]};
        border: none;
        border-radius: 5px;
    }}

    QProgressBar::chunk {{
        background-color: {c["accent"]};
        border-radius: 5px;
    }}

    QFrame#statusBanner {{
        border-radius: 10px;
        border: 1px solid {c["info_border"]};
        background-color: {c["info_bg"]};
    }}

    QFrame#statusBanner[statusLevel="info"] {{
        border-color: {c["info_border"]};
        background-color: {c["info_bg"]};
    }}

    QFrame#statusBanner[statusLevel="success"] {{
        border-color: {c["success_border"]};
        background-color: {c["success_bg"]};
    }}

    QFrame#statusBanner[statusLevel="warning"] {{
        border-color: {c["warning_border"]};
        background-color: {c["warning_bg"]};
    }}

    QFrame#statusBanner[statusLevel="error"] {{
        border-color: {c["error_border"]};
        background-color: {c["error_bg"]};
    }}

    QLabel#statusText[statusLevel="info"] {{
        color: {c["info_text"]};
        font-weight: 600;
    }}

    QLabel#statusText[statusLevel="success"] {{
        color: {c["success_text"]};
        font-weight: 600;
    }}

    QLabel#statusText[statusLevel="warning"] {{
        color: {c["warning_text"]};
        font-weight: 600;
    }}

    QLabel#statusText[statusLevel="error"] {{
        color: {c["error_text"]};
        font-weight: 600;
    }}

    QScrollBar:vertical {{
        background: transparent;
        width: 8px;
        margin: 2px;
    }}

    QScrollBar::handle:vertical {{
        background: {c["scrollbar"]};
        border-radius: 4px;
        min-height: 28px;
    }}

    QScrollBar::handle:vertical:hover {{
        background: {c["scrollbar_hover"]};
    }}

    QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical,
    QScrollBar::add-page:vertical, QScrollBar::sub-page:vertical {{
        background: none;
        height: 0px;
    }}

    QMessageBox, QProgressDialog {{
        background-color: {c["surface"]};
        color: {c["text"]};
    }}
    """


# --------------------------------------------------------------------------
# Terminal (Rich) palettes
# --------------------------------------------------------------------------

TERMINAL_PALETTES = {
    "dark": {
        "brand": "bold bright_red",
        "heading": "bold bright_white",
        "accent": "bright_cyan",
        "accent.bold": "bold bright_cyan",
        "muted": "grey66",
        "border": "grey42",
        "card.border": "cyan",
        "menu.border": "bright_black",
        "success": "bold bright_green",
        "warning": "bold gold1",
        "error": "bold bright_red",
        "info": "bright_cyan",
        "badge": "bold black on bright_cyan",
        "pill.ok": "bold green",
        "pill.warn": "bold gold1",
        "prompt": "bold bright_white",
        "progress.bar": "bright_red",
        "progress.complete": "bright_green",
    },
    "light": {
        "brand": "bold red3",
        "heading": "bold grey15",
        "accent": "blue3",
        "accent.bold": "bold blue3",
        "muted": "grey42",
        "border": "grey66",
        "card.border": "steel_blue",
        "menu.border": "grey58",
        "success": "bold dark_green",
        "warning": "bold dark_orange3",
        "error": "bold red3",
        "info": "blue3",
        "badge": "bold white on blue3",
        "pill.ok": "bold dark_green",
        "pill.warn": "bold dark_orange3",
        "prompt": "bold grey15",
        "progress.bar": "red3",
        "progress.complete": "dark_green",
    },
}
