from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path

from platformdirs import user_state_path

APP_NAME = "jvmind-mcp"
DEFAULT_PORT = 8090
MAT_MAJOR = (1, 17)
MAT_VERSION_SPEC = ">=1.17,<1.18"
HPROF_MAGIC = b"JAVA PROFILE"
_XMX_RE = re.compile(r"^\d+[kKmMgGtT]?$")


def _env(env: dict[str, str], key: str, default: str | None = None) -> str | None:
    value = env.get(key)
    if value is None or not value.strip():
        return default
    return value.strip()


def _env_bool(env: dict[str, str], key: str, default: bool) -> bool:
    value = _env(env, key)
    if value is None:
        return default
    return value.lower() not in ("0", "false", "no", "off")


def _env_int(env: dict[str, str], key: str, default: int) -> int:
    value = _env(env, key)
    try:
        return int(value) if value is not None else default
    except ValueError:
        return default


def _env_float(env: dict[str, str], key: str, default: float) -> float:
    value = _env(env, key)
    try:
        return float(value) if value is not None else default
    except ValueError:
        return default


def physical_memory_bytes() -> int | None:
    try:
        return os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES")
    except (AttributeError, ValueError, OSError):
        pass
    if os.name == "nt":
        try:
            import ctypes

            class _MemoryStatusEx(ctypes.Structure):
                _fields_ = [
                    ("dwLength", ctypes.c_ulong),
                    ("dwMemoryLoad", ctypes.c_ulong),
                    ("ullTotalPhys", ctypes.c_ulonglong),
                    ("ullAvailPhys", ctypes.c_ulonglong),
                    ("ullTotalPageFile", ctypes.c_ulonglong),
                    ("ullAvailPageFile", ctypes.c_ulonglong),
                    ("ullTotalVirtual", ctypes.c_ulonglong),
                    ("ullAvailVirtual", ctypes.c_ulonglong),
                    ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
                ]

            status = _MemoryStatusEx()
            status.dwLength = ctypes.sizeof(_MemoryStatusEx)
            if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
                return int(status.ullTotalPhys)
        except Exception:
            return None
    return None


def default_xmx() -> str:
    total = physical_memory_bytes()
    if not total:
        return "8g"
    gib = int(total * 0.70 // (1024**3))
    return f"{max(4, gib)}g"


def _java_command(env: dict[str, str]) -> str:
    explicit = _env(env, "JVMIND_JAVA")
    if explicit:
        return explicit
    java_home = _env(env, "JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if candidate.is_file():
            return str(candidate)
    return "java"


@dataclass(frozen=True)
class Settings:
    mat_home: Path | None
    java: str
    xmx: str
    service_url: str | None
    preferred_port: int
    state_dir: Path
    query_cache: bool
    http_timeout: float
    poll_interval: float
    poll_max_secs: float

    @classmethod
    def from_env(cls, env: dict[str, str] | None = None) -> Settings:
        env = dict(os.environ if env is None else env)
        mat_home = _env(env, "MAT_HOME")
        xmx = _env(env, "JVMIND_MAT_XMX") or default_xmx()
        if not _XMX_RE.match(xmx):
            xmx = default_xmx()
        state_dir = _env(env, "JVMIND_MCP_STATE_DIR")
        service_url = _env(env, "MAT_QUERY_SERVICE_URL")
        return cls(
            mat_home=Path(mat_home).expanduser() if mat_home else None,
            java=_java_command(env),
            xmx=xmx,
            service_url=service_url.rstrip("/") if service_url else None,
            preferred_port=_env_int(env, "MAT_QUERY_PORT", DEFAULT_PORT),
            state_dir=(
                Path(state_dir).expanduser()
                if state_dir
                else Path(user_state_path(APP_NAME, appauthor=False))
            ),
            query_cache=_env_bool(env, "MAT_QUERY_CACHE", True),
            http_timeout=_env_float(env, "JVMIND_MCP_HTTP_TIMEOUT", 60.0),
            poll_interval=_env_float(env, "JVMIND_MCP_POLL_INTERVAL", 2.0),
            poll_max_secs=_env_float(env, "JVMIND_MCP_POLL_MAX_SECS", 900.0),
        )

    @property
    def log_dir(self) -> Path:
        return self.state_dir / "logs"
