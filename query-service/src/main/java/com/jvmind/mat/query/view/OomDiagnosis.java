package com.jvmind.mat.query.view;

import com.jvmind.mat.query.model.Json;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IStackFrame;
import org.eclipse.mat.snapshot.model.IThreadStack;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * OOM 诊断（文档 13 想法1 + 与想法2/retained 联动）：识别触发 OOM 的线程，判定"元凶 vs 受害者"，
 * 给出完整因果链（触发线程 + 它的 retained 占比 + 它正在执行的 SQL）。
 *
 * <p>关键事实：hprof 没有"哪个线程触发 OOME"的字段，用<b>多信号启发式打分</b>：
 * <ul>
 *   <li>栈帧局部变量里有 java.lang.OutOfMemoryError 实例 → 强触发信号；</li>
 *   <li>栈顶在分配相关帧（Arrays.copyOf / *.grow / HashMap.resize / StringBuilder / newArray 等）→ 触发信号；</li>
 *   <li>线程 retained 占总堆百分比高 → 元凶信号。</li>
 * </ul>
 *
 * <p>判定：
 * <ul>
 *   <li><b>触发线程</b>：有 OOME 对象 或 栈顶分配帧。</li>
 *   <li><b>元凶(culprit)</b>：触发线程 且 retained 占比 ≥ 阈值（默认 30%）。</li>
 *   <li><b>受害者(victim)</b>：触发线程 但 retained 占比很低（堆是被别人吃满的）→ 同时给出真正大头。</li>
 * </ul>
 *
 * <p>全复用已有：ThreadsView 的栈帧、SqlExtractor 风格、getRetainedHeapSize、getUsedHeapSize。
 */
public final class OomDiagnosis {

    private OomDiagnosis() { }

    private static final int DEFAULT_CULPRIT_PCT = 30;   // 元凶阈值：retained 占总堆 %

    /** 分配相关栈帧（栈顶在这些帧 = 正在分配 → 触发点）。 */
    private static final Pattern ALLOC_FRAME = Pattern.compile(
            "Arrays\\.copyOf|\\.grow\\(|HashMap\\.resize|StringBuilder\\.|StringBuffer\\.|" +
            "ArrayList\\.|Arrays\\.copyOfRange|newArray|java\\.lang\\.reflect\\.Array\\.newInstance|" +
            "ByteArrayOutputStream|toByteArray|read.*Bytes", Pattern.CASE_INSENSITIVE);

    public static String diagnose(ISnapshot snapshot, int culpritPct) throws Exception {
        if (culpritPct <= 0) culpritPct = DEFAULT_CULPRIT_PCT;
        long totalHeap = Math.max(snapshot.getSnapshotInfo().getUsedHeapSize(), 1);

        List<Cand> triggers = new ArrayList<>();
        long maxThreadRetained = 0; int maxThreadId = -1; String maxThreadName = null;

        for (IClass tc : snapshot.getClassesByName("java.lang.Thread", true)) {
            for (int tid : tc.getObjectIds()) {
                IObject thread = safe(snapshot, tid);
                if (thread == null) continue;
                long ret = retained(snapshot, tid);
                String tname = ObjectRows.safeDisplayName(thread);
                if (ret > maxThreadRetained) { maxThreadRetained = ret; maxThreadId = tid; maxThreadName = tname; }

                IThreadStack stack;
                try { stack = snapshot.getThreadStack(tid); } catch (Exception e) { continue; }
                if (stack == null || stack.getStackFrames() == null) continue;
                IStackFrame[] frames = stack.getStackFrames();

                boolean hasOome = false; String oomeAt = null;
                boolean topAlloc = false; String topFrame = frames.length > 0 ? frames[0].getText() : null;

                // 栈顶是否分配帧（取前几帧，跳过 OOME 构造帧）
                for (int i = 0; i < Math.min(frames.length, 4); i++) {
                    String t = frames[i].getText();
                    if (t != null && ALLOC_FRAME.matcher(t).find()) { topAlloc = true; if (topFrame == null) topFrame = t; break; }
                }
                // 栈帧局部变量里有 OutOfMemoryError 实例？
                for (IStackFrame f : frames) {
                    int[] locals = f.getLocalObjectsIds();
                    if (locals == null) continue;
                    for (int lid : locals) {
                        IObject lo = safe(snapshot, lid);
                        if (lo != null && lo.getClazz() != null
                                && "java.lang.OutOfMemoryError".equals(lo.getClazz().getName())) {
                            hasOome = true; oomeAt = f.getText();
                        }
                    }
                    if (hasOome) break;
                }

                if (hasOome || topAlloc) {
                    double pct = 100.0 * ret / totalHeap;
                    triggers.add(new Cand(tid, tname, ret, pct, hasOome, topAlloc,
                            oomeAt != null ? oomeAt : topFrame));
                }
            }
        }

        // 触发线程按 retained 降序（最可能元凶在前）
        triggers.sort((a, b) -> Long.compare(b.retained, a.retained));

        Json arr = Json.arr();
        for (Cand c : triggers) {
            boolean culprit = c.pct >= culpritPct;
            String verdict = culprit ? "culprit" : "victim";
            List<String> reasons = new ArrayList<>();
            if (c.hasOome) reasons.add("栈帧持有 OutOfMemoryError 实例");
            if (c.topAlloc) reasons.add("栈顶在内存分配帧");
            reasons.add(String.format("retained 占总堆 %.1f%%", c.pct));
            arr.item(Json.obj()
                    .kv("threadId", c.tid)
                    .kv("threadName", c.name)
                    .kv("retainedBytes", c.retained)
                    .kv("retained", Json.humanBytes(c.retained))
                    .kv("percentHeap", Math.round(c.pct * 10) / 10.0)
                    .kv("verdict", verdict)
                    .kv("triggerFrame", c.frame)
                    .kv("reason", String.join("；", reasons))
                    .endObj());
        }
        // 总判定：retained 最大的触发线程定调
        String overallVerdict = triggers.isEmpty() ? "unclear"
                : (triggers.get(0).pct >= culpritPct ? "culprit" : "victim");

        Json out = Json.obj()
                .kv("totalHeapBytes", totalHeap)
                .kv("totalHeap", Json.humanBytes(totalHeap))
                .kv("triggerThreadCount", triggers.size())
                .kv("verdict", overallVerdict)
                .raw("triggerThreads", arr.endArr());

        // 受害者场景：触发线程都不占内存 → 指出真正的大头（最大 retained 线程）
        if ("victim".equals(overallVerdict) && maxThreadId >= 0) {
            double mp = 100.0 * maxThreadRetained / totalHeap;
            out.raw("culpritHint", Json.obj()
                    .kv("threadId", maxThreadId)
                    .kv("threadName", maxThreadName)
                    .kv("retained", Json.humanBytes(maxThreadRetained))
                    .kv("percentHeap", Math.round(mp * 10) / 10.0)
                    .kv("note", "触发线程 retained 都不大，真正占内存最多的线程在此（也可能是非线程持有，建议看 Leak Suspects）")
                    .endObj());
        }
        return out.endObj();
    }

    private record Cand(int tid, String name, long retained, double pct,
                        boolean hasOome, boolean topAlloc, String frame) { }

    private static long retained(ISnapshot s, int id) {
        try { return s.getRetainedHeapSize(id); } catch (Exception e) { return 0L; }
    }
    private static IObject safe(ISnapshot s, int id) {
        try { return s.getObject(id); } catch (Exception e) { return null; }
    }
}
