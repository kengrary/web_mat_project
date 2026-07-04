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
            int frameCount = 0;
            try {
                IThreadStack st = snapshot.getThreadStack(t.getObjectId());
                if (st != null && st.getStackFrames() != null) frameCount = st.getStackFrames().length;
            } catch (Exception e) { /* ignore */ }
            boolean hasStack = frameCount > 0;
            rows.item(Json.obj()
                    .kv("objectId", t.getObjectId())
                    .kv("name", threadName(t))
                    .kv("daemon", resolveBool(t, "daemon"))
                    .kv("priority", resolveInt(t, "priority"))
                    .kv("contextClassLoader", resolveLabel(snapshot, t, "contextClassLoader"))
                    .kv("retainedBytes", t.getRetainedHeapSize())
                    .kv("retained", Json.humanBytes(t.getRetainedHeapSize()))
                    .kv("hasStack", hasStack)
                    .kv("frameCount", frameCount)
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
                String text = frames[i].getText();
                FrameParts parts = parseFrame(text);
                Json row = Json.obj()
                        .kv("frameIdx", i)
                        .kv("text", text);
                if (parts != null) {
                    if (parts.className != null) row.kv("className", parts.className);
                    if (parts.methodName != null) row.kv("methodName", parts.methodName);
                    if (parts.fileName != null) row.kv("fileName", parts.fileName);
                    if (parts.lineNumber >= 0) row.kv("lineNumber", parts.lineNumber);
                    if (parts.nativeMethod) row.kv("nativeMethod", true);
                }
                rows.item(row
                        .kv("localsCount", locals == null ? 0 : locals.length)
                        .kv("maxLocalRetainedBytes", maxLocalRetained)
                        .kv("maxLocalRetained", Json.humanBytes(maxLocalRetained))
                        .endObj());
            }
        }
        return Json.obj().kv("threadId", threadId).raw("frames", rows.endArr()).endObj();
    }

    /**
     * Parse MAT's standard frame text into structured parts.
     * MAT formats frames as:
     *   "at pkg.Class.method(sig) (SourceFile.java:123)"
     *   "at pkg.Class.method(sig) (SourceFile.java(Native Method))"
     *   "at pkg.Class.method(sig) (Unknown Source)"
     *   Virtual-thread / lambda frames: "at pkg.Class$$Lambda+0x1234.run() ()"
     *   Continuation / JIT frames may have no source.
     * Returns null if the text doesn't match the expected pattern (caller falls back to text).
     */
    private static FrameParts parseFrame(String text) {
        if (text == null) return null;
        String t = text.trim();
        // Strip leading "at "
        if (t.startsWith("at ")) t = t.substring(3).trim();
        int paren = t.lastIndexOf('(');
        if (paren < 0) return null;
        String before = t.substring(0, paren);
        String inside = t.substring(paren + 1, t.endsWith(")") ? t.length() - 1 : t.length());
        // before = "pkg.Class.method(signature)"
        int sigStart = before.indexOf('(');
        String methodPart = sigStart >= 0 ? before.substring(0, sigStart) : before;
        int dot = methodPart.lastIndexOf('.');
        String cls = dot >= 0 ? methodPart.substring(0, dot) : "";
        String meth = dot >= 0 ? methodPart.substring(dot + 1) : methodPart;
        // inside = "File.java:123" or "File.java(Native Method)" or "Unknown Source" or ""
        String file = null;
        int line = -1;
        boolean nativeM = false;
        if (inside.contains("Native Method")) {
            nativeM = true;
            // filename may precede "(Native Method)" in some MAT versions; typically just "Native Method"
        } else if (!inside.isBlank() && !inside.contains("Unknown Source")) {
            int colon = inside.lastIndexOf(':');
            if (colon >= 0) {
                file = inside.substring(0, colon).trim();
                String lnStr = inside.substring(colon + 1).replace(")", "").trim();
                try { line = Integer.parseInt(lnStr); } catch (NumberFormatException ignore) { }
            } else {
                file = inside.replace(")", "").trim();
                if (file.isEmpty()) file = null;
            }
        }
        return new FrameParts(
                cls.isEmpty() ? null : cls,
                meth.isEmpty() ? null : meth,
                file,
                line,
                nativeM);
    }

    private record FrameParts(String className, String methodName, String fileName, int lineNumber, boolean nativeMethod) { }

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
