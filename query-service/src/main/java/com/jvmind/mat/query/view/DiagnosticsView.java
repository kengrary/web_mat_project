package com.jvmind.mat.query.view;

import com.jvmind.mat.query.core.QueryRunner;
import org.eclipse.mat.query.IResult;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.util.IProgressListener;

import java.util.Map;

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
     * Leak Suspects 报告（MAT 招牌）。返回 CompositeResult（结论文本 + 多个嫌疑段），
     * ResultRenderer 递归渲染成 {kind:composite, sections:[...]}。
     * 这是复合诊断工具 mat_diagnose_leak_suspects 的后端。
     */
    public static String leakSuspects(ISnapshot snapshot, IProgressListener listener) throws Exception {
        IResult r = QueryRunner.run(snapshot, "leakhunter", null, listener);
        return ResultRenderer.render(r);
    }

    /**
     * 单对象 → GC Root 最短路径（path2gc）。返回 IResultTree（引用链树）。
     * 泄漏归因核心："谁攥着这个对象不放"。
     *
     * @param objectId 目标对象
     * @param excludeWeakSoft 是否排除弱/软/虚引用（避免无关引用干扰，常用 true）
     */
    public static String pathToGcRoots(ISnapshot snapshot, int objectId,
                                       boolean excludeWeakSoft, IProgressListener listener) throws Exception {
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("object", new int[]{ objectId });
        // path2gc 支持排除模式参数（excludes）；不同 MAT 版本参数名/形态可能不同，
        // 这里仅传 object，excludeWeakSoft 的精细排除可在 merge_shortest_paths 用 -excludes 表达。
        IResult r = QueryRunner.run(snapshot, "path2gc", args, listener);
        return ResultRenderer.render(r);
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
