from __future__ import annotations

import threading
import time
from pathlib import Path
from typing import Any

from .config import HPROF_MAGIC, Settings
from .mat_client import MatClient
from .mat_runtime import MatError, MatInstall, MatService, detect, is_query_service
from .parse import ParseRun, clean_partial_indexes, indexes_ready, indexes_stale
from .store import StateStore, now_string


def parsing_message(record: dict[str, Any]) -> str:
    status = record.get("status") or "PARSING"
    progress = float(record.get("progress") or 0.0)
    percent = int(round(progress * 100)) if progress <= 1 else int(progress)
    phase = record.get("phase") or status
    if status == "QUEUED":
        return "堆转储排队中，稍后重试 / heap dump is queued, try again shortly"
    if status in ("PARSING", "DONE"):
        return (
            f"解析中(进度 {percent}%，阶段 {phase})，稍后再试 / "
            f"Parsing ({percent}%, {phase}), try again later"
        )
    if status == "FAILED":
        return f"解析失败 / parse failed: {record.get('error') or 'unknown error'}"
    if status == "CANCELLED":
        return "解析已取消 / parsing was cancelled"
    return f"报告状态 {status} / report status {status}"


def public_record(record: dict[str, Any]) -> dict[str, Any]:
    result = dict(record)
    progress = float(result.get("progress") or 0.0)
    result["progressPercent"] = int(round(progress * 100)) if progress <= 1 else int(progress)
    return result


class Engine:
    def __init__(self, settings: Settings, store: StateStore | None = None) -> None:
        self.settings = settings
        self.store = store or StateStore(settings.state_dir)
        self._install: MatInstall | None = None
        self._service: MatService | None = None
        self._client: MatClient | None = None
        self._lock = threading.RLock()
        self._parse_runs: dict[str, ParseRun] = {}
        self._parse_threads: dict[str, threading.Thread] = {}

    def install(self) -> MatInstall:
        with self._lock:
            if self._install is None:
                self._install = detect(self.settings)
            return self._install

    def open(self, path_value: str) -> dict[str, Any]:
        if not path_value or not path_value.strip():
            raise MatError("缺少 hprof 路径 / heap dump path is required")
        path = Path(path_value).expanduser()
        if not path.exists():
            raise MatError(f"文件不存在 / file not found: {path}")
        if not path.is_file():
            raise MatError(f"不是文件 / not a file: {path}")
        path = path.resolve()
        try:
            with path.open("rb") as handle:
                header = handle.read(16)
        except OSError as exc:
            raise MatError(f"无法读取 {path}: {exc}") from exc
        if header.startswith(b"\x1f\x8b"):
            raise MatError(
                "不支持 gzip 压缩的 hprof，请先解压 / gzip-compressed hprof is not "
                "supported; decompress it first."
            )
        if not header.startswith(HPROF_MAGIC):
            raise MatError(
                f"{path} 不是 Java hprof 文件（文件头不匹配）/ "
                f"{path} is not a Java hprof file (bad header)."
            )

        install = self.install()
        stat = path.stat()
        record = self.store.find_by_path(path)
        if record is None:
            record = self.store.upsert(path, stat.st_mtime, stat.st_size)

        report_id = record["report_id"]
        with self._lock:
            thread = self._parse_threads.get(report_id)
            running = thread is not None and thread.is_alive()
            if running:
                return public_record(self.store.get(report_id) or record)

            if (
                record.get("status") in ("QUEUED", "DONE")
                and indexes_ready(path)
                and not indexes_stale(path)
            ):
                if record.get("status") != "DONE":
                    self.store.update(
                        report_id,
                        status="DONE",
                        progress=1.0,
                        phase="DONE",
                        error=None,
                        parsed_at=now_string(),
                    )
                return public_record(self.store.get(report_id) or record)

            clean_partial_indexes(path)
            self.store.update(
                report_id,
                status="PARSING",
                progress=0.0,
                phase="Starting",
                error=None,
                mtime=stat.st_mtime,
                size=stat.st_size,
            )
            parse = ParseRun(
                install=install,
                hprof=path,
                xmx=self.settings.xmx,
                on_progress=self._progress_callback(report_id),
                log_path=self.settings.log_dir / f"parse-{report_id}.log",
            )
            self._parse_runs[report_id] = parse
            thread = threading.Thread(
                target=self._parse_worker,
                args=(report_id, path, parse),
                name=f"mat-parse-{report_id}",
                daemon=True,
            )
            self._parse_threads[report_id] = thread
            thread.start()
            return public_record(self.store.get(report_id) or record)

    def _progress_callback(self, report_id: str):
        state = {"last": 0.0, "phase": None}

        def update(progress: float, phase: str) -> None:
            now = time.monotonic()
            if phase != "DONE" and phase == state["phase"] and now - state["last"] < 0.75:
                return
            state["last"] = now
            state["phase"] = phase
            self.store.update(
                report_id,
                progress=max(0.0, min(1.0, progress)),
                phase=phase or "PARSING",
                status="PARSING" if phase != "DONE" else "DONE",
            )

        return update

    def _parse_worker(self, report_id: str, path: Path, parse: ParseRun) -> None:
        try:
            result = parse.run()
        except Exception as exc:
            result = None
            self.store.update(
                report_id,
                status="FAILED",
                phase="FAILED",
                error=f"{type(exc).__name__}: {exc}",
            )
        if result is not None:
            if result.status == "DONE":
                self.store.update(
                    report_id,
                    status="DONE",
                    progress=1.0,
                    phase="DONE",
                    error=None,
                    parsed_at=now_string(),
                )
            elif result.status == "CANCELLED":
                self.store.update(
                    report_id,
                    status="CANCELLED",
                    phase="CANCELLED",
                    error=None,
                )
            else:
                self.store.update(
                    report_id,
                    status="FAILED",
                    phase="FAILED",
                    error=result.error or "MAT parse failed",
                )
        with self._lock:
            self._parse_runs.pop(report_id, None)
            self._parse_threads.pop(report_id, None)

    def status(self, report_id: str) -> dict[str, Any] | None:
        if not report_id or not report_id.strip():
            return None
        record = self.store.get(report_id.strip())
        return public_record(record) if record else None

    def list(self) -> list[dict[str, Any]]:
        return [public_record(record) for record in self.store.list()]

    def ready(self, report_id: str) -> tuple[dict[str, Any], MatClient]:
        record = self.status(report_id)
        if record is None:
            raise MatError(
                f"报告 {report_id} 不存在，请先调用 open_heapdump / "
                f"report {report_id} not found; call open_heapdump first."
            )
        if record.get("status") != "DONE":
            raise MatError(parsing_message(record))
        path = Path(record["path"])
        if not path.exists():
            raise MatError(f"hprof 文件已不存在 / heap dump no longer exists: {path}")
        if indexes_stale(path) or not indexes_ready(path):
            self.store.update(
                record["report_id"],
                status="QUEUED",
                phase="Index missing",
                error="index files are missing or stale",
            )
            raise MatError(
                f"报告 {report_id} 的索引缺失或已过期，请重新调用 "
                f"open_heapdump({path}) / index files are missing or stale; call "
                f"open_heapdump again."
            )
        return record, self.client()

    def client(self) -> MatClient:
        with self._lock:
            if self._client is not None:
                if is_query_service(str(self._client.base_url)):
                    return self._client
                self._client.close()
                self._client = None
                self._service = None
                self.store.set_service(None)
            install = self.install()
            existing = self.store.get_service() or {}
            existing_port = existing.get("port")
            service = MatService(
                self.settings,
                install,
                existing_port=existing_port if isinstance(existing_port, int) else None,
                on_start=self.store.set_service,
            )
            service.ensure_started()
            self._service = service
            self._client = MatClient(
                service.url,
                timeout=self.settings.http_timeout,
                poll_interval=self.settings.poll_interval,
                poll_max_secs=self.settings.poll_max_secs,
            )
            return self._client

    def close(self, report_id: str, stop_service: bool = False) -> dict[str, Any]:
        with self._lock:
            parse = self._parse_runs.get(report_id)
            thread = self._parse_threads.get(report_id)
        if parse is not None:
            parse.cancel()
        if thread is not None and thread.is_alive():
            thread.join(timeout=10)
        record = self.store.remove(report_id)
        service_stopped = False
        if stop_service:
            service_stopped = self.stop_service()
        return {
            "closed": record is not None,
            "report_id": report_id,
            "serviceStopped": service_stopped,
        }

    def stop_service(self) -> bool:
        with self._lock:
            client, service = self._client, self._service
            self._client = None
            self._service = None
        if client is not None:
            client.close()
        if service is not None:
            owned = service.owns_process
            service.stop()
            self.store.set_service(None)
            return owned
        return False

    def shutdown(self) -> None:
        with self._lock:
            runs = list(self._parse_runs.values())
        for run in runs:
            run.cancel()
        self.stop_service()
