from __future__ import annotations

import argparse
import sys


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        prog="jvmind-mat-mcp",
        description="Local MCP server for Eclipse MAT heapdump analysis.",
    )
    parser.add_argument(
        "command",
        nargs="?",
        choices=["serve", "doctor", "uninstall"],
        default="serve",
        help="serve (default), doctor, or uninstall",
    )
    args = parser.parse_args(argv)
    if args.command == "serve":
        from .server import run

        run()
    elif args.command == "doctor":
        from .doctor import run_doctor

        sys.exit(run_doctor())
    elif args.command == "uninstall":
        from .doctor import run_uninstall

        sys.exit(run_uninstall())
