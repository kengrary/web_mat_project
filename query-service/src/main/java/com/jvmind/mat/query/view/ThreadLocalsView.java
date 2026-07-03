package com.jvmind.mat.query.view;

import com.jvmind.mat.query.model.Json;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.NamedReference;
import org.eclipse.mat.snapshot.model.ThreadToLocalReference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ThreadLocals 全局视图（文档 11）—— 专注 ThreadLocal 泄漏排查（线程池复用线程残留 ThreadLocal 是高频泄漏源）。
 *
 * <p>原理（读 MAT 源码确认）：MAT 解析时把线程持有的 ThreadLocal 值识别成线程对象的一种特殊出引用
 * {@link ThreadToLocalReference}。所以<b>不必手工解析 Thread.threadLocals→ThreadLocalMap→Entry[]</b>，
 * 只需遍历每个线程的 {@code getOutboundReferences()}，筛 {@code instanceof ThreadToLocalReference} 即得。
 * 每个 TL 引用：name（ThreadLocal key 描述）+ 目标值对象 id。
 *
 * <p>两种聚合（前端可切）：
 * <ul>
 *   <li><b>byThread</b>：每个线程下列出它的 ThreadLocal 值（看某线程残留了什么）；</li>
 *   <li><b>byValueClass</b>：按 ThreadLocal 值的类聚合（看哪类对象在大量 ThreadLocal 里残留 = 泄漏嫌疑）。</li>
 * </ul>
 */
public final class ThreadLocalsView {

    private ThreadLocalsView() { }

    /** 一条 ThreadLocal 记录 */
    private record TL(int threadId, String threadName, int valueId) { }

    /** 收集所有线程的 ThreadLocal 引用。 */
    private static List<TL> collect(ISnapshot snapshot) throws Exception {
        List<TL> out = new ArrayList<>();
        for (IClass c : snapshot.getClassesByName("java.lang.Thread", true)) {
            for (int tid : c.getObjectIds()) {
                IObject thread = snapshot.getObject(tid);
                String tname = ObjectRows.safeDisplayName(thread);
                for (NamedReference ref : thread.getOutboundReferences()) {
                    if (ref instanceof ThreadToLocalReference) {
                        try {
                            int vid = ref.getObjectId();
                            if (vid >= 0) out.add(new TL(tid, tname, vid));
                        } catch (Exception ignore) { }
                    }
                }
            }
        }
        return out;
    }

    /** 按线程聚合：每个线程 → 它的 ThreadLocal 值列表。 */
    public static String byThread(ISnapshot snapshot, int top) throws Exception {
        List<TL> all = collect(snapshot);
        // 线程 → 该线程的 TL 值
        Map<Integer, List<TL>> byT = new LinkedHashMap<>();
        for (TL t : all) byT.computeIfAbsent(t.threadId(), k -> new ArrayList<>()).add(t);

        Json threads = Json.arr();
        for (var e : byT.entrySet()) {
            List<TL> list = e.getValue();
            long totalRetained = 0;
            Json vals = Json.arr();
            for (TL t : list) {
                IObject v = snapshot.getObject(t.valueId());
                long ret = v.getRetainedHeapSize();
                totalRetained += ret;
                vals.item(Json.obj()
                        .kv("objectId", t.valueId())
                        .kv("label", ObjectRows.safeDisplayName(v))
                        .kv("type", v.getClazz() != null ? v.getClazz().getName() : "?")
                        .kv("retainedBytes", ret)
                        .kv("retained", Json.humanBytes(ret))
                        .endObj());
            }
            threads.item(Json.obj()
                    .kv("threadId", e.getKey())
                    .kv("threadName", list.get(0).threadName())
                    .kv("count", list.size())
                    .kv("totalRetainedBytes", totalRetained)
                    .kv("totalRetained", Json.humanBytes(totalRetained))
                    .raw("values", vals.endArr())
                    .endObj());
        }
        return Json.obj()
                .kv("groupBy", "thread")
                .kv("totalThreadLocals", all.size())
                .raw("threads", threads.endArr())
                .endObj();
    }

    /** 按值类聚合：哪类对象在大量 ThreadLocal 里残留 = 泄漏嫌疑（按 retained 降序）。 */
    public static String byValueClass(ISnapshot snapshot, int top) throws Exception {
        List<TL> all = collect(snapshot);
        // 值类名 → 累计
        Map<String, long[]> agg = new LinkedHashMap<>();   // [count, retainedSum]
        Map<String, Integer> sampleId = new LinkedHashMap<>();
        for (TL t : all) {
            IObject v = snapshot.getObject(t.valueId());
            String cls = v.getClazz() != null ? v.getClazz().getName() : "?";
            long[] a = agg.computeIfAbsent(cls, k -> new long[2]);
            a[0]++;
            a[1] += v.getRetainedHeapSize();
            sampleId.putIfAbsent(cls, t.valueId());
        }
        List<Map.Entry<String, long[]>> rows = new ArrayList<>(agg.entrySet());
        rows.sort((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1]));   // retained 降序

        Json arr = Json.arr();
        int n = Math.min(rows.size(), top);
        for (int i = 0; i < n; i++) {
            var e = rows.get(i);
            arr.item(Json.obj()
                    .kv("valueClass", e.getKey())
                    .kv("count", e.getValue()[0])            // 有多少个 ThreadLocal 指向该类对象
                    .kv("retainedBytes", e.getValue()[1])
                    .kv("retained", Json.humanBytes(e.getValue()[1]))
                    .kv("sampleObjectId", sampleId.get(e.getKey()))   // 一个样本，可点进去
                    .endObj());
        }
        return Json.obj()
                .kv("groupBy", "valueClass")
                .kv("totalThreadLocals", all.size())
                .kv("totalClasses", rows.size())
                .raw("rows", arr.endArr())
                .kv("returned", n)
                .endObj();
    }
}
