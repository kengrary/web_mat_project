package com.jvmind.mat.query.view;

import com.jvmind.mat.query.model.Json;
import org.eclipse.mat.query.Column;
import org.eclipse.mat.query.IResultTable;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.SnapshotFactory;
import org.eclipse.mat.snapshot.IOQLQuery;
import org.eclipse.mat.util.VoidProgressListener;

/**
 * OQL 视图（文档 06 §3.4）。
 *
 * <p>{@code SnapshotFactory.createQuery(oql).execute(snapshot, listener)} 返回 Object，两种形态：
 * <ul>
 *   <li><b>int[]</b>：对象 id 数组（如 {@code SELECT * FROM ...}）→ 渲染成对象行；</li>
 *   <li><b>IResultTable</b>：自定义投影（如 {@code SELECT s.count, s FROM ...}）→ 渲染成表格。</li>
 * </ul>
 * <p>安全：限制返回行数（limit），防 LLM/用户写出全表扫描撑爆响应（文档 04/05 安全要点）。
 * 执行时长上限建议在 HTTP 层用超时 + 取消（此处先做行数截断）。
 */
public final class OqlView {

    private static final VoidProgressListener SILENT = new VoidProgressListener();

    /**
     * @param view 'list'（默认，对象列表/表格）或 'histogram'（仅当结果是对象集 int[] 时，按类聚合）
     * @param sort 排序维度（histogram 视图用：shallow|retained|count；list 视图也可按 retained/shallow）
     */
    public static String execute(ISnapshot snapshot, String oql, int limit, int offset,
                                 String view, String sort,
                                 com.jvmind.mat.query.core.ResultSetCache cache, String dumpDir)
            throws Exception {
        IOQLQuery query = SnapshotFactory.createQuery(oql);
        Object result = query.execute(snapshot, SILENT);

        if (result == null) {
            return Json.obj().kv("kind", "empty").kv("totalRows", 0).endObj();
        }
        if (result instanceof int[] ids) {
            // ★ 把结果对象集存进缓存，返回 resultSetId，供后续投喂给 histogram/dominator/retained（分析流复用）
            String rsId = (cache != null) ? cache.put(dumpDir, ids) : null;

            // 直方图视图：对 OQL 结果对象集做 histogram（复用 HistogramView.bySubset）
            if ("histogram".equalsIgnoreCase(view)) {
                String json = HistogramView.bySubset(snapshot, ids, limit, offset, parseSort(sort), true);
                Json out = Json.obj()
                        .kv("kind", "histogram")
                        .kv("resultObjects", ids.length)
                        .raw("histogram", json);
                if (rsId != null) out.kv("resultSetId", rsId);
                return out.endObj();
            }
            return renderObjectIds(snapshot, ids, limit, offset, sort, rsId);
        }
        if (result instanceof IResultTable table) {
            return renderTable(snapshot, table, limit, offset);
        }
        // 其它（标量等）：直接 toString
        return Json.obj().kv("kind", "scalar").kv("value", String.valueOf(result)).endObj();
    }

    private static Integer[] box(int[] a) {
        Integer[] b = new Integer[a.length];
        for (int i = 0; i < a.length; i++) b[i] = a[i];
        return b;
    }

    private static HistogramView.Sort parseSort(String s) {
        if (s == null) return HistogramView.Sort.SHALLOW;
        return switch (s.toLowerCase()) {
            case "retained" -> HistogramView.Sort.RETAINED;
            case "count" -> HistogramView.Sort.COUNT;
            default -> HistogramView.Sort.SHALLOW;
        };
    }

    private static String renderObjectIds(ISnapshot snapshot, int[] ids, int limit, int offset, String sort,
                                          String resultSetId) throws Exception {
        // 排序（按 retained/shallow；默认不排，保持 OQL 返回顺序）
        Integer[] order = box(ids);
        if (sort != null && !sort.isBlank()) {
            final boolean byRet = "retained".equalsIgnoreCase(sort);
            java.util.Arrays.sort(order, (a, b) -> {
                try {
                    long va = byRet ? snapshot.getRetainedHeapSize(a) : snapshot.getHeapSize(a);
                    long vb = byRet ? snapshot.getRetainedHeapSize(b) : snapshot.getHeapSize(b);
                    return Long.compare(vb, va);   // 降序
                } catch (Exception e) { return 0; }
            });
        }
        int total = order.length;
        int from = Math.min(offset, total);
        int to = Math.min(offset + limit, total);
        Json rows = Json.arr();
        for (int i = from; i < to; i++) {
            rows.item(ObjectRows.row(snapshot, order[i]));
        }
        Json out = Json.obj()
                .kv("kind", "objectList")
                .raw("rows", rows.endArr())
                .kv("totalRows", total)
                .kv("returned", to - from)
                .kv("truncated", to < total);
        if (resultSetId != null) out.kv("resultSetId", resultSetId);
        if (to < total) out.kv("cursor", "offset:" + to);
        return out.endObj();
    }

    private static String renderTable(ISnapshot snapshot, IResultTable table, int limit, int offset) {
        Column[] cols = table.getColumns();
        int total = table.getRowCount();
        int from = Math.min(offset, total);
        int to = Math.min(offset + limit, total);

        // 列名
        Json colsJson = Json.arr();
        for (Column c : cols) colsJson.item(Json.obj().kv("label", c.getLabel()).endObj());

        Json rows = Json.arr();
        for (int r = from; r < to; r++) {
            Object row = table.getRow(r);
            Json cells = Json.arr();
            for (int ci = 0; ci < cols.length; ci++) {
                Object val = table.getColumnValue(row, ci);
                cells.item("\"" + escape(String.valueOf(val)) + "\"");
            }
            // 附带该行的对象 id（若有上下文），便于行内跳转/投喂
            int objId = -1;
            try {
                var ctx = table.getContext(row);
                if (ctx != null) objId = ctx.getObjectId();
            } catch (Throwable ignore) { }
            rows.item(Json.obj().kv("objectId", objId).raw("cells", cells.endArr()).endObj());
        }

        Json out = Json.obj()
                .kv("kind", "table")
                .raw("columns", colsJson.endArr())
                .raw("rows", rows.endArr())
                .kv("totalRows", total)
                .kv("returned", to - from)
                .kv("truncated", to < total);
        if (to < total) out.kv("cursor", "offset:" + to);
        return out.endObj();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
