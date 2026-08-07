package com.jvmind.mat.query.view;

import com.jvmind.mat.query.model.Json;
import org.eclipse.mat.query.Column;
import org.eclipse.mat.query.IContextObject;
import org.eclipse.mat.query.IResult;
import org.eclipse.mat.query.IResultPie;
import org.eclipse.mat.query.IResultTable;
import org.eclipse.mat.query.IResultTree;
import org.eclipse.mat.query.results.CompositeResult;
import org.eclipse.mat.query.results.TextResult;
import org.eclipse.mat.report.QuerySpec;
import org.eclipse.mat.report.SectionSpec;
import org.eclipse.mat.report.Spec;

import java.util.List;

/**
 * 通用结果渲染器（文档 08 §3 的"杠杆点"）：把任意 MAT {@link IResult} 递归渲染成瘦身 JSON。
 *
 * <p>覆盖 MAT 的全部结果形态（读源码确认的类型体系）：
 * <ul>
 *   <li>{@link IResultTable} → 表格（行+列，行带 objectId 便于跳转/投喂）；</li>
 *   <li>{@link IResultTree} → 树（一次一层，<b>懒加载</b>，不递归整棵树，对齐 MAT UI 效率）；</li>
 *   <li>{@link TextResult} → 文本/HTML；</li>
 *   <li>{@link CompositeResult} → 复合（具名子结果列表，递归渲染）；</li>
 *   <li>{@link SectionSpec} → leakhunter 等 HTML 报告 query 返回的 section 树，按 composite 形态展开；</li>
 *   <li>{@link QuerySpec} → 带 drill-down command 的具名子结果，展开内嵌 result 并透出 command；</li>
 *   <li>其它 → toString 兜底（{@code kind:"raw"}）。</li>
 * </ul>
 *
 * <p>有了它，接入一个新 query 只需：QueryRunner.run(...) → ResultRenderer.render(...)。
 * 这是从"逐个手写端点"变成"批量接入"的关键基础设施。
 */
public final class ResultRenderer {

    /** 树展开的默认每层上限（懒加载，防一层几千节点）。 */
    private static final int DEFAULT_TREE_LIMIT = 100;
    /** CompositeResult 递归深度上限（防极端嵌套）。 */
    private static final int MAX_DEPTH = 6;

    private ResultRenderer() { }

    /** 渲染顶层结果（树只渲染根这一层）。 */
    public static String render(IResult result) {
        return render(result, 0);
    }

    private static String render(IResult result, int depth) {
        if (result == null) {
            return Json.obj().kv("kind", "empty").endObj();
        }
        if (depth > MAX_DEPTH) {
            return Json.obj().kv("kind", "truncated").kv("reason", "max depth").endObj();
        }

        // 顺序有讲究：IResultTable / IResultTree 是接口，先判具体的
        if (result instanceof IResultTable table) {
            return renderTable(table);
        }
        if (result instanceof IResultTree tree) {
            return renderTreeLevel(tree, /*parent*/ null, DEFAULT_TREE_LIMIT);
        }
        // IResultPie is the interface that MAT's PieFactory.PieImpl
        // implements, but its class lives in org.eclipse.mat.report while
        // PieImpl lives in org.eclipse.mat.api. OSGi split packages mean
        // a direct instanceof check can return false even when the
        // concrete class genuinely implements IResultPie. Detect by
        // class name instead — `PieImpl` is unique enough for this
        // purpose (MAT internals + user extensions are unlikely to name
        // a top-level class the same).
        if (isPieImpl(result)) {
            return renderPie(asPieImpl(result));
        }
        if (result instanceof TextResult text) {
            return Json.obj()
                    .kv("kind", "text")
                    .kv("html", text.getText() != null && looksHtml(text.getText()))
                    .kv("text", text.getText())
                    .endObj();
        }
        if (result instanceof CompositeResult composite) {
            Json sections = Json.arr();
            for (CompositeResult.Entry entry : composite.getResultEntries()) {
                sections.item(Json.obj()
                        .kv("name", entry.getName())
                        .raw("result", render(entry.getResult(), depth + 1))
                        .endObj());
            }
            return Json.obj()
                    .kv("kind", "composite")
                    .raw("sections", sections.endArr())
                    .endObj();
        }
        if (result instanceof SectionSpec section) {
            Json parts = Json.arr();
            List<Spec> children = section.getChildren();
            if (children != null) {
                for (Spec child : children) {
                    parts.item(renderSpec(child, depth + 1));
                }
            }
            Json out = Json.obj().kv("kind", "section");
            if (section.getName() != null) out.kv("name", section.getName());
            if (section.getStatus() != null) out.kv("status", section.getStatus().name());
            out.raw("parts", parts.endArr());
            return out.endObj();
        }
        if (result instanceof QuerySpec qs) {
            return renderQuerySpec(qs, depth);
        }
        if (result instanceof Spec spec) {
            return renderSpec(spec, depth);
        }
        // 兜底
        return Json.obj().kv("kind", "raw").kv("value", String.valueOf(result)).endObj();
    }

    private static String renderSpec(Spec spec, int depth) {
        if (spec instanceof QuerySpec qs) return renderQuerySpec(qs, depth);
        if (spec instanceof SectionSpec section) {
            // 递归进入 render(IResult) 分支会再判 SectionSpec；这里直接内联以避免走 render 的空/深度检查重复报错
            Json parts = Json.arr();
            List<Spec> children = section.getChildren();
            if (children != null) {
                for (Spec child : children) {
                    parts.item(renderSpec(child, depth + 1));
                }
            }
            Json out = Json.obj().kv("kind", "section");
            if (section.getName() != null) out.kv("name", section.getName());
            if (section.getStatus() != null) out.kv("status", section.getStatus().name());
            out.raw("parts", parts.endArr());
            return out.endObj();
        }
        // 裸 Spec 基类本身：只带 name/params，没有 result
        Json out = Json.obj().kv("kind", "spec");
        if (spec.getName() != null) out.kv("name", spec.getName());
        return out.endObj();
    }

    private static String renderQuerySpec(QuerySpec qs, int depth) {
        Json out = Json.obj().kv("kind", "query");
        if (qs.getName() != null) out.kv("name", qs.getName());
        String cmd = qs.getCommand();
        if (cmd != null && !cmd.isEmpty()) out.kv("command", cmd);
        IResult inner = qs.getResult();
        out.raw("result", render(inner, depth + 1));
        return out.endObj();
    }

    // ===================== 表格 =====================

    private static String renderTable(IResultTable table) {
        Column[] cols = table.getColumns();
        boolean[] retainedCol = new boolean[cols.length];
        Json colsJson = Json.arr();
        for (int ci = 0; ci < cols.length; ci++) {
            Column c = cols[ci];
            String label = c.getLabel();
            retainedCol[ci] = label != null && label.toLowerCase().contains("retained");
            colsJson.item(Json.obj()
                    .kv("label", label)
                    .kv("retained", retainedCol[ci])
                    .endObj());
        }

        int total = table.getRowCount();
        int limit = Math.min(total, 200);          // 表格瘦身：默认上限 200 行
        Json rows = Json.arr();
        for (int r = 0; r < limit; r++) {
            Object row = table.getRow(r);
            rows.item(renderStructuredRow(table, cols, retainedCol, row));
        }
        Json out = Json.obj()
                .kv("kind", "table")
                .raw("columns", colsJson.endArr())
                .raw("rows", rows.endArr())
                .kv("totalRows", total)
                .kv("returned", limit)
                .kv("truncated", limit < total);
        return out.endObj();
    }

    // ===================== 树（懒加载一层）=====================

    /**
     * 渲染树的某一层。parent==null → 根层（getElements）；否则需要调用方在外层按 path 重新执行 query
     * 取到对应 parent 节点再展开（MAT 的树节点是 query 内部对象，无稳定跨请求 id）。
     *
     * <p>实践：对 path2gc 这类树，根层通常就够用；更深展开时，前端用节点的 objectId 重新发起
     * 一个"以该对象为起点"的 query（如 path2gc(object=该id)），而非在同一棵树里翻——
     * 这同时规避了"树节点无稳定 id 不能跨请求展开"的问题。
     */
    private static String renderTreeLevel(IResultTree tree, Object parent, int limit) {
        Column[] cols = tree.getColumns();
        boolean[] retainedCol = new boolean[cols.length];
        List<?> elements = (parent == null) ? tree.getElements() : tree.getChildren(parent);

        Json colsJson = Json.arr();
        for (int ci = 0; ci < cols.length; ci++) {
            Column c = cols[ci];
            String label = c.getLabel();
            retainedCol[ci] = label != null && label.toLowerCase().contains("retained");
            colsJson.item(Json.obj()
                    .kv("label", label)
                    .kv("retained", retainedCol[ci])
                    .endObj());
        }

        int total = elements == null ? 0 : elements.size();
        int n = Math.min(total, limit);
        Json rows = Json.arr();
        for (int i = 0; i < n; i++) {
            Object el = elements.get(i);
            // 列值
            Json cells = Json.arr();
            boolean approx = false;
            for (int ci = 0; ci < cols.length; ci++) {
                Object v = safeColumnValue(tree, el, ci);
                CellRendered cr = renderCell(v, retainedCol[ci]);
                cells.item(cr.text);
                if (cr.approx) approx = true;
            }
            int objId = contextObjectId(tree.getContext(el));
            boolean expandable = tree.hasChildren(el);
            Json row = Json.obj()
                    .kv("objectId", objId)
                    .kv("expandable", expandable);
            if (approx) row.kv("retainedApprox", true);
            rows.item(row
                    .raw("cells", cells.endArr())
                    .endObj());
        }
        return Json.obj()
                .kv("kind", "tree")
                .raw("columns", colsJson.endArr())
                .raw("rows", rows.endArr())
                .kv("totalRows", total)
                .kv("returned", n)
                .kv("truncated", n < total)
                .endObj();
    }

    /**
     * Render an {@link IResultPie} (e.g. MAT LeakHunterQuery's overview pie chart)
     * as a JSON payload the web frontend can render with inline SVG.
     *
     * <p>Each {@link IResultPie.Slice} is unwrapped into
     * {@code {objectId, label, value, color, description}}. The frontend
     * uses {@code value} for slice magnitude and {@code color} (hex) for
     * fill; the SVG geometry (arc paths) is computed client-side from
     * the cumulative angle of each slice.
     *
     * <p>{@link IResultPie.ColoredSlice} gives us the pie's per-slice
     * colour (java.awt.Color); when a slice lacks a colour we fall back
     * to a neutral grey so the chart never renders transparent.
     * {@link IResultPie.Slice#getContext()} may throw on broken snapshots,
     * so we catch and default to {@code objectId:-1} (slice is then
     * non-navigable in the UI; matches the rest of the codebase's
     * defensive pattern).
     */
    private static String renderPie(IResultPie pie) {
        Json slices = Json.arr();
        try {
            int i = 0;
            for (IResultPie.Slice s : pie.getSlices()) {
                if (s == null) continue;
                String label = s.getLabel() != null ? s.getLabel() : "";
                String desc = s.getDescription() != null ? s.getDescription() : "";
                double value = s.getValue();
                int oid = -1;
                try {
                    if (s.getContext() != null) oid = s.getContext().getObjectId();
                } catch (Throwable ignore) { /* skip */ }
                // Use the deterministic palette. Trying to read the
                // slice's actual java.awt.Color via reflection was
                // unreliable across OSGi classloader splits — each
                // call had to walk the class hierarchy and invoke
                // setAccessible(true), which occasionally hung on
                // some dumps. The MAT palette is visually close
                // enough that we lose little by skipping the original.
                String hex = FALLBACK_COLORS[i % FALLBACK_COLORS.length];
                i++;
                slices.item(Json.obj()
                        .kv("objectId", oid)
                        .kv("label", label)
                        .kv("value", value)
                        .kv("color", hex)
                        .kv("description", desc)
                        .endObj());
            }
        } catch (Throwable t) {
            // Defensive — pie rendering shouldn't fail the whole report.
        }
        return Json.obj()
                .kv("kind", "pie")
                .raw("slices", slices.endArr())
                .endObj();
    }

    // ===================== helpers =====================

    /**
     * Class-name based check whether {@code o} is a PieFactory.PieImpl.
     * {@code PieImpl} lives in {@code org.eclipse.mat.snapshot.query}
     * while {@link IResultPie} lives in {@code org.eclipse.mat.query}.
     * OSGi split-package classloaders can disagree on the {@code instanceof}
     * relation, so we walk the class hierarchy by simple name instead.
     */
    private static boolean isPieImpl(Object o) {
        if (o == null) return false;
        Class<?> c = o.getClass();
        while (c != null) {
            if ("PieImpl".equals(c.getSimpleName())) return true;
            c = c.getSuperclass();
        }
        return false;
    }

    /**
     * Cast to {@link IResultPie}. The caller has verified the runtime
     * type via {@link #isPieImpl(Object)}, so the cast is safe at runtime;
     * Java's type system doesn't know the shape across bundle classloaders.
     */
    @SuppressWarnings("unchecked")
    private static <T> T asPieImpl(Object o) {
        return (T) o;
    }

    /** Deterministic color palette — used when the slice's actual
     * {@code Color} can't be read (OSGi split-package blocks
     * {@code getColor()}, transient AWT issues, etc.). Palette colors
     * match MAT desktop's qualitative palette family so users get
     * visually similar charts. Stable across renders since slice
     * ordering matches the backend's iteration. */
    private static final String[] FALLBACK_COLORS = {
            "#5b9bd5", "#ed7d31", "#a5a5a5", "#ffc000", "#4472c4",
            "#70ad47", "#264478", "#9e480e", "#636363", "#997300",
    };

    private static String renderStructuredRow(IResultTable table, Column[] cols, boolean[] retainedCol, Object row) {
        Json cells = Json.arr();
        boolean approx = false;
        for (int ci = 0; ci < cols.length; ci++) {
            Object v = safeColumnValue(table, row, ci);
            CellRendered cr = renderCell(v, retainedCol[ci]);
            cells.item(cr.text);
            if (cr.approx) approx = true;
        }
        int objId = contextObjectId(table.getContext(row));
        Json out = Json.obj().kv("objectId", objId);
        if (approx) out.kv("retainedApprox", true);
        return out.raw("cells", cells.endArr()).endObj();
    }

    /**
     * Render a single cell value to a JSON-quoted string. For retained columns,
     * MAT uses negative {@code long} values to flag "approximate lower bound"
     * (see {@code ISnapshot.getMinRetainedSize()}); we abs() the value and set
     * {@link CellRendered#approx} so the caller can add a {@code retainedApprox}
     * marker for the frontend/agent.
     */
    private static CellRendered renderCell(Object v, boolean isRetainedCol) {
        if (v == null) return new CellRendered("null", false);
        String s;
        boolean approx = false;
        if (isRetainedCol && v instanceof Number n) {
            long lv = n.longValue();
            if (lv < 0) {
                s = String.valueOf(-lv);
                approx = true;
            } else {
                s = String.valueOf(lv);
            }
        } else {
            s = String.valueOf(v);
        }
        return new CellRendered("\"" + esc(s) + "\"", approx);
    }

    private record CellRendered(String text, boolean approx) { }

    private static Object safeColumnValue(Object structuredResult, Object row, int col) {
        try {
            if (structuredResult instanceof IResultTable t) return t.getColumnValue(row, col);
            if (structuredResult instanceof IResultTree t)  return t.getColumnValue(row, col);
        } catch (Throwable ignore) { }
        return "";
    }

    private static int contextObjectId(IContextObject ctx) {
        try {
            return ctx != null ? ctx.getObjectId() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean looksHtml(String s) {
        String t = s.trim();
        return t.startsWith("<") || t.contains("</") || t.contains("<br") || t.contains("<p>");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
