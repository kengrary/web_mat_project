# Java 端需求：支配树右键 "List objects" → References 视图

**作者**：前端团队（MAT web 工作台）
**日期**：2026-08-05
**目标版本**：`com.jvmind.mat.query 0.1.0`
**前置**：已完成 `POST /list-objects`（resultSetId 机制）。本需求新增 **`POST /references`**，直接返回引用对象的具体列表（含字段名 / 大小），供前端渲染 References 视图。

## 背景

上一个迭代实现了 `list-objects`（返回 resultSetId → 前端 histogram 按类聚合）。但用户反馈：**内容不对**——"List objects → with outgoing/incoming references" 应显示**具体的引用对象列表**（类似支配树），而不是按类聚合的 histogram。

因此新增 `POST /references` endpoint，返回该对象出/入引用对象的**详细行**（每行一个对象：`objectId / label / className / fieldName / shallow / retained`），前端用它渲染 References 视图。

## 改动范围

### 单文件

`/home/pan/web_mat_project/query-service/src/main/java/com/jvmind/mat/query/http/QueryHttpServer.java`

### 新增 handler

```java
/** references：收集目标对象的出/入引用对象，返回详细行（含字段名/大小）。
 *  body: {"direction":"out"|"in", "objectId":N}
 *  query: dumpDir, top, offset
 */
private void references(HttpExchange ex) throws IOException {
    Map<String, String> q = Query.parse(ex);
    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String direction = MiniJson.getString(body, "direction");   // "out" | "in"
    int objectId = (int) MiniJson.getLong(body, "objectId", -1);
    String dumpDir = q.get("dumpDir");
    int top = Query.intOr(q, "top", 50);
    int offset = Query.intOr(q, "offset", 0);

    if (objectId < 0) { send(ex, 400, err("missing 'objectId' in body")); return; }
    if (dumpDir == null || dumpDir.isBlank()) { send(ex, 400, err("missing 'dumpDir' query param")); return; }
    if (!"out".equalsIgnoreCase(direction) && !"in".equalsIgnoreCase(direction)) {
        send(ex, 400, err("direction must be 'out' or 'in'")); return;
    }

    withSnapshot(ex, s -> {
        try {
            // 构建 "objectId -> fieldName" 映射
            java.util.LinkedHashMap<Integer, String> fieldNames = new java.util.LinkedHashMap<>();
            java.util.List<Integer> order = new java.util.ArrayList<>();

            if ("out".equalsIgnoreCase(direction)) {
                IObject o = s.getObject(objectId);
                java.util.List<NamedReference> refs = o.getOutboundReferences();
                if (refs != null) {
                    for (NamedReference nr : refs) {
                        try {
                            int cid = nr.getObjectId();
                            if (cid >= 0 && !fieldNames.containsKey(cid)) {
                                fieldNames.put(cid, nr.getName());   // 字段名（如 "table" / "[7]"）
                                order.add(cid);
                            }
                        } catch (Exception ignore) { }
                    }
                }
            } else {
                // inbound：getInboundRefererIds 拿 referer ids，再反向查它指向我们的字段名
                int[] referers = s.getInboundRefererIds(objectId);
                if (referers != null) {
                    for (int rid : referers) {
                        if (fieldNames.containsKey(rid)) continue;
                        String fn = reverseFieldName(s, rid, objectId);
                        fieldNames.put(rid, fn);
                        order.add(rid);
                    }
                }
            }

            // 分页 + 渲染
            int total = order.size();
            int from = Math.min(offset, total);
            int to = Math.min(offset + top, total);

            Json rows = Json.arr();
            for (int i = from; i < to; i++) {
                int id = order.get(i);
                IObject obj = s.getObject(id);
                long shallow = obj.getUsedHeapSize();
                long retained = 0;
                try { retained = Math.abs(obj.getRetainedHeapSize()); } catch (Exception ignore) { }
                rows.item(Json.obj()
                        .kv("objectId", id)
                        .kv("label", ObjectRows.safeDisplayName(obj))
                        .kv("className", obj.getClazz() != null ? obj.getClazz().getName() : "?")
                        .kv("fieldName", fieldNames.getOrDefault(id, ""))
                        .kv("shallowBytes", shallow)
                        .kv("shallow", Json.humanBytes(shallow))
                        .kv("retainedBytes", retained)
                        .kv("retained", retained > 0 ? Json.humanBytes(retained) : "n/a")
                        .endObj());
            }

            Json out = Json.obj()
                    .kv("kind", "object-list")
                    .kv("direction", direction)
                    .kv("source", objectId)
                    .raw("rows", rows.endArr())
                    .kv("totalRows", total)
                    .kv("returned", to - from);
            if (to < total) out.kv("cursor", "offset:" + to);
            return out.endObj();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    });
}

/** inbound 用：找 referer 的 outbound refs 里指向 targetId 的那条的字段名。找不到返回 "". */
private static String reverseFieldName(ISnapshot s, int refererId, int targetId) {
    try {
        IObject referer = s.getObject(refererId);
        java.util.List<NamedReference> refs = referer.getOutboundReferences();
        if (refs != null) {
            for (NamedReference nr : refs) {
                try {
                    if (nr.getObjectId() == targetId) return nr.getName();
                } catch (Exception ignore) { }
            }
        }
    } catch (Exception ignore) { }
    return "";
}
```

### 注册路由

```java
ctx("/references", this::references);
```

### imports（如未导入）

```java
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.NamedReference;
```

## 出参

```json
{
  "kind": "object-list",
  "direction": "out",
  "source": 8141,
  "rows": [
    {
      "objectId": 8150,
      "label": "java.util.LinkedHashMap @ 0xa4456f0",
      "className": "java.util.LinkedHashMap",
      "fieldName": "lookup",
      "shallowBytes": 80,
      "shallow": "80 B",
      "retainedBytes": 157552,
      "retained": "153.9 KB"
    },
    {
      "objectId": 8160,
      "label": "java.lang.String @ 0xa445700",
      "className": "java.lang.String",
      "fieldName": "key",
      "shallowBytes": 24,
      "shallow": "24 B",
      "retainedBytes": 128,
      "retained": "128 B"
    }
  ],
  "totalRows": 7,
  "returned": 2,
  "cursor": "offset:2"
}
```

**字段名语义**：
- `direction=out`：`fieldName` = 当前对象**持有**目标对象的字段名（`NamedReference.getName()`）
- `direction=in`：`fieldName` = **哪个 referer 的哪个字段**指向当前对象（反向查找）

## 与 `list-objects` 的关系

- `list-objects`（已部署）**保留**——用于 downstream 场景（对引用集再做 histogram / dominator 等分析流）
- `references`（本次新增）——直接给详细行，前端 References 视图消费
- 两者共用 `getOutboundReferences` / `getInboundRefererIds` 收集逻辑，但出参不同

## 构建 + 部署

同上次流程：

```bash
cd /home/pan/web_mat_project/query-service
mvn -f pom.xml clean package -DskipTests
cp target/com.jvmind.mat.query-0.1.0.jar /home/pan/mat/plugins/
# bundles.info 注册 + -clean 重启（参考 IMPLEMENTATION_GUIDE.md）
```

## 测试 / 验证

```bash
# outbound
curl -X POST "http://127.0.0.1:8090/references?dumpDir=/home/pan/data/heapdumps/hd_f3b47736cdc2&top=5" \
  -H "Content-Type: application/json" \
  -d '{"direction":"out","objectId":8141}'
# → 每行含 fieldName（如 "lookup"）

# inbound
curl -X POST "http://127.0.0.1:8090/references?dumpDir=/home/pan/data/heapdumps/hd_f3b47736cdc2&top=5" \
  -H "Content-Type: application/json" \
  -d '{"direction":"in","objectId":8141}'
# → 每行含反向查到的字段名
```

## 边界情况

| 情况 | 处理 |
|---|---|
| outbound refs 为 null | 空数组 |
| inbound referers 为 null | 空数组 |
| 反向查找字段名失败 | `fieldName=""`，前端显示 "—" |
| 多字段指向同一对象（out）| LinkedHashMap 去重，取第一个字段名 |
| retained 未计算 | `retained="n/a"` |
| 大量引用（数万）| 分页 top/offset，一次只取一页 |

## 沟通

需要 Java 团队：
1. 确认开发 + build + 重启耗时
2. 部署后回复 commit/tag 名称，前端随后对接
