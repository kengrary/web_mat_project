package com.jvmind.mat.query.http;

import com.jvmind.mat.query.core.DumpLocator;
import com.jvmind.mat.query.core.ResultCache;
import com.jvmind.mat.query.core.SnapshotManager;
import com.jvmind.mat.query.model.Json;
import com.jvmind.mat.query.view.HistogramView;
import com.jvmind.mat.query.view.ObjectRows;
import com.jvmind.mat.query.view.OverviewView;
import com.jvmind.mat.query.view.DominatorView;
import com.jvmind.mat.query.view.ThreadsView;
import com.jvmind.mat.query.view.OqlView;
import com.jvmind.mat.query.view.DiagnosticsView;
import com.jvmind.mat.query.view.ToolsView;
import com.jvmind.mat.query.view.ObjectView;
import com.jvmind.mat.query.view.ArrayElementsView;
import com.jvmind.mat.query.view.CollectionEntriesView;
import com.jvmind.mat.query.view.ThreadLocalsView;
import com.jvmind.mat.query.view.SqlExtractor;
import com.jvmind.mat.query.view.OomDiagnosis;
import com.jvmind.mat.query.view.ConnectionPoolView;
import com.jvmind.mat.query.view.PoolLeakView;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.NamedReference;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 内网 REST 服务（JDK 内置 HttpServer，轻量，无重型框架）。
 *
 * <p>鉴权：<b>无</b> —— 仅靠网络 ACL，全内网通讯（已确认决策）。本服务只被 Python 反代调用。
 *
 * <p>所有查询走「acquire 租约 → 查询 → try-with-resources 自动 release」范式，
 * 保证引用计数正确、snapshot 不会在使用中被回收（SnapshotManager 三层软引用核心）。
 *
 * <p>路径约定：用 ?dumpDir= 传 NFS 目录（ByExplicitDir 定位），契合"Python 当唯一权威，
 * 把可信参数传给内网 Java"。也可改成 /snapshots/{dumpId}/... + DumpLocator.ByConvention。
 *
 * 端点（M2 范围）：
 *   GET /health
 *   GET /overview?dumpDir=...                            → 回填 stats / 概览
 *   GET /histogram?dumpDir=...&top=50&offset=0&sort=shallow
 * 后续（M3）：/dominator /threads /oql ...
 */
public final class QueryHttpServer {

    private static final Logger LOG = Logger.getLogger(QueryHttpServer.class.getName());

    private final SnapshotManager manager;
    private final com.jvmind.mat.query.task.TaskManager tasks;
    private final ResultCache cache;
    private final com.jvmind.mat.query.core.ResultSetCache resultSets =
            new com.jvmind.mat.query.core.ResultSetCache();
    private final HttpServer server;
    /** CORS 允许的来源；null/空=不发 CORS 头（默认，安全）。"*"=允许所有。进程内单服务，用 static 便于静态 send 读取。 */
    private static String corsOrigin;

    public QueryHttpServer(SnapshotManager manager, com.jvmind.mat.query.task.TaskManager tasks,
                           ResultCache cache,
                           String bindAddress, int port, int threads) throws IOException {
        this.manager = manager;
        this.tasks = tasks;
        this.cache = cache != null ? cache : new ResultCache(false, null);
        // CORS 来源：-DMAT_QUERY_CORS=*  或  =http://localhost:5173 。默认不发头（前端应走 Python 反代）
        String c = System.getProperty("MAT_QUERY_CORS");
        if (c == null || c.isBlank()) c = System.getenv("MAT_QUERY_CORS");
        corsOrigin = (c == null || c.isBlank()) ? null : c.trim();
        // bindAddress: "127.0.0.1"=仅本机(默认,最安全) / "0.0.0.0"=所有网卡(跨机访问)
        this.server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(threads));

        ctx("/health", this::health);
        ctx("/overview", ex -> {  // ?full=true → 含 heap dump 内部信息 + System Properties
            boolean full = "true".equalsIgnoreCase(Query.parse(ex).getOrDefault("full", "false"));
            withSnapshot(ex, s -> full ? OverviewView.toJsonFull(s) : OverviewView.toJson(s));
        });
        ctx("/histogram", this::histogram);
        ctx("/dominator", this::dominator);   // 支配树（对象级懒加载）
        ctx("/threads", this::threads);       // 线程树（三级）
        ctx("/oql", this::oql);               // OQL（POST body）
        // P0 王牌诊断：重操作走异步任务（提交即返回 taskId，/tasks/{id} 轮询）
        ctx("/leak-suspects", this::leakSuspects);
        ctx("/retained-set", this::retainedSet);
        ctx("/merge-paths", this::mergePaths);
        // 较轻的可同步：top-consumers / path2gc（path2gc 单对象通常快）
        ctx("/top-consumers", ex ->
                withSnapshot(ex, s -> DiagnosticsView.topConsumers(s, null)));
        ctx("/path2gc", this::path2gc);
        // 异步任务轮询/取消
        ctx("/tasks", this::taskPoll);
        // 工具自描述清单 + 通用 query 直通（agent 基础设施）
        ctx("/tools", this::tools);
        ctx("/query", this::genericQuery);
        ctx("/object", this::object);          // 对象详情（Inspector：字段+出/入引用+GC root）
        ctx("/threadlocals", this::threadlocals);  // ThreadLocal 全局视图（泄漏排查）
        ctx("/sql-in-threads", this::sqlInThreads); // 从线程栈提取 SQL（SQL 引起的 OOM 排查）
        ctx("/oom-diagnosis", this::oomDiagnosis);  // OOM 触发线程识别 + 元凶/受害者判定
        ctx("/diagnose-oom", this::diagnoseOom);    // 复合：OOM诊断+SQL风险+Leak Suspects 一次出（异步，含leakhunter）
        ctx("/connection-pools", ex ->              // 连接池配置+统计（密码脱敏，识别主流池）
                withSnapshot(ex, ConnectionPoolView::extract));
        ctx("/pool-leaks", this::poolLeaks);        // 连接池泄漏归因（借出超时连接+借出栈）
        ctx("/list-objects", this::listObjects);   // 收集目标对象的出/入引用对象 id，返回 resultSetId
        ctx("/array-elements", this::arrayElements);   // Object[] 元素列表（分页）
        ctx("/collection-entries", this::collectionEntries); // Map/Collection 实例条目（分页）
        ctx("/references", this::references);    // 详细行：每个引用对象 + 字段名/大小（References 视图）
    }

    /**
     * 注册 handler，统一处理 CORS 预检（OPTIONS）。
     * OPTIONS 请求直接回 204 + CORS 头（浏览器预检）；其余请求委托给真实 handler。
     */
    private void ctx(String path, com.sun.net.httpserver.HttpHandler handler) {
        server.createContext(path, ex -> {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                applyCors(ex);
                ex.sendResponseHeaders(204, -1);
                ex.close();
                return;
            }
            handler.handle(ex);
        });
    }

    /** 给响应加 CORS 头（仅当配置了 corsOrigin）。 */
    private static void applyCors(HttpExchange ex) {
        if (corsOrigin == null) return;
        var h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", corsOrigin);
        h.set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type");
        h.set("Access-Control-Max-Age", "600");
    }

    public void start() {
        server.start();
        LOG.info("MAT Query service listening on " + server.getAddress());
    }

    public void stop() {
        server.stop(2);
    }

    // ===================== handlers =====================

    private void health(HttpExchange ex) throws IOException {
        send(ex, 200, Json.obj().kv("status", "ok").endObj());
    }

    private void histogram(HttpExchange ex) throws IOException {
        Map<String, String> q = Query.parse(ex);
        int top = Query.intOr(q, "top", 50);
        int offset = Query.intOr(q, "offset", 0);
        HistogramView.Sort sort = switch (q.getOrDefault("sort", "shallow")) {
            case "retained" -> HistogramView.Sort.RETAINED;
            case "count"    -> HistogramView.Sort.COUNT;
            default         -> HistogramView.Sort.SHALLOW;
        };
        // 近似 retained 默认开启（便宜、贴近 MAT UI）；?approx=false 可关掉
        boolean approx = !"false".equalsIgnoreCase(q.getOrDefault("approx", "true"));
        // objectSet=rs-xxx：对某结果集做直方图（分析流复用）；否则全堆
        String rs = q.get("objectSet");
        withSnapshot(ex, s -> {
            try {
                if (rs != null && !rs.isBlank()) {
                    int[] ids = resultSets.get(rs, q.get("dumpDir"));
                    if (ids == null) throw new IllegalStateException("resultSet not found (expired?): " + rs);
                    return HistogramView.bySubset(s, ids, top, offset, sort, approx);
                }
                return HistogramView.byClass(s, top, offset, sort, approx);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** 支配树：?parent=ROOT|<objectId>&top=&offset= 。一次一层，懒加载。 */
    private void dominator(HttpExchange ex) throws IOException {
        Map<String, String> q = Query.parse(ex);
        String parent = q.getOrDefault("parent", "ROOT");
        int top = Query.intOr(q, "top", 50);
        int offset = Query.intOr(q, "offset", 0);
        withSnapshot(ex, s -> DominatorView.children(s, parent, top, offset));
    }

    /**
     * 线程树（三级）：
     *   /threads?dumpDir=                          → 线程列表
     *   /threads?dumpDir=&thread=<id>              → 该线程栈帧
     *   /threads?dumpDir=&thread=<id>&frame=<idx>  → 该帧局部变量
     */
    private void threads(HttpExchange ex) throws IOException {
        Map<String, String> q = Query.parse(ex);
        String thread = q.get("thread");
        if (thread == null) {
            withSnapshot(ex, s -> ThreadsView.threads(s, Query.intOr(q, "top", 100), Query.intOr(q, "offset", 0)));
            return;
        }
        int tid;
        try {
            tid = Query.parseObjectId(thread);
        } catch (IllegalArgumentException badThread) {
            send(ex, 400, err("invalid 'thread' parameter: " + badThread.getMessage()));
            return;
        }
        String frame = q.get("frame");
        if (frame == null) {
            withSnapshot(ex, s -> ThreadsView.frames(s, tid));
            return;
        }
        int frameIdx;
        try {
            frameIdx = Integer.parseInt(frame);
        } catch (NumberFormatException badFrame) {
            send(ex, 400, err("invalid 'frame' parameter: " + frame));
            return;
        }
        withSnapshot(ex, s -> ThreadsView.locals(s, tid, frameIdx));
    }

    /** OQL：POST body {"q":"...","limit":200,"offset":0}，dumpDir 走 query 参数。 */
    private void oql(HttpExchange ex) throws IOException {
        Map<String, String> q = Query.parse(ex);
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String oql = MiniJson.getString(body, "q");
        int limit = (int) MiniJson.getLong(body, "limit", 200);
        int offset = (int) MiniJson.getLong(body, "offset", 0);
        // view=list|histogram, sort=shallow|retained|count（query 参数，便于前端切换）
        String view = q.getOrDefault("view", "list");
        String sort = q.get("sort");
        if (oql == null || oql.isBlank()) {
            send(ex, 400, err("missing 'q' in body"));
            return;
        }
        String dumpDir = q.get("dumpDir");
        withSnapshot(ex, s -> OqlView.execute(s, oql, limit, offset, view, sort, resultSets, dumpDir));
    }

    /** list_objects：?dumpDir=&direction=out|in&objectId=N 。返回 resultSetId。 */
    private void listObjects(HttpExchange ex) throws IOException {
        Map<String, String> q = Query.parse(ex);
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String direction = MiniJson.getString(body, "direction"); // "out" | "in"
        long objectId = MiniJson.getLong(body, "objectId", -1);
        String dumpDir = q.get("dumpDir");
        if (objectId < 0) { send(ex, 400, err("missing 'objectId'")); return; }
        if (dumpDir == null || dumpDir.isBlank()) { send(ex, 400, err("missing 'dumpDir' query param")); return; }
        if (!"out".equalsIgnoreCase(direction) && !"in".equalsIgnoreCase(direction)) {
            send(ex, 400, err("direction must be 'out' or 'in'"));
            return;
        }
        final boolean outbound = "out".equalsIgnoreCase(direction);
        withSnapshot(ex, s -> {
            try {
                int[] ids;
                if (outbound) {
                    // outbound: dedupe getOutboundReferences().getObjectId()
                    IObject o = s.getObject((int) objectId);
                    java.util.Set<Integer> set = new java.util.LinkedHashSet<>();
                    List<NamedReference> refs = o.getOutboundReferences();
                    if (refs != null) {
                        for (NamedReference nr : refs) {
                            try {
                                int cid = nr.getObjectId();
                                if (cid >= 0) set.add(cid);
                            } catch (Exception ignore) { }
                        }
                    }
                    ids = set.stream().mapToInt(Integer::intValue).toArray();
                } else {
                    ids = s.getInboundRefererIds((int) objectId);
                    if (ids == null) ids = new int[0];
                }
                String rsId = resultSets.put(dumpDir, ids);
                return Json.obj()
                        .kv("resultSetId", rsId)
                        .kv("count", ids.length)
                        .endObj();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** references：收集目标对象的出/入引用对象，返回详细行（含字段名/大小）。
     *  body: {"direction":"out"|"in", "objectId":N}
     *  query: dumpDir, top, offset
     */
    private void references(HttpExchange ex) throws IOException {
        Map<String, String> q = Query.parse(ex);
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String direction = MiniJson.getString(body, "direction");   // "out" | "in"
        int objectId = (int) MiniJson.getLong(body, "objectId", -1);
        String dumpDir = q.get("dumpDir");
        int top = Query.intOr(q, "top", 50);
        int offset = Query.intOr(q, "offset", 0);

        if (objectId < 0) { send(ex, 400, err("missing 'objectId' in body")); return; }
        if (dumpDir == null || dumpDir.isBlank()) { send(ex, 400, err("missing 'dumpDir' query param")); return; }
        if (!"out".equalsIgnoreCase(direction) && !"in".equalsIgnoreCase(direction)) {
            send(ex, 400, err("direction must be 'out' or 'in'")); return;
        }

        withSnapshot(ex, s -> {
            try {
                // 构建 "objectId -> fieldName" 映射
                java.util.LinkedHashMap<Integer, String> fieldNames = new java.util.LinkedHashMap<>();
                java.util.List<Integer> order = new java.util.ArrayList<>();

                if ("out".equalsIgnoreCase(direction)) {
                    IObject o = s.getObject(objectId);
                    java.util.List<NamedReference> refs = o.getOutboundReferences();
                    if (refs != null) {
                        for (NamedReference nr : refs) {
                            try {
                                int cid = nr.getObjectId();
                                if (cid >= 0 && !fieldNames.containsKey(cid)) {
                                    fieldNames.put(cid, nr.getName());   // 字段名（如 "table" / "[7]"）
                                    order.add(cid);
                                }
                            } catch (Exception ignore) { }
                        }
                    }
                } else {
                    // inbound：getInboundRefererIds 拿 referer ids，再反向查它指向我们的字段名
                    int[] referers = s.getInboundRefererIds(objectId);
                    if (referers != null) {
                        for (int rid : referers) {
                            if (fieldNames.containsKey(rid)) continue;
                            String fn = reverseFieldName(s, rid, objectId);
                            fieldNames.put(rid, fn);
                            order.add(rid);
                        }
                    }
                }

                // 分页 + 渲染
                int total = order.size();
                int from = Math.min(offset, total);
                int to = Math.min(offset + top, total);

                Json rows = Json.arr();
                for (int i = from; i < to; i++) {
                    int id = order.get(i);
                    IObject obj = s.getObject(id);
                    long shallow = obj.getUsedHeapSize();
                    long retained = 0;
                    try { retained = Math.abs(obj.getRetainedHeapSize()); } catch (Exception ignore) { }
                    rows.item(Json.obj()
                            .kv("objectId", id)
                            .kv("label", ObjectRows.safeDisplayName(obj))
                            .kv("className", obj.getClazz() != null ? obj.getClazz().getName() : "?")
                            .kv("fieldName", fieldNames.getOrDefault(id, ""))
                            .kv("shallowBytes", shallow)
                            .kv("shallow", Json.humanBytes(shallow))
                            .kv("retainedBytes", retained)
                            .kv("retained", retained > 0 ? Json.humanBytes(retained) : "n/a")
                            .endObj());
                }

                Json out = Json.obj()
                        .kv("kind", "object-list")
                        .kv("direction", direction)
                        .kv("source", objectId)
                        .raw("rows", rows.endArr())
                        .kv("totalRows", total)
                        .kv("returned", to - from);
                if (to < total) out.kv("cursor", "offset:" + to);
                return out.endObj();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** inbound 用：找 referer 的 outbound refs 里指向 targetId 的那条的字段名。找不到返回 "". */
    private static String reverseFieldName(ISnapshot s, int refererId, int targetId) {
        try {
            IObject referer = s.getObject(refererId);
            java.util.List<NamedReference> refs = referer.getOutboundReferences();
            if (refs != null) {
                for (NamedReference nr : refs) {
                    try {
                        if (nr.getObjectId() == targetId) return nr.getName();
                    } catch (Exception ignore) { }
                }
            }
        } catch (Exception ignore) { }
        return "";
    }

    /** path2gc：?dumpDir=&object=<id>&excludeWeakSoft=true 。单对象通常快，同步返回。 */
    private void path2gc(HttpExchange ex) throws IOException {
        Map<String, String> q = Query.parse(ex);
        int objectId = Query.intOr(q, "object", -1);
        boolean excl = !"false".equalsIgnoreCase(q.getOrDefault("excludeWeakSoft", "true"));
        if (objectId < 0) { send(ex, 400, err("missing 'object'")); return; }
        withSnapshot(ex, s -> DiagnosticsView.pathToGcRoots(s, objectId, excl, null));
    }

    /** 连接池泄漏归因：?dumpDir=&thresholdMs=300000 （借出超阈值=疑似泄漏，默认5min）。 */
    private void poolLeaks(HttpExchange ex) throws IOException {
        long th = (long) Query.intOr(Query.parse(ex), "thresholdMs", 300000);
        withSnapshot(ex, s -> PoolLeakView.diagnose(s, th));
    }

    /** OOM 诊断：?dumpDir=&culpritPct=30 → 触发线程 + 元凶/受害者判定。 */
    private void oomDiagnosis(HttpExchange ex) throws IOException {
        int pct = Query.intOr(Query.parse(ex), "culpritPct", 30);
        withSnapshot(ex, s -> OomDiagnosis.diagnose(s, pct));
    }

    /**
     * 复合 OOM 诊断（mat_diagnose_oom）：?dumpDir=&culpritPct=30&onlyRisky=true
     * 聚合 oom-diagnosis + sql-in-threads + leak-suspects，给一个 summary + 三路原始 section。
     * 内含 leakhunter（全图分析重操作）→ 走异步任务，返回 taskId，轮询 /tasks/{id}。
     */
    private void diagnoseOom(HttpExchange ex) throws IOException {
        Map<String, String> q = Query.parse(ex);
        String dumpDir = q.get("dumpDir");
        if (blank(dumpDir)) { send(ex, 400, err("missing dumpDir")); return; }
        int pct = Query.intOr(q, "culpritPct", 30);
        boolean onlyRisky = !"false".equalsIgnoreCase(q.getOrDefault("onlyRisky", "true"));
        String taskId = submitTask("diagnose-oom", dumpDir,
                ResultCache.args("culpritPct", pct, "onlyRisky", onlyRisky),
                (s, progress) -> com.jvmind.mat.query.view.OomComposite.diagnose(s, pct, onlyRisky, progress));
        sendAccepted(ex, taskId);
    }

    /** 从线程栈提取 SQL：?dumpDir=&onlyRisky=true|false （默认 true，过滤心跳类）。 */
    private void sqlInThreads(HttpExchange ex) throws IOException {
        Map<String, String> q = Query.parse(ex);
        boolean onlyRisky = !"false".equalsIgnoreCase(q.getOrDefault("onlyRisky", "true"));
        withSnapshot(ex, s -> SqlExtractor.extract(s, onlyRisky));
    }

    /** ThreadLocals 全局视图：?dumpDir=&groupBy=valueClass|thread&top=  （泄漏排查）。 */
    private void threadlocals(HttpExchange ex) throws IOException {
        Map<String, String> q = Query.parse(ex);
        int top = Query.intOr(q, "top", 100);
        String groupBy = q.getOrDefault("groupBy", "valueClass");
        withSnapshot(ex, s -> "thread".equals(groupBy)
                ? ThreadLocalsView.byThread(s, top)
                : ThreadLocalsView.byValueClass(s, top));
    }

    /** 对象详情：?dumpDir=&id=<objectId> → 字段+出/入引用+GC root（Inspector 用）。 */
    private void object(HttpExchange ex) throws IOException {
        Map<String, String> q = Query.parse(ex);
        int id = Query.intOr(q, "id", -1);
        if (id < 0) { send(ex, 400, err("missing 'id'")); return; }
        withSnapshot(ex, s -> ObjectView.detail(s, id));
    }

    /** 对象数组元素列表：?id=<objectId>&top=&offset= 。分页。 */
    private void arrayElements(HttpExchange ex) throws IOException {
        Map<String, String> q = Query.parse(ex);
        int id = Query.intOr(q, "id", -1);
        if (id < 0) { send(ex, 400, err("missing 'id'")); return; }
        int top = Query.intOr(q, "top", 25);
        int offset = Query.intOr(q, "offset", 0);
        final int fid = id, ftop = top, foffset = offset;
        withSnapshot(ex, s -> ArrayElementsView.elements(s, fid, ftop, foffset));
    }

    /** Map / Collection 实例条目：?id=<objectId>&top=&offset= 。分页。 */
    private void collectionEntries(HttpExchange ex) throws IOException {
        Map<String, String> q = Query.parse(ex);
        int id = Query.intOr(q, "id", -1);
        if (id < 0) { send(ex, 400, err("missing 'id'")); return; }
        int top = Query.intOr(q, "top", 25);
        int offset = Query.intOr(q, "offset", 0);
        final int fid = id, ftop = top, foffset = offset;
        withSnapshot(ex, s -> CollectionEntriesView.entries(s, fid, ftop, foffset));
    }

    /** leak-suspects：重操作 → 异步任务，立即返回 taskId。 */
    private void leakSuspects(HttpExchange ex) throws IOException {
        String dumpDir = Query.parse(ex).get("dumpDir");
        if (blank(dumpDir)) { send(ex, 400, err("missing dumpDir")); return; }
        String taskId = submitTask("leak-suspects", dumpDir,
                (s, progress) -> DiagnosticsView.leakSuspects(s, progress));
        sendAccepted(ex, taskId);
    }

    /** merge_shortest_paths：重操作 → 异步。POST body {"objects":[...]} 。 */
    private void mergePaths(HttpExchange ex) throws IOException {
        asyncWithIds(ex, "merge-paths", DiagnosticsView::mergeShortestPaths);
    }

    /** show_retained_set：重操作 → 异步。POST body {"objects":[...]} 。 */
    private void retainedSet(HttpExchange ex) throws IOException {
        asyncWithIds(ex, "retained-set", DiagnosticsView::retainedSet);
    }

    /**
     * 工具自描述清单：GET /tools[?filter=关键字][&core=true]
     * 不需要 snapshot（纯反射 registry），但需 Equinox 已启动。供 agent 发现工具。
     */
    private void tools(HttpExchange ex) throws IOException {
        Map<String, String> q = Query.parse(ex);
        String filter = q.get("filter");
        boolean core = "true".equalsIgnoreCase(q.getOrDefault("core", "false"));
        boolean verbose = "true".equalsIgnoreCase(q.getOrDefault("verbose", "false"));
        try {
            send(ex, 200, ToolsView.list(filter, core, verbose));
        } catch (Throwable t) {
            send(ex, 500, err("tools listing failed: " + t.getMessage()
                    + " (is Equinox started / registry ready?)"));
        }
    }

    /**
     * 通用 query 直通：POST /query?dumpDir=...[&async=true]
     *   body {"command":"duplicate_classes","args":{"...":"..."}}
     * 覆盖 ~40 个长尾 query（QueryRunner + ResultRenderer）。
     * async=true → 走任务（适合可能重的 query）；默认同步。
     */
    private void genericQuery(HttpExchange ex) throws IOException {
        Map<String, String> q = Query.parse(ex);
        String dumpDir = q.get("dumpDir");
        if (blank(dumpDir)) { send(ex, 400, err("missing dumpDir")); return; }
        String body;
        try {
            body = new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) { send(ex, 400, err("cannot read body")); return; }

        String command = MiniJson.getString(body, "command");
        if (blank(command)) { send(ex, 400, err("missing 'command'")); return; }
        // args 解析：本骨架支持简单 string/number 参数（MiniJson）。复杂参数（对象 id 数组）
        // 走 "objects" 约定（与 retained-set/merge-paths 一致）。落地可换 Jackson 支持任意结构。
        Map<String, Object> args = parseArgs(body);

        boolean async = "true".equalsIgnoreCase(q.getOrDefault("async", "false"));
        if (async) {
            Map<String, String> cacheKey = ResultCache.args("command", command, "args", argsToCacheString(args));
            String taskId = submitTask("query:" + command, dumpDir, cacheKey,
                    (s, progress) -> DiagnosticsView.runArbitrary(s, command, args, progress));
            sendAccepted(ex, taskId);
        } else {
            withSnapshot(ex, s -> DiagnosticsView.runArbitrary(s, command, args, null));
        }
    }

    /** 把 args map 序列化成稳定字符串，用于缓存 key（TreeMap 排序 → 字符串）。*/
    private static String argsToCacheString(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "";
        java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>(args);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            sb.append(e.getKey()).append('=');
            Object v = e.getValue();
            if (v instanceof int[] arr) {
                int[] copy = arr.clone();
                java.util.Arrays.sort(copy);
                sb.append(java.util.Arrays.toString(copy));
            } else {
                sb.append(v);
            }
            sb.append(';');
        }
        return sb.toString();
    }

    /** 从 body 解析 args（轻量：取 "objects" int[] 约定，其余 query 可无参或后续扩展）。 */
    private static Map<String, Object> parseArgs(String body) {
        Map<String, Object> args = new java.util.HashMap<>();
        int[] objects = MiniJson.getIntArray(body, "objects");
        if (objects.length > 0) {
            args.put("objects", objects);   // 多数对象集类 query 的入参名
        }
        // 单对象约定
        long obj = MiniJson.getLong(body, "object", Long.MIN_VALUE);
        if (obj != Long.MIN_VALUE) {
            args.put("object", new int[]{ (int) obj });
        }
        return args;
    }

    /** 异步任务轮询：GET /tasks/{taskId} ；DELETE 取消。 */
    private void taskPoll(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();      // /tasks/t-xxx
        String taskId = path.length() > "/tasks/".length() ? path.substring("/tasks/".length()) : "";
        if (taskId.isBlank()) { send(ex, 400, err("missing taskId")); return; }
        if ("DELETE".equalsIgnoreCase(ex.getRequestMethod())) {
            boolean ok = tasks.cancel(taskId);
            send(ex, ok ? 200 : 404, Json.obj().kv("cancelled", ok).endObj());
            return;
        }
        send(ex, 200, tasks.poll(taskId));
    }

    /** 提交一个"需要 snapshot 的重操作"为异步任务：任务体内 acquire 租约，覆盖整个执行期。 */
    private String submitTask(String kind, String dumpDir, Map<String, String> cacheKey, SnapshotJob job) {
        // 1) 缓存命中 → 直接返回一个已 DONE 的 synthetic task，不进线程池、不开 snapshot
        String cached = cache.get(dumpDir, kind, cacheKey);
        if (cached != null) {
            return tasks.submitPrecomputed(kind, cached);
        }
        // 2) 缓存未命中 → 走异步执行，完成后写缓存
        return tasks.submit(kind, progress -> {
            try (SnapshotManager.Lease lease = manager.acquire(dumpDir)) {
                String result = job.run(lease.snapshot(), progress);
                cache.put(dumpDir, kind, cacheKey, result);
                return result;
            }
        });
    }

    /** 便捷版：无影响结果的参数（纯 dumpDir+kind 决定结果），如 leak-suspects。 */
    private String submitTask(String kind, String dumpDir, SnapshotJob job) {
        return submitTask(kind, dumpDir, ResultCache.args(), job);
    }

    private void sendAccepted(HttpExchange ex, String taskId) throws IOException {
        send(ex, 202, Json.obj().kv("taskId", taskId).kv("status", "RUNNING").endObj());
    }

    private void asyncWithIds(HttpExchange ex, String kind, IdsFn fn) throws IOException {
        Map<String, String> q = Query.parse(ex);
        String dumpDir = q.get("dumpDir");
        if (blank(dumpDir)) { send(ex, 400, err("missing dumpDir")); return; }
        // 对象来源二选一：?objectSet=rs-xxx（结果集复用）或 body {"objects":[ids]}
        int[] ids;
        String rs = q.get("objectSet");
        if (rs != null && !rs.isBlank()) {
            ids = resultSets.get(rs, dumpDir);
            if (ids == null) { send(ex, 409, err("resultSet not found (expired?): " + rs)); return; }
        } else {
            String body;
            try {
                body = new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException e) { send(ex, 400, err("cannot read body")); return; }
            ids = MiniJson.getIntArray(body, "objects");
            if (ids.length == 0) { send(ex, 400, err("missing 'objects' array or objectSet param")); return; }
        }
        final int[] objs = ids;
        // cache key 用 object id 集合（排序后拼接），不关心 body/rs 参数来源
        java.util.Arrays.sort(objs);
        StringBuilder sb = new StringBuilder(objs.length * 8);
        for (int i = 0; i < objs.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(objs[i]);
        }
        String taskId = submitTask(kind, dumpDir,
                ResultCache.args("objects", sb.toString()),
                (s, progress) -> fn.apply(s, objs, progress));
        sendAccepted(ex, taskId);
    }

    @FunctionalInterface
    private interface SnapshotJob {
        String run(org.eclipse.mat.snapshot.ISnapshot s,
                   org.eclipse.mat.util.IProgressListener progress) throws Exception;
    }

    @FunctionalInterface
    private interface IdsFn {
        String apply(org.eclipse.mat.snapshot.ISnapshot s, int[] ids,
                     org.eclipse.mat.util.IProgressListener l) throws Exception;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    /** 公共范式：解析 dumpDir → acquire 租约 → 执行 fn → 自动 release。 */
    private void withSnapshot(HttpExchange ex, SnapshotFn fn) throws IOException {
        Map<String, String> q = Query.parse(ex);
        String dumpDir = q.get("dumpDir");
        if (dumpDir == null || dumpDir.isBlank()) {
            send(ex, 400, err("missing dumpDir"));
            return;
        }
        try (SnapshotManager.Lease lease = manager.acquire(dumpDir)) {
            String json = fn.apply(lease.snapshot());
            send(ex, 200, json);
        } catch (IllegalArgumentException badInput) {
            // 用户输入问题（bad objectId / frame index 等），不是 server fault → 400
            send(ex, 400, err(badInput.getMessage()));
        } catch (IllegalStateException notReady) {
            // hprof/index 不在场（解析未完成或被删）
            send(ex, 409, err(notReady.getMessage()));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "query failed for " + dumpDir, e);
            send(ex, 500, err(e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
        }
    }

    @FunctionalInterface
    private interface SnapshotFn {
        String apply(ISnapshot snapshot) throws Exception;
    }

    private static String err(String msg) {
        return Json.obj().kv("error", msg).endObj();
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        applyCors(ex);   // 实际响应也带 CORS 头（仅当配置了 MAT_QUERY_CORS）
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
