package com.jvmind.mat.parser;

import com.google.gson.JsonObject;
import org.eclipse.mat.util.IProgressListener;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 把 MAT 的 {@link IProgressListener} 进度转成「每行一个 JSON」(JSONL) 写到 sink，
 * 供 Python worker 逐行解析，更新 heapdump_reports.progress / phase。
 *
 * <p>MAT 的进度模型：嵌套的 beginTask/worked。我们把"全局完成度"近似为
 * 已累计 worked / 顶层 totalWork。MAT 顶层把解析切成几个阶段，权重大致
 * {Pass1:700, ...:30, GarbageCleaner:90, ...:20, DominatorTree:150, ...:10}
 * （见解析管线），所以这个近似已能给出顺滑的 0~1 进度。
 *
 * <p>取消（cancel）：本进程不主动取消；中断由 Python 直接 kill 子进程实现
 * （最干净）。但仍实现 isCanceled，便于将来支持文件信号取消。
 */
public final class JsonProgressListener implements IProgressListener {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");   // 对齐现有系统本地时间格式

    private final ProgressSink sink;

    private volatile boolean canceled = false;

    // 顶层任务进度近似
    private long totalWork = IProgressListener.UNKNOWN_TOTAL_WORK;
    private long workedSoFar = 0;
    private String currentPhase = "Starting";
    private double lastEmitted = -1;

    // 节流：进度变化 < 1% 不重复输出，避免刷屏
    private static final double EMIT_STEP = 0.01;

    public JsonProgressListener(ProgressSink sink) {
        this.sink = sink;
    }

    @Override
    public void beginTask(String name, int total) {
        // 第一个 beginTask 视为顶层任务
        if (totalWork == IProgressListener.UNKNOWN_TOTAL_WORK && total > 0) {
            totalWork = total;
        }
        if (name != null && !name.isBlank()) {
            currentPhase = name;
        }
        emitProgress(true);
    }

    @Override
    public void subTask(String name) {
        if (name != null && !name.isBlank()) {
            currentPhase = name;
            emitProgress(true);   // 阶段切换总是输出
        }
    }

    @Override
    public void worked(int work) {
        workedSoFar += Math.max(work, 0);
        emitProgress(false);
    }

    @Override
    public void done() {
        // 不在此输出 done 事件——最终 done 由 Main 统一发，避免子任务 done 误判完成
    }

    @Override
    public boolean isCanceled() {
        return canceled;
    }

    @Override
    public void setCanceled(boolean value) {
        this.canceled = value;
    }

    @Override
    public void sendUserMessage(Severity severity, String message, Throwable exception) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "message");
        o.addProperty("severity", severity == null ? "INFO" : severity.name());
        o.addProperty("text", message);
        if (exception != null) {
            o.addProperty("exception", exception.toString());
        }
        o.addProperty("ts", now());
        sink.write(o);
    }

    private void emitProgress(boolean force) {
        double p = progressFraction();
        if (!force && p >= 0 && (p - lastEmitted) < EMIT_STEP) {
            return;
        }
        lastEmitted = p;
        JsonObject o = new JsonObject();
        o.addProperty("type", "progress");
        o.addProperty("phase", currentPhase);
        if (p >= 0) {
            o.addProperty("progress", round(p));
        }
        o.addProperty("ts", now());
        sink.write(o);
    }

    private double progressFraction() {
        if (totalWork <= 0) return -1;             // 未知，前端显示"进行中"
        return Math.min(1.0, (double) workedSoFar / (double) totalWork);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }
}
