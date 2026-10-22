from __future__ import annotations

import os
import re
import shutil
import subprocess
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

from .config import Settings
from .mat_runtime import MatError

_HELPER_MARKERS = (
    "sun.tools.jps.Jps",
    "sun.tools.jcmd.JCmd",
    "jdk.jcmd/sun.tools.jps.Jps",
    "jdk.jcmd/sun.tools.jcmd.JCmd",
)
_MAT_MARKER = "com.jvmind.mat.query.app"
_MAX_HEAP_RE = re.compile(r"-XX:MaxHeapSize=(\d+)")
_SECRET_WORD = r"(?:password|passwd|pwd|secret|token|api[_-]?key|credential|auth)"
_SECRET_D_RE = re.compile(rf"(?i)(-D[^\s=]*{_SECRET_WORD}[^\s=]*=)(\S+)")
_SECRET_FLAG_RE = re.compile(rf"(?i)(--?[^\s=]*{_SECRET_WORD}[^\s=]*[= ])(\S+)")


@dataclass(frozen=True)
class JavaProcess:
    pid: int
    main_class: str
    command: str
    jvm_args: tuple[str, ...] = ()

    def to_dict(self) -> dict[str, object]:
        return {
            "pid": self.pid,
            "mainClass": self.main_class,
            "command": mask_secrets(self.command),
            "jvmArgs": [mask_secrets(arg) for arg in self.jvm_args],
        }


def mask_secrets(text: str) -> str:
    text = _SECRET_D_RE.sub(lambda match: f"{match.group(1)}***", text)
    return _SECRET_FLAG_RE.sub(lambda match: f"{match.group(1)}***", text)


def format_bytes(value: int | None) -> str:
    if value is None:
        return "unknown"
    number = float(value)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if abs(number) < 1024 or unit == "TB":
            return f"{number:.1f} {unit}"
        number /= 1024
    return f"{number:.1f} PB"


def _run(cmd: list[str], timeout: float = 20.0) -> str:
    try:
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
    except FileNotFoundError as exc:
        raise MatError(
            f"找不到 JDK 工具 {cmd[0]} / JDK tool not found: {cmd[0]}. "
            f"Install a JDK 21 and set JVMIND_JAVA or JAVA_HOME."
        ) from exc
    except subprocess.TimeoutExpired as exc:
        raise MatError(f"执行 {' '.join(cmd)} 超时 / timed out: {' '.join(cmd)}") from exc
    except OSError as exc:
        raise MatError(f"无法执行 {cmd[0]}: {exc} / cannot run {cmd[0]}: {exc}") from exc
    output = (proc.stdout or "") + (proc.stderr or "")
    if proc.returncode != 0 and not output.strip():
        raise MatError(
            f"{cmd[0]} 退出码 {proc.returncode} / {cmd[0]} exited with {proc.returncode}"
        )
    return output


def _jvm_args(rest: str) -> tuple[str, ...]:
    return tuple(
        token
        for token in rest.split()
        if token.startswith(("-X", "-D", "-XX:", "-agent", "--add-"))
    )


def _is_filtered(pid: int, command: str, exclude_pids: set[int]) -> bool:
    if pid <= 0 or pid in exclude_pids:
        return True
    if any(marker in command for marker in _HELPER_MARKERS):
        return True
    return _MAT_MARKER in command


def parse_jcmd_list(output: str, exclude_pids: set[int] | None = None) -> list[JavaProcess]:
    exclude = exclude_pids or set()
    processes: list[JavaProcess] = []
    for line in output.splitlines():
        line = line.strip()
        if not line:
            continue
        parts = line.split(None, 2)
        if len(parts) < 2:
            continue
        try:
            pid = int(parts[0])
        except ValueError:
            continue
        if _is_filtered(pid, line, exclude):
            continue
        main_class = parts[1]
        rest = parts[2] if len(parts) > 2 else ""
        processes.append(JavaProcess(pid, main_class, line, _jvm_args(rest)))
    return processes


def parse_jps_list(output: str, exclude_pids: set[int] | None = None) -> list[JavaProcess]:
    exclude = exclude_pids or set()
    processes: list[JavaProcess] = []
    for line in output.splitlines():
        line = line.strip()
        if not line or "process information unavailable" in line:
            continue
        parts = line.split(None, 2)
        if len(parts) < 2:
            continue
        try:
            pid = int(parts[0])
        except ValueError:
            continue
        if _is_filtered(pid, line, exclude):
            continue
        main_class = parts[1]
        rest = parts[2] if len(parts) > 2 else ""
        processes.append(JavaProcess(pid, main_class, line, _jvm_args(rest)))
    return processes


def discover_processes(
    settings: Settings,
    exclude_pids: set[int] | None = None,
) -> list[JavaProcess]:
    exclude = set(exclude_pids or ())
    errors: list[str] = []
    merged: dict[int, JavaProcess] = {}
    jcmd_ok = False

    try:
        for process in parse_jcmd_list(_run([settings.jcmd, "-l"]), exclude):
            merged[process.pid] = process
        jcmd_ok = True
    except MatError as exc:
        errors.append(str(exc))

    try:
        for process in parse_jps_list(_run([settings.jps, "-lvm"]), exclude):
            existing = merged.get(process.pid)
            if existing is not None:
                merged[process.pid] = JavaProcess(
                    process.pid,
                    existing.main_class,
                    process.command or existing.command,
                    process.jvm_args or existing.jvm_args,
                )
            elif not jcmd_ok:
                merged[process.pid] = process
    except MatError as exc:
        errors.append(str(exc))

    if not merged and not jcmd_ok and errors:
        raise MatError(
            "无法发现 Java 进程。请确认 JDK 21 的 jcmd/jps 可用，并且当前用户有权 "
            f"attach。\nCould not discover Java processes: {'; '.join(errors)}"
        )
    return sorted(merged.values(), key=lambda process: process.pid)


def estimate_max_heap(settings: Settings, pid: int) -> int | None:
    try:
        output = _run([settings.jcmd, str(pid), "VM.flags"])
    except MatError:
        return None
    match = _MAX_HEAP_RE.search(output)
    return int(match.group(1)) if match else None


def resolve_output_dir(settings: Settings, output_dir: str | None) -> Path:
    raw = output_dir.strip() if output_dir and output_dir.strip() else str(settings.dump_dir)
    return Path(raw).expanduser().resolve()


def prepare_output_dir(directory: Path) -> None:
    try:
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    except OSError as exc:
        raise MatError(f"无法创建 dump 目录 {directory}: {exc}") from exc
    if not directory.is_dir():
        raise MatError(f"dump 路径不是目录 / dump path is not a directory: {directory}")
    if not os.access(directory, os.W_OK):
        raise MatError(f"dump 目录不可写 / dump directory is not writable: {directory}")


def disk_free(directory: Path) -> int:
    probe = directory
    while not probe.exists() and probe != probe.parent:
        probe = probe.parent
    return shutil.disk_usage(probe).free


def check_space(
    directory: Path,
    estimated_bytes: int | None,
    min_free_bytes: int,
) -> tuple[int, str | None]:
    free = disk_free(directory)
    required = max(min_free_bytes, estimated_bytes or 0)
    if free < required:
        raise MatError(
            f"剩余空间不足：{format_bytes(free)} 可用，至少需要 {format_bytes(required)}。"
            f" / Not enough disk space: {format_bytes(free)} free, "
            f"at least {format_bytes(required)} required."
        )
    warning = None
    if estimated_bytes and free < estimated_bytes * 2:
        warning = (
            f"剩余空间 {format_bytes(free)} 低于估算 dump 的 2 倍"
            f"（{format_bytes(estimated_bytes * 2)}）；MAT 索引可能写满磁盘。"
            f" / Free space is below 2x the estimated dump size; MAT indexes may "
            f"fill the disk."
        )
    return free, warning


def unique_dump_path(directory: Path, pid: int, main_class: str) -> Path:
    safe = re.sub(r"[^A-Za-z0-9._-]+", "_", main_class).strip("._-")[:60] or "java"
    stamp = time.strftime("%Y%m%d-%H%M%S")
    stem = f"{safe}-{pid}-{stamp}"
    candidate = directory / f"{stem}.hprof"
    index = 1
    while candidate.exists():
        candidate = directory / f"{stem}-{index}.hprof"
        index += 1
    return candidate


def live_dump_command(settings: Settings, pid: int, path: Path) -> list[str]:
    if "," in str(path):
        raise MatError(
            f"jmap 不支持包含逗号的输出路径 / jmap cannot use a path containing commas: {path}"
        )
    return [settings.jmap, f"-dump:live,format=b,file={path}", str(pid)]


def dump_live_heap(
    settings: Settings,
    pid: int,
    path: Path,
    timeout: float,
    log_path: Path,
    on_start: Callable[[subprocess.Popen[str]], None] | None = None,
) -> None:
    cmd = live_dump_command(settings, pid, path)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("a", encoding="utf-8") as log:
        log.write(
            f"\n=== {time.strftime('%Y-%m-%d %H:%M:%S')} dumping pid {pid} to {path} (live) ===\n"
        )
        log.flush()
        try:
            proc = subprocess.Popen(
                cmd,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                start_new_session=True,
            )
        except FileNotFoundError as exc:
            raise MatError(
                f"找不到 jmap: {settings.jmap}. Install a JDK 21 and set JVMIND_JMAP / JVMIND_JAVA."
            ) from exc
        except OSError as exc:
            raise MatError(f"无法执行 jmap: {exc}") from exc

        if on_start is not None:
            on_start(proc)

        try:
            output, _ = proc.communicate(timeout=timeout)
        except subprocess.TimeoutExpired as exc:
            proc.terminate()
            try:
                output, _ = proc.communicate(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
                output, _ = proc.communicate()
            log.write((output or "") + "\n")
            raise MatError(
                f"jmap 超过 {int(timeout)} 秒未完成，已终止 / jmap timed out after "
                f"{int(timeout)}s and was terminated."
            ) from exc

        log.write(output or "")
        log.flush()
        if proc.returncode != 0:
            tail = "\n".join((output or "").splitlines()[-20:])
            raise MatError(
                f"jmap 失败（exit={proc.returncode}）/ jmap failed "
                f"(exit={proc.returncode}):\n{tail}"
            )
        if not path.is_file() or path.stat().st_size == 0:
            tail = "\n".join((output or "").splitlines()[-20:])
            raise MatError(
                f"jmap 结束但未生成有效 hprof / jmap finished without a heap dump: {path}\n{tail}"
            )
