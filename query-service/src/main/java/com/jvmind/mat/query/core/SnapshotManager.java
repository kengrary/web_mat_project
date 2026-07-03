package com.jvmind.mat.query.core;

import org.eclipse.mat.snapshot.ISnapshot;

import java.io.File;
import java.lang.ref.SoftReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * 层 B：ISnapshot 缓存（见 mat-study/05 §6）。
 *
 * <p>核心要求与设计（读 MAT 源码后确认）：
 * <ul>
 *   <li><b>SoftReference 持有 snapshot</b>：内存紧张时整个 snapshot 可被 GC 回收，避免 Java 服务 OOM。
 *       回收后下次 acquire 因 index 仍在 NFS → openSnapshot 走 readFromFile，秒级重建。</li>
 *   <li><b>引用计数防"正在用时被回收"</b>：acquire 返回一个 {@link Lease}，请求作用域内持有
 *       对 holder 的<b>强引用</b>（Lease 字段），即便软引用被清，正在服务的请求也不会丢 snapshot。
 *       请求结束 {@code lease.close()} 减计数。</li>
 *   <li><b>MAT 内部已用 SoftReference 缓存 index 页（层 A）</b>，我们不重复造；只需保证 NFS 文件常驻 +
 *       JVM 合理堆大小让软引用发挥作用。</li>
 * </ul>
 *
 * <p>关键细节：MAT reopen 需要原始 hprof + 全套 index 都在可随机 seek 的文件系统（NFS 满足）。
 * 所以 dumpDir 下必须同时有 app.hprof 与 *.index。
 */
public final class SnapshotManager {

    private static final Logger LOG = Logger.getLogger(SnapshotManager.class.getName());

    /** dumpId → 软引用持有的 holder。holder 内含 inUse 引用计数。 */
    private final Map<String, SoftReference<SnapshotHolder>> cache = new ConcurrentHashMap<>();

    /** 每个 dumpId 一把锁，避免并发首次打开同一 snapshot（重复 openSnapshot 浪费内存）。 */
    private final Map<String, ReentrantLock> openLocks = new ConcurrentHashMap<>();

    private final DumpLocator locator;          // dumpId → dump 目录 / hprof 路径
    private final int maxConcurrentOpen;        // 同时 inUse>0 的 dump 上限（防一次涌入多个大 dump OOM）
    private final java.util.concurrent.Semaphore openPermits;

    public SnapshotManager(DumpLocator locator, int maxConcurrentOpen) {
        this.locator = locator;
        this.maxConcurrentOpen = maxConcurrentOpen;
        this.openPermits = new java.util.concurrent.Semaphore(maxConcurrentOpen);
    }

    /**
     * 获取一个 snapshot 的租约。<b>必须 try-with-resources 使用</b>，确保 close 减计数。
     *
     * <pre>{@code
     * try (var lease = mgr.acquire(dumpId)) {
     *     ISnapshot s = lease.snapshot();
     *     ... // 所有查询在此作用域内完成
     * }
     * }</pre>
     */
    public Lease acquire(String dumpId) throws Exception {
        SnapshotHolder holder = getOrOpen(dumpId);
        // 在返回前增计数 + 持强引用 → 此后即使软引用被清，holder 也不会被 GC
        holder.inUse.incrementAndGet();
        holder.lastAccess = System.currentTimeMillis();
        return new Lease(holder, this);
    }

    private SnapshotHolder getOrOpen(String dumpId) throws Exception {
        // 快路径：缓存命中且软引用未被清
        SoftReference<SnapshotHolder> ref = cache.get(dumpId);
        if (ref != null) {
            SnapshotHolder h = ref.get();
            if (h != null && !h.disposed) {
                return h;
            }
        }
        // 慢路径：按 dumpId 加锁，避免并发重复打开
        ReentrantLock lock = openLocks.computeIfAbsent(dumpId, k -> new ReentrantLock());
        lock.lock();
        try {
            // 双重检查
            ref = cache.get(dumpId);
            if (ref != null) {
                SnapshotHolder h = ref.get();
                if (h != null && !h.disposed) {
                    return h;
                }
            }
            return openNew(dumpId);
        } finally {
            lock.unlock();
        }
    }

    private SnapshotHolder openNew(String dumpId) throws Exception {
        DumpLocator.Location loc = locator.locate(dumpId);
        File hprof = loc.hprof();
        if (!hprof.isFile()) {
            throw new IllegalStateException("hprof not found for " + dumpId + ": " + hprof
                    + " (reopen needs hprof + *.index on NFS)");
        }
        // 限制并发打开数（每个大 dump 常驻内存约 N*28.25 + C*1000 字节）
        openPermits.acquire();
        try {
            LOG.info(() -> "Opening snapshot " + dumpId + " from " + hprof);
            // ★ index 已在 → openSnapshot 走 readFromFile，秒级；首次（无 index）才会真解析
            //   生产中 index 一定已由 parser-cli/ParseHeapDump 产出，所以这里总是秒级。
            ISnapshot snapshot = org.eclipse.mat.snapshot.SnapshotFactory.openSnapshot(
                    hprof, new SilentProgress());
            SnapshotHolder holder = new SnapshotHolder(dumpId, snapshot, hprof, loc.dumpDir());
            cache.put(dumpId, new SoftReference<>(holder));
            return holder;
        } catch (Exception e) {
            openPermits.release();
            throw e;
        }
    }

    /** 由 Lease.close 调用：减计数。计数归零时不立即 dispose（留给软引用/LRU 决定）。 */
    void release(SnapshotHolder holder) {
        int now = holder.inUse.decrementAndGet();
        holder.lastAccess = System.currentTimeMillis();
        if (now < 0) {
            LOG.warning("inUse went negative for " + holder.dumpId + " (double release?)");
            holder.inUse.set(0);
        }
    }

    /**
     * 主动释放一个 snapshot（删 dump 前调用，或内存兜底）。
     * 仅当 inUse==0 才 dispose；否则标记延迟，由最后一个 release 完成（这里简化为拒绝）。
     */
    public boolean close(String dumpId) {
        SoftReference<SnapshotHolder> ref = cache.remove(dumpId);
        if (ref == null) return true;
        SnapshotHolder h = ref.get();
        if (h == null) return true;
        if (h.inUse.get() > 0) {
            // 还有请求在用，放回缓存，拒绝立即关闭
            cache.put(dumpId, ref);
            return false;
        }
        disposeHolder(h);
        return true;
    }

    private void disposeHolder(SnapshotHolder h) {
        if (h.disposed) return;
        h.disposed = true;
        try {
            org.eclipse.mat.snapshot.SnapshotFactory.dispose(h.snapshot);   // 关 index 文件句柄
        } catch (Throwable t) {
            LOG.warning("dispose error for " + h.dumpId + ": " + t);
        } finally {
            openPermits.release();
        }
    }

    /** 内存兜底：dispose 所有 inUse==0 且空闲超过 idleMillis 的 snapshot（由 MemoryWatcher 触发）。 */
    public int evictIdle(long idleMillis) {
        long cutoff = System.currentTimeMillis() - idleMillis;
        int evicted = 0;
        for (var entry : cache.entrySet()) {
            SnapshotHolder h = entry.getValue().get();
            if (h != null && !h.disposed && h.inUse.get() == 0 && h.lastAccess < cutoff) {
                if (close(entry.getKey())) evicted++;
            }
        }
        return evicted;
    }

    // ===================== 内部类型 =====================

    /** 持有 snapshot 与运行期状态。被 SoftReference 包裹。 */
    static final class SnapshotHolder {
        final String dumpId;
        final ISnapshot snapshot;
        final File hprof;
        final File dumpDir;
        final AtomicInteger inUse = new AtomicInteger(0);
        volatile long lastAccess;
        volatile boolean disposed = false;

        SnapshotHolder(String dumpId, ISnapshot snapshot, File hprof, File dumpDir) {
            this.dumpId = dumpId;
            this.snapshot = snapshot;
            this.hprof = hprof;
            this.dumpDir = dumpDir;
            this.lastAccess = System.currentTimeMillis();
        }
    }

    /**
     * 租约：try-with-resources 范围内持有 holder 的<b>强引用</b>（防软引用回收正在用的 snapshot），
     * close 时减计数。这是"引用计数防回收"的落地点。
     */
    public static final class Lease implements AutoCloseable {
        private final SnapshotHolder holder;     // 强引用！请求期间 holder 不会被 GC
        private final SnapshotManager mgr;
        private boolean closed = false;

        Lease(SnapshotHolder holder, SnapshotManager mgr) {
            this.holder = holder;
            this.mgr = mgr;
        }

        public ISnapshot snapshot() { return holder.snapshot; }
        public String dumpId()      { return holder.dumpId; }
        public File dumpDir()       { return holder.dumpDir; }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            mgr.release(holder);
        }
    }

    /** 静默进度（reopen 已存在 index 时几乎瞬时，无需进度）。 */
    private static final class SilentProgress implements org.eclipse.mat.util.IProgressListener {
        public void beginTask(String n, int t) {}
        public void done() {}
        public boolean isCanceled() { return false; }
        public void setCanceled(boolean v) {}
        public void subTask(String n) {}
        public void worked(int w) {}
        public void sendUserMessage(Severity s, String m, Throwable e) {}
    }
}
