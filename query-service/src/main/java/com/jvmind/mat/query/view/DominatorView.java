package com.jvmind.mat.query.view;

import com.jvmind.mat.query.model.Json;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.NamedReference;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 支配树视图 —— 对象级，逐层懒加载（文档 06 §3.2 模式 A）。
 *
 * <p>核心（读 MAT 源码确认）：
 * <ul>
 *   <li>根 = {@code getImmediateDominatedIds(-1)}（虚拟根 -1 的直接被支配者 = 顶层支配者）；</li>
 *   <li>展开某节点 = {@code getImmediateDominatedIds(objectId)}（它直接支配的对象）；</li>
 *   <li>retained = {@code getRetainedHeapSize}，O(1) 查 o2ret 索引（支配树解析时已算）；</li>
 *   <li>percent = 该节点 retained 占总堆的百分比（MAT UI 的关键列）。</li>
 * </ul>
 * <p>一次只返回一层 Top-N（懒加载），对齐 MAT UI 效率——绝不递归整棵树。
 */
public final class DominatorView {

    private DominatorView() { }

    /**
     * @param parent ROOT 表示虚拟根（-1）；否则为某 objectId
     * @param top    本层返回前 N（按 retained 降序）
     * @param offset 分页偏移
     */
    public static String children(ISnapshot snapshot, String parent, int top, int offset) throws Exception {
        int parentId;
        if (parent == null || parent.isBlank() || "ROOT".equalsIgnoreCase(parent)) {
            parentId = -1;
        } else {
            try {
                parentId = Integer.parseInt(parent);
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("invalid 'parent' parameter (expected ROOT or decimal object id): " + parent, nfe);
            }
        }

        int[] childIds = snapshot.getImmediateDominatedIds(parentId);

        Map<Integer, String> fieldNames = buildFieldNameMap(snapshot, parentId);

        // 按 retained 降序（retained 是 O(1) 查表，排序整层是廉价的）
        Integer[] boxed = box(childIds);
        Arrays.sort(boxed, (a, b) -> Long.compare(retained(snapshot, b), retained(snapshot, a)));

        long totalHeap = snapshot.getSnapshotInfo().getUsedHeapSize();
        int total = boxed.length;
        int from = Math.min(offset, total);
        int to = Math.min(offset + top, total);

        Json rows = Json.arr();
        for (int i = from; i < to; i++) {
            int id = boxed[i];
            IObject o = snapshot.getObject(id);
            long ret = o.getRetainedHeapSize();
            long shallow = o.getUsedHeapSize();
            double pct = totalHeap > 0 ? (100.0 * ret / totalHeap) : 0.0;
            boolean expandable = snapshot.getImmediateDominatedIds(id).length > 0;

            rows.item(Json.obj()
                    .kv("objectId", id)
                    .kv("id", id)
                    .kv("fieldName", fieldNames.getOrDefault(id, ""))
                    .kv("label", ObjectRows.safeDisplayName(o))
                    .kv("className", o.getClazz() != null ? o.getClazz().getName() : "?")
                    .kv("type", o.getClazz() != null ? o.getClazz().getName() : "?")
                    .kv("shallowBytes", shallow)
                    .kv("shallow", Json.humanBytes(shallow))
                    .kv("retainedBytes", ret)
                    .kv("retained", Json.humanBytes(ret))
                    .kv("percent", round1(pct))
                    .kv("retainedPercent", round1(pct))
                    .kv("expandable", expandable)
                    .endObj());
        }

        Json out = Json.obj()
                .kv("parent", parent)
                .raw("rows", rows.endArr())
                .kv("totalRows", total)
                .kv("returned", to - from);
        if (to < total) {
            out.kv("cursor", "offset:" + to);
        }
        return out.endObj();
    }

    private static Map<Integer, String> buildFieldNameMap(ISnapshot s, int parentId) {
        Map<Integer, String> map = new HashMap<>();
        if (parentId == -1) return map;
        try {
            IObject parent = s.getObject(parentId);
            List<NamedReference> refs = parent.getOutboundReferences();
            if (refs != null) {
                for (NamedReference r : refs) {
                    try {
                        int cid = r.getObjectId();
                        if (cid >= 0) map.putIfAbsent(cid, r.getName());
                    } catch (Exception ignore) { }
                }
            }
        } catch (Exception ignore) { }
        return map;
    }

    private static long retained(ISnapshot s, int id) {
        try {
            return s.getRetainedHeapSize(id);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static Integer[] box(int[] a) {
        Integer[] b = new Integer[a.length];
        for (int i = 0; i < a.length; i++) b[i] = a[i];
        return b;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
