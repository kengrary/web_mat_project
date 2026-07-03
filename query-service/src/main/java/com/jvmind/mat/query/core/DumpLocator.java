package com.jvmind.mat.query.core;

import java.io.File;

/**
 * dumpId → NFS 上的 dump 目录 / hprof 文件 的定位器。
 *
 * <p>解耦"怎么找到文件"与"怎么管理 snapshot"。两种实现：
 * <ul>
 *   <li>{@link ByConvention}：约定目录布局 {@code <root>/<dumpId>/app.hprof}（最简单）。</li>
 *   <li>DB 实现（落地补）：查 heapdump_reports.dump_dir。但本服务"只读 DB"，且为降耦合，
 *       推荐 Python 反代时直接把 dump_dir 作为请求参数传来，Java 用 {@link ByExplicitDir}。</li>
 * </ul>
 */
public interface DumpLocator {

    Location locate(String dumpId) throws Exception;

    record Location(File dumpDir, File hprof) { }

    /** 约定布局：root/<dumpId>/app.hprof（hprof 名可配）。 */
    final class ByConvention implements DumpLocator {
        private final File root;
        private final String hprofName;

        public ByConvention(File root, String hprofName) {
            this.root = root;
            this.hprofName = hprofName;
        }

        @Override
        public Location locate(String dumpId) {
            File dir = new File(root, dumpId);
            return new Location(dir, new File(dir, hprofName));
        }
    }

    /**
     * 显式目录：调用方（Python 反代）直接给出 dumpDir，Java 在其中找唯一的 .hprof。
     * 契合"仅网络 ACL + Python 当唯一权威"——Java 不查 DB，参数由可信内网调用方提供。
     */
    final class ByExplicitDir implements DumpLocator {
        @Override
        public Location locate(String dumpDirPath) {
            File dir = new File(dumpDirPath);
            File[] hprofs = dir.listFiles((d, n) -> n.endsWith(".hprof") || n.endsWith(".bin"));
            if (hprofs == null || hprofs.length == 0) {
                throw new IllegalStateException("no .hprof under " + dir);
            }
            return new Location(dir, hprofs[0]);
        }
    }
}
