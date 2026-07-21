package com.jvmind.mat.query.http;

import com.sun.net.httpserver.HttpExchange;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** 极简 query string 解析。 */
final class Query {

    static Map<String, String> parse(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return m;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                m.put(dec(pair), "");
            } else {
                m.put(dec(pair.substring(0, eq)), dec(pair.substring(eq + 1)));
            }
        }
        return m;
    }

    static int intOr(Map<String, String> q, String key, int def) {
        String v = q.get(key);
        if (v == null || v.isBlank()) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }

    /**
     * 把外部输入解析成 MAT 内部 int objectId。允许十进制整数与 {@code 0x...} 前缀 hex。
     * <p>失败抛 {@link IllegalArgumentException}（message 带原值 + 期望格式），
     * 由调用方决定 400 / 500；用于必填 ID 字段。{@link #intOr} 仍用于可选参数静默回退。
     * <p><b>变更</b>（0.1.0 → 0.2.0）：移除「≤8 字符裸 hex」分支。所有 ≤8 字符的纯数字输入
     * （如 {@code "20"}、{@code "100"}）此前会被静默当 hex 解析（{@code "20"} → 32），
     * 现统一走十进制。MAT 报告里的地址请带 {@code 0x} 前缀。*/
    static int parseObjectId(String s) {
        if (s == null) {
            throw new IllegalArgumentException("missing object id");
        }
        String t = s.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("missing object id");
        }
        try {
            if (t.startsWith("0x") || t.startsWith("0X")) {
                long v = Long.parseLong(t.substring(2), 16);
                if (v > 0xFFFFFFFFL) {
                    throw new IllegalArgumentException("object id out of 32-bit range: " + s);
                }
                return (int) v;
            }
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "invalid object id: " + s
                + " (decimal integer expected; use '0x' prefix for hex)");
        }
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
