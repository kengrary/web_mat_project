package com.jvmind.mat.query.view;

import com.jvmind.mat.query.model.Json;
import org.eclipse.mat.snapshot.IPathsFromGCRootsComputer;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.IObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds JSON-friendly linear GC-root paths by using MAT's
 * {@link IPathsFromGCRootsComputer} directly (not via the UI-oriented IResultTree
 * produced by the path2gc query, which is lazily expanded and unsuitable for
 * one-shot Agent consumption).
 */
final class PathsLinearizer {

    private static final Logger LOG = Logger.getLogger(PathsLinearizer.class.getName());

    private PathsLinearizer() {}

    /**
     * Compute up to {@code maxPaths} shortest GC-root paths for {@code objectId},
     * each path limited to {@code maxDepth} hops, and return a Json-arr string
     * where each element is {length, chain:[{objectId,label,type,shallowBytes,
     * retainedBytes,gcRoot?},...]}.
     *
     * <p>The chain goes from target (index 0) UP to a GC root (last element).
     * When {@code maxPaths} paths exist, different branches show multiple leaks/
     * root holders.
     */
    static String pathsFromGCRoots(ISnapshot snapshot, int objectId, int maxPaths, int maxDepth) {
        Json out = Json.arr();
        try {
            @SuppressWarnings("unchecked")
            Map<org.eclipse.mat.snapshot.model.IClass, Set<String>> excludeMap = Collections.emptyMap();
            IPathsFromGCRootsComputer c = snapshot.getPathsFromGCRoots(objectId, excludeMap);
            Set<String> seen = new HashSet<>();   // de-dup identical paths (MAT can return near-dups)
            int emitted = 0;
            for (int p = 0; p < maxPaths * 2 && emitted < maxPaths; p++) {
                int[] ids = c.getNextShortestPath();
                if (ids == null || ids.length == 0) break;
                int[] chain = ids.length > maxDepth ? truncate(ids, maxDepth) : ids;
                String key = keyOf(chain);
                if (!seen.add(key)) continue;    // skip duplicates
                out.item(renderChain(snapshot, chain));
                emitted++;
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "pathsFromGCRoots failed for " + objectId, t);
        }
        return out.endArr();
    }

    private static int[] truncate(int[] ids, int max) {
        int[] out = new int[Math.min(ids.length, max)];
        System.arraycopy(ids, 0, out, 0, out.length);
        return out;
    }

    private static String keyOf(int[] chain) {
        StringBuilder sb = new StringBuilder(chain.length * 6);
        for (int id : chain) sb.append(id).append(',');
        return sb.toString();
    }

    private static String renderChain(ISnapshot snapshot, int[] chain) throws Exception {
        Json arr = Json.arr();
        for (int i = 0; i < chain.length; i++) {
            int id = chain[i];
            boolean isRoot = (i == chain.length - 1);
            IObject o = safeObject(snapshot, id);
            String label = o != null ? com.jvmind.mat.query.view.ObjectRows.safeDisplayName(o)
                    : "object @" + Long.toHexString(snapshot.mapIdToAddress(id));
            String type = o != null && o.getClazz() != null ? o.getClazz().getName() : "?";
            long shallow = 0, retained = 0;
            try { shallow = snapshot.getHeapSize(id); } catch (Throwable ignore) {}
            try { retained = snapshot.getRetainedHeapSize(id); } catch (Throwable ignore) {}
            boolean approxRet = retained < 0;
            long retAbs = Math.abs(retained);
            Json hop = Json.obj()
                    .kv("objectId", id)
                    .kv("label", label)
                    .kv("type", type)
                    .kv("shallowBytes", shallow)
                    .kv("shallow", Json.humanBytes(shallow))
                    .kv("retainedBytes", retAbs)
                    .kv("retained", Json.humanBytes(retAbs));
            if (approxRet) hop.kv("retainedApprox", true);
            if (isRoot) {
                hop.kv("gcRoot", true);
                String[] roots = safeGcRoots(snapshot, id);
                if (roots != null && roots.length > 0) hop.kv("gcRootTypes", String.join(",", roots));
            }
            arr.item(hop.endObj());
        }
        return Json.obj()
                .kv("length", chain.length)
                .raw("chain", arr.endArr())
                .endObj();
    }

    private static IObject safeObject(ISnapshot s, int id) {
        try { return s.getObject(id); } catch (Throwable t) { return null; }
    }

    private static String[] safeGcRoots(ISnapshot s, int id) {
        try {
            var info = s.getGCRootInfo(id);
            if (info == null || info.length == 0) return null;
            String[] out = new String[info.length];
            for (int i = 0; i < info.length; i++) out[i] = String.valueOf(info[i].getType());
            return out;
        } catch (Throwable t) { return null; }
    }
}
