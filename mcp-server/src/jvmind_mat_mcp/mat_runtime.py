from __future__ import annotations

import atexit
import contextlib
import hashlib
import os
import re
import shutil
import signal
import socket
import subprocess
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

import httpx

from . import QUERY_BUNDLE_NAME, QUERY_BUNDLE_VERSION
from .config import MAT_MAJOR, MAT_VERSION_SPEC, Settings

QUERY_BUNDLE_SYMBOLIC_NAME = "com.jvmind.mat.query"
QUERY_APPLICATION_ID = "com.jvmind.mat.query.app"


class MatError(RuntimeError):
    """User-actionable MAT/JDK/runtime error."""


@dataclass(frozen=True)
class MatInstall:
    home: Path
    version: str
    launcher: Path
    java: str
    java_major: int

    @property
    def plugins_dir(self) -> Path:
        return self.home / "plugins"

    @property
    def configuration_dir(self) -> Path:
        return self.home / "configuration"

    @property
    def bundles_info(self) -> Path:
        return self.configuration_dir / "org.eclipse.equinox.simpleconfigurator" / "bundles.info"

    @property
    def bundle_jar(self) -> Path:
        return self.plugins_dir / QUERY_BUNDLE_NAME


@dataclass(frozen=True)
class BundleChange:
    copied: bool
    registered: bool
    changed: bool


def _version_tuple(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in re.findall(r"\d+", version)[:4])


def _mat_candidates(settings: Settings) -> list[Path]:
    if settings.mat_home:
        return [settings.mat_home]
    candidates = [
        Path.home() / "mat",
        Path("/opt/mat"),
        Path("/usr/local/mat"),
        Path("/Applications/mat.app/Contents/Eclipse"),
    ]
    home = os.environ.get("HOME")
    if home:
        candidates.extend(Path(home).glob("mat*"))
    executable = shutil.which("MemoryAnalyzer")
    if executable:
        candidates.append(Path(executable).resolve().parent)
    return candidates


def _is_mat_home(path: Path) -> bool:
    return (path / "plugins").is_dir() and (path / "configuration").is_dir()


def find_mat_home(settings: Settings) -> Path:
    if settings.mat_home:
        home = settings.mat_home.expanduser().resolve()
        for candidate in (home, home / "Contents" / "Eclipse"):
            if _is_mat_home(candidate):
                return candidate
        raise MatError(
            f"MAT_HOME={home} 不是有效的 Eclipse MAT 目录"
            f"（需要 plugins/ 和 configuration/）。\n"
            f"MAT_HOME={home} is not a valid Eclipse MAT home (plugins/ and "
            f"configuration/ are required)."
        )
    for candidate in _mat_candidates(settings):
        try:
            home = candidate.expanduser().resolve()
        except OSError:
            continue
        if _is_mat_home(home):
            return home
    raise MatError(
        "未找到 Eclipse MAT。请安装 MAT 1.17.x（需要 JDK 21），并设置 MAT_HOME。\n"
        "Eclipse MAT 1.17.x was not found. Install it (JDK 21 required) and set "
        "MAT_HOME, e.g. export MAT_HOME=/opt/mat.\n"
        "下载 / download: https://eclipse.dev/mat/downloads.php"
    )


def read_mat_version(home: Path) -> str:
    versions: list[str] = []
    for jar in (home / "plugins").glob("org.eclipse.mat.api_*.jar"):
        match = re.search(
            r"org\.eclipse\.mat\.api_(\d+\.\d+\.\d+(?:\.[0-9A-Za-z]+)?)\.jar$", jar.name
        )
        if match:
            versions.append(match.group(1))
    if not versions:
        raise MatError(
            f"在 {home}/plugins 下找不到 org.eclipse.mat.api_*.jar，MAT 安装不完整。\n"
            f"org.eclipse.mat.api_*.jar is missing under {home}/plugins; the MAT "
            f"installation looks incomplete."
        )
    return max(versions, key=_version_tuple)


def find_launcher(home: Path) -> Path:
    matches = sorted((home / "plugins").glob("org.eclipse.equinox.launcher_*.jar"))
    if not matches:
        raise MatError(
            f"在 {home}/plugins 下找不到 org.eclipse.equinox.launcher_*.jar。\n"
            f"org.eclipse.equinox.launcher_*.jar is missing under {home}/plugins."
        )
    return max(matches, key=lambda path: _version_tuple(path.name))


_JAVA_VERSION_RE = re.compile(r'version\s+"(?:(\d+)\.)?(\d+)(?:[._](\d+))?')


def parse_java_version(output: str) -> int:
    match = _JAVA_VERSION_RE.search(output)
    if not match:
        raise MatError("无法识别 Java 版本 / could not parse the Java version output")
    first, second = match.group(1), match.group(2)
    return int(second) if first in (None, "1") else int(first)


def java_major(java: str) -> int:
    try:
        proc = subprocess.run(
            [java, "-version"],
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )
    except FileNotFoundError as exc:
        raise MatError(
            f"找不到 Java 可执行文件: {java}\n"
            f"Java executable not found: {java}. Install JDK 21 or set JVMIND_JAVA."
        ) from exc
    except subprocess.TimeoutExpired as exc:
        raise MatError(f"执行 `{java} -version` 超时 / timed out running java -version") from exc
    output = (proc.stderr or "") + (proc.stdout or "")
    if proc.returncode != 0 and not output:
        raise MatError(f"`{java} -version` 失败 / java -version failed: {proc.returncode}")
    return parse_java_version(output)


def detect(settings: Settings) -> MatInstall:
    home = find_mat_home(settings)
    version = read_mat_version(home)
    major = _version_tuple(version)[:2]
    if major != MAT_MAJOR:
        raise MatError(
            f"检测到 Eclipse MAT {version}，本工具要求 {MAT_VERSION_SPEC}。\n"
            f"Detected Eclipse MAT {version}, but this tool requires {MAT_VERSION_SPEC}."
        )
    launcher = find_launcher(home)
    major_java = java_major(settings.java)
    if major_java < 21:
        raise MatError(
            f"检测到 Java {major_java}，MAT 1.17 需要 JDK 21+。\n"
            f"Detected Java {major_java}; MAT 1.17 requires JDK 21+."
            " 设置 JVMIND_JAVA 指向 JDK 21 / set JVMIND_JAVA to a JDK 21 binary."
        )
    install = MatInstall(
        home=home,
        version=version,
        launcher=launcher,
        java=settings.java,
        java_major=major_java,
    )
    if not os.access(install.plugins_dir, os.W_OK):
        raise MatError(
            f"MAT 插件目录不可写: {install.plugins_dir}\n"
            f"The MAT plugins directory is not writable: {install.plugins_dir}.\n"
            f"请给当前用户写权限，或把 MAT 安装到用户可写目录 / grant write access."
        )
    if install.bundles_info.exists() and not os.access(install.bundles_info, os.W_OK):
        raise MatError(
            f"MAT bundles.info 不可写: {install.bundles_info}\n"
            f"{install.bundles_info} is not writable."
        )
    return install


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def bundle_source() -> Path:
    path = Path(__file__).resolve().parent / "vendor" / QUERY_BUNDLE_NAME
    if not path.is_file():
        raise MatError(
            f"安装包缺少查询 bundle: {path}\n"
            f"The wheel is missing the query bundle at {path}. Reinstall "
            f"jvmind-mat-mcp."
        )
    return path


def ensure_bundle(install: MatInstall) -> BundleChange:
    source = bundle_source()
    target = install.bundle_jar
    copied = False
    try:
        if not target.exists() or _sha256(source) != _sha256(target):
            install.plugins_dir.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            copied = True
    except OSError as exc:
        raise MatError(
            f"无法写入 MAT 插件目录 {install.plugins_dir}: {exc}\n"
            f"Cannot write to the MAT plugins directory {install.plugins_dir}: {exc}.\n"
            f"请给当前用户写权限，或把 MAT 安装到用户可写目录 / grant write access."
        ) from exc

    bundles_info = install.bundles_info
    try:
        original = bundles_info.read_text(encoding="utf-8", errors="replace").splitlines()
    except FileNotFoundError:
        original = []
    except OSError as exc:
        raise MatError(
            f"无法读取 {bundles_info}: {exc}\nCannot read {bundles_info}: {exc}"
        ) from exc

    new_line = (
        f"{QUERY_BUNDLE_SYMBOLIC_NAME},{QUERY_BUNDLE_VERSION},plugins/{QUERY_BUNDLE_NAME},4,true"
    )
    filtered = [
        line for line in original if not line.strip().startswith(f"{QUERY_BUNDLE_SYMBOLIC_NAME},")
    ]
    updated_lines = [*filtered, new_line]
    registered = updated_lines != original
    if registered:
        try:
            bundles_info.parent.mkdir(parents=True, exist_ok=True)
            backup = bundles_info.with_name(bundles_info.name + ".jvmind-mcp.bak")
            if bundles_info.exists() and not backup.exists():
                shutil.copy2(bundles_info, backup)
            temp = bundles_info.with_name(bundles_info.name + ".tmp")
            temp.write_text("\n".join(updated_lines) + "\n", encoding="utf-8")
            os.replace(temp, bundles_info)
        except OSError as exc:
            raise MatError(
                f"无法更新 {bundles_info}: {exc}\nCannot update {bundles_info}: {exc}.\n"
                f"请给 MAT configuration 目录写权限 / grant write access."
            ) from exc

    for stale in install.plugins_dir.glob(f"{QUERY_BUNDLE_SYMBOLIC_NAME}-*.jar"):
        if stale.name != QUERY_BUNDLE_NAME:
            with contextlib.suppress(OSError):
                stale.unlink()

    return BundleChange(copied=copied, registered=registered, changed=copied or registered)


def uninstall_bundle(install: MatInstall) -> bool:
    changed = False
    if install.bundle_jar.exists():
        install.bundle_jar.unlink()
        changed = True
    for stale in install.plugins_dir.glob(f"{QUERY_BUNDLE_SYMBOLIC_NAME}-*.jar"):
        try:
            stale.unlink()
            changed = True
        except OSError:
            pass
    bundles_info = install.bundles_info
    if bundles_info.exists():
        try:
            lines = bundles_info.read_text(encoding="utf-8", errors="replace").splitlines()
            kept = [
                line
                for line in lines
                if not line.strip().startswith(f"{QUERY_BUNDLE_SYMBOLIC_NAME},")
            ]
            if kept != lines:
                bundles_info.write_text("\n".join(kept) + "\n", encoding="utf-8")
                changed = True
        except OSError as exc:
            raise MatError(f"无法更新 {bundles_info}: {exc}") from exc
    return changed


def _port_free(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(("127.0.0.1", port))
        except OSError:
            return False
    return True


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def is_query_service(url: str) -> bool:
    try:
        response = httpx.get(f"{url.rstrip('/')}/health", timeout=2.0)
        return response.status_code == 200 and response.json().get("status") == "ok"
    except Exception:
        return False


def _tail(path: Path, lines: int = 40) -> str:
    try:
        content = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return ""
    return "\n".join(line[:500] for line in content[-lines:])


class MatService:
    def __init__(
        self,
        settings: Settings,
        install: MatInstall,
        existing_port: int | None = None,
        on_start: Callable[[dict], None] | None = None,
    ) -> None:
        self.settings = settings
        self.install = install
        self._port = existing_port or settings.preferred_port
        self._proc: subprocess.Popen[str] | None = None
        self._owns_process = False
        self._on_start = on_start
        self._stopped = False
        atexit.register(self.stop)

    @property
    def url(self) -> str:
        if self.settings.service_url:
            return self.settings.service_url
        return f"http://127.0.0.1:{self._port}"

    @property
    def owns_process(self) -> bool:
        return self._owns_process

    @property
    def port(self) -> int:
        return self._port

    @property
    def pid(self) -> int | None:
        return self._proc.pid if self._proc else None

    def ensure_started(self) -> str:
        if self.settings.service_url:
            if not is_query_service(self.settings.service_url):
                raise MatError(
                    f"MAT_QUERY_SERVICE_URL 不可用: {self.settings.service_url}\n"
                    f"MAT_QUERY_SERVICE_URL is not reachable: {self.settings.service_url}"
                )
            return self.settings.service_url

        candidate = self._port
        if is_query_service(f"http://127.0.0.1:{candidate}"):
            return self.url
        if not _port_free(candidate):
            candidate = _free_port()
        self._port = candidate
        self._start(candidate)
        return self.url

    def _start(self, port: int) -> None:
        bundle = ensure_bundle(self.install)
        log_dir = self.settings.log_dir
        log_dir.mkdir(parents=True, exist_ok=True)
        log_path = log_dir / "mat-service.log"
        cmd = [
            self.install.java,
            f"-Xmx{self.settings.xmx}",
            "-XX:+UseG1GC",
            "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
            f"-DMAT_QUERY_PORT={port}",
            "-DMAT_QUERY_BIND=127.0.0.1",
            f"-DMAT_QUERY_CACHE={'true' if self.settings.query_cache else 'false'}",
            "-jar",
            str(self.install.launcher),
            "-configuration",
            str(self.install.configuration_dir),
            "-consoleLog",
            "-nosplash",
            "-application",
            QUERY_APPLICATION_ID,
        ]
        if bundle.changed:
            cmd.insert(cmd.index("-consoleLog"), "-clean")
        try:
            handle = log_path.open("ab")
        except OSError as exc:
            raise MatError(f"无法写入日志 {log_path}: {exc}") from exc
        handle.write(
            f"\n=== {time.strftime('%Y-%m-%d %H:%M:%S')} starting MAT "
            f"(port={port}, xmx={self.settings.xmx}) ===\n".encode()
        )
        handle.flush()
        try:
            self._proc = subprocess.Popen(
                cmd,
                stdin=subprocess.DEVNULL,
                stdout=handle,
                stderr=subprocess.STDOUT,
                start_new_session=True,
                text=True,
            )
        except OSError as exc:
            handle.close()
            raise MatError(f"无法启动 MAT: {exc}\nFailed to start MAT: {exc}") from exc
        self._owns_process = True
        if self._on_start:
            self._on_start(
                {
                    "port": port,
                    "pid": self._proc.pid,
                    "mat_home": str(self.install.home),
                    "started_at": time.strftime("%Y-%m-%d %H:%M:%S"),
                }
            )
        try:
            self._wait_healthy(port, log_path)
        finally:
            handle.close()

    def _wait_healthy(self, port: int, log_path: Path) -> None:
        deadline = time.monotonic() + 120.0
        url = f"http://127.0.0.1:{port}"
        while time.monotonic() < deadline:
            if self._proc is not None and self._proc.poll() is not None:
                raise MatError(
                    f"MAT 查询服务启动失败（exit={self._proc.returncode}）。"
                    f"日志 / log: {log_path}\n{_tail(log_path)}"
                )
            if is_query_service(url):
                return
            time.sleep(0.5)
        if self._proc is not None:
            self._proc.terminate()
        raise MatError(
            f"MAT 查询服务在 120 秒内未就绪 / did not become ready in 120s。"
            f"日志 / log: {log_path}\n{_tail(log_path)}"
        )

    def stop(self) -> None:
        if self._stopped:
            return
        self._stopped = True
        proc = self._proc
        if not self._owns_process or proc is None or proc.poll() is not None:
            return
        try:
            os.killpg(proc.pid, signal.SIGTERM)
        except (AttributeError, OSError):
            proc.terminate()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except (AttributeError, OSError):
                proc.kill()
            with contextlib.suppress(subprocess.TimeoutExpired):
                proc.wait(timeout=5)
