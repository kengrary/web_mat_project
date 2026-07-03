package com.jvmind.mat.parser;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 方式 A：在最小化的 Equinox/OSGi 运行时里执行 MAT 解析。
 *
 * <p><b>为什么需要 OSGi</b>：MAT 用 Eclipse extension point 注册 hprof 解析器
 * （{@code org.eclipse.mat.parser.parser} 扩展点，HprofIndexBuilder/HprofHeapObjectReader）。
 * {@code SnapshotFactory.openSnapshot} 内部通过 ParserRegistry 反射发现这些扩展，
 * 而 ParserRegistry 依赖 Eclipse 的 IExtensionRegistry —— 它需要 OSGi 运行时初始化。
 * 脱离 OSGi 直接调（方式 B）时，registry 为空 → 找不到解析器 → 解析失败。
 *
 * <p><b>本类的两种落地实现（择一，取决于打包方式）</b>：
 *
 * <h3>实现 1：进程内启动 Equinox（EclipseStarter）</h3>
 * 用 {@code org.eclipse.core.runtime.adaptor.EclipseStarter.startup(...)} 起一个 headless
 * OSGi 容器，把 MAT 的 4 个 bundle（api/report/parser/hprof）+ 其依赖装进 install area，
 * 容器起来后 extension registry 就绪，再调 {@link HeapDumpParser#parse}。
 * <pre>
 *   Map&lt;String,String&gt; p = new HashMap&lt;&gt;();
 *   p.put("osgi.configuration.area", configDir);
 *   p.put("osgi.install.area", installDir);     // 含 plugins/ 放 MAT bundle
 *   p.put("eclipse.ignoreApp", "true");
 *   p.put("osgi.noShutdown", "true");
 *   BundleContext ctx = EclipseStarter.startup(new String[0], null);
 *   // 确保 MAT bundle 已 RESOLVED/ACTIVE，再执行解析
 * </pre>
 *
 * <h3>实现 2：委托给 MAT 自带的 application（最省事，推荐先用）</h3>
 * MAT 已提供命令行 application {@code org.eclipse.mat.api.parse}（见 ParseSnapshotApp）。
 * 最稳的做法是 <b>fork 一个标准 MAT 启动</b>：用 MAT 发行版的 launcher
 * （{@code org.eclipse.equinox.launcher} 的 Main）以 headless 方式运行该 application：
 * <pre>
 *   java -jar plugins/org.eclipse.equinox.launcher_*.jar
 *        -consoleLog -nosplash
 *        -application org.eclipse.mat.api.parse
 *        &lt;hprof&gt;
 *        -vmargs -Xmx32g
 * </pre>
 * 但 ParseSnapshotApp 的进度走 ConsoleProgressListener（文本），不是我们的 JSONL。
 * 因此推荐：<b>不直接用 ParseSnapshotApp，而是用实现 1 起容器后调我们自己的
 * {@link HeapDumpParser}</b>（这样进度才是结构化 JSONL）。
 *
 * <p>本骨架在此给出"起容器 + 调 HeapDumpParser"的占位流程；
 * 具体 EclipseStarter 接线依赖你最终的打包形态（是否把 MAT 作为 OSGi bundle 部署），
 * 落地时填充 TODO 处即可。
 */
public final class EquinoxLauncher {

    private EquinoxLauncher() { }

    public static JsonObject parse(CliOptions opts, ProgressSink sink) throws Exception {
        boolean started = startEquinoxIfNeeded(sink);
        try {
            // 容器就绪后，extension registry 可用，直接复用核心解析逻辑
            return new HeapDumpParser(sink).parse(opts);
        } finally {
            if (started) {
                stopEquinox(sink);
            }
        }
    }

    /**
     * 起最小 Equinox 容器，确保 MAT bundle 已激活、extension registry 就绪。
     *
     * @return true 表示由本进程启动了容器（需在结束时关闭）
     */
    private static boolean startEquinoxIfNeeded(ProgressSink sink) throws Exception {
        // 若已在 OSGi 环境内运行（例如被嵌进更大的容器），无需重复启动
        if (isOsgiRunning()) {
            return false;
        }

        info(sink, "Starting embedded Equinox runtime...");

        // ============ TODO（落地时按打包形态填充）============
        // 推荐用反射调用 EclipseStarter，避免在非 OSGi classpath 下编译期强依赖：
        //
        //   Class<?> starter = Class.forName("org.eclipse.core.runtime.adaptor.EclipseStarter");
        //   Map<String,String> initProps = osgiInitProps();
        //   starter.getMethod("setInitialProperties", Map.class).invoke(null, initProps);
        //   Object ctx = starter.getMethod("startup", String[].class, Runnable.class)
        //                       .invoke(null, new String[0], null);
        //   // 等待/确保 org.eclipse.mat.hprof 等 bundle 处于 ACTIVE
        //
        // initProps 关键项：
        //   osgi.install.area   → 含 plugins/ 目录（放 MAT 4 个 bundle + 依赖）
        //   osgi.configuration.area → 可写的临时配置目录
        //   eclipse.ignoreApp=true, osgi.noShutdown=true
        //   osgi.bundles=...（org.eclipse.core.runtime 等以 start level 自动启动）
        // ====================================================

        throw new UnsupportedOperationException(
                "EquinoxLauncher.startEquinoxIfNeeded() 尚未接线。\n"
              + "落地选项：\n"
              + "  (a) 填充上方 EclipseStarter 反射启动代码，把 MAT 4 个 bundle 放进 osgi.install.area/plugins；\n"
              + "  (b) 或临时改用 --mode direct（需保证 extension registry 以其它方式初始化）；\n"
              + "  (c) 或在外层直接用 MAT 发行版 launcher 运行 org.eclipse.mat.api.parse（进度为文本，非 JSONL）。");
    }

    private static void stopEquinox(ProgressSink sink) {
        try {
            Class<?> starter = Class.forName("org.eclipse.core.runtime.adaptor.EclipseStarter");
            starter.getMethod("shutdown").invoke(null);
            info(sink, "Equinox runtime stopped.");
        } catch (Throwable t) {
            System.err.println("[parser-cli] equinox shutdown warning: " + t);
        }
    }

    private static boolean isOsgiRunning() {
        try {
            Class<?> fu = Class.forName("org.osgi.framework.FrameworkUtil");
            Object bundle = fu.getMethod("getBundle", Class.class).invoke(null, EquinoxLauncher.class);
            return bundle != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("unused")
    private static Map<String, String> osgiInitProps() {
        Map<String, String> p = new HashMap<>();
        p.put("eclipse.ignoreApp", "true");
        p.put("osgi.noShutdown", "true");
        // p.put("osgi.install.area", ...);        // 落地填充
        // p.put("osgi.configuration.area", ...);  // 落地填充
        return p;
    }

    private static void info(ProgressSink sink, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "message");
        o.addProperty("severity", "INFO");
        o.addProperty("text", msg);
        sink.write(o);
    }
}
