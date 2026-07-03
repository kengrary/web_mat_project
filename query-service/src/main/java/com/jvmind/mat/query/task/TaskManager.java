package com.jvmind.mat.query.task;

import org.eclipse.mat.util.IProgressListener;

import java.lang.ref.SoftReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 重操作异步任务管理（文档05 §6.1 + 文档08 §2 的标注）。
 *
 * <p>为什么需要：leakhunter / retained_set / merge_paths 是全图分析，大堆上几十秒~几分钟。
 * 同步 HTTP 会阻塞请求线程、客户端易超时、并发几个就打满线程池。
 * 改为：提交即返回 taskId，后台线程跑，客户端轮询 /tasks/{id} 看进度/结果。
 *
 * <p>特性：
 * <ul>
 *   <li><b>进度</b>：提供一个 {@link IProgressListener} 注入 MAT query，回写 Task.progress；</li>
 *   <li><b>超时</b>：超时则 setCanceled(true)，MAT 协作式中断；</li>
 *   <li><b>结果软引用缓存</b>（层 C）：完成的结果用 SoftReference 存一段时间，
 *       agent 反复取/翻页命中缓存；内存紧张时可被回收。</li>
 * </ul>
 */
public final class TaskManager {

    private static final Logger LOG = Logger.getLogger(TaskManager.class.getName());

    private final ExecutorService pool;
    private final int timeoutSeconds;
    private final AtomicLong seq = new AtomicLong();
    /** taskId → 任务（运行中强引用，完成后结果转软引用）。 */
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    public TaskManager(int threads, int timeoutSeconds) {
        this.pool = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "mat-task");
            t.setDaemon(true);
            return t;
        });
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 任务体：执行返回瘦身 JSON 字符串。 */
    @FunctionalInterface
    public interface Job {
        String run(IProgressListener progress) throws Exception;
    }

    /** 提交一个重操作，立即返回 taskId。 */
    public String submit(String kind, Job job) {
        String id = "t-" + Long.toHexString(seq.incrementAndGet()) + "-"
                + Long.toHexString(System.nanoTime());
        Task task = new Task(id, kind);
        tasks.put(id, task);

        Future<?> future = pool.submit(() -> execute(task, job));
        task.future = future;
        return id;
    }

    private void execute(Task task, Job job) {
        task.status = Status.RUNNING;
        task.startedAt = System.currentTimeMillis();
        TaskProgress progress = new TaskProgress(task);
        try {
            String result = job.run(progress);
            task.result = new SoftReference<>(result);   // 层 C：软引用缓存结果
            task.status = Status.DONE;
        } catch (IProgressListener.OperationCanceledException ce) {
            task.status = Status.CANCELLED;
            task.error = "cancelled";
        } catch (Throwable t) {
            task.status = Status.FAILED;
            task.error = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
            LOG.warning("task " + task.id + " (" + task.kind + ") failed: " + t);
        } finally {
            task.finishedAt = System.currentTimeMillis();
        }
    }

    /** 轮询任务状态/结果，渲染成 JSON。 */
    public String poll(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) {
            return "{\"status\":\"NOT_FOUND\"}";
        }
        // 超时检查（运行中且超限 → 取消）
        if (task.status == Status.RUNNING
                && System.currentTimeMillis() - task.startedAt > timeoutSeconds * 1000L) {
            task.canceled = true;
            if (task.future != null) task.future.cancel(true);
            task.status = Status.FAILED;
            task.error = "timeout after " + timeoutSeconds + "s";
        }

        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"taskId\":\"").append(task.id).append("\"")
          .append(",\"kind\":\"").append(task.kind).append("\"")
          .append(",\"status\":\"").append(task.status).append("\"");
        if (task.status == Status.RUNNING) {
            sb.append(",\"progress\":").append(round(task.progress))
              .append(",\"phase\":\"").append(escape(task.phase)).append("\"");
        } else if (task.status == Status.DONE) {
            String r = task.result != null ? task.result.get() : null;
            if (r != null) {
                sb.append(",\"result\":").append(r);   // result 已是 JSON
            } else {
                // 软引用被回收 → 提示重跑
                sb.append(",\"resultEvicted\":true");
            }
        } else if (task.error != null) {
            sb.append(",\"error\":\"").append(escape(task.error)).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    /** 主动取消。 */
    public boolean cancel(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null) return false;
        task.canceled = true;
        if (task.future != null) task.future.cancel(true);
        return true;
    }

    public void shutdown() {
        pool.shutdownNow();
        try { pool.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignore) { }
    }

    // ===================== 内部类型 =====================

    enum Status { RUNNING, DONE, FAILED, CANCELLED }

    static final class Task {
        final String id;
        final String kind;
        volatile Status status = Status.RUNNING;
        volatile double progress = 0;
        volatile String phase = "";
        volatile SoftReference<String> result;
        volatile String error;
        volatile boolean canceled = false;
        volatile long startedAt;
        volatile long finishedAt;
        volatile Future<?> future;

        Task(String id, String kind) { this.id = id; this.kind = kind; }
    }

    /** 把 MAT 进度回写到 Task；支持超时/取消触发的协作式中断。 */
    static final class TaskProgress implements IProgressListener {
        private final Task task;
        private long total = -1;
        private long worked = 0;

        TaskProgress(Task task) { this.task = task; }

        public void beginTask(String name, int totalWork) {
            if (total < 0 && totalWork > 0) total = totalWork;
            if (name != null && !name.isBlank()) task.phase = name;
        }
        public void subTask(String name) { if (name != null && !name.isBlank()) task.phase = name; }
        public void worked(int work) {
            worked += Math.max(work, 0);
            if (total > 0) task.progress = Math.min(1.0, (double) worked / total);
        }
        public void done() { }
        public boolean isCanceled() { return task.canceled; }
        public void setCanceled(boolean value) { task.canceled = value; }
        public void sendUserMessage(Severity s, String m, Throwable e) { }
    }

    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
