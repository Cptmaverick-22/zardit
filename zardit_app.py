"""Unified entry point for Zardit (GUI by default, --cli for terminal UI)."""

import sys


def main(argv=None):
    args = list(sys.argv[1:] if argv is None else argv)

    if "--cli" in args or "--diagnostics" in args:
        cleaned = [arg for arg in args if arg != "--cli"]
        sys.argv = [sys.argv[0], *cleaned]
        import main as cli_main

        return cli_main.run()

    import gui

    return gui.main()


if __name__ == "__main__":
    main()
