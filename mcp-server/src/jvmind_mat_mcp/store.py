from __future__ import annotations

import json
import os
import tempfile
import time
import uuid
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any

from filelock import FileLock

STATE_VERSION = 1


def new_report_id() -> str:
    return f"hd_{uuid.uuid4().hex}"


def now_string() -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S")


class StateStore:
    def __init__(self, state_dir: Path) -> None:
        self.state_dir = state_dir
        self.path = state_dir / "state.json"
        self._lock = FileLock(str(state_dir / "state.lock"))

    def _load_unlocked(self) -> dict[str, Any]:
        try:
            with self.path.open(encoding="utf-8") as handle:
                data = json.load(handle)
        except FileNotFoundError:
            return {"version": STATE_VERSION, "heapdumps": {}, "service": None}
        except (OSError, ValueError):
            return {"version": STATE_VERSION, "heapdumps": {}, "service": None}
        if not isinstance(data, dict):
            return {"version": STATE_VERSION, "heapdumps": {}, "service": None}
        data.setdefault("version", STATE_VERSION)
        data.setdefault("heapdumps", {})
        data.setdefault("service", None)
        return data

    def _save_unlocked(self, data: dict[str, Any]) -> None:
        self.state_dir.mkdir(parents=True, exist_ok=True)
        fd, tmp_name = tempfile.mkstemp(prefix="state-", suffix=".tmp", dir=self.state_dir)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(data, handle, ensure_ascii=False, indent=2)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(tmp_name, self.path)
        finally:
            if os.path.exists(tmp_name):
                os.unlink(tmp_name)

    @contextmanager
    def transaction(self) -> Iterator[dict[str, Any]]:
        self.state_dir.mkdir(parents=True, exist_ok=True)
        with self._lock:
            data = self._load_unlocked()
            yield data
            self._save_unlocked(data)

    def read(self) -> dict[str, Any]:
        self.state_dir.mkdir(parents=True, exist_ok=True)
        with self._lock:
            return self._load_unlocked()

    def get(self, report_id: str) -> dict[str, Any] | None:
        record = self.read()["heapdumps"].get(report_id)
        return dict(record) if record else None

    def find_by_path(self, path: Path) -> dict[str, Any] | None:
        target = str(path)
        for record in self.read()["heapdumps"].values():
            if record.get("path") == target:
                return dict(record)
        return None

    def upsert(self, path: Path, mtime: float, size: int) -> dict[str, Any]:
        target = str(path)
        with self.transaction() as data:
            for record in data["heapdumps"].values():
                if record.get("path") == target:
                    record["mtime"] = mtime
                    record["size"] = size
                    record["filename"] = path.name
                    record["dump_dir"] = str(path.parent)
                    return dict(record)
            report_id = new_report_id()
            record = {
                "report_id": report_id,
                "path": target,
                "filename": path.name,
                "dump_dir": str(path.parent),
                "mtime": mtime,
                "size": size,
                "status": "QUEUED",
                "progress": 0.0,
                "phase": "QUEUED",
                "error": None,
                "created_at": now_string(),
                "updated_at": now_string(),
            }
            data["heapdumps"][report_id] = record
            return dict(record)

    def create_pending(self, path: Path, **metadata: Any) -> dict[str, Any]:
        target = str(path)
        with self.transaction() as data:
            for record in data["heapdumps"].values():
                if record.get("path") == target:
                    record.update(metadata)
                    record["updated_at"] = now_string()
                    return dict(record)
            report_id = new_report_id()
            record = {
                "report_id": report_id,
                "path": target,
                "filename": path.name,
                "dump_dir": str(path.parent),
                "mtime": 0.0,
                "size": 0,
                "status": "DUMPING",
                "progress": 0.0,
                "phase": "DUMPING",
                "error": None,
                "created_at": now_string(),
                "updated_at": now_string(),
                **metadata,
            }
            data["heapdumps"][report_id] = record
            return dict(record)

    def update(self, report_id: str, **fields: Any) -> dict[str, Any] | None:
        with self.transaction() as data:
            record = data["heapdumps"].get(report_id)
            if record is None:
                return None
            record.update(fields)
            record["updated_at"] = now_string()
            return dict(record)

    def remove(self, report_id: str) -> dict[str, Any] | None:
        with self.transaction() as data:
            record = data["heapdumps"].pop(report_id, None)
            return dict(record) if record else None

    def list(self) -> list[dict[str, Any]]:
        records = list(self.read()["heapdumps"].values())
        records.sort(key=lambda item: item.get("created_at", ""))
        return [dict(item) for item in records]

    def set_service(self, info: dict[str, Any] | None) -> None:
        with self.transaction() as data:
            data["service"] = info

    def get_service(self) -> dict[str, Any] | None:
        service = self.read().get("service")
        return dict(service) if service else None
