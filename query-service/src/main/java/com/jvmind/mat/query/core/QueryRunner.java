package com.jvmind.mat.query.core;

import org.eclipse.mat.query.IResult;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.query.SnapshotQuery;
import org.eclipse.mat.util.IProgressListener;
import org.eclipse.mat.util.VoidProgressListener;

import java.util.Map;

/**
 * 统一的 MAT query 执行器（接入"所有 query"的基础设施，文档 08 §3）。
 *
 * <p>核心（读 MAT 源码确认）：MAT 提供便捷入口
 * {@code SnapshotQuery.lookup(name, snapshot).setArgument(k,v).execute(listener) → IResult}。
 * 它内部处理了 QueryContext / ArgumentSet / 反射注入，我们无需手工构造。
 *
 * <p>这意味着：<b>任意一个 @CommandName 的 query 都能用这一个方法跑起来</b>
 * （leakhunter / path2gc / top_consumers / hash_entries / ...），
 * 返回的 IResult 再交给 {@link com.jvmind.mat.query.view.ResultRenderer} 统一渲染成瘦身 JSON。
 * 配合文档 04 的 QueryDescriptor 反射，可低成本批量接入几十个 query。
 */
public final class QueryRunner {

    private static final IProgressListener SILENT = new VoidProgressListener();

    private QueryRunner() { }

    /**
     * 执行一个 MAT query。
     *
     * @param snapshot 目标快照
     * @param command  query 的 @CommandName，如 "leakhunter"、"path2gc"、"top_consumers"
     * @param args     参数名 → 值。值类型按 query 的 @Argument 字段：
     *                 - 堆对象参数（Advice.HEAP_OBJECT）通常接受 int[]（对象 id）；
     *                 - 普通参数接受 String/Integer/Boolean 等。
     * @param listener 进度（默认静默；重 query 可传带超时的 listener）
     */
    public static IResult run(ISnapshot snapshot, String command,
                              Map<String, Object> args, IProgressListener listener) throws Exception {
        SnapshotQuery query = SnapshotQuery.lookup(command, snapshot);
        if (args != null) {
            for (Map.Entry<String, Object> e : args.entrySet()) {
                if (e.getValue() != null) {
                    query.setArgument(e.getKey(), e.getValue());
                }
            }
        }
        return query.execute(listener != null ? listener : SILENT);
    }

    /** 无参便捷版（如 leakhunter / top_consumers 默认参数即可）。 */
    public static IResult run(ISnapshot snapshot, String command) throws Exception {
        return run(snapshot, command, null, SILENT);
    }
}
