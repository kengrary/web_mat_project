package com.jvmind.mat.query.view;

import com.jvmind.mat.query.model.Json;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.IObject;

/**
 * 把 objectId 渲染成统一的"瘦身对象行" JSON —— 支配树/线程局部变量/OQL/引用 等多视图共用。
 *
 * <p>出参瘦身原则（文档 04/06）：
 * <ul>
 *   <li>对象用 <b>objectId</b>(int) 作稳定游标，地址只在详情给；</li>
 *   <li>label 用 MAT resolver 的 displayName（如连接池显示成 HikariPool[active=48/50]）；</li>
 *   <li>字节数附人性化字符串；</li>
 *   <li>retained 用支配树 O(1) 查表（getRetainedHeapSize），不做全图扫描。</li>
 * </ul>
 */
public final class ObjectRows {

    private ObjectRows() { }

    /** 单个对象 → JSON 片段（不含外层逗号，由调用方放进数组）。 */
    public static String row(ISnapshot snapshot, int objectId) throws Exception {
        IObject o = snapshot.getObject(objectId);
        long shallow = o.getUsedHeapSize();
        long retained = o.getRetainedHeapSize();   // O(1) 查 o2ret 索引（支配树已算）

        boolean expandable = snapshot.getImmediateDominatedIds(objectId).length > 0;

        return Json.obj()
                .kv("objectId", objectId)
                .kv("label", safeDisplayName(o))
                .kv("type", o.getClazz() != null ? o.getClazz().getName() : "?")
                .kv("shallowBytes", shallow)
                .kv("shallow", Json.humanBytes(shallow))
                .kv("retainedBytes", retained)
                .kv("retained", Json.humanBytes(retained))
                .kv("expandable", expandable)   // 支配树视图用：是否还有被它支配的子节点
                .endObj();
    }

    /** displayName 偶尔会抛/慢，做兜底。 */
    static String safeDisplayName(IObject o) {
        try {
            String n = o.getDisplayName();
            if (n != null && !n.isEmpty()) return n;
        } catch (Throwable ignore) {
            // resolver 可能对损坏对象抛异常
        }
        try {
            return o.getTechnicalName();
        } catch (Throwable ignore) {
            return "object @ " + Long.toHexString(o.getObjectAddress());
        }
    }
}
