package com.jvmind.mat.query.view;

import com.jvmind.mat.query.model.Json;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IStackFrame;
import org.eclipse.mat.snapshot.model.IThreadStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 线程树视图 —— 三级树（文档 06 §3.3）：线程 → 栈帧 → 栈帧局部变量。
 *
 * <p>映射（读 MAT 源码确认）：
 * <ul>
 *   <li>线程列表 = {@code getClassesByName("java.lang.Thread", true)} 的所有实例；</li>
 *   <li>线程属性 = resolveValue("daemon"/"priority"/"contextClassLoader") + getDisplayName（线程名）；</li>
 *   <li>栈帧 = {@code getThreadStack(threadId).getStackFrames()}，每帧 getText()；</li>
 *   <li>局部变量 = {@code IStackFrame.getLocalObjectsIds()}（对象 id 数组）。</li>
 * </ul>
 * <p>三级各一个端点，懒加载。局部变量持有的连接/大对象 = 连接泄漏归因金矿（呼应文档 03）。
 */
public final class ThreadsView {

    private ThreadsView() { }

    /** 一级：线程列表，按 retained 降序。 */
    public static String threads(ISnapshot snapshot, int top, int offset) throws Exception {
        List<IObject> threads = new ArrayList<>();
        for (IClass c : snapshot.getClassesByName("java.lang.Thread", true)) {
            for (int id : c.getObjectIds()) {
                threads.add(snapshot.getObject(id));
            }
        }
        threads.sort((a, b) -> Long.compare(b.getRetainedHeapSize(), a.getRetainedHeapSize()));

        int total = threads.size();
        int from = Math.min(offset, total);
        int to = Math.min(offset + top, total);

        Json rows = Json.arr();
        for (int i = from; i < to; i++) {
            IObject t = threads.get(i);
            boolean hasStack;
            try {
                IThreadStack st = snapshot.getThreadStack(t.getObjectId());
                hasStack = st != null && st.getStackFrames() != null && st.getStackFrames().length > 0;
            } catch (Exception e) {
                hasStack = false;
            }
            rows.item(Json.obj()
                    .kv("objectId", t.getObjectId())
                    .kv("name", threadName(t))
                    .kv("daemon", resolveBool(t, "daemon"))
                    .kv("priority", resolveInt(t, "priority"))
                    .kv("contextClassLoader", resolveLabel(snapshot, t, "contextClassLoader"))
                    .kv("retainedBytes", t.getRetainedHeapSize())
                    .kv("retained", Json.humanBytes(t.getRetainedHeapSize()))
                    .kv("hasStack", hasStack)
                    .endObj());
        }

        Json out = Json.obj().raw("rows", rows.endArr()).kv("totalRows", total).kv("returned", to - from);
        if (to < total) out.kv("cursor", "offset:" + to);
        return out.endObj();
    }

    /** 二级：某线程的栈帧。 */
    public static String frames(ISnapshot snapshot, int threadId) throws Exception {
        IThreadStack stack = snapshot.getThreadStack(threadId);
        Json rows = Json.arr();
        if (stack != null && stack.getStackFrames() != null) {
            IStackFrame[] frames = stack.getStackFrames();
            for (int i = 0; i < frames.length; i++) {
                int[] locals = frames[i].getLocalObjectsIds();
                long maxLocalRetained = 0;
                if (locals != null) {
                    for (int lid : locals) {
                        try { maxLocalRetained = Math.max(maxLocalRetained, snapshot.getRetainedHeapSize(lid)); }
                        catch (Exception ignore) { }
                    }
                }
                rows.item(Json.obj()
                        .kv("frameIdx", i)
                        .kv("text", frames[i].getText())
                        .kv("localsCount", locals == null ? 0 : locals.length)
                        .kv("maxLocalRetainedBytes", maxLocalRetained)
                        .kv("maxLocalRetained", Json.humanBytes(maxLocalRetained))
                        .endObj());
            }
        }
        return Json.obj().kv("threadId", threadId).raw("frames", rows.endArr()).endObj();
    }

    /** 三级：某栈帧的局部变量对象。 */
    public static String locals(ISnapshot snapshot, int threadId, int frameIdx) throws Exception {
        IThreadStack stack = snapshot.getThreadStack(threadId);
        Json rows = Json.arr();
        if (stack != null && stack.getStackFrames() != null) {
            IStackFrame[] frames = stack.getStackFrames();
            if (frameIdx >= 0 && frameIdx < frames.length) {
                int[] locals = frames[frameIdx].getLocalObjectsIds();
                if (locals != null) {
                    for (int lid : locals) {
                        rows.item(ObjectRows.row(snapshot, lid));
                    }
                }
            }
        }
        return Json.obj().kv("threadId", threadId).kv("frameIdx", frameIdx)
                .raw("locals", rows.endArr()).endObj();
    }

    // ===== helpers（线程属性提取，字段名兼容不同 JDK，呼应文档 03 的版本鲁棒性）=====

    private static String threadName(IObject t) {
        return ObjectRows.safeDisplayName(t);   // displayName 通常已是线程名
    }

    private static boolean resolveBool(IObject o, String field) {
        try {
            Object v = o.resolveValue(field);
            if (v == null) v = o.resolveValue("holder." + field);   // 新 JDK: Thread.holder.daemon
            return v instanceof Boolean b && b;
        } catch (Exception e) { return false; }
    }

    private static int resolveInt(IObject o, String field) {
        try {
            Object v = o.resolveValue(field);
            if (v == null) v = o.resolveValue("holder." + field);
            return v instanceof Number n ? n.intValue() : -1;
        } catch (Exception e) { return -1; }
    }

    private static String resolveLabel(ISnapshot s, IObject o, String field) {
        try {
            Object v = o.resolveValue(field);
            if (v instanceof IObject io) return ObjectRows.safeDisplayName(io);
        } catch (Exception ignore) { }
        return null;
    }
}
