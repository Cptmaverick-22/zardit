"""Unified entry point for Zardit (GUI by default, --cli for terminal UI)."""

import os
from pathlib import Path
import sys


def configure_bundled_tools_path():
    """Prepend bundled tool directories (FFmpeg, Deno) to PATH for frozen builds."""

    if not getattr(sys, "frozen", False):
        return

    candidates = []
    exe_dir = Path(sys.executable).resolve().parent
    candidates.extend([exe_dir, exe_dir / "bin"])

    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        mei_dir = Path(meipass).resolve()
        candidates.extend([mei_dir, mei_dir / "bin"])

    current_parts = [p for p in os.environ.get("PATH", "").split(os.pathsep) if p]
    prepend = []
    for folder in candidates:
        folder_str = str(folder)
        if folder.is_dir() and folder_str not in current_parts and folder_str not in prepend:
            prepend.append(folder_str)

    if prepend:
        os.environ["PATH"] = os.pathsep.join([*prepend, *current_parts])


def _launched_as_cli():
    """Return True when invoked via a zardit-cli binary or symlink."""

    names = []
    if sys.argv and sys.argv[0]:
        names.append(Path(sys.argv[0]).stem.lower())
    if getattr(sys, "frozen", False):
        names.append(Path(sys.executable).stem.lower())
    return any(name == "zardit-cli" for name in names)


def main(argv=None):
    configure_bundled_tools_path()
    args = list(sys.argv[1:] if argv is None else argv)

    if _launched_as_cli() or "--cli" in args or "--diagnostics" in args:
        cleaned = [arg for arg in args if arg != "--cli"]
        sys.argv = [sys.argv[0], *cleaned]
        import main as cli_main

        return cli_main.run()

    import gui

    return gui.main()


if __name__ == "__main__":
    main()
