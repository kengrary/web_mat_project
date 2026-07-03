package com.jvmind.mat.parser;

import com.google.gson.JsonObject;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * parser-cli 入口。
 *
 * <p>职责：解析参数 → 选择运行模式 → 跑解析 → 统一输出 done/退出码。
 * 所有异常都转成结构化 {"type":"done","status":"FAILED",...} 事件 + 对应退出码，
 * 让 Python worker 能稳定判定任务结果。
 */
public final class Main {

    public static void main(String[] args) {
        // 进度 sink 在参数解析后才知道目的地；先用临时 stdout 兜底参数错误
        ProgressSink sink = null;
        int exit;
        try {
            CliOptions opts = CliOptions.parse(args);
            sink = (opts.progressFile != null)
                    ? ProgressSink.toFile(opts.progressFile.getAbsolutePath())
                    : ProgressSink.toStdout();

            exit = run(opts, sink);

        } catch (CliOptions.ExitRequest er) {
            exit = er.code;
        } catch (IllegalArgumentException badArgs) {
            System.err.println("[parser-cli] " + badArgs.getMessage());
            CliOptions.printUsage();
            exit = CliOptions.EXIT_BAD_ARGS;
        } catch (Throwable t) {
            // 不应到这——run() 内部已兜底；这里是最后防线
            System.err.println("[parser-cli] fatal: " + t);
            exit = CliOptions.EXIT_PARSE_ERR;
        } finally {
            if (sink != null) sink.close();
        }
        System.exit(exit);
    }

    private static int run(CliOptions opts, ProgressSink sink) {
        try {
            JsonObject stats;
            if ("equinox".equals(opts.mode)) {
                // 方式 A：起最小 Equinox 跑 MAT application（复用 extension registry，最稳）
                stats = EquinoxLauncher.parse(opts, sink);
            } else {
                // 方式 B：直连 SnapshotFactory（需 registry 已就绪）
                stats = new HeapDumpParser(sink).parse(opts);
            }
            emitDoneOk(sink, opts.outDir.getAbsolutePath());
            return CliOptions.EXIT_OK;

        } catch (OutOfMemoryError oom) {
            emitFailed(sink, "OutOfMemoryError: " + oom.getMessage()
                    + " (give the parser more -Xmx, or use --discard-ratio)");
            return CliOptions.EXIT_OOM;
        } catch (Throwable t) {
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
            emitFailed(sink, msg);
            // 解析异常打到 stderr 便于排障（不污染 stdout 的 JSONL 协议）
            t.printStackTrace();
            return classifyExit(t);
        }
    }

    /** 区分"被取消"与"真失败"，便于 Python 区分 CANCELLED / FAILED。*/
    private static int classifyExit(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String n = c.getClass().getName();
            if (n.contains("OperationCanceled")) {
                return CliOptions.EXIT_CANCELLED;
            }
        }
        return CliOptions.EXIT_PARSE_ERR;
    }

    private static void emitDoneOk(ProgressSink sink, String indexDir) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "done");
        o.addProperty("status", "DONE");
        o.addProperty("indexDir", indexDir);
        o.addProperty("ts", now());
        sink.write(o);
    }

    private static void emitFailed(ProgressSink sink, String error) {
        if (sink == null) {
            System.err.println("[parser-cli] FAILED: " + error);
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "done");
        o.addProperty("status", "FAILED");
        o.addProperty("error", error);
        o.addProperty("ts", now());
        sink.write(o);
    }

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
