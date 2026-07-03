package com.jvmind.mat.parser;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 命令行参数解析与校验。
 *
 * <p>用法：
 * <pre>
 * java -Xmx32g -jar parser-cli.jar
 *      --hprof   /nfs/heapdumps/hd_xxx/app.hprof
 *      --out-dir /nfs/heapdumps/hd_xxx
 *      [--keep-unreachable] [--discard-ratio 50] [--snapshot-id "#1"]
 *      [--progress-file /path/progress.jsonl]
 *      [--mode equinox|direct]
 * </pre>
 */
public final class CliOptions {

    /** 退出码约定（与 README 一致，Python worker 据此判定任务结果）。*/
    public static final int EXIT_OK        = 0;
    public static final int EXIT_BAD_ARGS  = 2;
    public static final int EXIT_PARSE_ERR = 3;
    public static final int EXIT_OOM       = 79;   // 沿用 MAT 约定
    public static final int EXIT_CANCELLED = 130;

    public File hprof;
    public File outDir;
    public File progressFile;          // null → 进度走 stdout
    public boolean keepUnreachable;
    public Integer discardRatio;       // 实验特性：抽样丢弃百分比
    public String snapshotId;          // 多 dump 文件时选哪个，如 "#1"
    public String mode = "equinox";    // equinox | direct

    /** MAT openSnapshot 接受的 args map（透传）。*/
    public Map<String, String> toMatArgs() {
        Map<String, String> m = new HashMap<>();
        if (keepUnreachable) {
            m.put("keep_unreachable_objects", "true");
        }
        if (discardRatio != null) {
            m.put("discard_ratio", String.valueOf(discardRatio));
        }
        if (snapshotId != null && !snapshotId.isBlank()) {
            m.put("snapshot_identifier", snapshotId);
        }
        return m;
    }

    public static CliOptions parse(String[] args) {
        CliOptions o = new CliOptions();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--hprof"         -> o.hprof = new File(req(args, ++i, a));
                case "--out-dir"       -> o.outDir = new File(req(args, ++i, a));
                case "--progress-file" -> o.progressFile = new File(req(args, ++i, a));
                case "--keep-unreachable" -> o.keepUnreachable = true;
                case "--discard-ratio" -> o.discardRatio = Integer.parseInt(req(args, ++i, a));
                case "--snapshot-id"   -> o.snapshotId = req(args, ++i, a);
                case "--mode"          -> o.mode = req(args, ++i, a);
                case "-h", "--help"    -> { printUsage(); throw new ExitRequest(EXIT_OK); }
                default -> throw new IllegalArgumentException("Unknown argument: " + a);
            }
        }
        validate(o);
        return o;
    }

    private static void validate(CliOptions o) {
        if (o.hprof == null)  throw new IllegalArgumentException("--hprof is required");
        if (o.outDir == null) throw new IllegalArgumentException("--out-dir is required");
        if (!o.hprof.isFile()) throw new IllegalArgumentException("hprof not found: " + o.hprof);
        if (!o.mode.equals("equinox") && !o.mode.equals("direct"))
            throw new IllegalArgumentException("--mode must be 'equinox' or 'direct'");
    }

    private static String req(String[] args, int idx, String flag) {
        if (idx >= args.length) throw new IllegalArgumentException("Missing value for " + flag);
        return args[idx];
    }

    public static void printUsage() {
        System.err.println("""
            Usage: parser-cli --hprof <file> --out-dir <dir> [options]
              --hprof <file>          Path to the .hprof heap dump (required)
              --out-dir <dir>         Target dump directory on NFS (required)
              --progress-file <file>  Write JSONL progress here (default: stdout)
              --keep-unreachable      Keep unreachable objects in the snapshot
              --discard-ratio <pct>   Experimental: discard a fraction of objects
              --snapshot-id <id>      Select snapshot when file has many (e.g. "#1")
              --mode equinox|direct   Runtime mode (default: equinox)
            """);
    }

    /** 用于 --help 等需要立即按某退出码结束的内部信号。*/
    public static final class ExitRequest extends RuntimeException {
        public final int code;
        public ExitRequest(int code) { this.code = code; }
    }
}
