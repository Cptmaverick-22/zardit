#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PYTHON_BIN="$ROOT_DIR/.venv/bin/python"
if [[ ! -x "$PYTHON_BIN" ]]; then
    PYTHON_BIN="$(command -v python3)"
fi

BIN_DIR="$HOME/.local/bin"
APP_DIR="$HOME/.local/share/applications"
ICON_256_DIR="$HOME/.local/share/icons/hicolor/256x256/apps"
ICON_SVG_DIR="$HOME/.local/share/icons/hicolor/scalable/apps"

mkdir -p "$BIN_DIR" "$APP_DIR" "$ICON_256_DIR" "$ICON_SVG_DIR"

cat > "$BIN_DIR/zardit" <<EOF
#!/usr/bin/env bash
exec "$PYTHON_BIN" "$ROOT_DIR/zardit_app.py" "\$@"
EOF

cat > "$BIN_DIR/zardit-cli" <<EOF
#!/usr/bin/env bash
exec "$PYTHON_BIN" "$ROOT_DIR/zardit_app.py" --cli "\$@"
EOF

chmod +x "$BIN_DIR/zardit" "$BIN_DIR/zardit-cli"

cp "$ROOT_DIR/assets/zardit.png" "$ICON_256_DIR/zardit.png"
cp "$ROOT_DIR/assets/zardit.svg" "$ICON_SVG_DIR/zardit.svg"

sed "s|^Exec=.*|Exec=$BIN_DIR/zardit %U|" \
    "$ROOT_DIR/packaging/linux/zardit.desktop" > "$APP_DIR/zardit.desktop"

if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$APP_DIR" >/dev/null 2>&1 || true
fi

if command -v gtk-update-icon-cache >/dev/null 2>&1; then
    gtk-update-icon-cache -q "$HOME/.local/share/icons/hicolor" >/dev/null 2>&1 || true
fi

echo "Installed Zardit desktop launcher and CLI commands:"
echo "  GUI:      $BIN_DIR/zardit"
echo "  Terminal: $BIN_DIR/zardit-cli"
echo "  Desktop:  $APP_DIR/zardit.desktop"
