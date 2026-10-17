from __future__ import annotations

import os
import time
from pathlib import Path

import pytest

from jvmind_mat_mcp.config import Settings
from jvmind_mat_mcp.engine import Engine
from jvmind_mat_mcp.store import StateStore
from jvmind_mat_mcp.tools import Toolbox


@pytest.mark.integration
def test_real_heapdump_flow():
    hprof_value = os.environ.get("JVMIND_MCP_IT_HPROF")
    if not hprof_value:
        pytest.skip("set JVMIND_MCP_IT_HPROF to run the integration test")
    hprof = Path(hprof_value).expanduser()
    if not hprof.is_file():
        pytest.skip(f"heap dump not found: {hprof}")

    settings = Settings.from_env()
    engine = Engine(settings, StateStore(settings.state_dir))
    toolbox = Toolbox(engine)
    try:
        record = engine.open(str(hprof))
        deadline = time.monotonic() + 1800
        while time.monotonic() < deadline:
            record = engine.status(record["report_id"])
            if record["status"] in ("DONE", "FAILED", "CANCELLED"):
                break
            time.sleep(2)
        assert record["status"] == "DONE", record.get("error")

        report_id = record["report_id"]
        assert "Heap overview" in toolbox.overview(report_id)
        assert "Histogram" in toolbox.histogram(report_id, top=5)
        assert "Threads" in toolbox.threads(report_id)
    finally:
        engine.close(record["report_id"], stop_service=True)
        engine.shutdown()
