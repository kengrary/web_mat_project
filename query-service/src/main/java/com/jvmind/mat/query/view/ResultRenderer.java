package com.jvmind.mat.query.view;

import com.jvmind.mat.query.model.Json;
import org.eclipse.mat.query.Column;
import org.eclipse.mat.query.IContextObject;
import org.eclipse.mat.query.IResult;
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
        Json colsJson = Json.arr();
        for (Column c : cols) colsJson.item(Json.obj().kv("label", c.getLabel()).endObj());

        int total = table.getRowCount();
        int limit = Math.min(total, 200);          // 表格瘦身：默认上限 200 行
        Json rows = Json.arr();
        for (int r = 0; r < limit; r++) {
            Object row = table.getRow(r);
            rows.item(renderStructuredRow(table, cols.length, row));
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
        List<?> elements = (parent == null) ? tree.getElements() : tree.getChildren(parent);

        Json colsJson = Json.arr();
        for (Column c : cols) colsJson.item(Json.obj().kv("label", c.getLabel()).endObj());

        int total = elements == null ? 0 : elements.size();
        int n = Math.min(total, limit);
        Json rows = Json.arr();
        for (int i = 0; i < n; i++) {
            Object el = elements.get(i);
            Json row = Json.obj();
            // 列值
            Json cells = Json.arr();
            for (int ci = 0; ci < cols.length; ci++) {
                Object v = safeColumnValue(tree, el, ci);
                cells.item("\"" + esc(String.valueOf(v)) + "\"");
            }
            int objId = contextObjectId(tree.getContext(el));
            boolean expandable = tree.hasChildren(el);
            rows.item(row
                    .kv("objectId", objId)
                    .kv("expandable", expandable)   // 前端据此决定是否可继续展开
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

    // ===================== helpers =====================

    private static String renderStructuredRow(IResultTable table, int colCount, Object row) {
        Json cells = Json.arr();
        for (int ci = 0; ci < colCount; ci++) {
            Object v = safeColumnValue(table, row, ci);
            cells.item("\"" + esc(String.valueOf(v)) + "\"");
        }
        int objId = contextObjectId(table.getContext(row));
        return Json.obj().kv("objectId", objId).raw("cells", cells.endArr()).endObj();
    }

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
