package com.jvmind.mat.query.view;

import com.jvmind.mat.query.model.Json;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.SnapshotInfo;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IObject;

import java.io.File;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Collection;

/**
 * 概览视图。三个用途：
 * 1) {@link #toJson}：精简版，给 V1 解析回填 stats（heapdump_reports.stats）—— 保持轻量。
 * 2) {@link #toJsonFull}：完整版，前端"概览页"数据源 —— Heap Dump 内部信息 + JVM System Properties。
 *
 * 对应 MAT 的 "Heap Dump Overview" + "System Properties"（读源码确认字段）。
 */
public final class OverviewView {

    private OverviewView() { }

    /** 精简版（回填 stats 用，字段直接来自 SnapshotInfo）。 */
    public static String toJson(ISnapshot snapshot) {
        SnapshotInfo info = snapshot.getSnapshotInfo();
        Json j = baseInfo(snapshot, info);
        return j.endObj();
    }

    /** 完整版（概览页）：基础信息 + heap dump 内部细节 + System Properties。 */
    public static String toJsonFull(ISnapshot snapshot) {
        SnapshotInfo info = snapshot.getSnapshotInfo();
        Json j = baseInfo(snapshot, info);

        // ---- Heap Dump 内部信息（MAT HeapDumpInfoQuery）----
        prop(j, info, "heapFormat", "$heapFormat");
        Serializable oops = info.getProperty("$useCompressedOops");
        if (oops != null) j.kv("useCompressedOops", oops.toString());
        Serializable runtimeId = info.getProperty("$runtimeId");
        if (runtimeId != null) j.kv("snapshotIdentifier", runtimeId.toString());

        if (info.getPath() != null) {
            j.kv("filePath", info.getPath());
            try {
                long len = new File(info.getPath()).length();
                j.kv("fileLengthBytes", len).kv("fileLength", Json.humanBytes(len));
            } catch (Throwable ignore) { }
        }

        // ---- JVM System Properties（从堆里取 java.lang.System.props）----
        j.raw("systemProperties", systemProperties(snapshot));

        return j.endObj();
    }

    private static Json baseInfo(ISnapshot snapshot, SnapshotInfo info) {
        Json j = Json.obj()
                .kv("numObjects", info.getNumberOfObjects())
                .kv("numClasses", info.getNumberOfClasses())
                .kv("numClassLoaders", info.getNumberOfClassLoaders())
                .kv("numGcRoots", info.getNumberOfGCRoots())
                .kv("usedHeapSize", info.getUsedHeapSize())
                .kv("usedHeapSizeHuman", Json.humanBytes(info.getUsedHeapSize()))
                .kv("identifierSize", info.getIdentifierSize());
        if (info.getJvmInfo() != null) j.kv("jvmInfo", info.getJvmInfo());
        if (info.getCreationDate() != null) {
            j.kv("creationDate", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(info.getCreationDate()));
        }
        return j;
    }

    private static void prop(Json j, SnapshotInfo info, String outKey, String propKey) {
        Serializable v = info.getProperty(propKey);
        if (v != null) j.kv(outKey, v.toString());
    }

    /**
     * 从堆里提取 JVM System Properties（java.version / os.name / 启动相关等）。
     * MAT 做法：java.lang.System 类的静态字段 props（新 JDK 可能是 systemProperties）→ 一个 Map。
     * 用 MAT 的 CollectionExtractionUtils.extractMap 提取（跨 JDK / 跨 Map 类型，最稳）。
     */
    private static String systemProperties(ISnapshot snapshot) {
        Json arr = Json.arr();
        try {
            Collection<IClass> classes = snapshot.getClassesByName("java.lang.System", false);
            if (classes == null || classes.isEmpty()) return arr.endArr();
            IClass sys = classes.iterator().next();
            Object props = sys.resolveValue("props");
            if (props == null) props = sys.resolveValue("systemProperties");
            if (!(props instanceof IObject mapObj)) return arr.endArr();

            org.eclipse.mat.inspections.collectionextract.ExtractedMap map =
                    org.eclipse.mat.inspections.collectionextract.CollectionExtractionUtils.extractMap(mapObj);
            if (map == null) return arr.endArr();

            java.util.TreeMap<String, String> sorted = new java.util.TreeMap<>();
            int n = 0;
            for (java.util.Map.Entry<IObject, IObject> e : map) {
                if (n++ > 1000) break;   // 防异常大
                IObject k = e.getKey(), v = e.getValue();
                String ks = k != null ? safeName(k) : "null";
                String vs = v != null ? safeName(v) : "null";
                sorted.put(ks, vs);
            }
            for (var e : sorted.entrySet()) {
                arr.item(Json.obj().kv("name", e.getKey()).kv("value", e.getValue()).endObj());
            }
        } catch (Throwable ignore) {
            // 取不到不致命（某些 dump 无 System.props / 结构特殊 / 集合提取器不识别）
        }
        return arr.endArr();
    }

    /** String 对象取文本内容，其它取 displayName。 */
    private static String safeName(IObject o) {
        try {
            String s = o.getClassSpecificName();
            if (s != null) return s;
        } catch (Throwable ignore) { }
        return ObjectRows.safeDisplayName(o);
    }
}
