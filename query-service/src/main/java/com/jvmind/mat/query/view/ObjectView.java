package com.jvmind.mat.query.view;

import com.jvmind.mat.query.model.Json;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.Field;
import org.eclipse.mat.snapshot.model.GCRootInfo;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IInstance;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IPrimitiveArray;
import org.eclipse.mat.snapshot.model.IObjectArray;
import org.eclipse.mat.snapshot.model.NamedReference;

import java.util.List;

/**
 * 对象详情视图 —— Inspector 面板的后端（文档 09 §5）。
 *
 * <p>MAT Inspector 的核心：选中对象 → 看它的
 * <b>基本信息 + 字段值 + 出引用 + 入引用 + 是否 GC root</b>。本视图一次返回这些（引用各取前 N，懒加载更多）。
 *
 * <p>用到的 MAT API（已核对）：
 * {@code getObject / getClazz / getObjectAddress / getUsedHeapSize / getRetainedHeapSize /
 *  getGCRootInfo / getOutboundReferences(NamedReference) / IInstance.getFields(Field) /
 *  ISnapshot.getInboundRefererIds}。
 */
public final class ObjectView {

    private static final int REF_LIMIT = 50;   // 出/入引用各默认取前 50（防超大对象刷屏）

    private ObjectView() { }

    public static String detail(ISnapshot snapshot, int objectId) throws Exception {
        IObject o = snapshot.getObject(objectId);

        Json j = Json.obj()
                .kv("objectId", objectId)
                .kv("label", ObjectRows.safeDisplayName(o))
                .kv("type", o.getClazz() != null ? o.getClazz().getName() : "?")
                .kv("address", "0x" + Long.toHexString(o.getObjectAddress()))
                .kv("shallowBytes", o.getUsedHeapSize())
                .kv("shallow", Json.humanBytes(o.getUsedHeapSize()))
                .kv("retainedBytes", o.getRetainedHeapSize())
                .kv("retained", Json.humanBytes(o.getRetainedHeapSize()))
                .kv("gcRoot", isGcRoot(o));
        // 语义值（MAT 的 Value）：String→文本/URL→完整URL/char[]→字符串 等，含 Unicode
        try {
            String sv = o.getClassSpecificName();
            if (sv != null && !sv.isEmpty()) {
                j.kv("value", sv.length() > 4096 ? sv.substring(0, 4096) + "…" : sv);
            }
        } catch (Throwable ignore) { }

        // ---- 按对象种类分别渲染：实例字段 / 类对象(静态字段+类信息) / 数组(元素) ----
        if (o instanceof IClass clazz) {
            // 类对象本身（histogram 点进来的）：显示类信息 + 静态字段
            j.kv("kind", "class");
            j.kv("instances", clazz.getNumberOfObjects());
            try {
                IClass sup = clazz.getSuperClass();
                if (sup != null) j.kv("superclass", sup.getName());
            } catch (Throwable ignore) { }
            j.raw("staticFields", renderFieldList(clazz.getStaticFields()));
        } else if (o instanceof IInstance inst) {
            // 普通实例：实例字段 + 该类的静态字段（MAT Inspector 两者都给）
            j.kv("kind", "instance");
            j.raw("fields", renderFieldList(inst.getFields()));
            try {
                IClass c = inst.getClazz();
                if (c != null) j.raw("staticFields", renderFieldList(c.getStaticFields()));
            } catch (Throwable ignore) { }
        } else if (o instanceof IPrimitiveArray pa) {
            // 基本类型数组：长度 + 前若干元素值
            j.kv("kind", "primitiveArray");
            j.kv("length", pa.getLength());
            j.raw("elements", renderPrimitiveArray(pa));
        } else if (o instanceof IObjectArray oa) {
            // 对象数组：长度（元素引用见 outbound）
            j.kv("kind", "objectArray");
            j.kv("length", oa.getLength());
        }

        // ---- 出引用（getOutboundReferences）----
        j.raw("outbound", renderOutbound(o));

        // ---- 入引用（getInboundRefererIds）----
        j.raw("inbound", renderInbound(snapshot, objectId));

        return j.endObj();
    }

    /** 渲染一组 Field（实例字段 / 静态字段共用）。 */
    private static String renderFieldList(List<Field> fs) {
        Json arr = Json.arr();
        if (fs != null) {
            for (Field f : fs) {
                arr.item(renderField(f));
            }
        }
        return arr.endArr();
    }

    /** 基本类型数组：取前 N 个元素值（防超大数组）。 */
    private static String renderPrimitiveArray(IPrimitiveArray pa) {
        final int LIMIT = 100;
        int n = Math.min(pa.getLength(), LIMIT);
        Json arr = Json.arr();
        for (int i = 0; i < n; i++) {
            Object v = pa.getValueAt(i);
            arr.item("\"" + esc(String.valueOf(v)) + "\"");
        }
        return Json.obj()
                .raw("items", arr.endArr())
                .kv("total", pa.getLength())
                .kv("truncated", n < pa.getLength())
                .endObj();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String renderField(Field f) {
        Object v = f.getValue();
        String valStr;
        boolean isRef = false;
        int refId = -1;
        if (v instanceof NamedReference nr) {
            isRef = true;
            try { refId = nr.getObjectId(); } catch (Exception ignore) { }
            // 引用类型：优先显示目标对象的"语义内容"（getClassSpecificName）——
            // String/char[]/URL/Date 等直接看到内容（含中文/日文/emoji，UTF-8 正确输出）；
            // 拿不到内容则退回引用名（类名 @ 地址）。
            valStr = resolveRefValue(nr);
        } else {
            // 基础类型：直接值。char 额外给码点更友好（如不可见字符）。
            valStr = formatPrimitive(f, v);
        }
        Json o = Json.obj()
                .kv("name", f.getName())
                .kv("type", fieldType(f))   // 可读类型：ref / int / char / boolean ...
                .kv("value", valStr)
                .kv("isRef", isRef);
        if (isRef && refId >= 0) o.kv("refId", refId);   // 可点进去
        return o.endObj();
    }

    /** 引用字段值：目标对象的语义内容（String 文本等），失败退回引用名。 */
    private static String resolveRefValue(NamedReference nr) {
        try {
            IObject target = nr.getObject();
            if (target != null) {
                String semantic = target.getClassSpecificName();   // String→文本, char[]→字符串, URL→完整URL
                if (semantic != null && !semantic.isEmpty()) {
                    // String 等用引号包裹更直观；过长截断（保留 UTF-8 字符完整）
                    String s = semantic.length() > 200 ? semantic.substring(0, 200) + "…" : semantic;
                    return "\"" + s + "\"";
                }
            }
        } catch (Throwable ignore) { }
        return nr.getName();   // 退回 "java.lang.Foo @ 0x.."
    }

    /** 基础类型值格式化：char 显示成 'A' (65)，其余直接 toString。 */
    private static String formatPrimitive(Field f, Object v) {
        try {
            if (f.getType() == org.eclipse.mat.snapshot.model.IObject.Type.CHAR && v instanceof Character ch) {
                int code = ch;
                String shown = (code >= 0x20) ? String.valueOf(ch.charValue()) : "\\u" + String.format("%04x", code);
                return "'" + shown + "' (" + code + ")";
            }
        } catch (Throwable ignore) { }
        return String.valueOf(v);
    }

    /** 字段可读类型（getVerboseSignature 给 ref/int/char/... ；引用类型尽量给目标类名）。 */
    private static String fieldType(Field f) {
        try {
            String sig = f.getVerboseSignature();   // "ref" 或 "int"/"char"/...
            if (!"ref".equals(sig)) return sig;
            // 引用类型：值若是 NamedReference，名字里通常含目标类型信息；否则就标 ref
            return "ref";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String renderOutbound(IObject o) {
        Json arr = Json.arr();
        List<NamedReference> refs = o.getOutboundReferences();
        int total = refs == null ? 0 : refs.size();
        int n = Math.min(total, REF_LIMIT);
        for (int i = 0; i < n; i++) {
            NamedReference r = refs.get(i);
            int rid = -1;
            try { rid = r.getObjectId(); } catch (Exception ignore) { }
            arr.item(Json.obj()
                    .kv("name", r.getName())          // 引用名（字段名/数组下标等）
                    .kv("objectId", rid)
                    .endObj());
        }
        return Json.obj()
                .raw("items", arr.endArr())
                .kv("total", total)
                .kv("truncated", n < total)
                .endObj();
    }

    private static String renderInbound(ISnapshot snapshot, int objectId) throws Exception {
        int[] ids = snapshot.getInboundRefererIds(objectId);
        int total = ids == null ? 0 : ids.length;
        int n = Math.min(total, REF_LIMIT);
        Json arr = Json.arr();
        for (int i = 0; i < n; i++) {
            IObject r = snapshot.getObject(ids[i]);
            arr.item(Json.obj()
                    .kv("objectId", ids[i])
                    .kv("label", ObjectRows.safeDisplayName(r))
                    .endObj());
        }
        return Json.obj()
                .raw("items", arr.endArr())
                .kv("total", total)
                .kv("truncated", n < total)
                .endObj();
    }

    private static boolean isGcRoot(IObject o) {
        try {
            GCRootInfo[] gr = o.getGCRootInfo();
            return gr != null && gr.length > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
