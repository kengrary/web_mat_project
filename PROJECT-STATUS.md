# 项目总进度（JVMind × MAT Heapdump 分析）

> 一句话：基于 Eclipse MAT 内核，给现有 JVMind 平台增加 heapdump 分析能力
> （解析 → Web 查询 → 领域诊断 → 自然语言 Agent）。

## ✅ 里程碑：query-service 全面跑通（2026-06-28）
**Java MAT 查询服务作为 OSGi bundle 在真实环境（MAT 1.17 + JDK21）启动成功，所有端点验证通过。**
- `/health` → `{"status":"ok"}`
- overview / histogram / dominator / threads / oql / leak-suspects / path2gc / tools 等端点全面通过。

---

## 三个工程模块状态

### 1. mat-study/（设计文档，9 份）✅ 完成
01 架构 · 02 算法 · 03 连接池诊断 · 04 Agent 工具化 · 05 系统集成 ·
06 四大视图 API · 07 对接 JVMind · 08 功能盘点缺口 · README 蓝图

### 2. parser-cli/（解析层 L1）✅ V1 可用（已实测）
- **V1（ParseHeapDump 文本进度）**：零编译，发行版 + `v1-parsehd/mat_parse_runner.py` 即端到端可用。
  - 实测：真实 hprof 解析跑通、产出全套 index、进度解析器修了 3 个 bug（VERIFICATION.md）。
- V2（自写 jar + JSONL 进度）：骨架就绪，待 V1 验证链路后切换（接口契约一致，平滑替换）。

### 3. query-service/（查询层 L2）✅ 运行 + 端到端验证通过
- 运行形态：**路线2 = OSGi bundle**（与 MAT 同类空间，无反射），部署见 DEPLOY.md（bundles.info 注册 + -clean）。
- 已实现能力：
  - 四大视图：histogram（4 排序 + 近似 retained）/ dominator（懒加载 + percent）/ threads（三级树）/ oql（双形态）
  - P0 王牌：leak-suspects / path2gc / merge-paths / top-consumers / retained-set（重操作异步任务化）
  - 全量 query：`/tools`（反射自描述清单）+ `/query`（通用直通）覆盖全部 ~57 个 MAT query
  - 运行机制：SnapshotManager 三层软引用 + 引用计数 + 并发上限 + MemoryWatcher 内存兜底 + TaskManager 异步/超时/结果软引用缓存
- 实测：用 MAT 测试 hprof 验证 overview/histogram/dominator/threads 数据正确（DEPLOY.md）。

---

## ⏳ 待办（按优先级）

### P0 · 让它真正服务起来
- [ ] **Python 侧串通端到端** → 已整理成给 Python 团队的清单：**`TODO-PYTHON-TEAM.md`**（含表结构/上传/队列/反代/agent 集成 + 验收标准 + 参考物料）
      - `heapdump_reports` 表加进 init_db（文档07，含 worker_id/heartbeat/attempts）
      - `/api/heapdump-reports`：分块上传 + DB 队列(SKIP LOCKED) + worker fork ParseHeapDump + SSE 进度
      - 解析 DONE 后调 query-service `/overview` 回填 stats
      - 交互查询：Python 反代到 query-service（内网 ACL）
- [ ] `com.sun.net.httpserver` 若在某环境不可见 → config.ini 暴露包 或 换 Netty（备选）

### P1 · 领域诊断（差异化能力）
- [x] 连接池 Inspector：HikariCP / Druid / Tomcat JDBC / DBCP2 / c3p0（文档03，`/connection-pools` + `/pool-leaks`）
      - 配置 + 统计 + 密码脱敏；空闲/活跃连接明细区分：
        Druid=connections[](holder)/activeConnections(pooledConnection)；HikariCP=connectionBag.sharedList 的 PoolEntry 按 state(IN_USE/NOT_IN_USE) 分类。
      - 泄漏归因：借出超时连接 + 借出栈(Druid removeAbandoned)/持有者(path2gc)。
      - 真实环境(HikariCP/Druid hprof)字段路径仍待单测验证。
- [x] 复合 agent 工具 `mat_diagnose_oom`：OOM触发线程+SQL风险+Leak Suspects 一步聚合（`OomComposite` + `/diagnose-oom`，异步 taskId；文档04/13）
- [ ] 扩展 report_context type=heapdump，MAT 工具注入 ReAct loop（文档04/07）
- [ ] 跨 gc日志/jstack/heapdump 联合诊断

### P2 · 增强
- [ ] 重操作 leak/retained/merge 的前端轮询接入（taskId）
- [ ] histogram/dominator 聚合分组（by package/classloader）+ OQL resultSetId 缓存
- [ ] 两 dump 对比（delta_histogram / comparison_report）定位缓慢泄漏
- [ ] V1 → V2 解析器切换（精细 JSONL 进度）
- [ ] 大 hprof（GB 级）内存/耗时压测，调 -Xmx 与软引用回收

---

## 关键经验（踩过的坑，避免重复）
1. **MAT 依赖不在 Maven Central** → Tycho `packaging=eclipse-plugin` + .target/update-site，不用 `<dependencies>`。
2. **MAT 发行版根目录不是 p2 仓库** → 用 update-site（联网）或 Directory location（本地 plugins/）。
3. **版本↔JDK**：1.17→JDK21，1.14→JDK17，1.12→JDK11，三处（MANIFEST BREE / pom release / executionEnvironment）须一致。
4. **部署不是 dropins，是 bundles.info** → plugins/ 放 jar + bundles.info 加一行（auto-start=true）+ `-clean` 重启。
5. **A 方案独立 jar 会 CNF**（容器外 classloader 看不到 bundle 类）→ 选路线2（自身 bundle 化），代码直接 import MAT。
6. **getHistogram 记录**：无实例类 count/shallow=0 是正常现象，非 bug。
7. **近似 retained**：getMinRetainedSize（支配树 O(1)）便宜且够用，比精确 getRetainedSet 快几个数量级。
