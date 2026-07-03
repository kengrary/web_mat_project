package com.jvmind.mat.query.model;

/**
 * 极简 JSON 构建（避免引第三方库；落地可换 Jackson/Gson）。
 * 仅覆盖本服务输出所需：对象、数组、字符串/数字/布尔。
 */
public final class Json {

    private final StringBuilder sb = new StringBuilder(256);
    private boolean firstInScope = true;

    public static Json obj() {
        Json j = new Json();
        j.sb.append('{');
        return j;
    }

    public static Json arr() {
        Json j = new Json();
        j.sb.append('[');
        return j;
    }

    public Json kv(String k, String v) {
        comma(); key(k); str(v); return this;
    }

    public Json kv(String k, long v) {
        comma(); key(k); sb.append(v); return this;
    }

    public Json kv(String k, double v) {
        comma(); key(k); sb.append(v); return this;
    }

    public Json kv(String k, boolean v) {
        comma(); key(k); sb.append(v); return this;
    }

    /** 原样嵌入一个已构建好的 JSON 片段（对象或数组）。 */
    public Json raw(String k, String jsonFragment) {
        comma(); key(k); sb.append(jsonFragment); return this;
    }

    /** kv: 接受另一个 Json 对象作为 value（嵌套对象，自动 endObj）。 */
    public Json kv(String k, Json v) {
        if (v == null) return kv(k, (String) null);
        comma(); key(k); sb.append(v.endObj()); return this;
    }

    /** 数组中追加一个已构建好的 JSON 片段。 */
    public Json item(String jsonFragment) {
        comma(); sb.append(jsonFragment); return this;
    }

    /** 数组中追加一个 Json 对象（自动调用 endObj）。 */
    public Json item(Json obj) {
        return item(obj == null ? "null" : obj.endObj());
    }

    public String endObj() { sb.append('}'); return sb.toString(); }
    public String endArr() { sb.append(']'); return sb.toString(); }

    private void comma() {
        if (!firstInScope) sb.append(',');
        firstInScope = false;
    }

    private void key(String k) { str(k); sb.append(':'); }

    private void str(String s) {
        if (s == null) { sb.append("null"); return; }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    /** 人性化字节数，如 "523.4 MB"（对齐 MAT UI 展示，出参瘦身的一部分）。 */
    public static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        do { v /= 1024.0; i++; } while (v >= 1024 && i < u.length - 1);
        return String.format("%.1f %s", v, u[i]);
    }
}
