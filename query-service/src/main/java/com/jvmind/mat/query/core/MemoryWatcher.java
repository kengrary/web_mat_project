package com.jvmind.mat.query.core;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 内存兜底（文档 05 §6 整体内存策略）。
 *
 * <p>SoftReference 让内存紧张时 snapshot 可被 GC 回收，但 GC 时机不可控。
 * 本 watcher 用 MemoryMXBean 主动监控堆使用率，逼近阈值时<b>不等 GC</b>，
 * 直接 dispose 空闲（inUse==0 且超过 idleMillis）的 snapshot，提前释放 index 文件句柄与内存。
 *
 * <p>与软引用的关系：软引用是被动兜底，watcher 是主动提前，两者叠加更稳。
 */
public final class MemoryWatcher {

    private static final Logger LOG = Logger.getLogger(MemoryWatcher.class.getName());

    private final SnapshotManager manager;
    private final int thresholdPct;
    private final long idleMillis;
    private final MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
    private final ScheduledExecutorService exec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mat-memory-watcher");
                t.setDaemon(true);
                return t;
            });

    public MemoryWatcher(SnapshotManager manager, int thresholdPct, long idleMillis) {
        this.manager = manager;
        this.thresholdPct = thresholdPct;
        this.idleMillis = idleMillis;
    }

    public void start() {
        exec.scheduleAtFixedRate(this::check, 10, 10, TimeUnit.SECONDS);
    }

    public void stop() {
        exec.shutdownNow();
    }

    private void check() {
        try {
            var heap = memBean.getHeapMemoryUsage();
            long used = heap.getUsed();
            long max = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
            int pct = (int) (100L * used / Math.max(max, 1));
            if (pct >= thresholdPct) {
                int evicted = manager.evictIdle(idleMillis);
                LOG.warning(() -> "heap " + pct + "% >= " + thresholdPct
                        + "%, evicted " + evicted + " idle snapshots");
                if (evicted == 0) {
                    // 没有空闲可回收 → 建议提示运维：调小并发上限 / 加内存 / 调激进软引用回收
                    LOG.warning("memory pressure but no idle snapshot to evict "
                            + "(consider lowering MAT_QUERY_MAX_OPEN or adding heap)");
                }
            }
        } catch (Throwable t) {
            LOG.warning("memory watcher error: " + t);
        }
    }
}
