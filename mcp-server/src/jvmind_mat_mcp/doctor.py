from __future__ import annotations

import contextlib
import os
import shutil
from pathlib import Path

from . import QUERY_BUNDLE_NAME, __version__, mat_runtime
from .config import Settings
from .mat_runtime import MatError
from .store import StateStore


def _ok(message: str) -> str:
    return f"  [ok] {message}"


def _warn(message: str) -> str:
    return f"  [!!] {message}"


def _info(message: str) -> str:
    return f"  [..] {message}"


def run_doctor() -> int:
    settings = Settings.from_env()
    lines = [f"JVMind MAT MCP {__version__}", ""]
    failures = 0
    try:
        install = mat_runtime.detect(settings)
    except MatError as exc:
        lines.append(_warn(str(exc)))
        failures += 1
        install = None

    if install is not None:
        lines.append(_ok(f"Eclipse MAT {install.version}: {install.home}"))
        lines.append(_ok(f"Equinox launcher: {install.launcher.name}"))
        lines.append(_ok(f"Java {install.java_major}: {install.java}"))
        source = Path(__file__).resolve().parent / "vendor" / QUERY_BUNDLE_NAME
        if source.is_file():
            lines.append(_ok(f"Bundled query service: {QUERY_BUNDLE_NAME}"))
        else:
            lines.append(_warn(f"Bundled query service missing: {source}"))
            failures += 1
        if os.access(install.plugins_dir, os.W_OK):
            lines.append(_ok(f"MAT plugins directory is writable: {install.plugins_dir}"))
        else:
            lines.append(_warn(f"MAT plugins directory is not writable: {install.plugins_dir}"))
            failures += 1
        registered = False
        with contextlib.suppress(OSError):
            registered = any(
                line.startswith("com.jvmind.mat.query,")
                for line in install.bundles_info.read_text(
                    encoding="utf-8", errors="replace"
                ).splitlines()
            )
        lines.append(
            _ok("Query bundle is registered")
            if registered
            else _info("Query bundle is not installed yet (installed on first use)")
        )

    try:
        settings.state_dir.mkdir(parents=True, exist_ok=True)
        lines.append(_ok(f"State directory: {settings.state_dir}"))
    except OSError as exc:
        lines.append(_warn(f"State directory is not writable: {settings.state_dir}: {exc}"))
        failures += 1

    lines.extend(
        [
            "",
            f"Command: {settings.java}",
            f"MAT heap: -Xmx{settings.xmx}",
            f"Query service: {settings.service_url or 'managed locally'}",
            f"State store version: {StateStore(settings.state_dir).read().get('version')}",
            "",
        ]
    )
    if failures:
        lines.append("Doctor found problems. Fix the [!!] items and re-run.")
    else:
        lines.append("Ready. Start the server with: jvmind-mat-mcp")
    print("\n".join(lines))
    return 1 if failures else 0


def run_uninstall() -> int:
    settings = Settings.from_env()
    try:
        install = mat_runtime.detect(settings)
    except MatError as exc:
        print(f"[错误 / error] {exc}")
        return 1
    try:
        changed = mat_runtime.uninstall_bundle(install)
    except MatError as exc:
        print(f"[错误 / error] {exc}")
        return 1
    if changed:
        print(f"Removed the JVMind query bundle from {install.plugins_dir}.")
    else:
        print(f"No JVMind query bundle found in {install.plugins_dir}.")
    state_dir = settings.state_dir
    if state_dir.exists():
        shutil.rmtree(state_dir, ignore_errors=True)
        print(f"Removed state directory {state_dir}.")
    return 0
