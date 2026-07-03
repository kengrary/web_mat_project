"""
第一版（V1）解析驱动：直接调 MAT 发行版的 `org.eclipse.mat.api.parse` application，
解析其 ConsoleProgressListener 的**文本进度**，映射成 heapdump_reports.progress/phase。

为什么 V1 这样做（见 mat-study/ + parser-cli/MAT-DEPENDENCIES.md §4.5）：
- 零接线：发行版自带的 Equinox 已配好，extension registry 现成，最快跑通端到端。
- 代价：进度是 MAT 的文本（Task/Subtask/点号），比 JSONL 粗——但足够验证链路。
- 验证通过后，再切到 parser-cli 方式 A 拿精细 JSONL（接口契约同样是 progress/phase，平滑替换）。

内存控制：ParseHeapDump.sh 把 -Xmx 写死（3072M），不够大堆用。
所以这里**直接调底层 launcher jar**，-Xmx 可控；文本输出与脚本完全一致。

退出码：MAT application 正常 0；OOM 返回 79（ParseSnapshotApp 约定）。
中断：直接 kill 子进程（最干净），并清理半成品 index。
"""
from __future__ import annotations

import asyncio
import glob
import os
import re
import shutil
from dataclasses import dataclass
from datetime import datetime


# ---------------------------------------------------------------------------
# 阶段映射：把 MAT 的 Task/Subtask 文本映射成「阶段名 + 全局进度区间」
# 区间权重参考解析管线（Pass1+Pass2 最重，支配树次之），用于把"阶段内点号"
# 线性映射到一个全局 0~1 进度。区间是经验近似，给前端一个顺滑的推进感。
# ---------------------------------------------------------------------------
@dataclass
class Phase:
    key: str            # 写入 heapdump_reports.phase
    start: float        # 全局进度起点
    end: float          # 全局进度终点


# 匹配规则：(正则, Phase)。按出现顺序匹配 Task/Subtask 文本。
# 文本来自 ConsoleProgressListener: "Task: <name>" / "Subtask: <name>"
# ★ 规则与阶段顺序经真实 MAT 1.12 输出验证（见 v1-parsehd/README "实测验证"）。
_PHASE_RULES: list[tuple[re.Pattern, Phase]] = [
    # index 已存在时 MAT 直接 reopen，秒级完成（实测发现：第二次解析走这条）
    (re.compile(r"Reopening", re.I),                Phase("Reopening",             0.00, 1.00)),
    (re.compile(r"Scanning|Parsing", re.I),         Phase("Pass1 Scanning",        0.00, 0.35)),
    (re.compile(r"Extracting objects", re.I),       Phase("Pass2 Extracting",      0.35, 0.62)),
    # GarbageCleaner 阶段的真实 subtask 名（实测修正：原 "Removing unreachable" 是 Task 非 Subtask）
    (re.compile(r"unreachable|Marking reachable", re.I),
                                                    Phase("GarbageCleaner",        0.62, 0.70)),
    (re.compile(r"Re-indexing", re.I),              Phase("Re-indexing",           0.70, 0.78)),
    (re.compile(r"Writing.*index", re.I),           Phase("Writing index",         0.78, 0.82)),
    (re.compile(r"Dominator Tree|Depth-first|Computing dominators", re.I),
                                                    Phase("DominatorTree",         0.82, 0.95)),
    (re.compile(r"retained size|dominators index", re.I),
                                                    Phase("Retained sizes",        0.95, 0.99)),
]

# Task: / Subtask: 行
_TASK_RE = re.compile(r"^(Task|Subtask):\s*(.+?)\s*$")
# 用户消息行
_MSG_RE = re.compile(r"^\[(INFO|WARNING|ERROR|UNKNOWN)\]\s*(.*)$")
# "[....." 进度点（一行里可能是 "[" 开头跟若干点，或纯点续行）
_DOTS_RE = re.compile(r"^\[?(\.+)")
# MAT 报告对象数等关键信息
_OBJECTS_RE = re.compile(r"contains\s+([\d,]+)\s+objects", re.I)


def _now() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


class TextProgressMapper:
    """把 MAT 文本输出逐行映射成 (progress, phase)。无 totalWork，故进度按阶段区间 + 点号近似。"""

    def __init__(self) -> None:
        self.current: Phase | None = None
        self.dots_in_phase = 0
        # 经验值：每个阶段大致 80 个点（ConsoleProgressListener 把 totalWork 切成 ~80 点）
        self._dots_per_phase = 80
        self.last_progress = 0.0

    def feed(self, line: str) -> tuple[float | None, str | None, str | None]:
        """
        返回 (progress, phase, message)。
        progress/phase 为 None 表示本行无进度更新；message 非 None 表示一条 INFO/WARN/ERROR。
        """
        line = line.rstrip("\n")

        m = _TASK_RE.match(line.strip())
        if m:
            name = m.group(2)
            phase = self._match_phase(name)
            if phase:
                # 防阶段标签回退：MAT 的 "Re-indexing classes"/"Re-indexing outbound" 会穿插在
                # Writing index 之间，若直接切回会让 phase 标签来回跳。只接受 start 不低于当前阶段的。
                if self.current is not None and phase.start < self.current.start:
                    # 仍按当前阶段推进，不切回更早的标签
                    return None, None, None
                self.current = phase
                self.dots_in_phase = 0
                self.last_progress = max(self.last_progress, phase.start)
                return self.last_progress, phase.key, None
            return None, None, None

        msg = _MSG_RE.match(line.strip())
        if msg:
            return None, None, f"[{msg.group(1)}] {msg.group(2)}"

        # 进度点：在当前阶段区间内线性推进
        dots = _DOTS_RE.match(line.strip())
        if dots and self.current:
            self.dots_in_phase += len(dots.group(1))
            frac = min(1.0, self.dots_in_phase / self._dots_per_phase)
            p = self.current.start + (self.current.end - self.current.start) * frac
            self.last_progress = max(self.last_progress, round(p, 3))
            return self.last_progress, self.current.key, None

        return None, None, None

    @staticmethod
    def _match_phase(name: str) -> Phase | None:
        for pat, phase in _PHASE_RULES:
            if pat.search(name):
                return phase
        return None


def _clean_partial_indexes(dump_dir: str, hprof_name: str) -> None:
    """中断/失败后清理半成品 index（保留原始 hprof）。"""
    for f in glob.glob(os.path.join(dump_dir, "*.index")):
        try:
            os.remove(f)
        except OSError:
            pass
    for f in glob.glob(os.path.join(dump_dir, "*.threads")) + \
             glob.glob(os.path.join(dump_dir, "*.log.index")):
        try:
            os.remove(f)
        except OSError:
            pass


async def run_parse_v1(
    *,
    mat_home: str,                 # MAT 发行版解压目录（含 plugins/）
    hprof_path: str,
    dump_dir: str,                 # = os.path.dirname(hprof_path)，index 落这里
    xmx: str = "32g",
    java: str = "java",
    on_update,                     # async fn(progress: float|None, phase: str)
    on_message=None,               # async fn(text: str) | None
    should_cancel=None,            # async fn() -> bool | None
) -> str:
    """
    返回 'DONE' | 'FAILED' | 'CANCELLED'

    注意：MAT 把 index 写到 hprof 所在目录。V1 直接写正式 dump_dir（不走 .tmp/rename），
    所以失败/中断必须 _clean_partial_indexes。切到 parser-cli 方式 A 后才有原子 rename。
    """
    launcher = _find_launcher(mat_home)
    cmd = [
        java, f"-Xmx{xmx}", "-XX:+UseG1GC",
        "-jar", launcher,
        "-consoleLog", "-nosplash",
        "-application", "org.eclipse.mat.api.parse",
        hprof_path,
        # 可透传报告: "org.eclipse.mat.api:suspects"（V1 先不带，纯解析最快）
    ]

    proc = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,   # MAT 进度走 stdout；合并便于逐行读
        cwd=dump_dir,
    )

    mapper = TextProgressMapper()
    canceled = False

    async def pump_cancel():
        nonlocal canceled
        if should_cancel is None:
            return
        while proc.returncode is None:
            if await should_cancel():
                canceled = True
                proc.kill()
                return
            await asyncio.sleep(2)

    cancel_task = asyncio.create_task(pump_cancel())

    try:
        assert proc.stdout is not None
        async for raw in proc.stdout:
            line = raw.decode("utf-8", "replace")
            progress, phase, message = mapper.feed(line)
            if progress is not None:
                await on_update(progress, phase or "")
            if message and on_message:
                await on_message(message)
    finally:
        rc = await proc.wait()
        cancel_task.cancel()

    hprof_name = os.path.basename(hprof_path)
    if canceled:
        _clean_partial_indexes(dump_dir, hprof_name)
        return "CANCELLED"
    if rc == 0 and _index_present(dump_dir):
        await on_update(1.0, "DONE")
        return "DONE"
    if rc == 79:
        _clean_partial_indexes(dump_dir, hprof_name)
        return "FAILED"   # OOM
    _clean_partial_indexes(dump_dir, hprof_name)
    return "FAILED"


def _index_present(dump_dir: str) -> bool:
    # 解析成功的标志：生成了主 index（<prefix>.index）与若干 *.index
    return len(glob.glob(os.path.join(dump_dir, "*.index"))) > 0


def _find_launcher(mat_home: str) -> str:
    matches = glob.glob(os.path.join(mat_home, "plugins", "org.eclipse.equinox.launcher_*.jar"))
    if not matches:
        raise FileNotFoundError(
            f"equinox launcher not found under {mat_home}/plugins/ "
            f"(expected org.eclipse.equinox.launcher_*.jar)"
        )
    return sorted(matches)[-1]   # 取版本最高


# ---- 自测：用一行行喂入模拟 MAT 输出，验证阶段映射 ----
if __name__ == "__main__":
    sample = [
        "Task: Parsing /nfs/heapdumps/hd_x/app.hprof",
        "Subtask: Scanning /nfs/heapdumps/hd_x/app.hprof",
        "[................",
        "[INFO] Heap /nfs/.../app.hprof contains 1,234,567 objects",
        "Subtask: Extracting objects from /nfs/.../app.hprof",
        "[..............................",
        "Task: Removing unreachable objects",
        "Subtask: Re-indexing objects",
        "Subtask: Writing /nfs/.../app.idx.index",
        "Task: Calculating Dominator Tree",
        "Subtask: Computing dominators",
        "Subtask: Calculate retained sizes",
    ]
    mp = TextProgressMapper()
    for ln in sample:
        p, ph, msg = mp.feed(ln)
        if p is not None:
            print(f"  progress={p:<6} phase={ph}")
        if msg:
            print(f"  message: {msg}")
