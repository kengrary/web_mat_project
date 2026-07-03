package com.jvmind.mat.query.equinox;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 【路线1专用 / 路线2 下不使用】进程内启动 Equinox/OSGi。
 *
 * <p><b>我们已选路线2</b>（query-service 自身做成 OSGi bundle，入口 {@code QueryServiceApp}），
 * 路线2 下由 MAT launcher 起容器，本类完全用不到。本类仅在"坚持独立 jar = 路线1（全反射）"时才需要，
 * 配合 {@link #loadMatClass(String)} 桥接。保留以备路线1，正常落地请忽略本类。
 *
 * <hr/>
 * A 方案（路线1）：进程内启动 Equinox/OSGi，使 MAT 的 extension registry 就绪。
 *
 * <p><b>为什么必须这样</b>（读 MAT 源码确认）：MAT 用 {@code Platform.getExtensionRegistry()}
 * 发现 hprof 解析器（{@code org.eclipse.mat.parser.parser} 扩展点）。而 ParserPlugin/ReportPlugin
 * 是 bundle activator——只有这些 bundle 被 install + start，extension 才会注册、registry 才有内容。
 * 脱离 OSGi 直接 SnapshotFactory.openSnapshot → registry 空 → 找不到解析器 → 失败。
 *
 * <p><b>实现</b>：用<b>反射</b>调 {@code org.eclipse.core.runtime.adaptor.EclipseStarter}
 * （API 已核对：setInitialProperties / startup / isRunning / shutdown），
 * 避免本类编译期强依赖 org.eclipse.osgi（它只在运行期 classpath/install area 提供）。
 *
 * <p><b>启动后</b>：确保 MAT 4 个 bundle（api/report/parser/hprof）处于 ACTIVE。
 * 用 simpleconfigurator + MAT 自带 bundles.info 加载全部 bundle（已实测，比手写 osgi.bundles 稳），
 * 拿到 bundle 后按需 {@code bundle.start()}（MAT bundle 是 lazy activation，state 可能是 STARTING）。
 *
 * <p><b>★ 关键（实测发现，见 EQUINOX-A-VERIFICATION.md）</b>：OSGi 里 bundle 类被 bundle classloader
 * 隔离，容器<b>外部</b>的代码 {@code Class.forName("org.eclipse.mat...")} 会 ClassNotFoundException。
 * 因此本类提供 {@link #loadMatClass(String)}：通过 mat.api bundle 的 classloader 加载 MAT 类。
 * 路线1（全反射）的 View/QueryRunner 必须经此拿 SnapshotFactory 等；
 * 路线2（query-service 自身 bundle 化）则可直接 import，无需本桥。
 *
 * <p>线程模型：EclipseStarter 是进程级单例（singleton-global），全进程只启动一次。
 */
public final class EquinoxBootstrap {

    private static final Logger LOG = Logger.getLogger(EquinoxBootstrap.class.getName());

    /** MAT 解析必须 ACTIVE 的核心 bundle（注册 extension 的关键）。 */
    private static final String[] REQUIRED_BUNDLES = {
            "org.eclipse.mat.report",
            "org.eclipse.mat.api",
            "org.eclipse.mat.parser",
            "org.eclipse.mat.hprof",
    };

    private static volatile boolean started = false;

    private EquinoxBootstrap() { }

    /**
     * 起 Equinox（幂等，进程内只起一次）。
     *
     * @param matHome   MAT 发行版根目录（含 plugins/，放着所有 bundle jar）
     * @param configDir 可写的配置目录（Equinox 缓存/状态），如 /tmp/mat-equinox-config
     */
    public static synchronized void start(File matHome, File configDir) throws Exception {
        if (started || isRunning()) {
            started = true;
            return;
        }
        File plugins = new File(matHome, "plugins");
        if (!plugins.isDirectory()) {
            throw new IllegalStateException("MAT plugins dir not found: " + plugins
                    + " (set MAT_HOME to the unpacked MAT distribution)");
        }
        configDir.mkdirs();

        Class<?> starter = Class.forName("org.eclipse.core.runtime.adaptor.EclipseStarter");

        Map<String, String> props = buildInitProps(plugins, configDir);

        Method setProps = starter.getMethod("setInitialProperties", Map.class);
        setProps.invoke(null, props);

        LOG.info("Starting embedded Equinox (install=" + plugins + ")");
        Method startup = starter.getMethod("startup", String[].class, Runnable.class);
        // BundleContext systemCtx =
        startup.invoke(null, new String[0], null);

        // 校验 MAT bundle 已 ACTIVE（osgi.bundles 已声明 @start，这里确认 + 必要时手动 start）
        ensureBundlesActive(starter);

        started = true;
        LOG.info("Equinox started, MAT extension registry ready.");
    }

    /** 关闭容器（进程退出钩子调用）。 */
    public static synchronized void stop() {
        if (!started) return;
        try {
            Class<?> starter = Class.forName("org.eclipse.core.runtime.adaptor.EclipseStarter");
            starter.getMethod("shutdown").invoke(null);
            LOG.info("Equinox stopped.");
        } catch (Throwable t) {
            LOG.warning("Equinox shutdown error: " + t);
        } finally {
            started = false;
        }
    }

    public static boolean isRunning() {
        try {
            Class<?> starter = Class.forName("org.eclipse.core.runtime.adaptor.EclipseStarter");
            return (boolean) starter.getMethod("isRunning").invoke(null);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Map<String, String> buildInitProps(File plugins, File configDir) {
        File matHome = plugins.getParentFile();
        Map<String, String> p = new HashMap<>();
        // install area = MAT 发行版根（其 plugins/ 下是所有 bundle）
        p.put("osgi.install.area", matHome.toURI().toString());
        p.put("osgi.configuration.area", configDir.toURI().toString());
        // headless：不跑 application、不随 application 退出而关容器
        p.put("eclipse.ignoreApp", "true");
        p.put("osgi.noShutdown", "true");
        // 每次干净启动，避免旧缓存导致 bundle 状态异常
        p.put("osgi.clean", "true");
        p.put("osgi.console", "");

        // ★ 实测正解：用 simpleconfigurator + MAT 自带 bundles.info 自动加载全部 bundle 闭包，
        //   而非手写 osgi.bundles 列表（手写易漏依赖）。见 EQUINOX-A-VERIFICATION.md。
        File sc = findBundleJarFile(plugins, "org.eclipse.equinox.simpleconfigurator");
        File bundlesInfo = new File(matHome,
                "configuration/org.eclipse.equinox.simpleconfigurator/bundles.info");
        File osgiFw = findBundleJarFile(plugins, "org.eclipse.osgi");
        if (sc != null && bundlesInfo.isFile()) {
            p.put("osgi.bundles", "reference:file:" + sc.getName() + "@1:start");
            p.put("org.eclipse.equinox.simpleconfigurator.configUrl", bundlesInfo.toURI().toString());
            if (osgiFw != null) p.put("osgi.framework", osgiFw.toURI().toString());
        } else {
            // 回退：发行版无 simpleconfigurator 时，手写最小 bundle 列表（旧逻辑）
            LOG.warning("simpleconfigurator/bundles.info not found, falling back to manual osgi.bundles");
            p.put("osgi.bundles", buildOsgiBundles(plugins));
        }
        return p;
    }

    private static File findBundleJarFile(File plugins, String prefix) {
        String name = findBundleJar(plugins, prefix);
        return name == null ? null : new File(name);
    }

    /**
     * ★ 通过 mat.api bundle 的 classloader 加载 MAT 类（解决容器外 AppClassLoader 的 CNF）。
     * 路线1（全反射）下，QueryRunner/各 View 必须用它拿 SnapshotFactory/ISnapshot/SnapshotQuery 等。
     * 实测验证：bundle.loadClass("org.eclipse.mat.snapshot.SnapshotFactory") 成功。
     */
    public static Class<?> loadMatClass(String fqn) throws Exception {
        Class<?> starter = Class.forName("org.eclipse.core.runtime.adaptor.EclipseStarter");
        Object ctx = starter.getMethod("getSystemBundleContext").invoke(null);
        Object[] bundles = (Object[]) findMethod(ctx.getClass(), "getBundles").invoke(ctx);
        Object apiBundle = findBundle(bundles, "org.eclipse.mat.api");
        if (apiBundle == null) throw new IllegalStateException("org.eclipse.mat.api bundle not found");
        Method loadClass = apiBundle.getClass().getMethod("loadClass", String.class);
        return (Class<?>) loadClass.invoke(apiBundle, fqn);
    }

    /**
     * 构造 osgi.bundles 列表：core runtime 一族 + MAT 4 个 bundle，都标 @start。
     * 用通配匹配 plugins/ 下的实际 jar 文件名（带版本号），找到则加入。
     */
    private static String buildOsgiBundles(File plugins) {
        // 这些 bundle 名前缀需要在 plugins/ 里找到对应 jar。core.runtime 会传递拉起其依赖闭包，
        // 但显式列出关键的更稳。start level：runtime 先于 MAT。
        String[] runtime = {
                "org.eclipse.equinox.common",
                "org.eclipse.equinox.registry",
                "org.eclipse.equinox.preferences",
                "org.eclipse.equinox.app",
                "org.eclipse.core.runtime",
                "org.eclipse.core.contenttype",
                "org.eclipse.core.jobs",
                "org.eclipse.core.resources",
                "org.eclipse.core.commands",
                "org.eclipse.core.expressions",
        };
        List<String> entries = new ArrayList<>();
        for (String b : runtime) {
            String jar = findBundleJar(plugins, b);
            if (jar != null) entries.add("reference:file:" + jar + "@2:start");
        }
        for (String b : REQUIRED_BUNDLES) {
            String jar = findBundleJar(plugins, b);
            if (jar != null) entries.add("reference:file:" + jar + "@4:start");
            else LOG.warning("required MAT bundle jar not found in plugins/: " + b);
        }
        return String.join(",", entries);
    }

    /** 在 plugins/ 找形如 <prefix>_<version>.jar 的文件，返回其文件名。 */
    private static String findBundleJar(File plugins, String prefix) {
        File[] hits = plugins.listFiles((dir, name) ->
                name.startsWith(prefix + "_") && name.endsWith(".jar"));
        if (hits == null || hits.length == 0) return null;
        // 多版本取最新
        String best = hits[0].getName();
        for (File f : hits) if (f.getName().compareTo(best) > 0) best = f.getName();
        return new File(plugins, best).getAbsolutePath();
    }

    /** 校验 MAT bundle ACTIVE；若仅 RESOLVED 则手动 start（触发 activator 注册 extension）。 */
    private static void ensureBundlesActive(Class<?> starter) throws Exception {
        Object systemCtx = starter.getMethod("getSystemBundleContext").invoke(null);
        if (systemCtx == null) {
            throw new IllegalStateException("system bundle context null after startup");
        }
        Class<?> ctxClass = systemCtx.getClass();
        Method getBundles = findMethod(ctxClass, "getBundles");
        Object[] bundles = (Object[]) getBundles.invoke(systemCtx);

        for (String need : REQUIRED_BUNDLES) {
            Object bundle = findBundle(bundles, need);
            if (bundle == null) {
                throw new IllegalStateException("MAT bundle not installed: " + need
                        + " (check osgi.bundles / plugins dir)");
            }
            int state = (int) bundle.getClass().getMethod("getState").invoke(bundle);
            final int ACTIVE = 32, RESOLVED = 4, STARTING = 8;
            if (state != ACTIVE && state != STARTING) {
                // 手动 start（lazy activation 的 bundle 可能还没 ACTIVE）
                try {
                    bundle.getClass().getMethod("start").invoke(bundle);
                } catch (Exception e) {
                    LOG.warning("failed to start bundle " + need + ": " + e);
                }
            }
        }
    }

    private static Object findBundle(Object[] bundles, String symbolicNamePrefix) throws Exception {
        for (Object b : bundles) {
            Object sn = b.getClass().getMethod("getSymbolicName").invoke(b);
            if (sn != null && sn.toString().startsWith(symbolicNamePrefix)) return b;
        }
        return null;
    }

    private static Method findMethod(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getMethods()) if (m.getName().equals(name)) return m;
        }
        throw new IllegalStateException("method not found: " + name);
    }
}
