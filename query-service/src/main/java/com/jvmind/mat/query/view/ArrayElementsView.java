package com.jvmind.mat.query.view;

import com.jvmind.mat.query.model.Json;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IObjectArray;

/**
 * 对象数组元素视图 —— Object[] 的元素列表（分页）。
 *
 * <p>MAT Inspector：选中一个 {@code Object[]}（{@code kind=objectArray}），
 * 应展示它的元素（前 25 + 点击加载更多），而不是只显示数组长度。
 * 用 {@link IObjectArray#getReferenceArray(int, int)} 批量拿 objectId，
 * 再逐个 {@code snapshot.getObject()} 渲染 label/className/address。
 *
 * <p>覆盖类型：一切 {@code Object[]} 子类 —— 纯 {@code Object[]}、
 * {@code String[]}、HashMap 的 {@code table}（HashMap$Entry[]）、
 * ArrayList 的 {@code elementData}（Object[]）等。
 */
public final class ArrayElementsView {

    private ArrayElementsView() { }

    /** GET /array-elements?id=<objectId>&top=25&offset=0 */
    public static String elements(ISnapshot s, int objectId, int top, int offset) throws Exception {
        IObject o = s.getObject(objectId);
        if (o == null) {
            return Json.obj().kv("error", "object not found: " + objectId).endObj();
        }
        if (!(o instanceof IObjectArray oa)) {
            return Json.obj()
                    .kv("error", "object " + objectId + " is not an object array (" + o.getClazz().getName() + ")")
                    .endObj();
        }

        int total = oa.getLength();
        int from = Math.min(offset, total);
        int to = Math.min(offset + top, total);

        long[] ids = oa.getReferenceArray(from, to - from);
        Json rows = Json.arr();
        for (long addr : ids) {
            if (addr <= 0) {
                // 0 = null 槽位（空数组元素）；负数同理
                rows.item(Json.obj().kv("isNull", true).endObj());
            } else {
                // getReferenceArray 返回的是原始堆地址，需转 objectId
                int id = s.mapAddressToId(addr);
                if (id < 0) {
                    rows.item(Json.obj().kv("isNull", true).endObj());
                } else {
                    rows.item(renderRef(s, id));
                }
            }
        }

        Json out = Json.obj()
                .kv("kind", "array-elements")
                .kv("objectId", objectId)
                .kv("totalRows", total)
                .kv("returned", to - from)
                .raw("rows", rows.endArr());
        if (to < total) {
            out.kv("cursor", "offset:" + to);
        }
        return out.endObj();
    }

    /** 单个元素渲染：objectId + label + className + address。 */
    private static String renderRef(ISnapshot s, int id) {
        try {
            IObject child = s.getObject(id);
            return Json.obj()
                    .kv("objectId", id)
                    .kv("label", ObjectRows.safeDisplayName(child))
                    .kv("className", child.getClazz() != null ? child.getClazz().getName() : "?")
                    .kv("address", "0x" + Long.toHexString(child.getObjectAddress()))
                    .endObj();
        } catch (Throwable t) {
            return Json.obj()
                    .kv("objectId", id)
                    .kv("label", "object #" + id)
                    .kv("className", "?")
                    .endObj();
        }
    }
}
