# Java 端需求：支配树右键 "List objects → outgoing/incoming references"

**作者**：前端团队（MAT web 工作台）
**日期**：2026-08-05
**目标版本**：`com.jvmind.mat.query 0.1.0`

## 背景

前端支配树每行已有右键菜单 `[Path to GC roots / Merge paths to GC roots / Inspect]`。要对齐 MAT 桌面版，需要加 **"List objects"** 能力：

- **with outgoing references**：收集目标对象的所有**出引用对象**（`getOutboundReferences()` 指向的 objectId 集合），对这集合做 histogram，按类聚合展示"这个对象引用了哪些类的对象"。
- **with incoming references**：收集所有**引用目标对象**的 objectId（`getInboundRefererIds(objectId)`），做同样 histogram，展示"谁引用了这个对象"。

前端拿到结果集后，跳转到 Histogram tab 展示按类分布。

## 实现思路

后端已具备支撑机制：

- `ResultSetCache.put(dumpDir, int[] objectIds)` → 返回 `resultSetId`
- `/histogram?objectSet=rs-xxx` → `HistogramView.bySubset(...)` 对结果集做按类聚合（现有代码已支持）

所以 Java 端**只需新增一个 endpoint**：把"目标对象的出/入引用对象 id 数组"存进缓存，返回 `resultSetId`。前端拿它喂给已有的 `/histogram?objectSet=`。

## 改动范围

### 单文件

`/home/pan/web_mat_project/query-service/src/main/java/com/jvmind/mat/query/http/QueryHttpServer.java`

### 1) 新增 handler

```java
/** list_objects：收集目标对象的出/入引用对象 id，存入结果集缓存，返回 resultSetId。 */
private void listObjects(HttpExchange ex) throws IOException {
    Map<String, String> q = Query.parse(ex);
    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String direction = MiniJson.getString(body, "direction");   // "out" | "in"
    int objectId = (int) MiniJson.getLong(body, "objectId", -1);
    String dumpDir = q.get("dumpDir");

    if (objectId < 0) { send(ex, 400, err("missing 'objectId' in body")); return; }
    if (dumpDir == null || dumpDir.isBlank()) { send(ex, 400, err("missing 'dumpDir' query param")); return; }
    if (!"out".equalsIgnoreCase(direction) && !"in".equalsIgnoreCase(direction)) {
        send(ex, 400, err("direction must be 'out' or 'in'")); return;
    }

    withSnapshot(ex, s -> {
        try {
            int[] ids;
            if ("out".equalsIgnoreCase(direction)) {
                // 出引用：遍历 NamedReference，收集指向的对象 id（去重、过滤无效）
                IObject o = s.getObject(objectId);
                java.util.Set<Integer> set = new java.util.LinkedHashSet<>();
                java.util.List<NamedReference> refs = o.getOutboundReferences();
                if (refs != null) {
                    for (NamedReference nr : refs) {
                        try {
                            int cid = nr.getObjectId();
                            if (cid >= 0) set.add(cid);
                        } catch (Exception ignore) { }
                    }
                }
                ids = set.stream().mapToInt(Integer::intValue).toArray();
            } else {
                // 入引用：直接取 referer ids
                int[] referers = s.getInboundRefererIds(objectId);
                ids = referers != null ? referers : new int[0];
            }
            String rsId = resultSets.put(dumpDir, ids);
            return Json.obj()
                    .kv("resultSetId", rsId)
                    .kv("count", ids.length)
                    .endObj();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    });
}
```

### 2) 注册路由

在 `ctx("/oql", this::oql)` 附近的 `ctx(...)` 列表里加一行：

```java
ctx("/list-objects", this::listObjects);
```

### 3) imports

如未导入：

```java
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.NamedReference;
```

（`List` 若 `java.util.List` 已别处使用则无需重复导入）

## 出参

```json
{ "resultSetId": "rs-1-a1b2c3-1692...", "count": 1248 }
```

## 前端消费方式（Java 团队不需要做）

前端收到 `resultSetId` 后调用：

```
GET /histogram?dumpDir=...&objectSet=rs-1-a1b2c3-1692...&sort=retained
```

该请求走**现有的** `histogram` handler（line 150-175）里的 `objectSet` 分支 → `HistogramView.bySubset(...)`，无需新代码。

## 构建 + 部署

参考之前的部署步骤（`IMPLEMENTATION_GUIDE.md` 或上次 `targetClass` / `fieldName` 改动的部署路径）：

```bash
cd /home/pan/web_mat_project/query-service
mvn -f pom.xml clean package -DskipTests
cp target/com.jvmind.mat.query-0.1.0.jar /home/pan/mat/plugins/
# bundles.info 注册（版本不变通常可跳过）
grep "com.jvmind.mat.query" /home/pan/mat/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info \
  || echo "com.jvmind.mat.query,0.1.0,plugins/com.jvmind.mat.query-0.1.0.jar,4,true" \
       >> /home/pan/mat/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info
# -clean 重启
kill <现有 MAT 进程>
cd /home/pan/mat
./MemoryAnalyzer -consoleLog -nosplash -clean \
    -application com.jvmind.mat.query.app \
    -vmargs -Xmx4g \
    -DMAT_QUERY_BIND=0.0.0.0 -DMAT_QUERY_PORT=8090 -DMAT_QUERY_CORS='*'
```

## 测试 / 验证

```bash
# 出引用
curl -X POST "http://127.0.0.1:8090/list-objects?dumpDir=/home/pan/data/heapdumps/hd_f3b47736cdc2" \
  -H "Content-Type: application/json" \
  -d '{"direction":"out","objectId":8141}'
# → {"resultSetId":"rs-...","count":N}

# 入引用
curl -X POST "http://127.0.0.1:8090/list-objects?dumpDir=/home/pan/data/heapdumps/hd_f3b47736cdc2" \
  -H "Content-Type: application/json" \
  -d '{"direction":"in","objectId":8141}'
# → {"resultSetId":"rs-...","count":M}

# 用 resultSetId 拉 histogram（无需 Java 改动）
curl "http://127.0.0.1:8090/histogram?dumpDir=/home/pan/data/heapdumps/hd_f3b47736cdc2&objectSet=rs-...&sort=retained"
```

## 边界情况

| 情况 | 处理 |
|---|---|
| `getOutboundReferences()` 返回 null | 已判空，ids 为空数组 |
| `getInboundRefererIds()` 返回 null | 已判空，ids 为空数组 |
| 单个出引用对象 id 重复（多字段指向同一对象） | LinkedHashSet 去重，取第一个 |
| 大量引用（数千/数万）| 不截断——ResultSetCache SoftReference 持有，histogram 由 MAT 处理 |
| 无效 objectId（负值 / 不存在）| objectId < 0 直接 400；不存在时 `s.getObject` 抛异常 → 500 |
| resultSetId 被软引用回收 | 前端重跑 listObjects 步骤即可恢复 |

## 沟通

需要 Java 团队：
1. 确认开发 + build + 重启耗时
2. 部署后回复 commit/tag 名称，前端随后对接
