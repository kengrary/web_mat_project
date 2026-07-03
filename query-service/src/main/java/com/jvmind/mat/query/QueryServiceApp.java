package com.jvmind.mat.query;

import com.jvmind.mat.query.core.DumpLocator;
import com.jvmind.mat.query.core.MemoryWatcher;
import com.jvmind.mat.query.core.SnapshotManager;
import com.jvmind.mat.query.http.QueryHttpServer;
import com.jvmind.mat.query.task.TaskManager;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

/**
 * 路线2：query-service 作为 OSGi bundle，以 {@link IApplication} 方式由 Equinox 启动。
 *
 * <p><b>为什么是路线2（实测结论，见 EQUINOX-A-VERIFICATION.md）</b>：
 * 把本服务做成 bundle 并 {@code Require-Bundle: org.eclipse.mat.api} 后，本 bundle 与 MAT 处于
 * <b>同一 OSGi 类空间</b>，可以直接 {@code import org.eclipse.mat.snapshot.SnapshotFactory} 等——
 * 避免了 A 方案"容器外 AppClassLoader 看不到 bundle 类"的 ClassNotFoundException。
 * SnapshotManager / View / QueryRunner 的现有 import 全部保留，无需改成反射。
 *
 * <p>启动流程：Equinox 起容器 → 解析本 bundle 的 Require-Bundle（自动激活 MAT 4 个 bundle，
 * registry 就绪）→ 调本 application 的 start() → new QueryHttpServer。
 * 无需我们手写 EquinoxBootstrap / loadMatClass（那是路线1的产物，路线2用不到）。
 *
 * <p>注册见 plugin.xml 的 {@code org.eclipse.core.runtime.applications} 扩展。
 * 启动命令（headless）：
 * <pre>
 *   ./MemoryAnalyzer -consoleLog -nosplash -application com.jvmind.mat.query.app
 *       -vmargs -Xmx16g -DMAT_QUERY_PORT=8090
 * </pre>
 * 配置走 System property（-D...）或环境变量，键同原 Main。
 */
public final class QueryServiceApp implements IApplication {

    private QueryHttpServer http;
    private MemoryWatcher watcher;
    private TaskManager tasks;
    private volatile boolean stop = false;

    @Override
    public Object start(IApplicationContext context) throws Exception {
        // 绑定地址：默认 127.0.0.1(仅本机，最安全)；跨机访问设 0.0.0.0(所有网卡)
        // ⚠️ 本服务无鉴权，设 0.0.0.0 时务必靠防火墙/安全组/网络ACL 限制来源，只放行 Python 主控机
        String bindAddr = strProp("MAT_QUERY_BIND", "127.0.0.1");
        int port        = intProp("MAT_QUERY_PORT", 8090);
        int threads     = intProp("MAT_QUERY_THREADS", 16);
        int maxOpen     = intProp("MAT_QUERY_MAX_OPEN", 4);
        int heapPct     = intProp("MAT_QUERY_HEAP_PCT", 85);
        int taskThreads = intProp("MAT_QUERY_TASK_THREADS", 2);
        int taskTimeout = intProp("MAT_QUERY_TASK_TIMEOUT", 300);

        // 此时已在 OSGi 内、MAT registry 就绪：可直接用 MAT API（SnapshotManager 内部 import SnapshotFactory）
        DumpLocator locator = new DumpLocator.ByExplicitDir();
        SnapshotManager manager = new SnapshotManager(locator, maxOpen);
        tasks = new TaskManager(taskThreads, taskTimeout);
        watcher = new MemoryWatcher(manager, heapPct, /*idleMillis*/ 60_000);
        watcher.start();
        http = new QueryHttpServer(manager, tasks, bindAddr, port, threads);
        http.start();

        System.out.println("[query-service] started on " + bindAddr + ":" + port + " (OSGi application)");

        // application 保持运行，直到 stop() 被调用（headless 长驻服务）
        synchronized (this) {
            while (!stop) {
                this.wait();
            }
        }
        return IApplication.EXIT_OK;
    }

    @Override
    public void stop() {
        synchronized (this) {
            stop = true;
            this.notifyAll();
        }
        if (http != null) http.stop();
        if (watcher != null) watcher.stop();
        if (tasks != null) tasks.shutdown();
        System.out.println("[query-service] stopped");
    }

    private static int intProp(String key, int def) {
        String v = strProp(key, null);
        if (v == null || v.isBlank()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    /** 配置优先级：-D系统属性 > 环境变量 > 默认值。 */
    private static String strProp(String key, String def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }
}
