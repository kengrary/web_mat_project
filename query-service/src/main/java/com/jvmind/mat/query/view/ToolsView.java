package com.jvmind.mat.query.view;

import com.jvmind.mat.query.model.Json;
import org.eclipse.mat.query.annotations.Argument;
import org.eclipse.mat.query.registry.ArgumentDescriptor;
import org.eclipse.mat.query.registry.QueryDescriptor;
import org.eclipse.mat.query.registry.QueryRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具自描述清单（文档 04 §0 / 文档 08 §4.5 的 TODO 落地）。
 *
 * <p>核心洞察（读 MAT 源码确认）：MAT 每个 query 都用 {@code @CommandName/@Help/@Argument} 自描述，
 * 并由 {@code QueryRegistry → QueryDescriptor → ArgumentDescriptor} 反射成元数据。
 * 这<b>几乎就是一份现成的 tool schema</b>——我们把它翻译成 LLM function-calling 友好的 JSON，
 * 一次性把 MAT 全部 ~57 个 query 暴露成 agent 可发现、可调用的工具，无需逐个手写。
 *
 * <p>用法：
 * <ul>
 *   <li>{@code GET /tools} → 全部 query 的清单（name/help/category/args）；</li>
 *   <li>{@code GET /tools?filter=leak} → 按关键字过滤（对应 search_tools 元工具）；</li>
 *   <li>agent 据此决定调哪个，再 {@code POST /query {command,args}} 执行。</li>
 * </ul>
 *
 * <p>注意：需要 Equinox 已启动（registry 就绪），否则 getQueries() 为空。
 */
public final class ToolsView {

    private ToolsView() { }

    /**
     * @param filter 关键字（在 name/help 里匹配，大小写不敏感）；null/空 = 全部
     * @param coreOnly 只返回"核心工具集"（建议给 agent 常驻的那十几个），其余走过滤检索
     * @param verbose true=完整元数据（调试/文档用）；false=精简（默认，给 agent 用，省 token）
     */
    public static String list(String filter, boolean coreOnly, boolean verbose) {
        QueryRegistry registry = QueryRegistry.instance();
        String f = filter == null ? null : filter.toLowerCase();

        List<QueryDescriptor> all = new ArrayList<>(registry.getQueries());
        Json tools = Json.arr();
        int count = 0;
        for (QueryDescriptor q : all) {
            String id = safe(q.getIdentifier());
            if (id.isEmpty()) continue;
            if (coreOnly && !CORE_TOOLS.contains(id)) continue;
            if (f != null && !matches(q, f)) continue;
            tools.item(verbose ? renderToolVerbose(q) : renderToolCompact(q));
            count++;
        }
        return Json.obj()
                .raw("tools", tools.endArr())
                .kv("count", count)
                .kv("coreOnly", coreOnly)
                .kv("verbose", verbose)
                .endObj();
    }

    private static boolean matches(QueryDescriptor q, String f) {
        if (safe(q.getIdentifier()).toLowerCase().contains(f)) return true;
        if (safe(q.getName()).toLowerCase().contains(f)) return true;
        String help = safe(q.getHelp());
        return help.toLowerCase().contains(f);
    }

    /**
     * 精简版（默认，给 agent）：只给选工具/调用必需的最少信息。
     * - command：调用名（POST /query 用）
     * - desc：help 的第一句（≤120 字），够 agent 判断用途
     * - args：只列<b>agent 真正要填的参数</b>——过滤掉 snapshot（内部参数，我们用 dumpDir 定位）；
     *         每个参数只给 name/type/required(+ 可选 advice)，<b>不给 default/help</b>（省 token）。
     */
    private static String renderToolCompact(QueryDescriptor q) {
        Json args = Json.arr();
        for (ArgumentDescriptor a : q.getArguments()) {
            String an = safe(a.getName());
            if (isInternalArg(an)) continue;        // 过滤 snapshot 等内部参数
            Json one = Json.obj()
                    .kv("name", an)
                    .kv("type", jsonType(a))
                    .kv("required", a.isMandatory());
            String adv = adviceName(a);
            if (adv != null) one.kv("advice", adv);
            args.item(one.endObj());
        }
        return Json.obj()
                .kv("command", q.getIdentifier())
                .kv("desc", firstSentence(safe(q.getHelp()), 120))
                .raw("args", args.endArr())
                .endObj();
    }

    /** 完整版（?verbose=true）：给调试/文档/工具检索，含全部元数据。 */
    private static String renderToolVerbose(QueryDescriptor q) {
        Json args = Json.arr();
        for (ArgumentDescriptor a : q.getArguments()) {
            String an = safe(a.getName());
            if (isInternalArg(an)) continue;
            Json o = Json.obj()
                    .kv("name", an)
                    .kv("type", jsonType(a))
                    .kv("required", a.isMandatory());
            String help = a.getHelp();
            if (help != null && !help.isBlank()) o.kv("help", trim(decode(help), 200));
            String adv = adviceName(a);
            if (adv != null) o.kv("advice", adv);
            Object def = a.getDefaultValue();
            if (def != null) o.kv("default", trim(decode(String.valueOf(def)), 120));
            args.item(o.endObj());
        }
        return Json.obj()
                .kv("command", q.getIdentifier())
                .kv("name", cleanName(safe(q.getName()), q.getIdentifier()))
                .kv("help", trim(decode(safe(q.getHelp())), 400))
                .kv("category", cleanCategory(safe(q.getCategory())))
                .raw("args", args.endArr())
                .endObj();
    }

    private static String adviceName(ArgumentDescriptor a) {
        try {
            Argument.Advice advice = a.getAdvice();
            if (advice != null && advice != Argument.Advice.NONE) return advice.name();
        } catch (Throwable ignore) { }
        return null;
    }

    /** MAT 内部/由本服务代填的参数，agent 不该看到。 */
    private static boolean isInternalArg(String name) {
        return name.equals("snapshot")        // 我们用 dumpDir 定位，不暴露
            || name.equals("contexts")        // 内部上下文
            || name.equals("subject");        // 内部
    }

    /** help 取第一句（到第一个句号/换行），再截断——给 agent 选工具够用。 */
    private static String firstSentence(String s, int max) {
        if (s == null || s.isEmpty()) return "";
        s = decode(s).replaceAll("\\s+", " ").trim();
        int end = -1;
        for (String stop : new String[]{". ", "。", "\n"}) {
            int i = s.indexOf(stop);
            if (i > 0 && (end < 0 || i < end)) end = i + 1;
        }
        if (end > 0) s = s.substring(0, end).trim();
        return s.length() <= max ? s : s.substring(0, max).trim() + "…";
    }

    /** name 脏值兜底（有的 name 是菜单项名如 "exclude custom fields..."）→ 退回 identifier。 */
    private static String cleanName(String name, String id) {
        if (name.isEmpty() || name.toLowerCase().contains("custom fields")
                || name.toLowerCase().startsWith("exclude ")) {
            return id;
        }
        return name;
    }

    /** category 脏值清理（__hidden__ / 数字前缀 "3|xxx" → 去掉）。 */
    private static String cleanCategory(String c) {
        if (c.equals("__hidden__") || c.isEmpty()) return "";
        int bar = c.indexOf('|');
        if (bar >= 0 && c.substring(0, bar).matches("\\d+")) return c.substring(bar + 1);
        return c;
    }

    /** 解码 MAT 文本里的 HTML 实体（&lt; 等）。 */
    private static String decode(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
    }

    /** MAT 参数类型 → LLM 友好的 JSON schema 类型提示。 */
    private static String jsonType(ArgumentDescriptor a) {
        if (a.isBoolean()) return "boolean";
        if (a.isEnum())    return "enum";
        Class<?> t = a.getType();
        boolean numeric = t == int.class || t == Integer.class || t == long.class
                || t == Long.class || t == double.class || t == Double.class;
        String base;
        if (numeric) base = "number";
        else if (t == String.class) base = "string";
        else {
            // 堆对象参数通常是 int[]/IObject → 对外统一表达为 object id
            base = "objectId";
        }
        return a.isMultiple() ? (base + "[]") : base;
    }

    /**
     * 核心工具集（建议给 agent 常驻的最常用工具，文档04 §4.3 分级暴露）。
     * 其余 ~40 个走 GET /tools?filter= 按需检索，避免一次性撑爆 agent 的 context。
     */
    private static final java.util.Set<String> CORE_TOOLS = java.util.Set.of(
            "histogram",
            "dominator_tree",
            "thread_overview",
            "oql",
            "leakhunter",
            "path2gc",
            "merge_shortest_paths",
            "top_consumers",
            "show_retained_set",
            "duplicate_classes",
            "gc_roots",
            "find_strings"
    );

    private static String safe(String s) { return s == null ? "" : s; }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
