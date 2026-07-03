package com.jvmind.mat.query.core;

import java.lang.ref.SoftReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 结果集缓存（文档05 层 C / 文档06 §3.4 的 resultSetId 复用）。
 *
 * <p>作用：把"一批对象 id"（OQL 结果、histogram 选中、dominator 子树等）暂存在后端，给一个
 * resultSetId。之后对这批对象再做别的分析（histogram / dominator / retained_set / merge_paths）
 * 时，前端只传短 id，后端取回 int[]，无需重查或回传几万个 id。
 * 这是 MAT "分析流"（层层钻取、对子集反复换角度）的支撑机制。
 *
 * <p>用 <b>SoftReference</b> 持有：内存紧张可被回收；回收后前端需重新产出结果集（返回 NOT_FOUND，
 * 前端重跑那一步即可）。按 dumpDir 隔离，避免跨 dump 串号。
 */
public final class ResultSetCache {

    /** resultSetId → 软引用持有的对象 id 数组（+ 所属 dumpDir，防串号）。 */
    private final Map<String, SoftReference<Entry>> cache = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    public record Entry(String dumpDir, int[] objectIds) { }

    /** 存一个结果集，返回 resultSetId。 */
    public String put(String dumpDir, int[] objectIds) {
        String id = "rs-" + Long.toHexString(seq.incrementAndGet())
                + "-" + Long.toHexString(System.nanoTime());
        cache.put(id, new SoftReference<>(new Entry(dumpDir, objectIds)));
        return id;
    }

    /**
     * 取回结果集的对象 id 数组。
     * @return null 表示不存在或已被软引用回收（前端应重跑产出步骤），或 dumpDir 不匹配。
     */
    public int[] get(String resultSetId, String dumpDir) {
        SoftReference<Entry> ref = cache.get(resultSetId);
        if (ref == null) return null;
        Entry e = ref.get();
        if (e == null) { cache.remove(resultSetId); return null; }  // 被回收，清理
        if (dumpDir != null && !dumpDir.equals(e.dumpDir())) return null;  // 防跨 dump 串号
        return e.objectIds();
    }

    public boolean exists(String resultSetId) {
        SoftReference<Entry> ref = cache.get(resultSetId);
        return ref != null && ref.get() != null;
    }
}
