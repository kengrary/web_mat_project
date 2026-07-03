package com.jvmind.mat.query.view;

import com.jvmind.mat.query.model.Json;
import org.eclipse.mat.inspections.collectionextract.CollectionExtractionUtils;
import org.eclipse.mat.inspections.collectionextract.ExtractedCollection;
import org.eclipse.mat.inspections.collectionextract.ExtractedMap;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IObjectArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 连接池泄漏归因（文档 03 §6.5 TODO 落地）：找出"借出后久未归还"的连接，给借出时长 + 借出栈 + 持有者线索。
 *
 * <p>核心数据（各池存的"借出证据"）：
 * <ul>
 *   <li><b>Druid（最全，有借出栈）</b>：DruidDataSource.activeConnections（Map，借出中的连接）；
 *       DruidPooledConnection.connectedTimeMillis（借出时刻）+ connectStackTrace（借出栈，
 *       removeAbandoned/logAbandoned 开启时有）。</li>
 *   <li><b>HikariCP</b>：pool.connectionBag.sharedList 的 PoolEntry，state=IN_USE 且 lastBorrowed 很老。
 *       默认无借出栈（开 leakDetectionThreshold 才有线索）。</li>
 * </ul>
 *
 * <p>时间判定：dump 无"当前时间"，用所有借出连接时间戳的<b>最大值近似 dump 时刻</b>，算相对借出时长，
 * 超阈值（默认 5 分钟）= 疑似泄漏。
 *
 * <p>归因：借出栈（Druid 直接有）/ 持有者（path2gc 看谁攥着连接，前端按需）。
 */
public final class PoolLeakView {

    private PoolLeakView() { }

    private static final long DEFAULT_LEAK_MS = 5 * 60 * 1000L;   // 借出超 5 分钟疑似泄漏

    public static String diagnose(ISnapshot snapshot, long leakThresholdMs) throws Exception {
        if (leakThresholdMs <= 0) leakThresholdMs = DEFAULT_LEAK_MS;
        List<Borrowed> all = new ArrayList<>();

        collectDruid(snapshot, all);
        collectHikari(snapshot, all);

        // dump 时刻近似 = 所有借出连接时间戳最大值
        long now = 0;
        for (Borrowed b : all) if (b.borrowedAtMs > now) now = b.borrowedAtMs;

        // 算时长、标泄漏、按借出时长降序
        for (Borrowed b : all) b.ageMs = (b.borrowedAtMs > 0 && now > 0) ? (now - b.borrowedAtMs) : -1;
        final long threshold = leakThresholdMs;
        all.sort((x, y) -> Long.compare(y.ageMs, x.ageMs));

        Json arr = Json.arr();
        int leaks = 0;
        for (Borrowed b : all) {
            boolean leak = b.ageMs >= 0 && b.ageMs >= threshold;
            if (leak) leaks++;
            Json o = Json.obj()
                    .kv("pool", b.pool)
                    .kv("connectionId", b.connId)
                    .kv("label", b.label)
                    .kv("borrowedAgeMs", b.ageMs)
                    .kv("borrowedAge", b.ageMs >= 0 ? humanDuration(b.ageMs) : "unknown")
                    .kv("suspectedLeak", leak);
            if (b.borrowStack != null && !b.borrowStack.isEmpty()) {
                Json st = Json.arr();
                for (String f : b.borrowStack) st.item("\"" + esc(f) + "\"");
                o.raw("borrowStack", st.endArr());   // ★ 借出栈 = 泄漏点
            } else {
                o.kv("borrowStackHint", "无借出栈（Druid 需开 removeAbandoned/logAbandoned；Hikari 需开 leakDetectionThreshold）");
            }
            arr.item(o.endObj());
        }
        return Json.obj()
                .kv("borrowedTotal", all.size())
                .kv("suspectedLeaks", leaks)
                .kv("thresholdMs", threshold)
                .kv("note", all.isEmpty() ? "未发现借出中的连接（或池类型/版本字段未识别）" : "借出时长基于 dump 内时间戳近似")
                .raw("connections", arr.endArr())
                .endObj();
    }

    // ===================== Druid =====================
    private static void collectDruid(ISnapshot s, List<Borrowed> out) {
        try {
            for (IClass c : classes(s, "com.alibaba.druid.pool.DruidDataSource")) {
                for (int dsId : c.getObjectIds()) {
                    IObject ds = obj(s, dsId);
                    if (ds == null) continue;
                    Object ac = ds.resolveValue("activeConnections");   // Map<DruidPooledConnection,?>
                    if (!(ac instanceof IObject mapObj)) continue;
                    ExtractedMap map = CollectionExtractionUtils.extractMap(mapObj);
                    if (map == null) continue;
                    for (Map.Entry<IObject, IObject> e : map) {
                        IObject conn = e.getKey();   // DruidPooledConnection
                        if (conn == null) continue;
                        Borrowed b = new Borrowed();
                        b.pool = "Druid"; b.connId = conn.getObjectId();
                        b.label = ObjectRows.safeDisplayName(conn);
                        b.borrowedAtMs = longVal(conn, "connectedTimeMillis");
                        b.borrowStack = readStackTrace(conn, "connectStackTrace");
                        out.add(b);
                    }
                }
            }
        } catch (Throwable ignore) { }
    }

    // ===================== HikariCP =====================
    private static void collectHikari(ISnapshot s, List<Borrowed> out) {
        try {
            for (IClass c : classes(s, "com.zaxxer.hikari.pool.HikariPool")) {
                for (int pId : c.getObjectIds()) {
                    IObject pool = obj(s, pId);
                    if (pool == null) continue;
                    Object shared = pool.resolveValue("connectionBag.sharedList");  // CopyOnWriteArrayList<PoolEntry>
                    if (!(shared instanceof IObject listObj)) continue;
                    ExtractedCollection list = CollectionExtractionUtils.extractList(listObj);
                    if (list == null) continue;
                    for (IObject entry : list) {     // ExtractedCollection 迭代出 IObject
                        if (entry == null) continue;
                        int state = (int) longVal(entry, "state");   // 1=IN_USE
                        if (state != 1) continue;                    // 只看借出中的
                        Borrowed b = new Borrowed();
                        b.pool = "HikariCP"; b.connId = entry.getObjectId();
                        b.label = ObjectRows.safeDisplayName(entry);
                        // lastBorrowed 是 nanoTime（相对值）；用最大值近似 now 同样适用（都是 nano）
                        long nano = longVal(entry, "lastBorrowed");
                        b.borrowedAtMs = nano > 0 ? nano / 1_000_000L : 0;   // 转 ms 量级供统一比较
                        out.add(b);
                    }
                }
            }
        } catch (Throwable ignore) { }
    }

    // ===================== helpers =====================

    private static List<String> readStackTrace(IObject conn, String field) {
        try {
            Object st = conn.resolveValue(field);
            if (st instanceof IObjectArray arr) {
                long[] refs = arr.getReferenceArray();
                List<String> frames = new ArrayList<>();
                ISnapshot s = conn.getSnapshot();
                for (long addr : refs) {
                    if (addr == 0) continue;
                    try {
                        int id = s.mapAddressToId(addr);
                        IObject ste = s.getObject(id);
                        String f = ste.getClassSpecificName();   // "at Class.method(File:line)"
                        if (f != null) frames.add(f);
                    } catch (Exception ignore) { }
                    if (frames.size() >= 30) break;
                }
                return frames;
            }
        } catch (Throwable ignore) { }
        return null;
    }

    private static long longVal(IObject o, String path) {
        try {
            Object v = o.resolveValue(path);
            if (v instanceof Number n) return n.longValue();
        } catch (Throwable ignore) { }
        return 0;
    }

    private static List<IClass> classes(ISnapshot s, String name) {
        try { var c = s.getClassesByName(name, true); return c == null ? List.of() : new ArrayList<>(c); }
        catch (Exception e) { return List.of(); }
    }
    private static IObject obj(ISnapshot s, int id) { try { return s.getObject(id); } catch (Exception e) { return null; } }

    private static String humanDuration(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60; s %= 60;
        if (m < 60) return m + "m" + s + "s";
        long h = m / 60; m %= 60;
        return h + "h" + m + "m";
    }
    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private static final class Borrowed {
        String pool; int connId; String label;
        long borrowedAtMs; long ageMs;
        List<String> borrowStack;
    }
}
