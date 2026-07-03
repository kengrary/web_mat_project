"""
参考片段：Python worker 如何 fork parser-cli 并消费其 JSONL 进度。

定位（见 mat-study/07）：worker 从 DB 队列(SKIP LOCKED)取到一个 heapdump_reports 任务后，
调用本片段的 run_parser()，把进度实时写回 heapdump_reports.progress/phase，
完成后写 stats，失败/取消按退出码区分。

这不是完整 worker，只示范"与 parser-cli 的契约"。请并入你现有 FastAPI/SQLAlchemy 体系。
"""
import asyncio
import json
from datetime import datetime

PARSER_JAR = "/opt/jvmind/parser-cli.jar"
JAVA = "java"


def _now() -> str:
    # 对齐现有系统时间格式：本地时区、yyyy-MM-dd HH:mm:ss（INTEGRATION_GUIDE §2/§6.3）
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


async def run_parser(
    *,
    report_id: str,
    hprof_path: str,
    dump_dir: str,
    xmx: str = "32g",
    keep_unreachable: bool = False,
    discard_ratio: int | None = None,
    snapshot_id: str | None = None,
    on_update,            # async fn(progress: float|None, phase: str)  → 写 DB
    on_stats,             # async fn(stats: dict)                       → 写 DB
    should_cancel,        # async fn() -> bool                          → 查 DB 是否被请求中断
) -> str:
    """
    返回最终 status: 'DONE' | 'FAILED' | 'CANCELLED'
    """
    cmd = [
        JAVA, f"-Xmx{xmx}",
        # 解析进程独立 JVM 参数：大堆 + 适合大对象的 GC
        "-XX:+UseG1GC",
        "-jar", PARSER_JAR,
        "--hprof", hprof_path,
        "--out-dir", dump_dir,
        "--mode", "equinox",
        # 进度走 stdout（JSONL），逐行解析
    ]
    if keep_unreachable:
        cmd.append("--keep-unreachable")
    if discard_ratio is not None:
        cmd += ["--discard-ratio", str(discard_ratio)]
    if snapshot_id:
        cmd += ["--snapshot-id", snapshot_id]

    proc = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )

    final_status = "FAILED"
    final_error = None

    async def pump_cancel():
        # 周期性检查 DB 是否请求中断 → 直接 kill 子进程（最干净的中断方式）
        while proc.returncode is None:
            if await should_cancel():
                proc.kill()
                return
            await asyncio.sleep(2)

    cancel_task = asyncio.create_task(pump_cancel())

    try:
        assert proc.stdout is not None
        async for raw in proc.stdout:
            line = raw.decode("utf-8", "replace").strip()
            if not line or not line.startswith("{"):
                continue  # 忽略非协议行（MAT 内部偶发 stdout）
            try:
                evt = json.loads(line)
            except json.JSONDecodeError:
                continue

            etype = evt.get("type")
            if etype == "progress":
                await on_update(evt.get("progress"), evt.get("phase", ""))
            elif etype == "stats":
                await on_stats(evt.get("data", {}))
            elif etype == "message":
                # 可选：把 INFO/WARNING 累积进日志
                pass
            elif etype == "done":
                final_status = evt.get("status", "FAILED")
                final_error = evt.get("error")
    finally:
        rc = await proc.wait()
        cancel_task.cancel()

        # 退出码兜底（即使没收到 done 事件也能判定，呼应 CliOptions 退出码约定）
        if final_status not in ("DONE", "CANCELLED"):
            if rc == 0:
                final_status = "DONE"
            elif rc == 130:
                final_status = "CANCELLED"
            elif rc == 79:
                final_status = "FAILED"
                final_error = final_error or "OutOfMemoryError during parse"
            else:
                final_status = "FAILED"
                final_error = final_error or f"parser exited with code {rc}"

    # 调用方据此更新 heapdump_reports.status/finished_at/error
    return final_status


# ---- 使用示例（伪，接你现有 DB 层）----
# async def handle_task(report):
#     async def on_update(p, phase):
#         await db_update_report(report.id, progress=p, phase=phase)
#     async def on_stats(stats):
#         await db_update_report(report.id, stats=json.dumps(stats))
#     async def should_cancel():
#         return (await db_get_status(report.id)) == "CANCEL_REQUESTED"
#
#     await db_update_report(report.id, status="PARSING",
#                            started_at=_now(), worker_id=WORKER_ID)
#     status = await run_parser(
#         report_id=report.id,
#         hprof_path=f"{report.dump_dir}/app.hprof",
#         dump_dir=report.dump_dir,
#         on_update=on_update, on_stats=on_stats, should_cancel=should_cancel,
#     )
#     await db_update_report(report.id, status=status, finished_at=_now())
