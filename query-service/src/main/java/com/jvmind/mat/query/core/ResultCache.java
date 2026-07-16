package com.jvmind.mat.query.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 重 query 结果的磁盘缓存（文档 08 §7）。
 *
 * <p>为什么需要：leakhunter / retained_set / merge_paths / diagnose-oom 在大堆上一次
 * 几十秒到几分钟；软引用缓存在堆吃紧时会被回收（→ {@code resultEvicted:true} 强制重跑）。
 * 磁盘缓存把渲染好的 JSON 落到 dump 目录下 {@code .query-cache/}，进程重启、软引用回收
 * 都不丢；命中时直接返回缓存 JSON，无需重新打开 snapshot 执行 query。
 *
 * <p>缓存 key = {@code <kind>-<argsHash>.json}。{@code argsHash} 由参数名-值对排序后做
 * SHA-256 截断 16 字节（32 hex）。cache-hit 校验：缓存文件 mtime ≥ hprof 文件 mtime，
 * 否则视为过期（hprof 被替换 → 缓存自动失效，不需要维护版本号）。
 *
 * <p>缓存生命周期随 dump：文件存在 dumpDir/.query-cache/ 下，删 dump 目录即清缓存。
 * 查询结果 JSON 通常几百 KB ~ 几 MB，对 NFS 无压力。
 *
 * <p>失败不影响主流程：任何 IO 异常都降级为"未命中"并打 warning 日志。
 */
public final class ResultCache {

    private static final Logger LOG = Logger.getLogger(ResultCache.class.getName());
    private static final String SUBDIR = ".query-cache";

    private final boolean enabled;
    private final String overrideDir;

    public ResultCache(boolean enabled, String overrideDir) {
        this.enabled = enabled;
        this.overrideDir = overrideDir;
    }

    public static ResultCache fromSystemProps() {
        boolean enabled = !"false".equalsIgnoreCase(strProp("MAT_QUERY_CACHE", "true"));
        String dir = strProp("MAT_QUERY_CACHE_DIR", null);
        return new ResultCache(enabled, dir);
    }

    /** 读取缓存；返回 null 表示未命中或禁用。 */
    public String get(String dumpDir, String kind, Map<String, String> args) {
        if (!enabled) return null;
        try {
            Path hprof = findHprof(dumpDir);
            Path file = cacheFile(dumpDir, kind, args);
            if (!Files.exists(file)) return null;
            if (hprof != null) {
                long hprofMod = Files.getLastModifiedTime(hprof).toMillis();
                long cacheMod = Files.getLastModifiedTime(file).toMillis();
                if (cacheMod < hprofMod) {
                    LOG.fine("cache stale (hprof newer): " + file);
                    return null;
                }
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            LOG.info("cache HIT " + kind + " (" + content.length() + " bytes) for " + dumpDir);
            return content;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "cache read failed for " + kind, e);
            return null;
        }
    }

    /** 写入缓存；写入失败被静默吞掉（降级为不缓存）。 */
    public void put(String dumpDir, String kind, Map<String, String> args, String json) {
        if (!enabled || json == null) return;
        try {
            Path file = cacheFile(dumpDir, kind, args);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            LOG.fine("cache STORE " + kind + " (" + json.length() + " bytes) -> " + file);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "cache write failed for " + kind, e);
        }
    }

    /**
     * 构造稳定的参数 Map（TreeMap 按 key 排序），caller 把影响结果的参数塞进来即可。
     * 不要把不影响结果的参数（比如 progress listener、request id）放进来。
     */
    public static Map<String, String> args(Object... kvPairs) {
        TreeMap<String, String> m = new TreeMap<>();
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            Object k = kvPairs[i], v = kvPairs[i + 1];
            if (k == null || v == null) continue;
            m.put(k.toString(), v.toString());
        }
        return m;
    }

    private Path cacheFile(String dumpDir, String kind, Map<String, String> args) {
        Path dir = overrideDir != null ? Paths.get(overrideDir) : Paths.get(dumpDir, SUBDIR);
        String hash = hashArgs(kind, args);
        return dir.resolve(kind + "-" + hash + ".json");
    }

    private static Path findHprof(String dumpDir) {
        try {
            try (var stream = Files.list(Paths.get(dumpDir))) {
                return stream.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.endsWith(".hprof") || n.endsWith(".hprof.gz");
                }).findFirst().orElse(null);
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static String hashArgs(String kind, Map<String, String> args) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(kind.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            if (args != null) {
                for (Map.Entry<String, String> e : args.entrySet()) {
                    md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                    md.update((byte) 0x1f);
                    md.update(e.getValue().getBytes(StandardCharsets.UTF_8));
                    md.update((byte) 0x1e);
                }
            }
            byte[] d = md.digest();
            return HexFormat.of().formatHex(d, 0, 16);   // 16 bytes → 32 hex chars
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static String strProp(String key, String def) {
        String v = System.getProperty(key);
        if (v == null) v = System.getenv(key);
        return v != null && !v.isBlank() ? v : def;
    }
}
