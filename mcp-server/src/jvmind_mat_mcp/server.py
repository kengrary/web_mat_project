from __future__ import annotations

import json
import logging
from contextlib import asynccontextmanager
from typing import Any

from mcp.server.mcpserver import Context, MCPServer

from . import __version__
from .config import Settings
from .engine import Engine
from .mat_runtime import MatError
from .store import StateStore
from .tools import Toolbox

INSTRUCTIONS = """\
JVMind MAT analyzes Java heap dumps locally with Eclipse MAT.

Workflow:
1. Call open_heapdump(path) with an absolute path to a .hprof file. Parsing runs
   in the background and returns immediately with status=PARSING.
2. Poll get_heapdump_status(report_id) until status=DONE. Large heap dumps can
   take several minutes.
3. Call mat_overview first, then mat_histogram / mat_dominator / mat_threads /
   mat_leak_suspects and other tools. All tools take the report_id.
4. close_heapdump(report_id) releases the local handle; pass stop_service=true
   to also shut down the MAT helper process.

All heap data stays on this machine. The helper binds to 127.0.0.1 only.
"""


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2)


def _error(exc: Exception) -> str:
    return f"[错误 / error] {exc}"


def create_server(
    settings: Settings | None = None,
    engine: Engine | None = None,
) -> MCPServer:
    settings = settings or Settings.from_env()
    engine = engine or Engine(settings, StateStore(settings.state_dir))
    toolbox = Toolbox(engine)
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)

    @asynccontextmanager
    async def lifespan(_server: MCPServer):
        try:
            yield {}
        finally:
            engine.shutdown()

    mcp = MCPServer(
        name="jvmind-mat",
        version=__version__,
        instructions=INSTRUCTIONS,
        lifespan=lifespan,
    )

    @mcp.tool(
        name="open_heapdump",
        description=(
            "Open a local Java heap dump (.hprof) by absolute path. Starts MAT "
            "indexing in the background and returns a report_id immediately with "
            "status=PARSING. Poll get_heapdump_status until DONE. / "
            "按绝对路径打开本地 Java 堆转储（.hprof）。后台启动 MAT 解析并立即返回 "
            "report_id 与 status=PARSING，用 get_heapdump_status 轮询直到 DONE。"
        ),
    )
    def open_heapdump(path: str) -> str:
        try:
            return _json(engine.open(path))
        except MatError as exc:
            return _error(exc)

    @mcp.tool(
        name="get_heapdump_status",
        description=(
            "Get parse status and progress for a heap dump report. / "
            "查询堆转储报告的解析状态与进度。"
        ),
    )
    def get_heapdump_status(report_id: str) -> str:
        record = engine.status(report_id)
        if record is None:
            return _error(MatError(f"报告 {report_id} 不存在 / report not found"))
        return _json(record)

    @mcp.tool(
        name="list_heapdumps",
        description=(
            "List heap dumps known to this MCP server with their status. / "
            "列出本 MCP 服务已知的堆转储及其状态。"
        ),
    )
    def list_heapdumps() -> str:
        return _json(engine.list())

    @mcp.tool(
        name="close_heapdump",
        description=(
            "Release a heap dump handle. Set stop_service=true to also stop the "
            "MAT helper process. / 释放堆转储句柄；stop_service=true 时同时停止 "
            "MAT 辅助进程。"
        ),
    )
    def close_heapdump(report_id: str, stop_service: bool = False) -> str:
        return _json(engine.close(report_id, stop_service=stop_service))

    @mcp.tool(
        name="mat_overview",
        description=(
            "Show heap overview (used/committed/live heap, object/class/classloader "
            "counts, JDK version, leak suspect count). Always call this first to "
            "understand a heapdump. / 展示堆总览（已用/已提交/存活堆、对象/类/"
            "类加载器数量、JDK 版本、泄漏嫌疑数量）。分析 heapdump 时先调用本工具。"
        ),
    )
    def mat_overview(report_id: str) -> str:
        return toolbox.overview(report_id)

    @mcp.tool(
        name="mat_histogram",
        description=(
            "Class histogram sorted by shallow/retained/count. Top-N by default; "
            "pass top to override. objectSet reuses a prior OQL/RS result. / "
            "按 shallow/retained/count 排序的类直方图，默认 Top-20。"
            "objectSet 可复用先前 OQL/RS 结果。"
        ),
    )
    def mat_histogram(
        report_id: str,
        top: int = 20,
        sort: str = "retained",
        objectSet: str | None = None,
    ) -> str:
        return toolbox.histogram(report_id, top=top, sort=sort, objectSet=objectSet)

    @mcp.tool(
        name="mat_dominator",
        description=(
            "Dominator tree: list biggest retained-heap dominators, drill down by "
            "passing parent objectId. / 支配树：列出 retained 最大的支配者，"
            "传 parent=objectId 可下钻。"
        ),
    )
    def mat_dominator(report_id: str, parent: str = "ROOT", top: int = 20) -> str:
        return toolbox.dominator(report_id, parent=parent, top=top)

    @mcp.tool(
        name="mat_threads",
        description=(
            "List threads (name/state/daemon/frameCount), or drill into a thread's "
            "stack frames (pass thread=id) or frame locals (pass frame=idx). / "
            "列出线程（名/状态/daemon/帧数），或下钻到某线程的栈帧（传 thread=id）、"
            "某帧的局部变量（再传 frame=idx）。"
        ),
    )
    def mat_threads(
        report_id: str,
        thread: str | None = None,
        frame: int | None = None,
    ) -> str:
        return toolbox.threads(report_id, thread=thread, frame=frame)

    @mcp.tool(
        name="mat_threadlocals",
        description=(
            "Inspect thread locals grouped by valueClass (default) or thread. "
            "Useful for finding thread-local leaks. / 按 valueClass（默认）或 "
            "thread 分组查看 ThreadLocal，常用于发现 ThreadLocal 泄漏。"
        ),
    )
    def mat_threadlocals(report_id: str, groupBy: str = "valueClass") -> str:
        return toolbox.threadlocals(report_id, groupBy=groupBy)

    @mcp.tool(
        name="mat_object",
        description=(
            "Inspect a single object (class, fields, statics, in/out refs, GC root). "
            "Required: object id. / 检查单个对象（类、字段、静态字段、入/出引用、"
            "GC Root）。必须传 object id。"
        ),
    )
    def mat_object(report_id: str, id: str) -> str:
        return toolbox.object(report_id, id)

    @mcp.tool(
        name="mat_path2gc",
        description=(
            "Shortest path from the object to a GC root (shows why an object is "
            "reachable and not collected). excludeWeakSoft defaults true. / "
            "对象到 GC Root 的最短路径（说明对象为何可达未回收）。"
            "excludeWeakSoft 默认 true。"
        ),
    )
    def mat_path2gc(report_id: str, object: str, excludeWeakSoft: bool = True) -> str:
        return toolbox.path2gc(report_id, object, excludeWeakSoft=excludeWeakSoft)

    @mcp.tool(
        name="mat_oql",
        description=(
            "Run an OQL query against the heap (slow!). Returns rows + a reusable "
            'resultSetId. Examples: "SELECT * FROM com.example.Foo s", '
            '"SELECT s.id.toString() FROM java.lang.String s". OQL can read '
            "arbitrary strings from the heap; handle results as private data. / "
            "执行 OQL 查询（较慢）。返回行和可复用的 resultSetId。示例："
            "SELECT * FROM com.example.Foo s。OQL 可读取堆内任意字符串，注意隐私。"
        ),
    )
    def mat_oql(
        report_id: str,
        q: str,
        limit: int = 50,
        view: str = "list",
        sort: str = "shallow",
    ) -> str:
        return toolbox.oql(report_id, q, limit=limit, view=view, sort=sort)

    @mcp.tool(
        name="mat_leak_suspects",
        description=(
            "Run LeakHunter (async) — returns leak suspects with retained size and "
            "description. SLOW: may take a minute. / 运行 LeakHunter（异步），"
            "返回疑似泄漏点及 retained 大小与描述。慢查询，可能耗时数十秒。"
        ),
    )
    async def mat_leak_suspects(report_id: str, ctx: Context) -> str:
        return await toolbox.leak_suspects(report_id, ctx=ctx)

    @mcp.tool(
        name="mat_diagnose_oom",
        description=(
            "Run comprehensive OOM diagnosis (async) combining leak suspects + "
            "SQL-in-threads + dominator analysis. / 综合 OOM 诊断（异步），"
            "聚合泄漏嫌疑、线程中的 SQL、支配树分析。"
        ),
    )
    async def mat_diagnose_oom(
        ctx: Context,
        report_id: str,
        culpritPct: int = 30,
        onlyRisky: bool = True,
    ) -> str:
        return await toolbox.diagnose_oom(
            report_id,
            culpritPct=culpritPct,
            onlyRisky=onlyRisky,
            ctx=ctx,
        )

    @mcp.tool(
        name="mat_connection_pools",
        description=(
            "Diagnose DB connection pools (active/idle counts + leaked connections "
            "held > thresholdMs). / 诊断数据库连接池（活跃/空闲数 + 持有超过 "
            "thresholdMs 的泄漏连接）。"
        ),
    )
    def mat_connection_pools(report_id: str, thresholdMs: int = 300000) -> str:
        return toolbox.connection_pools(report_id, thresholdMs=thresholdMs)

    @mcp.tool(
        name="mat_top_consumers",
        description=(
            "Top memory consumers (biggest objects/classes by retained heap). / "
            "内存占用 Top（按 retained 排序的最大对象/类）。"
        ),
    )
    def mat_top_consumers(report_id: str) -> str:
        return toolbox.top_consumers(report_id)

    @mcp.tool(
        name="mat_sql_in_threads",
        description=(
            "Find SQL statements (JDBC PreparedStatement) in thread stacks — useful "
            "for finding long-running queries at OOM. / 在线程栈中找 SQL（JDBC "
            "PreparedStatement），用于定位 OOM 时还在执行的慢查询。"
        ),
    )
    def mat_sql_in_threads(report_id: str, onlyRisky: bool = True) -> str:
        return toolbox.sql_in_threads(report_id, onlyRisky=onlyRisky)

    return mcp


def run() -> None:
    mcp = create_server()
    mcp.run("stdio")


if __name__ == "__main__":
    run()
