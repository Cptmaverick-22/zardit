#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

USE_SYSTEM_TOOLS=0
for arg in "$@"; do
    case "$arg" in
        --use-system-tools) USE_SYSTEM_TOOLS=1 ;;
        *)
            echo "Unknown option: $arg" >&2
            exit 1
            ;;
    esac
done

PYTHON_BIN="python3"
if [[ -x "$ROOT_DIR/.venv/bin/python" ]]; then
    PYTHON_BIN="$ROOT_DIR/.venv/bin/python"
fi

if ! "$PYTHON_BIN" -m PyInstaller --version >/dev/null 2>&1; then
    echo "Error: PyInstaller is required in $PYTHON_BIN (pip install pyinstaller)." >&2
    exit 1
fi

VERSION="$("$PYTHON_BIN" -c 'import config; print(config.APP_VERSION)')"
ARCH="$(uname -m)"
CACHE_DIR="$ROOT_DIR/packaging/cache"
APPDIR="$ROOT_DIR/AppDir"
OUT_APPIMAGE="$ROOT_DIR/dist/Zardit-${VERSION}-${ARCH}.AppImage"

mkdir -p "$CACHE_DIR" "$ROOT_DIR/dist"
rm -rf "$APPDIR" "$ROOT_DIR/build/zardit" "$ROOT_DIR/dist/zardit"

echo "==> Building PyInstaller bundle for Zardit v${VERSION}..."
"$PYTHON_BIN" -m PyInstaller --noconfirm --clean "$ROOT_DIR/packaging/linux/zardit.spec"

echo "==> Assembling AppDir..."
mkdir -p \
    "$APPDIR/usr/bin" \
    "$APPDIR/usr/lib/zardit" \
    "$APPDIR/usr/share/applications" \
    "$APPDIR/usr/share/licenses/zardit" \
    "$APPDIR/usr/share/metainfo" \
    "$APPDIR/usr/share/icons/hicolor/256x256/apps" \
    "$APPDIR/usr/share/icons/hicolor/scalable/apps"

APP_ID="io.github.cptmaverick_22.zardit"

cp -a "$ROOT_DIR/dist/zardit/." "$APPDIR/usr/lib/zardit/"
cp "$ROOT_DIR/packaging/linux/AppRun" "$APPDIR/AppRun"
chmod +x "$APPDIR/AppRun"

cp "$ROOT_DIR/packaging/linux/zardit.desktop" "$APPDIR/${APP_ID}.desktop"
cp "$ROOT_DIR/packaging/linux/zardit.desktop" "$APPDIR/usr/share/applications/${APP_ID}.desktop"
cp "$ROOT_DIR/packaging/linux/${APP_ID}.appdata.xml" "$APPDIR/usr/share/metainfo/${APP_ID}.appdata.xml"
cp "$ROOT_DIR/LICENSE" "$APPDIR/usr/share/licenses/zardit/LICENSE"
cp "$ROOT_DIR/assets/zardit.png" "$APPDIR/zardit.png"
cp "$ROOT_DIR/assets/zardit.png" "$APPDIR/.DirIcon"
cp "$ROOT_DIR/assets/zardit.png" "$APPDIR/usr/share/icons/hicolor/256x256/apps/zardit.png"
cp "$ROOT_DIR/assets/zardit.svg" "$APPDIR/usr/share/icons/hicolor/scalable/apps/zardit.svg"

if [[ "$USE_SYSTEM_TOOLS" -eq 1 ]]; then
    echo "==> Copying system ffmpeg and deno..."
    cp "$(command -v ffmpeg)" "$APPDIR/usr/bin/ffmpeg"
    cp "$(command -v deno)" "$APPDIR/usr/bin/deno"
else
    FFMPEG_CACHE="$CACHE_DIR/ffmpeg"
    if [[ ! -x "$FFMPEG_CACHE" ]]; then
        echo "==> Downloading static FFmpeg (${ARCH})..."
        FFMPEG_ARCHIVE="$CACHE_DIR/ffmpeg.tar.xz"
        curl -fsSL "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-linux64-gpl.tar.xz" -o "$FFMPEG_ARCHIVE"
        FFMPEG_EXTRACT="$CACHE_DIR/ffmpeg-extract"
        rm -rf "$FFMPEG_EXTRACT"
        mkdir -p "$FFMPEG_EXTRACT"
        tar -xf "$FFMPEG_ARCHIVE" -C "$FFMPEG_EXTRACT"
        find "$FFMPEG_EXTRACT" -type f -name ffmpeg -exec cp {} "$FFMPEG_CACHE" \;
        rm -rf "$FFMPEG_EXTRACT" "$FFMPEG_ARCHIVE"
        chmod +x "$FFMPEG_CACHE"
    fi
    cp "$FFMPEG_CACHE" "$APPDIR/usr/bin/ffmpeg"

    DENO_CACHE="$CACHE_DIR/deno"
    if [[ ! -x "$DENO_CACHE" ]]; then
        echo "==> Downloading Deno (${ARCH})..."
        DENO_ZIP="$CACHE_DIR/deno.zip"
        curl -fsSL "https://github.com/denoland/deno/releases/latest/download/deno-${ARCH}-unknown-linux-gnu.zip" -o "$DENO_ZIP"
        "$PYTHON_BIN" -c "
import zipfile, sys
with zipfile.ZipFile(sys.argv[1]) as zf:
    zf.extract('deno', sys.argv[2])
" "$DENO_ZIP" "$CACHE_DIR"
        rm -f "$DENO_ZIP"
        chmod +x "$DENO_CACHE"
    fi
    cp "$DENO_CACHE" "$APPDIR/usr/bin/deno"
fi

chmod +x "$APPDIR/usr/bin/ffmpeg" "$APPDIR/usr/bin/deno"

APPIMAGETOOL="appimagetool"
if ! command -v "$APPIMAGETOOL" >/dev/null 2>&1; then
    TOOL_CACHE="$CACHE_DIR/appimagetool-${ARCH}.AppImage"
    if [[ ! -x "$TOOL_CACHE" ]]; then
        echo "==> Downloading appimagetool (${ARCH})..."
        curl -fsSL "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-${ARCH}.AppImage" -o "$TOOL_CACHE"
        chmod +x "$TOOL_CACHE"
    fi
    APPIMAGETOOL="$TOOL_CACHE"
fi

echo "==> Packing ${OUT_APPIMAGE}..."
rm -f "$OUT_APPIMAGE"
ARCH="$ARCH" "$APPIMAGETOOL" --appimage-extract-and-run "$APPDIR" "$OUT_APPIMAGE"
chmod +x "$OUT_APPIMAGE"

echo "==> Done: $OUT_APPIMAGE"
