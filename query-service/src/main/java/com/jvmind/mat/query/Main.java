package com.jvmind.mat.query;

/**
 * 【已废弃 / 仅路线1参考】独立 jar 进程内起 Equinox 的入口。
 *
 * <p><b>实测证明这条路在 A 方案下不可行</b>（见 EQUINOX-A-VERIFICATION.md）：
 * 容器外的 AppClassLoader 看不到 bundle 里的 MAT 类 → 直接 import/调用 SnapshotFactory 会
 * ClassNotFoundException。若坚持独立 jar，必须走"路线1：全反射 + EquinoxBootstrap.loadMatClass"。
 *
 * <p><b>已采用路线2</b>：query-service 自身做成 OSGi bundle，入口是 {@link QueryServiceApp}
 * （IApplication），由 MAT/Equinox launcher 启动。那样与 MAT 同类空间，现有 import 全部可用。
 *
 * <p>本类保留仅作路线1的占位参考，<b>路线2 下不使用</b>。生产请用：
 * <pre>
 *   ./MemoryAnalyzer -consoleLog -nosplash -application com.jvmind.mat.query.app -vmargs -Xmx16g
 * </pre>
 */
@Deprecated
public final class Main {
    public static void main(String[] args) {
        System.err.println("Main is deprecated. Use the OSGi application 'com.jvmind.mat.query.app' "
                + "(see QueryServiceApp / README route 2). For standalone jar, switch to route 1 (full reflection).");
        System.exit(2);
    }
}
