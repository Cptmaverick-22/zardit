# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller specification for Zardit (Windows onedir bundle)."""

from pathlib import Path

ROOT = Path(SPECPATH).resolve().parent.parent
ICON_PATH = str(ROOT / "assets" / "zardit.ico")

_DATAS = [
    (ICON_PATH, "assets"),
    (str(ROOT / "assets" / "zardit.png"), "assets"),
    (str(ROOT / "assets" / "zardit.svg"), "assets"),
    (str(ROOT / "LICENSE"), "."),
]
if (ROOT / "build_info.json").is_file():
    _DATAS.append((str(ROOT / "build_info.json"), "."))

a = Analysis(
    [str(ROOT / "zardit_app.py")],
    pathex=[str(ROOT)],
    binaries=[],
    datas=_DATAS,
    hiddenimports=[
        "gui",
        "main",
        "config",
        "desktop",
        "downloader",
        "zardit_core",
        "zardit_core.app_updater",
        "zardit_core.errors",
        "zardit_core.facebook",
        "zardit_core.formats",
        "zardit_core.instagram",
        "zardit_core.naming",
        "zardit_core.urls",
        "history",
        "logs",
        "theme",
        "updater",
        "yt_dlp",
        "rich",
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        "PySide6.Qt3DAnimation",
        "PySide6.Qt3DCore",
        "PySide6.Qt3DExtras",
        "PySide6.Qt3DInput",
        "PySide6.Qt3DLogic",
        "PySide6.Qt3DRender",
        "PySide6.QtBluetooth",
        "PySide6.QtCharts",
        "PySide6.QtDataVisualization",
        "PySide6.QtGraphs",
        "PySide6.QtLocation",
        "PySide6.QtMultimedia",
        "PySide6.QtMultimediaWidgets",
        "PySide6.QtNetworkAuth",
        "PySide6.QtNfc",
        "PySide6.QtPdf",
        "PySide6.QtPdfWidgets",
        "PySide6.QtPositioning",
        "PySide6.QtQml",
        "PySide6.QtQuick",
        "PySide6.QtQuick3D",
        "PySide6.QtQuickWidgets",
        "PySide6.QtRemoteObjects",
        "PySide6.QtScxml",
        "PySide6.QtSensors",
        "PySide6.QtSerialBus",
        "PySide6.QtSerialPort",
        "PySide6.QtSpatialAudio",
        "PySide6.QtSql",
        "PySide6.QtStateMachine",
        "PySide6.QtTest",
        "PySide6.QtTextToSpeech",
        "PySide6.QtWebChannel",
        "PySide6.QtWebEngineCore",
        "PySide6.QtWebEngineQuick",
        "PySide6.QtWebEngineWidgets",
        "PySide6.QtWebSockets",
    ],
    noarchive=False,
    optimize=0,
)

pyz = PYZ(a.pure)

exe_gui = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="Zardit",
    icon=ICON_PATH,
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
)

exe_cli = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="zardit-cli",
    icon=ICON_PATH,
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,
)

coll = COLLECT(
    exe_gui,
    exe_cli,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    name="Zardit",
)
