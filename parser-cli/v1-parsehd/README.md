# V1：用 ParseHeapDump（文本进度）跑通端到端

> 已采纳的实务建议：**第一版先用 MAT 发行版的 `org.eclipse.mat.api.parse` application 跑通端到端**，
> Python 解析其文本进度；验证链路后再换 parser-cli 方式 A 拿精细 JSONL 进度（接口契约不变，平滑替换）。

## 为什么 V1 这么做
- **零接线**：发行版自带的 Equinox 已配好，extension registry 现成 → 最快跑通。
- **风险后置**：把最易踩坑的 EquinoxLauncher 接线推到 V2。
- **契约一致**：V1 的 worker 回调也是 `on_update(progress, phase)` / `on_message(text)`，
  与 parser-cli JSONL 版完全一致 → V2 只需替换 runner，worker/DB/路由都不动。

## 与 V2（parser-cli JSONL）的差异
| | V1（ParseHeapDump 文本） | V2（parser-cli 方式 A） |
|---|---|---|
| 启动 | 发行版 launcher，零接线 | 自接 Equinox（EquinoxLauncher TODO）|
| 进度 | 文本 Task/Subtask/点号 → 阶段+区间近似 | MAT IProgressListener → 精细 JSONL |
| 半成品隔离 | **直接写正式目录**，失败需清理 `*.index` | `.tmp/` + 原子 rename，天然隔离 |
| -Xmx 控制 | 直接调底层 launcher jar（绕开脚本写死的 3072M）| jar 自己设 |
| stats 提取 | 解析完读 SnapshotInfo（需另跑或解析报告）| 解析后直接读 SnapshotInfo 输出 |

> ⚠️ V1 的两个注意点：
> 1. **半成品**：MAT 把 index 写到 hprof 同目录，V1 不走 .tmp，所以**失败/中断必须清理 `*.index`**
>    （`mat_parse_runner._clean_partial_indexes` 已实现）。否则查询机可能读到坏 index。
> 2. **stats**：V1 的纯 parse 不直接给 SnapshotInfo 数值。两种补法：
>    - (a) 解析时带报告 `org.eclipse.mat.api:suspects`，从产出读概览；
>    - (b) **推荐**：解析完成后，由 **Java MAT Query 服务** reopen（index 已在，秒级）调
>      `getSnapshotInfo()` 回填 `heapdump_reports.stats`。这样 V1 不依赖文本里抓数字。

## 文本进度协议（MAT ConsoleProgressListener，已核对源码）
```
Task: Parsing <file>                          # 顶层任务
Subtask: Scanning <file>                       # ★ 阶段锚点 → Pass1
[................                               # 点号 = 进度（每点约 totalWork/80）
[INFO] Heap <file> contains 1,234,567 objects  # 用户消息
Subtask: Extracting objects from <file>        # → Pass2
Task: Removing unreachable objects             # → GarbageCleaner
Subtask: Re-indexing objects                   # → Re-indexing
Subtask: Writing <file>.idx.index              # → Writing index
Task: Calculating Dominator Tree               # → DominatorTree
Subtask: Computing dominators
Subtask: Calculate retained sizes              # → Retained sizes
]                                              # 完成
```
`mat_parse_runner.TextProgressMapper` 把这些映射成 (progress, phase)，阶段区间权重见代码（Pass1+Pass2 最重）。

## 就绪判定（关键）
解析成功 = **退出码 0** 且 **dump 目录生成了 `*.index`**（`_index_present`）。
Python worker 据此把 `heapdump_reports.status` 置 DONE；查询工具调用前校验 status=DONE（文档07 §11.5）。

## 端到端串联（V1 全景）
```
前端分块上传 → FastAPI /api/heapdump-reports 落 NFS(dump_dir) → 建报告 status=QUEUED
worker(SKIP LOCKED 取任务)
  → run_parse_v1(mat_home, hprof, dump_dir, on_update/on_message/should_cancel)
  → 实时写 heapdump_reports.progress/phase（SSE 推前端）
  → DONE 后：调 Java MAT Query 服务 reopen 回填 stats
前端出现"类 MAT 菜单" → 交互查询(Python 反代 → Java MAT 服务)
agent 工具(report_id) → 同样经 Python 反代 → Java MAT 服务
```

## 部署前置
1. MAT 发行版解压到 `MAT_HOME`（如 `/opt/mat`），确认 `plugins/org.eclipse.equinox.launcher_*.jar` 存在。
2. JDK 版本匹配 MAT（1.17.x→JDK21；1.14.0→JDK17，见 ../MAT-DEPENDENCIES.md）。
3. 解析机内存：`-Xmx` 按最大 dump 估算（`N×28.25 + C×1000` 字节 + 余量）。

## 自测
```bash
python3 mat_parse_runner.py     # 喂模拟 MAT 输出，打印 progress/phase 映射
```

## 切到 V2 时
只需把 worker 里的 `run_parse_v1(...)` 换成调 `parser-cli.jar`（JSONL），其余（DB/路由/SSE/agent）零改动。
