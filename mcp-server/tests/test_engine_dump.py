from __future__ import annotations

import threading
import time
from pathlib import Path

import pytest

from jvmind_mat_mcp import jvm
from jvmind_mat_mcp.config import Settings
from jvmind_mat_mcp.engine import Engine
from jvmind_mat_mcp.mat_runtime import MatError
from jvmind_mat_mcp.store import StateStore


class FakeProc:
    pid = 999


def _wait_for_dump(engine: Engine, timeout: float = 3.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        with engine._lock:
            if not engine._dump_threads:
                return
        time.sleep(0.01)
    raise AssertionError("dump thread did not finish")


def _patch_process(monkeypatch, settings: Settings, tmp_path: Path) -> Path:
    process = jvm.JavaProcess(
        pid=1234,
        main_class="com.example.Main",
        command="1234 com.example.Main -Xmx1g",
    )
    monkeypatch.setattr(jvm, "discover_processes", lambda *args, **kwargs: [process])
    monkeypatch.setattr(jvm, "estimate_max_heap", lambda *args, **kwargs: 1024**3)
    monkeypatch.setattr(jvm, "check_space", lambda *args, **kwargs: (10 * 1024**3, None))
    path = tmp_path / "live.hprof"
    monkeypatch.setattr(jvm, "unique_dump_path", lambda *args, **kwargs: path)
    monkeypatch.setattr(jvm, "prepare_output_dir", lambda *args, **kwargs: None)
    return path


def test_preview_dump_requires_confirmation(monkeypatch, settings: Settings, tmp_path: Path):
    _patch_process(monkeypatch, settings, tmp_path)
    engine = Engine(settings, StateStore(settings.state_dir))
    result = engine.preview_dump(1234)
    assert result["action"] == "confirm_required"
    assert result["pid"] == 1234
    assert result["live"] is True
    assert result["outputDir"] == str(tmp_path / "dumps")
    assert engine.store.list() == []


def test_dump_moves_to_done(monkeypatch, settings: Settings, tmp_path: Path):
    path = _patch_process(monkeypatch, settings, tmp_path)
    engine = Engine(settings, StateStore(settings.state_dir))
    monkeypatch.setattr(engine, "install", lambda: object())
    started = threading.Event()
    release = threading.Event()
    opened: list[Path] = []

    def fake_dump(settings_value, pid, output, timeout, log_path, on_start=None):
        started.set()
        assert release.wait(3)
        output.write_bytes(b"JAVA PROFILE 1.0.2")
        if on_start:
            on_start(FakeProc())

    def fake_open(value):
        opened.append(Path(value))
        record = engine.store.find_by_path(Path(value))
        assert record is not None
        engine.store.update(record["report_id"], status="DONE", progress=1.0, phase="DONE")
        return {}

    monkeypatch.setattr(jvm, "dump_live_heap", fake_dump)
    monkeypatch.setattr(engine, "open", fake_open)

    result = engine.dump(1234)
    assert result["status"] == "DUMPING"
    assert result["pid"] == 1234
    assert started.wait(2)
    assert engine.close(result["report_id"])["closed"] is False
    release.set()
    _wait_for_dump(engine)

    assert opened == [path]
    record = engine.store.get(result["report_id"])
    assert record["status"] == "DONE"
    assert record["size"] > 0


def test_dump_failure_cleans_up(monkeypatch, settings: Settings, tmp_path: Path):
    path = _patch_process(monkeypatch, settings, tmp_path)
    engine = Engine(settings, StateStore(settings.state_dir))
    monkeypatch.setattr(engine, "install", lambda: object())

    def fake_dump(*args, **kwargs):
        path.write_bytes(b"partial")
        raise MatError("jmap failed")

    monkeypatch.setattr(jvm, "dump_live_heap", fake_dump)
    result = engine.dump(1234)
    _wait_for_dump(engine)

    record = engine.store.get(result["report_id"])
    assert record["status"] == "FAILED"
    assert record["phase"] == "DUMP_FAILED"
    assert "jmap failed" in record["error"]
    assert not path.exists()


def test_dump_timeout_validation(monkeypatch, settings: Settings, tmp_path: Path):
    _patch_process(monkeypatch, settings, tmp_path)
    engine = Engine(settings, StateStore(settings.state_dir))
    with pytest.raises(MatError, match="timeout"):
        engine.preview_dump(1234, timeout=0)
    with pytest.raises(MatError, match="timeout"):
        engine.preview_dump(1234, timeout=4000)
