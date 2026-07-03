package com.jvmind.mat.query.view;

import com.jvmind.mat.query.model.Json;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 连接池配置 + 统计提取（呼应文档 03）。识别主流连接池，提取配置项与运行统计，
 * <b>敏感字段（密码等）统一脱敏</b>。
 *
 * <p>配置驱动：每种池一个 {@link Spec}（类名 + 配置字段路径 + 统计字段路径），用 resolveValue 逐字段读。
 * 支持 HikariCP / Druid / Tomcat JDBC / DBCP2 / c3p0；字段路径耐版本变化（取不到的字段跳过，不崩）。
 *
 * <p>安全：password/secret/credential 等字段一律输出 "******"（绝不回传明文）。
 */
public final class ConnectionPoolView {

    private ConnectionPoolView() { }

    /** 敏感字段名（小写包含即脱敏）。 */
    private static final Pattern SECRET = Pattern.compile(
            "passwd|password|secret|credential|token|privatekey", Pattern.CASE_INSENSITIVE);
    private static final String MASK = "******";

    /** 连接池定义：类名 → 配置字段(显示名→resolveValue路径) + 统计字段。 */
    private record Spec(String type, String className,
                        Map<String, String> config, Map<String, String> stats) { }

    private static final List<Spec> SPECS = new ArrayList<>();
    static {
        // HikariCP（配置在 HikariDataSource；统计需看 pool）
        SPECS.add(new Spec("HikariCP", "com.zaxxer.hikari.HikariDataSource",
                ordered("poolName","poolName", "maxPoolSize","maxPoolSize", "minIdle","minIdle",
                        "connectionTimeout","connectionTimeout", "idleTimeout","idleTimeout",
                        "maxLifetime","maxLifetime", "jdbcUrl","jdbcUrl", "username","username",
                        "password","password", "leakDetectionThreshold","leakDetectionThreshold",
                        "validationTimeout","validationTimeout"),
                ordered()));   // Hikari 统计要遍历 connectionBag，单列处理（见 hikariStats）
        // Druid（配置+统计都在 DruidDataSource 字段）
        SPECS.add(new Spec("Druid", "com.alibaba.druid.pool.DruidDataSource",
                ordered("name","name", "maxActive","maxActive", "minIdle","minIdle",
                        "initialSize","initialSize", "maxWait","maxWait", "url","url",
                        "username","username", "password","password",
                        "validationQuery","validationQuery", "testWhileIdle","testWhileIdle",
                        "removeAbandoned","removeAbandoned"),
                ordered("active","activeCount", "idle","poolingCount",
                        "maxActive","maxActive", "createCount","createCount", "destroyCount","destroyCount",
                        "connectErrorCount","connectErrorCount")));
        // Tomcat JDBC
        SPECS.add(new Spec("TomcatJDBC", "org.apache.tomcat.jdbc.pool.DataSource",
                ordered("maxActive","poolProperties.maxActive", "minIdle","poolProperties.minIdle",
                        "maxWait","poolProperties.maxWait", "url","poolProperties.url",
                        "username","poolProperties.username", "password","poolProperties.password",
                        "validationQuery","poolProperties.validationQuery"),
                ordered("active","pool.busy.size", "idle","pool.idle.size", "size","pool.size")));
        // DBCP2
        SPECS.add(new Spec("DBCP2", "org.apache.commons.dbcp2.BasicDataSource",
                ordered("maxTotal","maxTotal", "minIdle","minIdle", "maxWaitMillis","maxWaitMillis",
                        "url","url", "username","username", "password","password",
                        "validationQuery","validationQuery"),
                ordered("active","connectionPool.numActive", "idle","connectionPool.numIdle")));
        // c3p0
        SPECS.add(new Spec("c3p0", "com.mchange.v2.c3p0.ComboPooledDataSource",
                ordered("maxPoolSize","maxPoolSize", "minPoolSize","minPoolSize",
                        "jdbcUrl","jdbcUrl", "user","user", "password","password"),
                ordered()));
    }

    public static String extract(ISnapshot snapshot) throws Exception {
        Json pools = Json.arr();
        int count = 0;
        for (Spec spec : SPECS) {
            for (IClass c : safeClasses(snapshot, spec.className())) {
                for (int id : c.getObjectIds()) {
                    IObject ds = safe(snapshot, id);
                    if (ds == null) continue;
                    pools.item(renderPool(snapshot, ds, spec, id));
                    count++;
                }
            }
        }
        return Json.obj().kv("count", count).raw("pools", pools.endArr()).endObj();
    }

    private static String renderPool(ISnapshot snapshot, IObject ds, Spec spec, int id) {
        Json config = Json.arr();
        for (var e : spec.config().entrySet()) {
            String val = resolveStr(ds, e.getValue());
            if (val == null) continue;
            if (SECRET.matcher(e.getKey()).find() || SECRET.matcher(e.getValue()).find()) {
                val = MASK;   // ★ 密码等脱敏
            }
            config.item(Json.obj().kv("name", e.getKey()).kv("value", val).endObj());
        }

        Json stats = Json.arr();
        for (var e : spec.stats().entrySet()) {
            String val = resolveStr(ds, e.getValue());
            if (val != null) stats.item(Json.obj().kv("name", e.getKey()).kv("value", val).endObj());
        }

        Json out = Json.obj()
                .kv("type", spec.type())
                .kv("objectId", id)
                .kv("label", ObjectRows.safeDisplayName(ds))
                .raw("config", config.endArr());

        // HikariCP 统计特殊：遍历 pool.connectionBag.sharedList 数 active/idle
        if ("HikariCP".equals(spec.type())) {
            String hs = hikariStats(snapshot, ds);
            if (hs != null) out.raw("stats", hs); else out.raw("stats", stats.endArr());
            // 明确区分空闲连接(state=NOT_IN_USE) 与 活跃连接(state=IN_USE)，照 Druid 那套
            String conns = hikariConnections(snapshot, ds);
            if (conns != null) out.raw("connections", conns);
            return out.endObj();
        }
        out.raw("stats", stats.endArr());
        // Druid：明确区分空闲连接(connections[0..poolingCount]) 与 活跃连接(activeConnections)
        if ("Druid".equals(spec.type())) {
            String conns = druidConnections(snapshot, ds);
            if (conns != null) out.raw("connections", conns);
        }
        return out.endObj();
    }

    /**
     * Druid 连接明细（读 Druid 源码确认）：
     * - 空闲连接：connections[] (DruidConnectionHolder[])，<b>前 poolingCount 个有效</b>（数组按 maxActive 分配，后面是 null）。
     * - 活跃连接：activeConnections (Map&lt;DruidPooledConnection,?&gt;)，借出中的。
     * 两者是不同类型对象（holder vs pooledConnection），分别列出。
     */
    private static String druidConnections(ISnapshot snapshot, IObject ds) {
        try {
            int poolingCount = (int) longOf(ds, "poolingCount");
            // 空闲连接：截取前 poolingCount 个 holder
            Json idle = Json.arr();
            Object connsArr = ds.resolveValue("connections");   // DruidConnectionHolder[]
            if (connsArr instanceof org.eclipse.mat.snapshot.model.IObjectArray arr) {
                long[] refs = arr.getReferenceArray();
                int n = Math.min(poolingCount, refs.length);
                for (int i = 0; i < n; i++) {
                    if (refs[i] == 0) continue;
                    IObject holder = byAddr(snapshot, refs[i]);
                    if (holder == null) continue;
                    idle.item(Json.obj()
                            .kv("holderId", holder.getObjectId())
                            .kv("connId", refId(holder, "conn"))           // 真实连接对象
                            .kv("useCount", String.valueOf(longOf(holder, "useCount")))
                            .kv("connectTimeMillis", String.valueOf(longOf(holder, "connectTimeMillis")))
                            .endObj());
                }
            }
            // 活跃连接：activeConnections map 的 key
            Json active = Json.arr();
            Object ac = ds.resolveValue("activeConnections");
            if (ac instanceof IObject mapObj) {
                var map = org.eclipse.mat.inspections.collectionextract.CollectionExtractionUtils.extractMap(mapObj);
                if (map != null) {
                    int cnt = 0;
                    for (var e : map) {
                        if (cnt++ > 500) break;
                        IObject pc = e.getKey();   // DruidPooledConnection
                        if (pc == null) continue;
                        active.item(Json.obj()
                                .kv("connId", pc.getObjectId())
                                .kv("connectedTimeMillis", String.valueOf(longOf(pc, "connectedTimeMillis")))
                                .kv("hasBorrowStack", pc.resolveValue("connectStackTrace") != null)
                                .endObj());
                    }
                }
            }
            return Json.obj()
                    .kv("idleCount", poolingCount)
                    .raw("idle", idle.endArr())
                    .raw("active", active.endArr())
                    .kv("note", "空闲=connections[0..poolingCount](holder)；活跃=activeConnections(pooledConnection)。借出栈/泄漏归因见 /pool-leaks")
                    .endObj();
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * HikariCP 连接明细（读 HikariCP 源码确认）：
     * - 连接都在 {@code pool.connectionBag.sharedList}（CopyOnWriteArrayList&lt;PoolEntry&gt;）。
     * - 用 PoolEntry.state 区分：<b>1=IN_USE(活跃/借出中)</b>、<b>0=NOT_IN_USE(空闲)</b>、-1=REMOVED、-2=RESERVED。
     * - PoolEntry 有 connection(真实连接对象)、lastAccessed、lastBorrowed(借出时刻 nanoTime)。
     *
     * <p>与 Druid 的区别：Druid 空闲/活跃是两个不同容器(connections[]/activeConnections)且对象类型不同；
     * Hikari 全在同一个 sharedList，靠 state 分类，对象类型都是 PoolEntry。
     * Hikari 默认无借出栈（需开 leakDetectionThreshold），借出时长/泄漏归因见 /pool-leaks。
     */
    private static String hikariConnections(ISnapshot snapshot, IObject ds) {
        try {
            Object pool = ds.resolveValue("pool");
            if (!(pool instanceof IObject p)) return null;
            Object shared = p.resolveValue("connectionBag.sharedList");   // CopyOnWriteArrayList<PoolEntry>
            if (!(shared instanceof IObject listObj)) return null;
            var list = org.eclipse.mat.inspections.collectionextract.CollectionExtractionUtils.extractList(listObj);
            if (list == null) return null;

            Json idle = Json.arr();
            Json active = Json.arr();
            int idleCount = 0, activeCount = 0, other = 0, cnt = 0;
            for (IObject entry : list) {   // ExtractedCollection 迭代出 IObject(PoolEntry)
                if (entry == null) continue;
                if (cnt++ > 1000) break;   // 防超大池
                int state = (int) longOf(entry, "state");
                Json row = Json.obj()
                        .kv("entryId", entry.getObjectId())
                        .kv("connId", refId(entry, "connection"))            // 真实连接对象
                        .kv("state", hikariState(state))
                        .kv("lastBorrowedNanos", String.valueOf(longOf(entry, "lastBorrowed")))
                        .kv("lastAccessedNanos", String.valueOf(longOf(entry, "lastAccessed")));
                if (state == 1) { active.item(row); activeCount++; }       // IN_USE
                else if (state == 0) { idle.item(row); idleCount++; }      // NOT_IN_USE
                else { other++; }                                         // REMOVED/RESERVED 不列
            }
            return Json.obj()
                    .kv("idleCount", idleCount)
                    .kv("activeCount", activeCount)
                    .kv("otherCount", other)
                    .raw("idle", idle.endArr())
                    .raw("active", active.endArr())
                    .kv("note", "空闲=PoolEntry.state=NOT_IN_USE；活跃=state=IN_USE(借出中)；均取自 pool.connectionBag.sharedList。借出栈/泄漏归因见 /pool-leaks")
                    .endObj();
        } catch (Throwable e) {
            return null;
        }
    }

    private static String hikariState(int state) {
        switch (state) {
            case 1: return "IN_USE";
            case 0: return "NOT_IN_USE";
            case -1: return "REMOVED";
            case -2: return "RESERVED";
            default: return "UNKNOWN(" + state + ")";
        }
    }

    private static long longOf(IObject o, String path) {
        try { Object v = o.resolveValue(path); if (v instanceof Number n) return n.longValue(); }
        catch (Throwable ignore) { }
        return 0;
    }
    private static int refId(IObject o, String path) {
        try { Object v = o.resolveValue(path); if (v instanceof IObject io) return io.getObjectId(); }
        catch (Throwable ignore) { }
        return -1;
    }
    private static IObject byAddr(ISnapshot s, long addr) {
        try { return s.getObject(s.mapAddressToId(addr)); } catch (Exception e) { return null; }
    }

    /** HikariCP：active/idle 需遍历 connectionBag.sharedList 的 PoolEntry.state。 */
    private static String hikariStats(ISnapshot snapshot, IObject ds) {
        try {
            Object pool = ds.resolveValue("pool");
            if (!(pool instanceof IObject p)) return null;
            // ConcurrentBag.sharedList 是 CopyOnWriteArrayList → 取 size 作 total（精确 active 需读每个 entry.state）
            String total = resolveStr(p, "connectionBag.sharedList.size");
            String waiting = resolveStr(p, "connectionBag.waiters.value");
            Json arr = Json.arr();
            if (total != null) arr.item(Json.obj().kv("name","total").kv("value", total).endObj());
            if (waiting != null) arr.item(Json.obj().kv("name","waiting").kv("value", waiting).endObj());
            arr.item(Json.obj().kv("name","note")
                    .kv("value","active/idle 需展开 connectionBag.sharedList 看 PoolEntry.state（泄漏诊断见 path2gc）").endObj());
            return arr.endArr();
        } catch (Throwable e) {
            return null;
        }
    }

    /** resolveValue 点路径 → 字符串值（String 取文本，数值/布尔 toString）。 */
    private static String resolveStr(IObject obj, String path) {
        try {
            Object v = obj.resolveValue(path);
            if (v == null) return null;
            if (v instanceof IObject io) {
                String s = io.getClassSpecificName();
                return s != null ? s : ObjectRows.safeDisplayName(io);
            }
            return String.valueOf(v);
        } catch (Throwable e) {
            return null;
        }
    }

    private static List<IClass> safeClasses(ISnapshot s, String name) {
        try {
            var c = s.getClassesByName(name, true);   // 含子类（覆盖版本子类/包装）
            return c == null ? List.of() : new ArrayList<>(c);
        } catch (Exception e) { return List.of(); }
    }
    private static IObject safe(ISnapshot s, int id) {
        try { return s.getObject(id); } catch (Exception e) { return null; }
    }

    /** 有序键值对构造（保证字段显示顺序）。 */
    private static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
