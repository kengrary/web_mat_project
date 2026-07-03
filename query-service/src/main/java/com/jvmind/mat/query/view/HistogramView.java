package com.jvmind.mat.query.view;

import com.jvmind.mat.query.model.Json;
import org.eclipse.mat.snapshot.ClassHistogramRecord;
import org.eclipse.mat.snapshot.Histogram;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.util.IProgressListener;
import org.eclipse.mat.util.VoidProgressListener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Histogram 视图（文档 06 §3.1）。
 *
 * <p>V1 先实现 BY_CLASS（最常用）。子功能（superclass/classloader/package 分组、对子集做 histogram、
 * package 下钻）后续补 —— groupBy 已留参数位。
 *
 * <p>出参瘦身：默认按 retained 降序取 Top-N，附 totalRows 与 cursor，绝不一次返回几千行。
 */
public final class HistogramView {

    private static final IProgressListener SILENT = new VoidProgressListener();

    public enum Sort { RETAINED, SHALLOW, COUNT }

    /**
     * @param top    返回前 N 行
     * @param offset 游标偏移（分页）
     * @param sort   排序维度
     * @param approxRetained 是否用<b>近似 retained</b> 补齐未计算的 retained。
     *
     * <p><b>关于 retained 的三种情况（读 MAT 源码确认）</b>：
     * <ul>
     *   <li>histogram 自带的 retained 可能为 0（未计算）。</li>
     *   <li><b>近似（approximation，便宜）</b>：{@code record.calculateRetainedSize(s, true, true, ...)}
     *       内部走 {@link ISnapshot#getMinRetainedSize}：在<b>已算好的支配树</b>上对该类实例求
     *       "顶层祖先的 retained 之和"——每个祖先 retained 是 O(1) 查 o2ret 索引。
     *       成本≈一次祖先查找+若干 O(1) 读，比精确快几个数量级。
     *       这是<b>下界近似</b>（min）：同类实例共享的被支配子树可能少算，故偏小但很接近，
     *       MAT 内部用<b>负数</b>标记"这是近似值"。</li>
     *   <li><b>精确（贵）</b>：{@code getHeapSize(getRetainedSet(...))} 全图标记-清除，不在 histogram 默认做。</li>
     * </ul>
     * 默认开启近似：既能给出有意义的 retained 排序（贴近 MAT UI），又不至于像精确算法那样卡住请求。
     */
    public static String byClass(ISnapshot snapshot, int top, int offset, Sort sort,
                                 boolean approxRetained) throws Exception {
        Histogram histogram = snapshot.getHistogram(SILENT);
        return renderRecords(snapshot, histogram, top, offset, sort, approxRetained);
    }

    /** 对一个对象子集（如 OQL 结果）做直方图 —— 复用同一渲染。 */
    public static String bySubset(ISnapshot snapshot, int[] objectIds, int top, int offset,
                                  Sort sort, boolean approxRetained) throws Exception {
        Histogram histogram = snapshot.getHistogram(objectIds, SILENT);
        return renderRecords(snapshot, histogram, top, offset, sort, approxRetained);
    }

    /** 把 Histogram 的类记录排序+分页+瘦身成 JSON（byClass / bySubset 共用）。 */
    private static String renderRecords(ISnapshot snapshot, Histogram histogram, int top, int offset,
                                        Sort sort, boolean approxRetained) throws Exception {
        List<ClassHistogramRecord> records = new ArrayList<>(histogram.getClassHistogramRecords());

        // 按 retained 排序时，先用近似补齐 retained（便宜）。其余维度无需触碰 retained。
        if (sort == Sort.RETAINED && approxRetained) {
            for (ClassHistogramRecord r : records) {
                r.calculateRetainedSize(snapshot, true, true, SILENT);
            }
        }

        Comparator<ClassHistogramRecord> cmp = switch (sort) {
            case COUNT    -> Comparator.comparingLong(ClassHistogramRecord::getNumberOfObjects).reversed();
            case RETAINED -> Comparator.comparingLong(HistogramView::absRetained).reversed();
            case SHALLOW  -> Comparator.comparingLong(ClassHistogramRecord::getUsedHeapSize).reversed();
        };
        records.sort(cmp);

        int total = records.size();
        int from = Math.min(offset, total);
        int to = Math.min(offset + top, total);

        Json rows = Json.arr();
        long shallowSum = 0;
        for (int i = from; i < to; i++) {
            ClassHistogramRecord r = records.get(i);
            shallowSum += r.getUsedHeapSize();

            long raw = r.getRetainedHeapSize();
            long retainedBytes = Math.abs(raw);
            boolean approx = raw < 0;          // MAT 约定：负数 = 近似值
            boolean hasRetained = raw != 0;

            Json row = Json.obj()
                    .kv("classId", r.getClassId())
                    .kv("label", r.getLabel())
                    .kv("count", r.getNumberOfObjects())
                    .kv("shallowBytes", r.getUsedHeapSize())
                    .kv("shallow", Json.humanBytes(r.getUsedHeapSize()))
                    .kv("retainedBytes", hasRetained ? retainedBytes : 0)
                    .kv("retained", hasRetained ? Json.humanBytes(retainedBytes) : "n/a")
                    // 诚实标注：approximate=true 时前端可显示 "≈"，提示这是下界近似而非精确值
                    .kv("retainedApprox", approx);
            rows.item(row.endObj());
        }

        Json out = Json.obj()
                .kv("groupBy", "class")
                .kv("sort", sort.name().toLowerCase())
                .kv("retainedMode", (sort == Sort.RETAINED && approxRetained) ? "approximate" : "raw")
                .raw("rows", rows.endArr())
                .kv("totalRows", total)
                .kv("returned", to - from)
                .kv("pageShallow", Json.humanBytes(shallowSum));
        if (to < total) {
            out.kv("cursor", "offset:" + to);
        }
        return out.endObj();
    }

    /** retained 排序用：取绝对值（近似值存为负，绝对值才是大小）。 */
    private static long absRetained(ClassHistogramRecord r) {
        return Math.abs(r.getRetainedHeapSize());
    }
}
