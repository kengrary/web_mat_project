package com.jvmind.mat.parser;

import com.google.gson.JsonObject;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.SnapshotFactory;
import org.eclipse.mat.snapshot.SnapshotInfo;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 解析的核心编排（"direct"模式：直接调 SnapshotFactory）。
 *
 * <p>关键流程（呼应文档 02/05/06）：
 * <ol>
 *   <li>把 hprof 拷贝/链接进 <b>临时工作目录</b> {@code <outDir>/.tmp/}（MAT 把 index 写到 hprof 同目录）；</li>
 *   <li>{@code SnapshotFactory.openSnapshot(hprof, args, listener)} 跑完整管线，产出全套 index；</li>
 *   <li>读 {@link SnapshotInfo} 提取 stats；</li>
 *   <li>{@code SnapshotFactory.dispose(snapshot)} 释放句柄；</li>
 *   <li>把 .tmp 下的产物 <b>原子 rename</b> 到正式 outDir —— 保证查询机绝不读到半成品 index。</li>
 * </ol>
 *
 * <p>为什么用 .tmp + rename：NFS 上同目录 rename 是原子的；若解析中途崩溃/被 kill，
 * 正式目录里永远没有半成品 index，僵尸任务可安全重试（文档 07 §10）。
 */
public final class HeapDumpParser {

    private final ProgressSink sink;

    public HeapDumpParser(ProgressSink sink) {
        this.sink = sink;
    }

    /**
     * @return stats JSON（写入 heapdump_reports.stats）
     */
    public JsonObject parse(CliOptions opts) throws Exception {
        Path outDir = opts.outDir.toPath();
        Files.createDirectories(outDir);

        Path tmpDir = outDir.resolve(".tmp");
        // 清理上一次失败遗留的 .tmp（僵尸任务重试场景）
        deleteRecursively(tmpDir);
        Files.createDirectories(tmpDir);

        // MAT 把 index 写到 hprof 所在目录；为隔离半成品，把 hprof 放进 .tmp 再解析。
        // 大文件用硬链接避免复制（同一 NFS 文件系统内）；跨文件系统降级为软链/复制。
        Path hprofInTmp = tmpDir.resolve(opts.hprof.getName());
        linkOrCopy(opts.hprof.toPath(), hprofInTmp);

        JsonProgressListener listener = new JsonProgressListener(sink);

        ISnapshot snapshot = null;
        try {
            Map<String, String> matArgs = opts.toMatArgs();
            // ★ 核心：复用 MAT 内核跑完整解析管线，产出全套 index 到 .tmp
            snapshot = SnapshotFactory.openSnapshot(hprofInTmp.toFile(), matArgs, listener);

            JsonObject stats = extractStats(snapshot.getSnapshotInfo());
            emitStats(stats);

            // 必须先 dispose 释放 index 文件句柄，才能安全 rename（尤其 Windows/NFS）
            SnapshotFactory.dispose(snapshot);
            snapshot = null;

            // 把 .tmp 的产物搬到正式目录（原子语义尽力而为）
            promoteTmpToFinal(tmpDir, outDir, opts.hprof.getName());

            return stats;
        } finally {
            if (snapshot != null) {
                try { SnapshotFactory.dispose(snapshot); } catch (Throwable ignore) { }
            }
        }
    }

    /** 从 SnapshotInfo 抽取 stats —— 这些字段会写入 heapdump_reports.stats。*/
    private JsonObject extractStats(SnapshotInfo info) {
        JsonObject s = new JsonObject();
        s.addProperty("numObjects", info.getNumberOfObjects());
        s.addProperty("numClasses", info.getNumberOfClasses());
        s.addProperty("numClassLoaders", info.getNumberOfClassLoaders());
        s.addProperty("numGcRoots", info.getNumberOfGCRoots());
        s.addProperty("usedHeapSize", info.getUsedHeapSize());
        s.addProperty("identifierSize", info.getIdentifierSize());
        s.addProperty("jvmInfo", info.getJvmInfo());
        if (info.getCreationDate() != null) {
            s.addProperty("creationDate",
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(info.getCreationDate()));
        }
        return s;
    }

    private void emitStats(JsonObject stats) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "stats");
        o.add("data", stats);
        sink.write(o);
    }

    /**
     * 把 .tmp 下的 index 与 hprof 原子地搬到正式目录。
     * 策略：逐个文件 move（同 NFS 目录内 rename 原子）；hprof 本身用真实文件覆盖临时链接。
     */
    private void promoteTmpToFinal(Path tmpDir, Path outDir, String hprofName) throws IOException {
        try (Stream<Path> files = Files.list(tmpDir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String name = p.getFileName().toString();
                Path target = outDir.resolve(name);
                if (name.equals(hprofName)) {
                    // hprof 在 .tmp 里是链接/副本；正式目录需要真实可读的 hprof（reopen 要 seek 它）
                    // 若 outDir 已有真实 hprof（上传时直接落正式目录），跳过；否则把链接 move 过去
                    if (Files.exists(target)) continue;
                }
                atomicMove(p, target);
            }
        }
        deleteRecursively(tmpDir);
    }

    private static void atomicMove(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // 某些 NFS 配置不支持 ATOMIC_MOVE，降级（仍优于复制：rename 通常原子）
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void linkOrCopy(Path src, Path dst) throws IOException {
        try {
            Files.createLink(dst, src);                 // 硬链接：零拷贝，同文件系统
        } catch (UnsupportedOperationException | IOException e) {
            try {
                Files.createSymbolicLink(dst, src.toAbsolutePath());  // 软链接
            } catch (UnsupportedOperationException | IOException e2) {
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);  // 最后才复制
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())  // 先删子项
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignore) { } });
        }
    }
}
