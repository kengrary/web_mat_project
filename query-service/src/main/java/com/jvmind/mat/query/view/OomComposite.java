package com.jvmind.mat.query.view;

import com.jvmind.mat.query.model.Json;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.util.IProgressListener;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 复合诊断工具 <b>mat_diagnose_oom</b> 的后端：一次打开 snapshot，聚合三路领域诊断 ——
 * <ol>
 *   <li>{@link OomDiagnosis}：OOM 触发线程识别 + 元凶/受害者判定（谁触发了 OOM、它自己是不是大头）。</li>
 *   <li>{@link SqlExtractor}：线程栈里的 SQL 提取 + 风险分级（SELECT * / 无分页 等 = 内存炸点）。</li>
 *   <li>{@link DiagnosticsView#leakSuspects}：MAT 招牌 Leak Suspects（全图支配树找泄漏嫌疑）。</li>
 * </ol>
 *
 * <p><b>为什么做成一个工具</b>：agent 排查 OOM 时，这三步几乎总是连着问。合成一个 endpoint：
 * ① 只开一次 snapshot（省内存/IO，三路共用同一租约）；② 给一个 {@code summary}
 * 把三路结论拧成一句话（verdict + headline + recommendation），减少 agent 的 token 与往返。
 *
 * <p><b>成本</b>：内含 leakhunter（全图分析，可能几分钟）→ 本工具走<b>异步任务</b>（返回 taskId，轮询 /tasks/{id}）。
 *
 * <p>实现取舍：不改三个 view 的 API，直接复用它们返回的 JSON 字符串作为 {@code sections} 原样嵌入；
 * summary 用轻量正则从这些 JSON 里取几个关键字段（verdict / 计数）合成，<b>不重复计算</b>。
 */
public final class OomComposite {

    private OomComposite() { }

    /**
     * @param culpritPct OOM 元凶阈值（retained 占总堆 %，默认 30 由调用方传入）
     * @param onlyRiskySql true=SQL 只看 risky/unknown（过滤心跳类），减小输出
     */
    public static String diagnose(ISnapshot snapshot, int culpritPct, boolean onlyRiskySql,
                                  IProgressListener listener) throws Exception {
        // —— 三路诊断（同一 snapshot，按从轻到重排，leakhunter 放最后）——
        String oomJson;
        try { oomJson = OomDiagnosis.diagnose(snapshot, culpritPct); }
        catch (Exception e) { oomJson = errSection(e); }

        String sqlJson;
        try { sqlJson = SqlExtractor.extract(snapshot, onlyRiskySql); }
        catch (Exception e) { sqlJson = errSection(e); }

        if (listener != null) listener.subTask("leak-suspects (leakhunter)");
        String leakJson;
        try { leakJson = DiagnosticsView.leakSuspects(snapshot, listener); }
        catch (Exception e) { leakJson = errSection(e); }

        // —— 合成 summary（从三路 JSON 取关键字段，不重算）——
        String oomVerdict = str(oomJson, "verdict");                 // culprit|victim|unclear
        long triggerCount = num(oomJson, "triggerThreadCount");
        long riskySql     = num(sqlJson, "risky");
        long sqlTotal     = num(sqlJson, "total");

        String topThread     = firstObjStr(oomJson, "triggerThreads", "threadName");
        String topThreadRet  = firstObjStr(oomJson, "triggerThreads", "retained");
        String topThreadPct  = firstObjNum(oomJson, "triggerThreads", "percentHeap");
        String culpritHintTh = str(oomJson, "culpritHint", "threadName"); // 受害者场景下的真元凶线索（嵌套对象）

        Verdict v = synthVerdict(oomVerdict, triggerCount, riskySql);
        String headline = headline(v, oomVerdict, triggerCount, topThread, topThreadRet, topThreadPct,
                riskySql, sqlTotal, culpritHintTh);
        String recommend = recommend(v, riskySql, oomVerdict);

        Json summary = Json.obj()
                .kv("verdict", v.code)                 // sql_oom | thread_culprit | leak_suspect | victim | unclear
                .kv("confidence", v.confidence)        // high | medium | low
                .kv("headline", headline)
                .kv("recommendation", recommend)
                .kv("oomVerdict", oomVerdict == null ? "unclear" : oomVerdict)
                .kv("triggerThreadCount", triggerCount)
                .kv("riskySqlCount", riskySql)
                .kv("sqlTotal", sqlTotal);

        // 三路原始结果原样嵌入，前端/agent 需要细节时直接读
        Json sections = Json.obj()
                .raw("oomDiagnosis", oomJson)
                .raw("sqlInThreads", sqlJson)
                .raw("leakSuspects", leakJson);

        return Json.obj()
                .raw("summary", summary.endObj())
                .raw("sections", sections.endObj())
                .kv("note", "复合诊断：OOM触发线程 + SQL风险 + Leak Suspects。各 section 为对应单工具原始输出。")
                .endObj();
    }

    // ===================== 结论合成 =====================

    private record Verdict(String code, String confidence) { }

    /**
     * 综合判定优先级（经验法则）：
     * - 有高风险 SQL 且 OOM 元凶成立 → sql_oom（最可操作，直接给到代码层）。
     * - OOM 元凶线程成立（无高风险SQL）→ thread_culprit。
     * - OOM 判受害者 / 无明确触发线程 → 看 Leak Suspects → leak_suspect 或 victim。
     */
    private static Verdict synthVerdict(String oomVerdict, long triggerCount, long riskySql) {
        boolean culprit = "culprit".equals(oomVerdict);
        if (culprit && riskySql > 0)  return new Verdict("sql_oom", "high");
        if (culprit)                  return new Verdict("thread_culprit", "high");
        if (riskySql > 0)             return new Verdict("sql_oom", "medium");
        if ("victim".equals(oomVerdict)) return new Verdict("leak_suspect", "medium");
        if (triggerCount > 0)         return new Verdict("thread_culprit", "low");
        return new Verdict("leak_suspect", "low");
    }

    private static String headline(Verdict v, String oomVerdict, long triggerCount,
                                   String topThread, String topThreadRet, String topThreadPct,
                                   long riskySql, long sqlTotal, String culpritHint) {
        StringBuilder sb = new StringBuilder();
        switch (v.code) {
            case "sql_oom" -> {
                sb.append("疑似 SQL 触发 OOM：发现 ").append(riskySql).append(" 条高风险/未知风险 SQL");
                if (topThread != null) sb.append("；触发线程 ").append(topThread);
                if (topThreadRet != null) sb.append(" 持有 ").append(topThreadRet)
                        .append(topThreadPct != null ? "（占堆 " + topThreadPct + "%）" : "");
                sb.append("。大结果集（SELECT */无分页）是常见炸点。");
            }
            case "thread_culprit" -> {
                sb.append("OOM 元凶为线程");
                if (topThread != null) sb.append(" ").append(topThread);
                if (topThreadRet != null) sb.append("，retained ").append(topThreadRet)
                        .append(topThreadPct != null ? "（占堆 " + topThreadPct + "%）" : "");
                sb.append("。该线程持有大量内存。");
            }
            case "leak_suspect" -> {
                if ("victim".equals(oomVerdict)) {
                    sb.append("触发 OOM 的线程本身不占内存（受害者）");
                    if (culpritHint != null) sb.append("，真正的大头线索：").append(culpritHint);
                    sb.append("。建议看 Leak Suspects 找真正持有者。");
                } else {
                    sb.append("未识别到明确的 OOM 触发线程，转向 Leak Suspects 报告找泄漏嫌疑。");
                }
            }
            default -> sb.append("证据不足以定调，请结合各 section 细节人工研判。");
        }
        if (sqlTotal > 0 && !"sql_oom".equals(v.code)) sb.append("（另:线程栈中共 ").append(sqlTotal).append(" 条 SQL）");
        return sb.toString();
    }

    private static String recommend(Verdict v, long riskySql, String oomVerdict) {
        return switch (v.code) {
            case "sql_oom" -> "先看 sections.sqlInThreads 里 risk=risky 的 SQL 与对应线程 retained；"
                    + "对 SELECT */无分页加分页或 WHERE，再用 path2gc 确认结果集持有链。";
            case "thread_culprit" -> "用 /object 或 path2gc 展开元凶线程的栈帧局部变量，定位大对象的分配点。";
            case "leak_suspect" -> "看 sections.leakSuspects 的嫌疑段（problemSuspects/累加器），"
                    + "对嫌疑对象跑 path2gc / merge-paths 找 GC Root 持有链。";
            default -> "结合 sections 三路证据人工研判；可调 culpritPct 阈值或单独跑 /histogram、/dominator。";
        };
    }

    // ===================== 轻量 JSON 取值（避免引第三方解析）=====================

    private static String errSection(Exception e) {
        return Json.obj().kv("error", e.getMessage() != null ? e.getMessage() : e.getClass().getName()).endObj();
    }

    /** 顶层字符串字段 "key":"value"。 */
    private static String str(String json, String key) {
        if (json == null) return null;
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? unesc(m.group(1)) : null;
    }
    /** 嵌套对象里的字符串字段："parent":{...,"child":"value"...} （取 parent 之后第一个 child）。 */
    private static String str(String json, String parent, String child) {
        if (json == null) return null;
        int p = json.indexOf("\"" + parent + "\"");
        if (p < 0) return null;
        Matcher m = Pattern.compile("\"" + Pattern.quote(child) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json.substring(p));
        return m.find() ? unesc(m.group(1)) : null;
    }
    /** 顶层数值字段 "key":number。 */
    private static long num(String json, String key) {
        if (json == null) return 0;
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }
    /** 数组 "arrKey":[ {...,"field":"value"...}, ...] 里第一个元素的字符串字段。 */
    private static String firstObjStr(String json, String arrKey, String field) {
        if (json == null) return null;
        int a = json.indexOf("\"" + arrKey + "\"");
        if (a < 0) return null;
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json.substring(a));
        return m.find() ? unesc(m.group(1)) : null;
    }
    /** 数组里第一个元素的数值字段（返回原文，保留小数）。 */
    private static String firstObjNum(String json, String arrKey, String field) {
        if (json == null) return null;
        int a = json.indexOf("\"" + arrKey + "\"");
        if (a < 0) return null;
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
                .matcher(json.substring(a));
        return m.find() ? m.group(1) : null;
    }
    private static String unesc(String s) {
        return s == null ? null : s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", " ");
    }
}
