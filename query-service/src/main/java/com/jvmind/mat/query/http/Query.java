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

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
