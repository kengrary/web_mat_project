package com.jvmind.mat.query.view;

import com.jvmind.mat.query.core.QueryRunner;
import com.jvmind.mat.query.model.Json;
import org.eclipse.mat.query.IResult;
import org.eclipse.mat.query.IResultTable;
import org.eclipse.mat.query.results.CompositeResult;
import org.eclipse.mat.query.results.TextResult;
import org.eclipse.mat.report.QuerySpec;
import org.eclipse.mat.report.SectionSpec;
import org.eclipse.mat.report.Spec;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.util.IProgressListener;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P0 高价值诊断视图（文档 08 §2）：把 MAT 的王牌 query 通过
 * {@link QueryRunner}（统一执行）+ {@link ResultRenderer}（统一渲染）暴露出来。
 *
 * <p>注意它们的成本：leakhunter/retained_set 较重（涉及全图分析），HTTP 层应配超时 +
 * 异步任务（文档05 §6.1）。本类只负责"执行+渲染"，调度交给上层。
 */
public final class DiagnosticsView {

    private DiagnosticsView() { }

    /**
     * Leak Suspects 报告（MAT 招牌）。返回结构化 JSON：
     * <ul>
     *   <li>{@code kind:"leak-suspects"}</li>
     *   <li>{@code suspects:[{name,className,instanceCount,retainedBytes,description,preferredObjectId}]}
     *       — 前端/Agent 直接消费的紧凑数组，每个嫌疑段提炼出关键信息，不暴露 MAT 的
     *       SectionSpec/QuerySpec 嵌套细节；</li>
     *   <li>{@code report:{kind:"section", parts:[...]}} — 完整递归渲染的 MAT 报告树
     *       （饼图/子表/钻取命令等），用于"展开查看细节"。</li>
     * </ul>
     *
     * <p>当 dump 里没有任何嫌疑时（MAT 返回 {@code TextResult("Nothing found")}），
     * {@code suspects:[]} 且 {@code report.kind:"text"}。
     */
    public static String leakSuspects(ISnapshot snapshot, IProgressListener listener) throws Exception {
        IResult r = QueryRunner.run(snapshot, "leakhunter", null, listener);
        String fullReport = ResultRenderer.render(r);

        Json suspects = Json.arr();
        if (r instanceof SectionSpec root) {
            List<Spec> children = root.getChildren();
            if (children != null) {
                for (Spec child : children) {
                    if (!(child instanceof QuerySpec qs)) continue;
                    String name = qs.getName();
                    if (name == null || !name.startsWith("Problem Suspect ")) continue;
                    suspects.item(extractSuspect(qs, name));
                }
            }
        }
        return Json.obj()
                .kv("kind", "leak-suspects")
                .raw("suspects", suspects.endArr())
                .raw("report", fullReport)
                .endObj();
    }

    /**
     * Extract a compact summary from one "Problem Suspect N" QuerySpec.
     * Inner CompositeResult layout (per LeakHunterQuery source):
     * <pre>
     *   entries[0]   = Description TextResult (HTML: "X instances of C occupy N bytes...")
     *   entries[1..] = QuerySpec: "Suspect Objects by Class" (pie-table),
     *                              "All Accumulated Objects by Class" (retained-set histogram),
     *                              "Shortest Paths To GC Roots" (path2gc tree),
     *                              "Thread Details", etc.
     * </pre>
     */
    private static String extractSuspect(QuerySpec qs, String fallbackName) {
        Json out = Json.obj().kv("name", fallbackName);
        String description = null;
        long retainedBytes = -1;
        int preferredObjectId = -1;
        String className = null;
        int instanceCount = -1;

        IResult inner = qs.getResult();
        if (inner instanceof CompositeResult cr) {
            for (CompositeResult.Entry e : cr.getResultEntries()) {
                IResult child = e.getResult();
                // First TextResult child is the description (HTML).
                if (description == null && child instanceof TextResult tr && tr.getText() != null) {
                    description = stripHtml(tr.getText());
                    SuspectHeader h = parseSuspectHeader(description);
                    if (h != null) {
                        className = h.className;
                        instanceCount = h.instanceCount;
                        retainedBytes = h.retainedBytes;
                    }
                }
                // First table with a "Retained Heap" column → top-row objectId as the
                // preferred navigation anchor for path2gc/object drill-down.
                if (preferredObjectId < 0) {
                    IResultTable table = asTable(child);
                    if (table != null && hasRetainedColumn(table) && table.getRowCount() > 0) {
                        try {
                            preferredObjectId = table.getContext(table.getRow(0)).getObjectId();
                        } catch (Throwable ignored) { /* skip */ }
                    }
                }
            }
        }
        if (description != null) out.kv("description", description);
        if (className != null) out.kv("className", className);
        if (instanceCount > 0) out.kv("instanceCount", instanceCount);
        if (retainedBytes >= 0) out.kv("retainedBytes", retainedBytes);
        if (preferredObjectId >= 0) out.kv("preferredObjectId", preferredObjectId);
        String cmd = qs.getCommand();
        if (cmd != null && !cmd.isEmpty()) out.kv("command", cmd);
        return out.endObj();
    }

    /** Unwrap a QuerySpec-wrapped table, or pass a bare table through. */
    private static IResultTable asTable(IResult r) {
        if (r instanceof IResultTable t) return t;
        if (r instanceof QuerySpec qs && qs.getResult() instanceof IResultTable t) return t;
        return null;
    }

    private static boolean hasRetainedColumn(IResultTable t) {
        for (org.eclipse.mat.query.Column c : t.getColumns()) {
            if (c.getLabel() != null && c.getLabel().toLowerCase().contains("retained")) return true;
        }
        return false;
    }

    private static String stripHtml(String s) {
        if (s == null) return null;
        String noTags = s.replaceAll("<[^>]+>", "");
        return noTags.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replaceAll("\\s+", " ").trim();
    }

    /**
     * Parse the MAT LeakHunter description text (HTML stripped) to extract
     * className, instanceCount, and retainedBytes. MAT uses several phrasings:
     * <ul>
     *   <li>"1,136 instances of {@code <class>} [loaded by {@code <loader>}] occupy 319,784 (27.31%) bytes. ..."</li>
     *   <li>"12 instances of {@code <class>} occupy 128,496 (10.97%) bytes. ..."</li>
     *   <li>"One instance of {@code <class>} [loaded by {@code <loader>}] occupies 157,624 (13.46%) bytes. ..."</li>
     *   <li>"The class {@code <class>}, loaded by {@code <loader>}, occupies 154,304 (13.18%) bytes. ..."</li>
     * </ul>
     */
    private static SuspectHeader parseSuspectHeader(String text) {
        if (text == null) return null;
        Matcher m = HEADER_N_INST.matcher(text);
        if (m.find()) return new SuspectHeader(cleanClassName(m.group(2)), parseCommaInt(m.group(1)), parseCommaLong(m.group(3)));
        m = HEADER_ONE_INST.matcher(text);
        if (m.find()) return new SuspectHeader(cleanClassName(m.group(1)), 1, parseCommaLong(m.group(2)));
        m = HEADER_THE_CLASS.matcher(text);
        if (m.find()) return new SuspectHeader(cleanClassName(m.group(1)), 1, parseCommaLong(m.group(2)));
        return null;
    }

    /** Trim " loaded by ..." / ", loaded by ..." suffix that the regex leaves in className. */
    private static String cleanClassName(String s) {
        if (s == null) return null;
        int idx = s.indexOf(" loaded by ");
        if (idx >= 0) s = s.substring(0, idx);
        return s.trim().replaceAll(",+$", "");
    }

    private record SuspectHeader(String className, int instanceCount, long retainedBytes) { }

    private static long parseCommaLong(String s) {
        if (s == null) return -1;
        try { return Long.parseLong(s.replace(",", "")); } catch (NumberFormatException e) { return -1; }
    }

    private static int parseCommaInt(String s) {
        long v = parseCommaLong(s);
        return v < 0 || v > Integer.MAX_VALUE ? -1 : (int) v;
    }

    private static final Pattern HEADER_N_INST = Pattern.compile(
            "^([\\d,]+) instances of (.+?) occupy ([\\d,]+) ");
    private static final Pattern HEADER_ONE_INST = Pattern.compile(
            "^One instance of (.+?) occupies ([\\d,]+) ");
    private static final Pattern HEADER_THE_CLASS = Pattern.compile(
            "^The class (.+?), loaded by .+?, occupies ([\\d,]+) ");

    /**
     * 单对象 → GC Root 最短路径（path2gc）。
     * 泄漏归因核心："谁攥着这个对象不放"。
     *
     * <p>MAT 内置 {@code path2gc} query 返回 IResultTree，但它是 UI 懒加载形态——只有 root 节点，
     * 子节点靠 UI 再次发 query 展开（不是一次性给完整路径）。为了让 Agent/前端直接拿到可读链，这里
     * 直接用 {@link ISnapshot#getPathsFromGCRoots} 计算最短路径（MAT 内部支配树/引用链 BFS），
     * 同时附上原始 tree 用于交互式展开。
     *
     * @param objectId 目标对象
     * @param excludeWeakSoft 是否排除弱/软/虚引用（避免无关引用干扰，常用 true）
     */
    public static String pathToGcRoots(ISnapshot snapshot, int objectId,
                                       boolean excludeWeakSoft, IProgressListener listener) throws Exception {
        IResult r = QueryRunner.run(snapshot, "path2gc",
                Map.of("object", new int[]{ objectId }), listener);
        String rendered = ResultRenderer.render(r);
        String paths = PathsLinearizer.pathsFromGCRoots(snapshot, objectId, 5, 30);
        return Json.obj()
                .kv("kind", "paths-to-gc-roots")
                .kv("objectId", objectId)
                .raw("paths", paths)
                .raw("tree", rendered)
                .endObj();
    }

    /**
     * 多对象合并最短路径（merge_shortest_paths），按类分组 —— 找泄漏模式最常用。
     */
    public static String mergeShortestPaths(ISnapshot snapshot, int[] objectIds,
                                            IProgressListener listener) throws Exception {
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("objects", objectIds);
        IResult r = QueryRunner.run(snapshot, "merge_shortest_paths", args, listener);
        return ResultRenderer.render(r);
    }

    /** 最大内存消费者（top_consumers）：按类/包/类加载器聚合"谁占内存"。 */
    public static String topConsumers(ISnapshot snapshot, IProgressListener listener) throws Exception {
        IResult r = QueryRunner.run(snapshot, "top_consumers", null, listener);
        return ResultRenderer.render(r);
    }

    /** 任意对象集的 retained set（show_retained_set）："删掉这批能省多少"。 */
    public static String retainedSet(ISnapshot snapshot, int[] objectIds,
                                     IProgressListener listener) throws Exception {
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("objects", objectIds);
        IResult r = QueryRunner.run(snapshot, "show_retained_set", args, listener);
        return ResultRenderer.render(r);
    }

    /**
     * 通用直通：执行任意 MAT query（文档 04 自动导出/长尾覆盖）。
     * 谨慎暴露给外部——可作为内部/agent 高级能力。
     */
    public static String runArbitrary(ISnapshot snapshot, String command,
                                      Map<String, Object> args, IProgressListener listener) throws Exception {
        IResult r = QueryRunner.run(snapshot, command, args, listener);
        return ResultRenderer.render(r);
    }
}
