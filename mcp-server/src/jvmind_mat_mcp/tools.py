from __future__ import annotations

import asyncio
import json
import time
from typing import Any

from .engine import Engine
from .mat_runtime import MatError

TOP_DEFAULT = 20
TOP_MAX = 50


def _fmt_bytes(value: Any) -> str:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return str(value)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if abs(number) < 1024 or unit == "TB":
            return f"{number:,.1f} {unit}" if unit != "B" else f"{int(number)} B"
        number /= 1024
    return f"{number:,.1f} PB"


def _trim_list(items: list, top: int, label: str) -> tuple[list, int, str]:
    if not isinstance(items, list):
        return [], 0, ""
    total = len(items)
    if top <= 0 or total <= top:
        return items, total, ""
    return (
        items[:top],
        total,
        f"（共 {total} 项，仅展示前 {top} 项 / {total} {label}, showing top {top}）",
    )


def _bilingual_lines(zh_lines: list[str], en_lines: list[str]) -> str:
    return "\n".join(f"{zh} / {en}" for zh, en in zip(zh_lines, en_lines, strict=False))


def _int(value: Any, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _looks_like_address(value: str) -> bool:
    text = (value or "").strip()
    if not text:
        return False
    if text.startswith(("0x", "0X")):
        rest = text[2:]
        return bool(rest) and all(ch in "0123456789abcdefABCDEF" for ch in rest)
    return any(ch in "abcdefABCDEF" for ch in text) and all(
        ch in "0123456789abcdefABCDEF" for ch in text
    )


def _progress_percent(data: dict[str, Any]) -> int:
    progress = data.get("progress", 0) or 0
    if isinstance(progress, float) and progress <= 1.0:
        progress = int(progress * 100)
    return max(0, min(100, int(progress)))


class Toolbox:
    def __init__(self, engine: Engine) -> None:
        self.engine = engine

    def _ready(self, report_id: str) -> tuple[dict[str, Any] | None, Any, str | None]:
        try:
            record, client = self.engine.ready(report_id)
            return record, client, None
        except MatError as exc:
            return None, None, str(exc)

    def overview(self, report_id: str) -> str:
        record, client, error = self._ready(report_id)
        if error:
            return error
        result = client.overview(record["dump_dir"])
        if not result.ok:
            return result.error
        data = result.data or {}

        def pick(*keys: str, default: Any = "") -> Any:
            for key in keys:
                if key in data and data[key] not in (None, "", 0):
                    return data[key]
            return default

        used = pick("usedHeapSize", "usedHeap", "heapUsed", "used")
        committed = pick(
            "committedHeapSize",
            "committedHeap",
            "heapCommitted",
            "committed",
            "maxHeap",
            default=None,
        )
        live = pick("liveHeapSize", "liveHeap", "live")
        object_count = pick("numObjects", "objectCount", "objects")
        class_count = pick("numClasses", "classCount", "classes")
        classloaders = pick("numClassLoaders", "classLoaderCount", "classloaders")
        jvm = data.get("jvmInfo") or data.get("systemProperties") or {}
        java_version = ""
        if isinstance(jvm, list):
            for prop in jvm:
                if isinstance(prop, dict) and prop.get("name") == "java.version":
                    java_version = prop.get("value", "")
                    break
        elif isinstance(jvm, dict):
            java_version = jvm.get("javaVersion", "") or jvm.get("java.version", "")

        zh = [
            f"堆总览 · {record['filename']}",
            f"- 已用堆: {_fmt_bytes(used)}"
            + (f" / 已提交: {_fmt_bytes(committed)}" if committed else ""),
        ]
        en = [
            f"Heap overview · {record['filename']}",
            f"- Used: {_fmt_bytes(used)}"
            + (f" / Committed: {_fmt_bytes(committed)}" if committed else ""),
        ]
        if live:
            zh.append(f"- 存活对象占用: {_fmt_bytes(live)}")
            en.append(f"- Live set: {_fmt_bytes(live)}")
        if object_count:
            zh.append(
                f"- 对象数: {object_count:,}；类数: {class_count or '-'}；"
                f"类加载器: {classloaders or '-'}"
            )
            en.append(
                f"- Objects: {object_count:,}; classes: {class_count or '-'}; "
                f"classloaders: {classloaders or '-'}"
            )
        if java_version:
            zh.append(f"- JDK: {java_version}")
            en.append(f"- JDK: {java_version}")
        leak_count = data.get("leakSuspectCount")
        if leak_count is not None:
            zh.append(f"- 疑似泄漏: {leak_count} 处；可用 mat_leak_suspects({report_id}) 详情")
            en.append(
                f"- Leak suspects: {leak_count}; run mat_leak_suspects({report_id}) for details"
            )
        return _bilingual_lines(zh, en)

    def histogram(
        self,
        report_id: str,
        top: int = TOP_DEFAULT,
        sort: str = "retained",
        objectSet: str | None = None,
    ) -> str:
        record, client, error = self._ready(report_id)
        if error:
            return error
        top = max(1, min(_int(top, TOP_DEFAULT), TOP_MAX))
        if sort not in ("shallow", "retained", "count"):
            sort = "retained"
        result = client.histogram(record["dump_dir"], top, sort, objectSet)
        if not result.ok:
            return result.error
        items = (result.data or {}).get("rows", result.data or [])
        sliced, total, more = _trim_list(items, top, "classes")
        zh = [f"Histogram (sort={sort}, top={top}) · {record['filename']}"]
        en = list(zh)
        if more:
            zh.append(more.split(" / ")[0])
            en.append(more.split(" / ")[1])
        for index, item in enumerate(sliced, 1):
            if not isinstance(item, dict):
                continue
            name = item.get("label") or item.get("className") or "?"
            shallow = item.get("shallowBytes", item.get("shallowHeap", item.get("shallow", 0)))
            retained = item.get("retainedBytes", item.get("retainedHeap", item.get("retained", 0)))
            count = item.get("count", item.get("objectCount", item.get("numberOfObjects", 0)))
            object_id = item.get("objectId", item.get("id", ""))
            line = (
                f"{index:>2}. {name}  objects={count:,}  "
                f"shallow={_fmt_bytes(shallow)}  retained={_fmt_bytes(retained)}"
                + (f"  id={object_id}" if object_id else "")
            )
            zh.append(line)
            en.append(line)
        return _bilingual_lines(zh, en)

    def dominator(self, report_id: str, parent: str = "ROOT", top: int = TOP_DEFAULT) -> str:
        record, client, error = self._ready(report_id)
        if error:
            return error
        top = max(1, min(_int(top, TOP_DEFAULT), TOP_MAX))
        parent = parent or "ROOT"
        result = client.dominator(record["dump_dir"], parent, top)
        if not result.ok:
            return result.error
        items = (result.data or {}).get("rows", result.data or [])
        sliced, _, more = _trim_list(items if isinstance(items, list) else [], top, "children")
        zh = [f"Dominator tree (parent={parent}, top={top}) · {record['filename']}"]
        en = list(zh)
        if more:
            zh.append(more.split(" / ")[0])
            en.append(more.split(" / ")[1])
        for index, item in enumerate(sliced, 1):
            if not isinstance(item, dict):
                continue
            name = item.get("label") or item.get("className") or item.get("type") or "?"
            retained = item.get("retainedBytes", item.get("retainedHeap", item.get("retained", 0)))
            percent = item.get("retainedPercent", item.get("percent", ""))
            object_id = item.get("objectId", item.get("id", ""))
            percent_text = f" ({percent}%)" if percent not in ("", None) else ""
            line = f"{index:>2}. {name}  retained={_fmt_bytes(retained)}{percent_text}" + (
                f"  id={object_id}" if object_id else ""
            )
            zh.append(line)
            en.append(line)
        return _bilingual_lines(zh, en)

    def threads(
        self,
        report_id: str,
        thread: str | None = None,
        frame: int | None = None,
    ) -> str:
        record, client, error = self._ready(report_id)
        if error:
            return error
        if thread and _looks_like_address(thread):
            return (
                f"thread 参数 '{thread}' 看起来是对象地址（不是线程 id）。\n"
                f"thread param '{thread}' looks like an object address, not a thread id.\n"
                f"线程 id 必须是 mat_threads({report_id}) 列表里每行的 objectId 字段"
                f"（十进制整数）。\n"
                f"Use the decimal objectId shown in mat_threads({report_id}) output.\n"
                f"重新列出线程后再选：mat_threads({report_id})\n"
                f"Re-list threads then pick: mat_threads({report_id})"
            )
        frame_value = _int(frame, -1) if frame is not None else -1
        result = client.threads(
            record["dump_dir"],
            thread=thread or None,
            frame=frame_value if frame_value >= 0 else None,
        )
        if not result.ok:
            return result.error
        data = result.data
        if not thread:
            items = (
                data if isinstance(data, list) else (data.get("rows") or data.get("threads") or [])
            )
            sliced, _, more = _trim_list(items, TOP_DEFAULT, "threads")
            zh = [f"Threads (top={TOP_DEFAULT}) · {record['filename']}"]
            en = list(zh)
            if more:
                zh.append(more.split(" / ")[0])
                en.append(more.split(" / ")[1])
            for index, item in enumerate(sliced, 1):
                if not isinstance(item, dict):
                    continue
                name = item.get("name") or "?"
                state = item.get("state") or item.get("status") or "?"
                daemon = "daemon" if item.get("daemon") else ""
                frames = item.get("frameCount") or item.get("stackDepth") or ""
                object_id = item.get("objectId", "")
                address = item.get("address") or ""
                id_part = f"  objId={object_id} ← thread id" if object_id not in ("", None) else ""
                address_part = f"  addr={address} (not a thread id)" if address else ""
                line = (
                    f"{index:>2}. [{state}] {name} {daemon} frames={frames}{id_part}{address_part}"
                )
                zh.append(line)
                en.append(line)
            return _bilingual_lines(zh, en)
        if frame_value >= 0:
            return (
                f"Locals @ frame={frame_value} · {thread}\n"
                + json.dumps(data, ensure_ascii=False, indent=2)[:3000]
            )
        frames = (
            data
            if isinstance(data, list)
            else (data.get("frames") or data.get("stack") or data.get("stackTrace") or [])
        )
        if not frames and isinstance(data, dict):
            return (
                f"Thread '{thread}' response (no frames found, keys: {list(data.keys())}):\n"
                + json.dumps(data, ensure_ascii=False, indent=2)[:2000]
            )
        lines = [
            f"Stack of '{thread}' ({len(frames)} frames):",
            f"线程 '{thread}' 堆栈（共 {len(frames)} 帧）：",
        ]
        for index, item in enumerate(frames[:50]):
            if isinstance(item, dict):
                text = item.get("text")
                if text:
                    lines.append(f"  #{index} {text}")
                    continue
                class_name = (
                    item.get("className") or item.get("class") or item.get("declaringClass") or ""
                )
                method = item.get("methodName") or item.get("method") or item.get("name") or "?"
                source = item.get("fileName") or item.get("source") or item.get("file") or ""
                line_number = item.get("lineNumber") or item.get("line") or ""
                location = f"{source}:{line_number}" if source else "?:?"
                lines.append(f"  #{index} at {class_name}.{method}({location})")
            else:
                lines.append(f"  #{index} {item}")
        if len(frames) > 50:
            lines.append(f"... ({len(frames) - 50} more / 还有 {len(frames) - 50} 帧)")
        return "\n".join(lines)

    def threadlocals(self, report_id: str, groupBy: str = "valueClass") -> str:
        record, client, error = self._ready(report_id)
        if error:
            return error
        group_by = groupBy or "valueClass"
        result = client.threadlocals(record["dump_dir"], group_by)
        if not result.ok:
            return result.error
        data = result.data
        items = (
            data if isinstance(data, list) else (data.get("groups") or data.get("entries") or [])
        )
        sliced, total, more = _trim_list(items, TOP_DEFAULT, "entries")
        out = [
            f"ThreadLocals (groupBy={group_by}) · {record['filename']} ({total})",
            f"ThreadLocals（groupBy={group_by}）· {record['filename']}（{total}）",
        ]
        if more:
            out.append(more)
        for index, item in enumerate(sliced, 1):
            out.append(f"{index}. {json.dumps(item, ensure_ascii=False)[:300]}")
        return "\n".join(out)

    def object(self, report_id: str, id: str) -> str:
        record, client, error = self._ready(report_id)
        if error:
            return error
        result = client.object(record["dump_dir"], id)
        if not result.ok:
            return result.error
        return "\n".join(
            [
                f"Object {id} · {record['filename']}",
                f"对象 {id} · {record['filename']}",
                json.dumps(result.data or {}, ensure_ascii=False, indent=2)[:4000],
            ]
        )

    def path2gc(self, report_id: str, object: str, excludeWeakSoft: bool = True) -> str:
        record, client, error = self._ready(report_id)
        if error:
            return error
        result = client.path2gc(record["dump_dir"], object, bool(excludeWeakSoft))
        if not result.ok:
            return result.error
        data = result.data
        if isinstance(data, dict) and "paths" in data:
            paths = data["paths"] or []
        elif isinstance(data, list):
            paths = data
        else:
            paths = [data]
        sliced, _, more = _trim_list(paths, TOP_DEFAULT, "paths")
        out = [
            f"Paths to GC roots for {object} · {record['filename']}",
            f"对象 {object} 到 GC Root 的路径 · {record['filename']}",
        ]
        if more:
            out.append(more)
        for index, path in enumerate(sliced, 1):
            out.append(f"--- path {index} ---")
            out.append(json.dumps(path, ensure_ascii=False, indent=2)[:2000])
        return "\n".join(out)

    def oql(
        self,
        report_id: str,
        q: str,
        limit: int = 50,
        view: str = "list",
        sort: str = "shallow",
    ) -> str:
        record, client, error = self._ready(report_id)
        if error:
            return error
        limit = max(1, min(_int(limit, 50), 200))
        view = view or "list"
        sort = sort or "shallow"
        result = client.oql(record["dump_dir"], q, limit, view, sort)
        if not result.ok:
            return result.error
        data = result.data or {}
        rows = data.get("rows") if isinstance(data, dict) else data
        result_set_id = data.get("resultSetId") if isinstance(data, dict) else None
        if isinstance(rows, list):
            sliced, _, more = _trim_list(rows, limit, "rows")
            out = [
                f"OQL result (limit={limit}) · {record['filename']}",
                f"OQL 查询结果（limit={limit}）· {record['filename']}",
            ]
            if result_set_id:
                out.append(
                    f"resultSetId: {result_set_id}"
                    "（可用于 objectSet 二次查询 / usable as objectSet in follow-up queries）"
                )
            if more:
                out.append(more)
            for index, row in enumerate(sliced, 1):
                out.append(f"{index:>3}. {json.dumps(row, ensure_ascii=False)[:300]}")
            return "\n".join(out)
        return json.dumps(data, ensure_ascii=False, indent=2)[:4000]

    async def leak_suspects(self, report_id: str, ctx: Any = None) -> str:
        record, client, error = self._ready(report_id)
        if error:
            return error
        submitted = await asyncio.to_thread(client.submit_leak_suspects, record["dump_dir"])
        if not submitted.ok:
            return submitted.error
        task_id = (submitted.data or {}).get("taskId")
        if not task_id:
            return "未返回任务 ID / no taskId returned"
        deadline = time.monotonic() + client.poll_max_secs
        while time.monotonic() < deadline:
            result = await asyncio.to_thread(client.get_task, task_id)
            if not result.ok:
                return result.error
            data = result.data or {}
            status = data.get("status")
            if status == "DONE":
                if data.get("resultEvicted"):
                    return "结果已被回收，请重新查询 / result evicted, re-run query"
                return self._format_leak_suspects(record["filename"], data.get("result"))
            if status in ("FAILED", "CANCELLED"):
                return data.get("error") or f"任务 {status} / task {status}"
            percent = _progress_percent(data)
            if ctx is not None:
                await ctx.report_progress(percent, 100, f"LeakHunter {percent}%")
            await asyncio.sleep(client.poll_interval)
        return f"等待任务超时（{int(client.poll_max_secs)}s） / timed out waiting for leak suspects"

    @staticmethod
    def _format_leak_suspects(filename: str, data: Any) -> str:
        suspects = data.get("suspects") if isinstance(data, dict) else data
        if not isinstance(suspects, list):
            suspects = [suspects] if suspects else []
        sliced, total, more = _trim_list(suspects, TOP_DEFAULT, "suspects")
        zh = [
            f"Leak Suspects · {filename}",
            f"疑似内存泄漏 ({total} 项)" if total else "未发现明显泄漏 / No obvious leak suspects",
        ]
        en = [
            f"Leak Suspects · {filename}",
            f"({total} suspects)" if total else "No obvious leak suspects",
        ]
        if more:
            zh.append(more.split(" / ")[0])
            en.append(more.split(" / ")[1])
        for index, suspect in enumerate(sliced, 1):
            if not isinstance(suspect, dict):
                zh.append(f"{index}. {suspect}")
                en.append(f"{index}. {suspect}")
                continue
            description = suspect.get("description") or suspect.get("problem") or ""
            size = (
                suspect.get("retainedBytes")
                or suspect.get("retainedHeapSize")
                or suspect.get("retained")
                or suspect.get("bytes")
            )
            class_name = suspect.get("className") or suspect.get("suspectClass") or ""
            line = f"{index}. "
            if class_name:
                line += f"{class_name} "
            if size:
                line += f"retained={_fmt_bytes(size)} "
            if description:
                line += f"- {str(description)[:200]}"
            zh.append(line)
            en.append(line)
        return _bilingual_lines(zh, en)

    async def diagnose_oom(
        self,
        report_id: str,
        culpritPct: int = 30,
        onlyRisky: bool = True,
        ctx: Any = None,
    ) -> str:
        record, client, error = self._ready(report_id)
        if error:
            return error
        culprit = _int(culpritPct, 30)
        submitted = await asyncio.to_thread(
            client.submit_diagnose_oom,
            record["dump_dir"],
            culprit,
            bool(onlyRisky),
        )
        if not submitted.ok:
            return submitted.error
        task_id = (submitted.data or {}).get("taskId")
        if not task_id:
            return "未返回任务 ID / no taskId returned"
        deadline = time.monotonic() + client.poll_max_secs
        while time.monotonic() < deadline:
            result = await asyncio.to_thread(client.get_task, task_id)
            if not result.ok:
                return result.error
            data = result.data or {}
            status = data.get("status")
            if status == "DONE":
                return "\n".join(
                    [
                        f"OOM 诊断结论 · {record['filename']}",
                        f"OOM Diagnosis · {record['filename']}",
                        json.dumps(data.get("result") or {}, ensure_ascii=False, indent=2)[:5000],
                    ]
                )
            if status in ("FAILED", "CANCELLED"):
                return data.get("error") or f"任务 {status} / task {status}"
            percent = _progress_percent(data)
            if ctx is not None:
                await ctx.report_progress(percent, 100, f"OOM diagnosis {percent}%")
            await asyncio.sleep(client.poll_interval)
        return f"等待任务超时（{int(client.poll_max_secs)}s） / timed out waiting for OOM diagnosis"

    def connection_pools(self, report_id: str, thresholdMs: int = 300000) -> str:
        record, client, error = self._ready(report_id)
        if error:
            return error
        threshold = _int(thresholdMs, 300000)
        pools = client.connection_pools(record["dump_dir"])
        leaks = client.pool_leaks(record["dump_dir"], threshold)
        zh = [f"连接池诊断 · {record['filename']}"]
        en = [f"Connection Pool Diagnosis · {record['filename']}"]
        if pools.ok:
            zh.append(f"[连接池统计 / pools] {json.dumps(pools.data, ensure_ascii=False)[:2000]}")
            en.append(zh[-1])
        else:
            zh.append(f"[连接池查询失败] {pools.error}")
            en.append(f"[pools failed] {pools.error}")
        if leaks.ok:
            items = leaks.data or []
            if isinstance(items, list):
                sliced, total, more = _trim_list(items, TOP_DEFAULT, "leaks")
                zh.append(
                    f"[连接泄漏嫌疑 / leaks] {total} 项"
                    + (f"（仅展示前 {TOP_DEFAULT}）" if more else "")
                )
                en.append(
                    f"[leaks] {total} found" + (f" (showing top {TOP_DEFAULT})" if more else "")
                )
                for index, item in enumerate(sliced, 1):
                    line = f"  {index}. {json.dumps(item, ensure_ascii=False)[:300]}"
                    zh.append(line)
                    en.append(line)
            else:
                zh.append(f"[pool-leaks] {json.dumps(leaks.data, ensure_ascii=False)[:2000]}")
                en.append(zh[-1])
        else:
            zh.append(f"[泄漏检测失败] {leaks.error}")
            en.append(f"[leaks failed] {leaks.error}")
        return _bilingual_lines(zh, en)

    def top_consumers(self, report_id: str) -> str:
        record, client, error = self._ready(report_id)
        if error:
            return error
        result = client.top_consumers(record["dump_dir"])
        if not result.ok:
            return result.error
        return "\n".join(
            [
                f"Top Memory Consumers · {record['filename']}",
                f"内存占用 Top · {record['filename']}",
                json.dumps(result.data or {}, ensure_ascii=False, indent=2)[:4000],
            ]
        )

    def sql_in_threads(self, report_id: str, onlyRisky: bool = True) -> str:
        record, client, error = self._ready(report_id)
        if error:
            return error
        result = client.sql_in_threads(record["dump_dir"], bool(onlyRisky))
        if not result.ok:
            return result.error
        data = result.data or []
        items = (
            data if isinstance(data, list) else (data.get("matches") or data.get("threads") or [])
        )
        sliced, total, more = _trim_list(items, TOP_DEFAULT, "matches")
        zh = [f"线程中的 SQL · {record['filename']} ({total} 条)"]
        en = [f"SQL in threads · {record['filename']} ({total} matches)"]
        if more:
            zh.append(more.split(" / ")[0])
            en.append(more.split(" / ")[1])
        for index, item in enumerate(sliced, 1):
            line = f"{index}. {json.dumps(item, ensure_ascii=False)[:300]}"
            zh.append(line)
            en.append(line)
        return _bilingual_lines(zh, en)
