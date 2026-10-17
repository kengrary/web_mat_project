from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


@pytest.mark.asyncio
async def test_stdio_initialize_list_and_error_path(tmp_path: Path):
    env = dict(os.environ)
    env["JVMIND_MCP_STATE_DIR"] = str(tmp_path / "state")
    env.pop("MAT_HOME", None)
    env.pop("MAT_QUERY_SERVICE_URL", None)
    params = StdioServerParameters(
        command=sys.executable,
        args=["-m", "jvmind_mat_mcp"],
        env=env,
        cwd=Path(__file__).resolve().parents[1],
    )
    async with (
        stdio_client(params) as (read, write),
        ClientSession(read, write) as session,
    ):
        initialized = await session.initialize()
        assert initialized.server_info.name == "jvmind-mat"
        tools = await session.list_tools()
        names = {tool.name for tool in tools.tools}
        assert "open_heapdump" in names
        assert "mat_overview" in names
        assert len(names) == 17

        result = await session.call_tool("open_heapdump", {"path": str(tmp_path / "missing.hprof")})
        assert result.content
        text = result.content[0].text
        assert "file not found" in text
