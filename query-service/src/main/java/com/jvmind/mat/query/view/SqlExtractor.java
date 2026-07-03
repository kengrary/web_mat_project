package com.jvmind.mat.query.view;

import com.jvmind.mat.query.model.Json;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IStackFrame;
import org.eclipse.mat.snapshot.model.IThreadStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SQL 提取器（文档 13 想法2）：从线程栈帧的局部变量里找出正在执行的 SQL 语句，
 * 直接定位"SQL 引起的 OOM"（如 SELECT * 无分页把几百万行读进内存）。
 *
 * <p>三层识别：
 * <ol>
 *   <li><b>已知 statement 类 + 字段路径</b>（配置驱动，支持主流库/ORM/连接池包装，耐版本变化）；</li>
 *   <li><b>通用正则兜底</b>：栈帧局部变量里任意 String，长得像 SQL 就抓；</li>
 *   <li>对每条 SQL <b>风险过滤/打分</b>：排除心跳/校验类（如 SELECT 1），标记疑似大查询。</li>
 * </ol>
 *
 * <p>复用：栈帧局部变量（ThreadsView 已有）、resolveValue 点路径深读、getClassSpecificName 取文本。
 */
public final class SqlExtractor {

    private SqlExtractor() { }

    // ---- 1) 已知 statement 类 → SQL 字段路径（resolveValue 点路径）。主流库 + ORM + 连接池包装 ----
    //     一个类可有多个候选路径（版本差异），按序尝试，取到非空即用。
    private static final Map<String, String[]> SQL_PATHS = new LinkedHashMap<>();
    static {
        // MySQL Connector/J 8
        SQL_PATHS.put("com.mysql.cj.jdbc.ClientPreparedStatement", new String[]{"sql", "((PreparedQuery)query).originalSql"});
        SQL_PATHS.put("com.mysql.cj.jdbc.ServerPreparedStatement", new String[]{"sql"});
        // MySQL 5
        SQL_PATHS.put("com.mysql.jdbc.PreparedStatement", new String[]{"originalSql", "sql"});
        SQL_PATHS.put("com.mysql.jdbc.JDBC4PreparedStatement", new String[]{"originalSql"});
        // PostgreSQL
        SQL_PATHS.put("org.postgresql.jdbc.PgPreparedStatement", new String[]{"preparedQuery.query.nativeSql", "preparedQuery.key"});
        SQL_PATHS.put("org.postgresql.jdbc.PgStatement", new String[]{"lastSimpleQuery.nativeSql"});
        // Oracle
        SQL_PATHS.put("oracle.jdbc.driver.OraclePreparedStatement", new String[]{"sqlObject.originalSql", "originalSql", "sql"});
        SQL_PATHS.put("oracle.jdbc.driver.T4CPreparedStatement", new String[]{"sqlObject.originalSql", "originalSql"});
        // SQL Server
        SQL_PATHS.put("com.microsoft.sqlserver.jdbc.SQLServerPreparedStatement", new String[]{"preparedSQL", "userSQL"});
        // MariaDB
        SQL_PATHS.put("org.mariadb.jdbc.ClientPreparedStatement", new String[]{"sql"});
        SQL_PATHS.put("org.mariadb.jdbc.ServerPreparedStatement", new String[]{"sql"});
        // H2 / SQLite / DB2（常见）
        SQL_PATHS.put("org.h2.jdbc.JdbcPreparedStatement", new String[]{"sqlStatement"});
        SQL_PATHS.put("org.sqlite.jdbc4.JDBC4PreparedStatement", new String[]{"sql"});
        SQL_PATHS.put("com.ibm.db2.jcc.am.PreparedStatement", new String[]{"sqlStatement"});
        // ORM / 框架
        SQL_PATHS.put("org.apache.ibatis.mapping.BoundSql", new String[]{"sql"});                 // MyBatis
        SQL_PATHS.put("org.hibernate.query.sql.spi.NativeQueryImpl", new String[]{"sqlString"});  // Hibernate native
        // 连接池包装（取代理后的 delegate 再递归，或包装里直接存 sql）
        SQL_PATHS.put("com.alibaba.druid.proxy.jdbc.PreparedStatementProxyImpl", new String[]{"sql"});
        SQL_PATHS.put("com.alibaba.druid.pool.DruidPooledPreparedStatement", new String[]{"sql", "stmt.sql"});
        SQL_PATHS.put("com.zaxxer.hikari.pool.ProxyPreparedStatement", new String[]{"delegate.sql", "delegate.originalSql"});
    }

    // ---- 2) 通用 SQL 正则（兜底：局部变量里任意 String 像 SQL）----
    private static final Pattern SQL_LIKE = Pattern.compile(
            "^\\s*(SELECT|INSERT|UPDATE|DELETE|MERGE|WITH|CALL)\\b", Pattern.CASE_INSENSITIVE);

    // ---- 3) 无害 SQL 过滤（心跳/连接校验，肯定不会 OOM）----
    private static final Pattern HARMLESS = Pattern.compile(
            "^\\s*(" +
            "SELECT\\s+1(\\s+FROM\\s+DUAL)?" +          // select 1 / select 1 from dual
            "|SELECT\\s+'?\\w'?\\s*$" +                  // select 'x'
            "|/\\*\\s*ping\\s*\\*/" +                    // /* ping */ SELECT ...
            "|VALUES\\s*\\(\\s*1\\s*\\)" +               // VALUES(1) (DB2/H2 校验)
            ")", Pattern.CASE_INSENSITIVE);

    /**
     * @param onlyRisky true=只返回有风险的（过滤心跳类）；false=全部
     */
    public static String extract(ISnapshot snapshot, boolean onlyRisky) throws Exception {
        List<Hit> hits = scan(snapshot);

        // 线程 retained 缓存（同线程多条 SQL 不重复算）
        Map<Integer, Long> threadRetained = new java.util.HashMap<>();
        java.util.function.ToLongFunction<Integer> retOf = id -> threadRetained.computeIfAbsent(id, k -> {
            try { return snapshot.getRetainedHeapSize(k); } catch (Exception e) { return 0L; }
        });
        // 排序：高风险优先，其次线程 retained 降序（最可能是 OOM 元凶的排最前）
        hits.sort((a, b) -> {
            int ra = riskRank(a.risk), rb = riskRank(b.risk);
            if (ra != rb) return Integer.compare(ra, rb);
            return Long.compare(retOf.applyAsLong(b.threadId), retOf.applyAsLong(a.threadId));
        });

        Json arr = Json.arr();
        int risky = 0;
        for (Hit h : hits) {
            if (onlyRisky && h.risk == Risk.HARMLESS) continue;
            if (h.risk == Risk.HARMLESS) {} else risky++;
            long tret = retOf.applyAsLong(h.threadId);
            arr.item(Json.obj()
                    .kv("threadId", h.threadId)
                    .kv("threadName", h.threadName)
                    .kv("threadRetainedBytes", tret)
                    .kv("threadRetained", Json.humanBytes(tret))   // 线程 retained：大 + 高风险SQL = OOM 元凶强证据
                    .kv("frame", h.frame)
                    .kv("source", h.source)
                    .kv("objectId", h.objectId)
                    .kv("sql", h.sql.length() > 2000 ? h.sql.substring(0, 2000) + "…" : h.sql)
                    .kv("risk", h.risk.name().toLowerCase())     // risky | harmless | unknown
                    .kv("reason", h.reason)
                    .endObj());
        }
        return Json.obj()
                .kv("total", hits.size())
                .kv("risky", risky)
                .raw("items", arr.endArr())
                .endObj();
    }

    // ===================== 内部 =====================

    private enum Risk { RISKY, HARMLESS, UNKNOWN }

    /** 排序优先级：RISKY 最前，UNKNOWN 次之，HARMLESS 最后。 */
    private static int riskRank(Risk r) {
        return switch (r) { case RISKY -> 0; case UNKNOWN -> 1; case HARMLESS -> 2; };
    }

    private record Hit(int threadId, String threadName, String frame, String source,
                       int objectId, String sql, Risk risk, String reason) { }

    private static List<Hit> scan(ISnapshot snapshot) throws Exception {
        List<Hit> out = new ArrayList<>();
        for (IClass tc : snapshot.getClassesByName("java.lang.Thread", true)) {
            for (int tid : tc.getObjectIds()) {
                IObject thread = snapshot.getObject(tid);
                String tname = ObjectRows.safeDisplayName(thread);
                IThreadStack stack;
                try { stack = snapshot.getThreadStack(tid); } catch (Exception e) { continue; }
                if (stack == null || stack.getStackFrames() == null) continue;

                for (IStackFrame frame : stack.getStackFrames()) {
                    int[] locals = frame.getLocalObjectsIds();
                    if (locals == null) continue;
                    for (int lid : locals) {
                        IObject local = safeObj(snapshot, lid);
                        if (local == null) continue;
                        // a) 已知 statement 类
                        Hit h = fromKnownStatement(snapshot, local, tid, tname, frame.getText());
                        // b) 兜底：local 本身是 String 且像 SQL
                        if (h == null) h = fromRawString(local, tid, tname, frame.getText());
                        if (h != null) out.add(h);
                    }
                }
            }
        }
        return dedup(out);
    }

    private static Hit fromKnownStatement(ISnapshot s, IObject local, int tid, String tname, String frameText) {
        try {
            String cls = local.getClazz() != null ? local.getClazz().getName() : "";
            String[] paths = SQL_PATHS.get(cls);
            if (paths == null) {
                // 也匹配子类/包装（前缀粗判，覆盖匿名/版本子类）
                for (var e : SQL_PATHS.entrySet()) {
                    if (cls.startsWith(e.getKey())) { paths = e.getValue(); break; }
                }
            }
            if (paths == null) return null;
            for (String path : paths) {
                String sql = resolveString(local, path);
                if (sql != null && !sql.isBlank()) {
                    return classify(tid, tname, frameText, cls, local.getObjectId(), sql);
                }
            }
        } catch (Throwable ignore) { }
        return null;
    }

    private static Hit fromRawString(IObject local, int tid, String tname, String frameText) {
        try {
            IClass c = local.getClazz();
            if (c == null || !"java.lang.String".equals(c.getName())) return null;
            String s = local.getClassSpecificName();
            if (s != null && SQL_LIKE.matcher(s).find()) {
                return classify(tid, tname, frameText, "String (local var)", local.getObjectId(), s);
            }
        } catch (Throwable ignore) { }
        return null;
    }

    /** 沿点路径取最终的 String 值（resolveValue 深读 + getClassSpecificName）。 */
    private static String resolveString(IObject obj, String path) {
        try {
            Object v = obj.resolveValue(path);
            if (v == null) return null;
            if (v instanceof IObject io) return io.getClassSpecificName();   // String 取文本
            return String.valueOf(v);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 风险分级：过滤心跳类，标记疑似大查询。 */
    private static Hit classify(int tid, String tname, String frameText, String source, int oid, String sql) {
        String trimmed = sql.trim();
        Risk risk; String reason;
        if (HARMLESS.matcher(trimmed).find()) {
            risk = Risk.HARMLESS; reason = "心跳/连接校验查询，不会 OOM";
        } else if (SQL_LIKE.matcher(trimmed).find()) {
            // 启发式风险信号：SELECT * / 无 WHERE / 无 LIMIT 的查询更可能 OOM。
            // 关键：有分页(LIMIT/ROWNUM/FETCH FIRST/TOP)的查询结果集受限 → 不算高风险。
            String low = trimmed.toLowerCase();
            boolean hasLimit = low.contains("limit") || low.contains("rownum")
                    || low.contains("fetch first") || low.matches("(?is)^\\s*select\\s+top\\b.*");
            boolean selectStar = trimmed.matches("(?is)^\\s*SELECT\\s+\\*.*");
            boolean noWhere = trimmed.matches("(?is)^\\s*SELECT\\b((?!\\bWHERE\\b).)*$");
            if (hasLimit) {
                risk = Risk.UNKNOWN; reason = "含分页(LIMIT/ROWNUM/...)，结果集受限";
            } else if (selectStar || noWhere) {
                risk = Risk.RISKY;
                List<String> rs = new ArrayList<>();
                if (selectStar) rs.add("SELECT *");
                if (noWhere) rs.add("无 WHERE");
                rs.add("无分页 → 疑似大结果集");
                reason = String.join("，", rs);
            } else {
                risk = Risk.UNKNOWN; reason = "查询语句（有 WHERE，风险待评估）";
            }
        } else {
            risk = Risk.UNKNOWN; reason = "可能的 SQL";
        }
        return new Hit(tid, tname, frameText, source, oid, trimmed, risk, reason);
    }

    /** 同一 SQL 在一个线程多帧重复出现 → 去重（按 thread+sql）。 */
    private static List<Hit> dedup(List<Hit> in) {
        Map<String, Hit> m = new LinkedHashMap<>();
        for (Hit h : in) {
            String key = h.threadId + "|" + h.sql;
            m.putIfAbsent(key, h);   // 保留第一个（通常栈更上层 = 更接近调用点）
        }
        return new ArrayList<>(m.values());
    }

    private static IObject safeObj(ISnapshot s, int id) {
        try { return s.getObject(id); } catch (Exception e) { return null; }
    }
}
