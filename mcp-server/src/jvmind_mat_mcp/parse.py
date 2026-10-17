from __future__ import annotations

import contextlib
import subprocess
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

from .mat_runtime import MatInstall

_PROGRESS_DOTS = 80


@dataclass(frozen=True)
class Phase:
    key: str
    start: float
    end: float


_PHASE_RULES: list[tuple[str, Phase]] = [
    ("reopening", Phase("Reopening", 0.00, 1.00)),
    ("scanning|parsing", Phase("Pass1 Scanning", 0.00, 0.35)),
    ("extracting objects", Phase("Pass2 Extracting", 0.35, 0.62)),
    ("unreachable|marking reachable", Phase("GarbageCleaner", 0.62, 0.70)),
    ("re-indexing", Phase("Re-indexing", 0.70, 0.78)),
    ("writing.*index", Phase("Writing index", 0.78, 0.82)),
    ("dominator tree|depth-first|computing dominators", Phase("DominatorTree", 0.82, 0.95)),
    ("retained size|dominators index", Phase("Retained sizes", 0.95, 0.99)),
]


class TextProgressMapper:
    def __init__(self) -> None:
        self.current: Phase | None = None
        self.dots_in_phase = 0
        self.last_progress = 0.0

    def feed(self, line: str) -> tuple[float | None, str | None, str | None]:
        stripped = line.strip()
        if stripped.startswith(("Task:", "Subtask:")):
            name = stripped.split(":", 1)[1].strip()
            phase = self._match(name)
            if phase is None:
                return None, None, None
            if self.current is not None and phase.start < self.current.start:
                return None, None, None
            self.current = phase
            self.dots_in_phase = 0
            self.last_progress = max(self.last_progress, phase.start)
            return self.last_progress, phase.key, None
        if stripped.startswith(("[INFO]", "[WARNING]", "[ERROR]", "[UNKNOWN]")):
            return None, None, stripped[:300]
        dots = stripped.lstrip("[")
        if dots and self.current and set(dots) == {"."}:
            self.dots_in_phase += len(dots)
            frac = min(1.0, self.dots_in_phase / _PROGRESS_DOTS)
            progress = self.current.start + (self.current.end - self.current.start) * frac
            self.last_progress = max(self.last_progress, round(progress, 3))
            return self.last_progress, self.current.key, None
        return None, None, None

    @staticmethod
    def _match(name: str) -> Phase | None:
        import re

        for pattern, phase in _PHASE_RULES:
            if re.search(pattern, name, re.IGNORECASE):
                return phase
        return None


def index_prefix(hprof: Path) -> str:
    return hprof.stem


def _is_index_for(hprof: Path, path: Path) -> bool:
    prefix = index_prefix(hprof)
    return path.is_file() and path.name.startswith(prefix + ".") and path.name.endswith(".index")


def index_files(hprof: Path) -> list[Path]:
    return sorted(path for path in hprof.parent.iterdir() if _is_index_for(hprof, path))


def indexes_ready(hprof: Path) -> bool:
    files = index_files(hprof)
    return bool(files) and all(path.stat().st_size > 0 for path in files)


def indexes_stale(hprof: Path) -> bool:
    files = index_files(hprof)
    if not files:
        return True
    newest = max(path.stat().st_mtime for path in files)
    return newest < hprof.stat().st_mtime


def clean_partial_indexes(hprof: Path) -> None:
    prefix = index_prefix(hprof)
    for path in hprof.parent.iterdir():
        if not path.is_file():
            continue
        name = path.name
        matched = _is_index_for(hprof, path) or name == f"{prefix}.threads"
        if matched:
            with contextlib.suppress(OSError):
                path.unlink()


@dataclass
class ParseResult:
    status: str
    returncode: int | None = None
    error: str | None = None


class ParseRun:
    """Run the MAT parse application and expose cancellation to the engine."""

    def __init__(
        self,
        install: MatInstall,
        hprof: Path,
        xmx: str,
        on_progress: Callable[[float, str], None],
        log_path: Path,
    ) -> None:
        self.install = install
        self.hprof = hprof
        self.xmx = xmx
        self.on_progress = on_progress
        self.log_path = log_path
        self._proc: subprocess.Popen[str] | None = None
        self._cancelled = threading.Event()
        self._lock = threading.Lock()

    def cancel(self) -> None:
        self._cancelled.set()
        with self._lock:
            proc = self._proc
        if proc is not None and proc.poll() is None:
            with contextlib.suppress(OSError):
                proc.terminate()

    def run(self) -> ParseResult:
        cmd = [
            self.install.java,
            f"-Xmx{self.xmx}",
            "-XX:+UseG1GC",
            "-jar",
            str(self.install.launcher),
            "-configuration",
            str(self.install.configuration_dir),
            "-consoleLog",
            "-nosplash",
            "-application",
            "org.eclipse.mat.api.parse",
            str(self.hprof),
        ]
        self.log_path.parent.mkdir(parents=True, exist_ok=True)
        start_offset = self.log_path.stat().st_size if self.log_path.exists() else 0
        mapper = TextProgressMapper()
        with self.log_path.open("a", encoding="utf-8", errors="replace") as log:
            log.write(
                f"\n=== {time.strftime('%Y-%m-%d %H:%M:%S')} parse "
                f"{self.hprof} (xmx={self.xmx}) ===\n"
            )
            log.flush()
            try:
                proc = subprocess.Popen(
                    cmd,
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    bufsize=1,
                    cwd=str(self.hprof.parent),
                    start_new_session=True,
                )
            except OSError as exc:
                return ParseResult(
                    "FAILED", error=f"无法启动 MAT 解析进程 / failed to start MAT: {exc}"
                )
            with self._lock:
                self._proc = proc
            assert proc.stdout is not None
            for line in proc.stdout:
                log.write(line)
                if self._cancelled.is_set():
                    break
                progress, phase, message = mapper.feed(line)
                if progress is not None:
                    self.on_progress(progress, phase or "")
                if message:
                    log.flush()
            if self._cancelled.is_set() and proc.poll() is None:
                with contextlib.suppress(OSError):
                    proc.terminate()
            returncode = proc.wait()
            log.flush()

        if self._cancelled.is_set():
            clean_partial_indexes(self.hprof)
            return ParseResult("CANCELLED", returncode)

        if returncode == 0 and indexes_ready(self.hprof):
            self.on_progress(1.0, "DONE")
            return ParseResult("DONE", returncode)

        clean_partial_indexes(self.hprof)
        with self.log_path.open("rb") as log:
            log.seek(start_offset)
            run_log = log.read().decode("utf-8", "replace")
        tail = "\n".join(line.strip()[:500] for line in run_log.splitlines()[-15:])
        if returncode == 79 or "OutOfMemoryError" in tail:
            hint = (
                f"MAT 解析内存不足（exit={returncode}）/ MAT ran out of memory. "
                f"请调大 JVMIND_MAT_XMX（当前 {self.xmx}）后重试 / "
                f"increase JVMIND_MAT_XMX (currently {self.xmx}) and retry."
            )
        elif returncode == 0:
            hint = (
                "MAT 解析进程正常退出但没有生成索引 / MAT exited successfully "
                "without producing index files."
            )
        else:
            hint = f"MAT 解析失败（exit={returncode}）/ MAT parse failed (exit={returncode})."
        return ParseResult("FAILED", returncode, f"{hint}\n{tail}".strip())
