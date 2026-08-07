package com.jvmind.mat.query.view;

import com.jvmind.mat.query.model.Json;
import org.eclipse.mat.inspections.collectionextract.CollectionExtractionUtils;
import org.eclipse.mat.inspections.collectionextract.ICollectionExtractor;
import org.eclipse.mat.inspections.collectionextract.IMapExtractor;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.IObject;

import java.util.Iterator;
import java.util.Map;

/**
 * 容器条目视图 —— Map / Collection 实例的元素列表（分页）。
 *
 * <p>MAT Inspector：选中一个 {@code java.util.Map} 或 {@code Collection} 实例
 * （{@code kind=instance}），应展示它的条目而不是内部字段。
 * 复用 MAT 的 {@link CollectionExtractionUtils}，自动支持：
 * <ul>
 *   <li>Map：HashMap / LinkedHashMap / IdentityHashMap / WeakHashMap / Hashtable /
 *       TreeMap / EnumMap / ConcurrentHashMap / Properties 等（返回 key+value）</li>
 *   <li>Collection：ArrayList / Vector / LinkedList / HashSet / LinkedHashSet /
 *       TreeSet / PriorityQueue / ArrayDeque / EnumSet / CopyOnWriteArrayList 等
 *       （返回单值）</li>
 * </ul>
 */
public final class CollectionEntriesView {

    /** 防超大容器刷爆内存的硬上限。 */
    private static final int MAX = 50000;

    private CollectionEntriesView() { }

    /** GET /collection-entries?id=<objectId>&top=25&offset=0 */
    public static String entries(ISnapshot s, int objectId, int top, int offset) throws Exception {
        IObject o = s.getObject(objectId);
        if (o == null) {
            return Json.obj().kv("error", "object not found: " + objectId).endObj();
        }

        ICollectionExtractor ext;
        try {
            ext = CollectionExtractionUtils.findCollectionExtractor(o);
        } catch (Throwable t) {
            ext = null;
        }
        if (ext == null) {
            return Json.obj()
                    .kv("error", "no collection extractor for " + (o.getClazz() != null ? o.getClazz().getName() : "?"))
                    .endObj();
        }

        boolean isMap = ext instanceof IMapExtractor;
        Json rows = Json.arr();
        int total = 0;
        boolean truncated = false;

        if (isMap) {
            IMapExtractor me = (IMapExtractor) ext;
            Iterator<Map.Entry<IObject, IObject>> it = me.extractMapEntries(o);
            int from = Math.min(offset, 0);   // 先占位，下方统一算
            from = Math.max(offset, 0);
            int idx = 0;
            while (it.hasNext() && idx < MAX) {
                Map.Entry<IObject, IObject> e = it.next();
                if (idx >= offset && idx < offset + top) {
                    rows.item(Json.obj()
                            .raw("key", renderObj(e.getKey()))
                            .raw("value", renderObj(e.getValue()))
                            .endObj());
                }
                idx++;
            }
            total = idx;
            truncated = idx >= MAX && it.hasNext();
        } else {
            // Collection：extractEntryIds 直接给 int[]（比 iterator 快）
            int[] ids;
            try {
                ids = ext.extractEntryIds(o);
            } catch (Throwable t) {
                ids = new int[0];
            }
            total = ids.length;
            int from = Math.min(offset, total);
            int to = Math.min(offset + top, total);
            for (int i = from; i < to; i++) {
                int id = ids[i];
                rows.item(id >= 0 ? renderRef(s, id) : Json.obj().kv("isNull", true).endObj());
            }
            truncated = total > MAX;
        }

        Json out = Json.obj()
                .kv("kind", isMap ? "map-entries" : "collection-entries")
                .kv("objectId", objectId)
                .kv("totalRows", total)
                .kv("returned", Math.max(0, Math.min(top, total - Math.max(offset, 0))))
                .raw("rows", rows.endArr());
        if (offset + top < total) {
            out.kv("cursor", "offset:" + Math.min(offset + top, total));
        }
        if (truncated) {
            out.kv("truncated", true);
        }
        return out.endObj();
    }

    /** Map entry 的 key/value 渲染（IObject 形态）。 */
    private static String renderObj(IObject o) {
        if (o == null) {
            return Json.obj().kv("isNull", true).endObj();
        }
        try {
            return Json.obj()
                    .kv("objectId", o.getObjectId())
                    .kv("label", ObjectRows.safeDisplayName(o))
                    .kv("className", o.getClazz() != null ? o.getClazz().getName() : "?")
                    .kv("address", "0x" + Long.toHexString(o.getObjectAddress()))
                    .endObj();
        } catch (Throwable t) {
            return Json.obj().kv("isNull", true).endObj();
        }
    }

    /** Collection 元素的渲染（按 objectId）。 */
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
