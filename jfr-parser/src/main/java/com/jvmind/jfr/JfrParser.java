package com.jvmind.jfr;

import java.io.StringWriter;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/**
 * JFR → DuckDB 解析器（spec v2.0 §3.1）。
 *
 * <p>协议：{@code java -jar jfr-parser.jar <input.jfr> <output.duckdb>}
 * 退出码 0 = 成功；非 0 = 失败（stderr 带原因）。
 *
 * <p>核心流程：
 * <ol>
 *   <li>建表（stack_traces / hotspots / recording_meta），关自动提交；</li>
 *   <li>RecordingFile 单次流式遍历，内存级聚合：
 *       {@code jdk.ObjectAllocationSample}（JDK16+）/
 *       {@code jdk.ObjectAllocationInNewTLAB}（weight=tlabSize）/
 *       {@code jdk.ObjectAllocationOutsideTLAB}（weight=objectSize）
 *       → MEM_ALLOC（weight=分配字节），
 *       {@code jdk.ExecutionSample} → CPU_SAMPLE（weight=采样次数）；</li>
 *   <li>堆栈降维 processStack：反转帧序 → 过滤非业务前缀 → 取前 5 业务帧
 *       拼 "ClsA.m1 <- ClsB.m2" → MD5 作 stack_hash，完整堆栈存 JSON；</li>
 *   <li>批量 INSERT + 单次 commit。</li>
 * </ol>
 *
 * <p>业务帧过滤：默认排除 {@code java. javax. jdk. sun. com.sun.} 前缀
 * （可用 -Djfr.exclude.prefixes=com.foo.,bar. 追加）。CGLIB/$$Lambda$ 合成帧
 * 同样跳过——它们会污染业务指纹。
 */
public final class JfrParser {

    /** 非业务帧前缀（小写匹配）。 */
    private static final String[] DEFAULT_EXCLUDES = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
    };

    private static final int TOP_BUSINESS_FRAMES = 5;
    private static final int MAX_FULL_FRAMES = 64; // full_frames JSON 截断上限（token 预算）

    private static final String EV_MEM_ALLOC = "MEM_ALLOC";
    private static final String EV_CPU_SAMPLE = "CPU_SAMPLE";

    /** 聚合键：(type, stackHash) → 累加器。LinkedHashMap 保持首现顺序，输出稳定。 */
    private final Map<String, Agg> aggs = new LinkedHashMap<>();
    /** 堆栈字典：hash → StackEntry（top_frames + full_frames JSON）。 */
    private final Map<String, StackEntry> stacks = new HashMap<>();

    private static final class Agg {
        long weight;
        long sampleCount;
    }

    private record StackEntry(String topFrames, String fullFramesJson) { }

    /** 堆栈身份缓存（memo）：key = 逐帧 (classId | methodNameHash | line) 的
     * 64-bit 滚动哈希，命中（long[] 精确比对）直接复用 ProcessedStack；只有首个
     * 出现才做完整降维。利用 JFR 类常量池 id（RecordedClass.getId()，JDK9+ 公有
     * API，跨 CheckPoint 全录制稳定）当"字典 ID"，避免重复帧解析 / 字符串拼接 /
     * full_frames JSON / MD5 —— 对应 spec 的「堆栈指纹缓存」。 */
    private final Map<Long, List<CanonEntry>> stackCache = new HashMap<>();
    /** 逐事件复用的身份键缓冲（按需扩容，热路径零分配）。 */
    private long[] scratch = new long[64];
    private long stackCacheHits;
    private long stackCacheMisses;

    /** 规范化身份键（与 scratch 相同布局的 long[]）→ 已降维结果。ps 可为 null（无业务帧）。 */
    private record CanonEntry(long[] key, ProcessedStack ps) { }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: java -jar jfr-parser.jar <input.jfr> <output.duckdb>");
            System.exit(2);
        }
        try {
            new JfrParser().run(args[0], args[1]);
        } catch (Exception e) {
            System.err.println("[jfr-parser] FAILED: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private String startTimeIso = "";
    private long totalEvents;
    private long memEvents;
    private long cpuEvents;

    void run(String input, String output) throws Exception {
        String excludesCsv = System.getProperty("jfr.exclude.prefixes", "");
        List<String> excludes = new ArrayList<>(List.of(DEFAULT_EXCLUDES));
        for (String p : excludesCsv.split(",")) {
            String t = p.trim().toLowerCase();
            if (!t.isEmpty()) excludes.add(t);
        }

        try (RecordingFile rf = new RecordingFile(java.nio.file.Path.of(input));
             Connection conn = openDuckdb(output)) {
            createTables(conn);
            while (rf.hasMoreEvents()) {
                RecordedEvent ev = rf.readEvent();
                totalEvents++;
                // 事件按时间序存储：首个事件的 startTime ≈ 录制有效起点
                if (startTimeIso.isEmpty()) {
                    startTimeIso = java.time.format.DateTimeFormatter
                            .ofPattern("yyyy-MM-dd HH:mm:ss")
                            .withZone(java.time.ZoneId.systemDefault())
                            .format(ev.getStartTime());
                }
                String name = ev.getEventType().getName();
                switch (name) {
                    // JDK 16+: 采样事件（profile 档默认开启），weight=tlabSize
                    case "jdk.ObjectAllocationSample" -> {
                        memEvents++;
                        accumulate(ev, EV_MEM_ALLOC, ev.getLong("weight"), excludes);
                    }
                    // JDK 8-15: 逐 TLAB 分配事件。分配量口径与 ObjectAllocationSample
                    // 一致 —— NewTLAB 取 tlabSize（整块 TLAB，代表本次新分配的字节数，
                    // JMC 分配视图同口径）；OutsideTLAB 取 objectSize。字段名跨 JDK
                    // 版本有差异（objectSize/allocationSize），逐个尝试。
                    case "jdk.ObjectAllocationInNewTLAB" -> {
                        memEvents++;
                        accumulate(ev, EV_MEM_ALLOC, eventBytes(ev, "tlabSize", "allocationSize"), excludes);
                    }
                    case "jdk.ObjectAllocationOutsideTLAB" -> {
                        memEvents++;
                        accumulate(ev, EV_MEM_ALLOC, eventBytes(ev, "objectSize", "allocationSize"), excludes);
                    }
                    case "jdk.ExecutionSample" -> {
                        cpuEvents++;
                        accumulate(ev, EV_CPU_SAMPLE, 1L, excludes);
                    }
                    // 其余事件类型跳过（单次遍历里顺手过滤，成本可忽略）
                    default -> { }
                }
            }
            flush(conn);
            insertMeta(conn);
            commit(conn);
        }
        System.out.println("[jfr-parser] OK total=" + totalEvents
                + " mem=" + memEvents + " cpu=" + cpuEvents
                + " unique_stacks=" + stacks.size()
                + " stack_cache_hits=" + stackCacheHits
                + " stack_cache_misses=" + stackCacheMisses);
    }

    // ------------------------------------------------------------------
    // 事件 → 内存聚合
    // ------------------------------------------------------------------

    /** 依次尝试字段名取非负 long（跨 JDK 版本的字段命名差异容错）。 */
    private static long eventBytes(RecordedEvent ev, String... names) {
        for (String n : names) {
            try {
                long v = ev.getLong(n);
                if (v > 0) return v;
            } catch (IllegalArgumentException ignored) {
                // 该 JDK 版本无此字段，尝试下一个
            }
        }
        return 0L;
    }

    private void accumulate(RecordedEvent ev, String type, long weight, List<String> excludes) {
        RecordedStackTrace st = ev.getStackTrace();
        if (st == null) return;
        ProcessedStack ps = processStack(st, excludes);
        if (ps == null) return; // 无业务帧（纯框架代码热点）不入库
        String key = type + "|" + ps.hash();
        Agg a = aggs.computeIfAbsent(key, k -> new Agg());
        a.weight += Math.max(0L, weight);
        a.sampleCount++;
        stacks.putIfAbsent(ps.hash(), new StackEntry(ps.topFrames(), ps.fullJson()));
    }

    private record ProcessedStack(String hash, String topFrames, String fullJson) { }

    /**
     * spec §3.1 processStack：
     * JFR RecordedStackTrace.getFrames() 是 leaf-first（帧[0]=最内层正在执行的
     * 方法）。反转成 root-first 后顺序过滤业务帧，取前 5 个作为指纹 ——
     * 即「业务入口路径」而非「叶子方法」，同一入口多次调用聚合到一行，
     * 大幅压缩行数且更符合"哪条业务链路在吃资源"的专家视角。
     *
     * <p>结果按「堆栈身份」memo（stackCache）：逐帧取 JFR 类常量池 id
     * （RecordedClass.getId()）+ 方法名 hash + 行号，滚动 64-bit 哈希并用
     * long[] 精确比对。相同堆栈的后续事件只付 O(帧数) 整数运算，跳过字符串
     * 拼接 / 业务过滤 / full_frames JSON / MD5 —— 40K 分配事件若只有 ~3K
     * 不同堆栈，处理量减少 90%+。
     */
    private ProcessedStack processStack(RecordedStackTrace st, List<String> excludes) {
        List<RecordedFrame> frames = st.getFrames();
        if (frames == null || frames.isEmpty()) return null;

        // ---- 身份键（热路径，复用 scratch，零分配）。
        // leaf-first 顺序即可 —— 键只需确定性，不依赖帧序（降维时再反转）。 ----
        int n = frames.size();
        if (scratch.length < n * 2) scratch = new long[n * 2]; // 每帧占 2 个 long
        long h = 0x9e3779b97f4a7c15L; // splitmix 种子
        int i = 0;
        for (RecordedFrame f : frames) {
            RecordedMethod m = frameMethod(f);
            if (m == null) continue;
            String name = m.getName();
            if (name == null) continue; // 与降维跳过规则一致
            RecordedClass c = m.getType();
            if (c == null) continue;
            scratch[i++] = c.getId();
            scratch[i++] = ((long) name.hashCode() << 32) ^ (f.getLineNumber() & 0xffffffffL);
            h ^= scratch[i - 2] ^ scratch[i - 1];
            h *= 0x9e3779b97f4a7c15L;
        }
        if (i == 0) return null; // 无有效帧（与原逻辑一致）

        List<CanonEntry> bucket = stackCache.get(h);
        if (bucket != null) {
            for (CanonEntry e : bucket) {
                if (e.key.length == i && Arrays.equals(e.key, 0, i, scratch, 0, i)) {
                    stackCacheHits++;
                    return e.ps;
                }
            }
        }
        stackCacheMisses++;
        ProcessedStack ps = computeProcessedStack(frames, excludes);
        if (bucket == null) {
            bucket = new ArrayList<>(1);
            stackCache.put(h, bucket);
        }
        bucket.add(new CanonEntry(Arrays.copyOf(scratch, i), ps));
        return ps;
    }

    /** 首个出现才执行的完整堆栈降维（full_frames JSON + 业务过滤 + join + MD5）。 */
    private ProcessedStack computeProcessedStack(List<RecordedFrame> frames, List<String> excludes) {
        // 反转 → root-first
        List<RecordedFrame> ordered = new ArrayList<>(frames);
        java.util.Collections.reverse(ordered);

        List<String> business = new ArrayList<>();
        StringWriter fullJson = new StringWriter();
        fullJson.append("[");
        int written = 0;
        boolean first = true;
        for (RecordedFrame f : ordered) {
            String cls = frameClassName(f);
            String method = frameMethodName(f);
            if (cls == null || method == null) continue;
            // full_frames JSON（截断到 MAX_FULL_FRAMES，标记 truncated）。
            // 注：RecordedClass.getName() 已返回 dotted（内部已做 '/'→'.'），无需再 replace。
            if (written < MAX_FULL_FRAMES) {
                if (!first) fullJson.append(",");
                first = false;
                fullJson.append("{\"method\":\"").append(esc(cls)).append('#')
                        .append(esc(method)).append('"');
                int line = f.getLineNumber();
                if (line > 0) fullJson.append(",\"line\":").append(String.valueOf(line));
                fullJson.append("}");
                written++;
            }
            // 业务帧判定（合成帧一律跳过）
            if (isBusiness(cls, excludes)) {
                if (business.size() < TOP_BUSINESS_FRAMES) {
                    business.add(cls + "." + method);
                }
            }
        }
        fullJson.append(written >= MAX_FULL_FRAMES ? ",{\"truncated\":true}" : "");
        fullJson.append("]");
        if (business.isEmpty()) return null;
        String top = String.join(" <- ", business);
        return new ProcessedStack(md5(top), top, fullJson.toString());
    }

    private static boolean isBusiness(String dottedCls, List<String> excludes) {
        if (dottedCls.contains("$$Lambda")) return false;
        if (dottedCls.contains("$$SpringCGLIB$$") || dottedCls.contains("$$EnhancerBy")) return false;
        String lower = dottedCls.toLowerCase();
        for (String p : excludes) {
            if (lower.startsWith(p)) return false;
        }
        return true;
    }

    private static String frameClassName(RecordedFrame f) {
        try {
            // 注意：f.getType() 返回的是帧执行模式注记（"Interpreted"/"JIT compiled"/
            // "Inlined"），不是类名！类名在 RecordedMethod → RecordedClass.getName()。
            return f.getMethod().getType().getName();
        } catch (Exception e) {
            return null;
        }
    }

    private static String frameMethodName(RecordedFrame f) {
        try {
            return f.getMethod() != null ? f.getMethod().getName() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 取帧方法（异常安全，供身份键构建）。 */
    private static RecordedMethod frameMethod(RecordedFrame f) {
        try {
            return f.getMethod();
        } catch (Exception e) {
            return null;
        }
    }

    private static final MessageDigest MD5_DIGEST = initMd5();

    private static MessageDigest initMd5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String md5(String s) {
        // digest(byte[]) 是单次调用且自带 reset，可安全复用同一实例
        byte[] d = MD5_DIGEST.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(32);
        for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xf, 16))
                           .append(Character.forDigit(b & 0xf, 16));
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    // ------------------------------------------------------------------
    // DuckDB 写入
    // ------------------------------------------------------------------

    private Connection openDuckdb(String path) throws SQLException {
        // duckdb_jdbc shaded：Class.forName 非必需（JDBC4 SPI 自动加载），
        // 但显式加载能把"驱动缺失"与"SQL 错误"区分开。
        try {
            Class.forName("org.duckdb.DuckDBDriver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("duckdb_jdbc driver not on classpath", e);
        }
        Connection conn = DriverManager.getConnection("jdbc:duckdb:" + path);
        conn.setAutoCommit(false); // spec §5.2 批量事务
        return conn;
    }

    private void createTables(Connection conn) throws SQLException {
        // 幂等重跑：目标文件已存在（如上次中途失败/旧版本产物）时清空旧数据，
        // 否则 stack_traces 主键冲突导致 flush 失败。
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS recording_meta");
            st.execute("DROP TABLE IF EXISTS hotspots");
            st.execute("DROP TABLE IF EXISTS stack_traces");
        }
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS stack_traces (
                    stack_hash VARCHAR PRIMARY KEY,
                    top_frames VARCHAR,
                    full_frames JSON
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS hotspots (
                    hotspot_type VARCHAR,
                    stack_hash VARCHAR,
                    weight BIGINT,
                    sample_count INTEGER
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS recording_meta (
                    key VARCHAR PRIMARY KEY,
                    value VARCHAR
                )""");
        }
    }

    private void flush(Connection conn) throws SQLException {
        try (PreparedStatement psStack = conn.prepareStatement(
                "INSERT INTO stack_traces (stack_hash, top_frames, full_frames) VALUES (?, ?, ?)");
             PreparedStatement psHot = conn.prepareStatement(
                "INSERT INTO hotspots (hotspot_type, stack_hash, weight, sample_count) VALUES (?, ?, ?, ?)")) {
            for (Map.Entry<String, StackEntry> e : stacks.entrySet()) {
                psStack.setString(1, e.getKey());
                psStack.setString(2, e.getValue().topFrames());
                psStack.setString(3, e.getValue().fullFramesJson());
                psStack.addBatch();
            }
            psStack.executeBatch();

            for (Map.Entry<String, Agg> e : aggs.entrySet()) {
                int sep = e.getKey().indexOf('|');
                String type = e.getKey().substring(0, sep);
                String hash = e.getKey().substring(sep + 1);
                psHot.setString(1, type);
                psHot.setString(2, hash);
                psHot.setLong(3, e.getValue().weight);
                psHot.setInt(4, (int) Math.min(Integer.MAX_VALUE, e.getValue().sampleCount));
                psHot.addBatch();
            }
            psHot.executeBatch();
        }
    }

    private void insertMeta(Connection conn) throws SQLException {
        String startIso = startTimeIso;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO recording_meta (key, value) VALUES (?, ?)")) {
            ps.setString(1, "start_time"); ps.setString(2, startIso); ps.addBatch();
            ps.setString(1, "total_events"); ps.setString(2, Long.toString(totalEvents)); ps.addBatch();
            ps.setString(1, "mem_events"); ps.setString(2, Long.toString(memEvents)); ps.addBatch();
            ps.setString(1, "cpu_events"); ps.setString(2, Long.toString(cpuEvents)); ps.addBatch();
            ps.setString(1, "unique_stacks"); ps.setString(2, Long.toString(stacks.size())); ps.addBatch();
            ps.executeBatch();
        }
    }

    private void commit(Connection conn) throws SQLException {
        conn.commit();
    }
}
