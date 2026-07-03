package com.jvmind.mat.query.http;

/**
 * 极简 JSON 取值（仅用于解析 OQL 的小 POST body：{"q":"...","limit":200,"offset":0}）。
 * 不是通用 JSON 解析器；生产可换 Jackson。只够取顶层 string/number 字段。
 */
final class MiniJson {

    /** 取顶层字符串字段值（处理转义）。找不到返回 null。 */
    static String getString(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return null;
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int i = colon + 1;
        // 跳过空白
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return null;
        i++; // 跳过开引号
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i++);
            if (c == '\\' && i < json.length()) {
                char e = json.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    default -> sb.append(e);
                }
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 取顶层 int 数组字段，如 "objects":[1,2,3]。找不到返回空数组。 */
    static int[] getIntArray(String json, String key) {
        if (json == null) return new int[0];
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return new int[0];
        int lb = json.indexOf('[', k + needle.length());
        if (lb < 0) return new int[0];
        int rb = json.indexOf(']', lb);
        if (rb < 0) return new int[0];
        String inner = json.substring(lb + 1, rb).trim();
        if (inner.isEmpty()) return new int[0];
        String[] parts = inner.split(",");
        int[] out = new int[parts.length];
        int n = 0;
        for (String p : parts) {
            p = p.trim();
            if (p.isEmpty()) continue;
            try { out[n++] = Integer.parseInt(p); } catch (NumberFormatException ignore) { }
        }
        if (n == out.length) return out;
        int[] trimmed = new int[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    /** 取顶层数字字段值。找不到返回 def。 */
    static long getLong(String json, String key, long def) {
        if (json == null) return def;
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return def;
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) return def;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        int start = i;
        while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '-')) i++;
        if (i == start) return def;
        try {
            return Long.parseLong(json.substring(start, i));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
