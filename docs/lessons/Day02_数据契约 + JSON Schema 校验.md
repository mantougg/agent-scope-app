# Day 2 · 数据契约 + JSON Schema 校验

> 上级文档：[../learning.md](../learning.md)
> 配套笔记：[../agents/02-core-concepts.md § Msg](../agents/02-core-concepts.md) · [../agents/03-react-agent.md § Structured Output](../agents/03-react-agent.md)
> 前置：[Day 1 · 项目骨架 + AS-Java Hello World](<Day01_项目骨架 + AS-Java Hello World.md>) 已完成

## 0. 一句话目标

**今天结束时**，你能在 IDE 里 `new AnalysisResult(...)` 拿到一份合法的「应用 / 模块 / 数据模型」三层 POJO；能用 `SchemaValidator.validate(...)` 把一坨"未来 LLM 会吐出来的" JSON 验出每一处错位，并拿到中文友好的错误列表；`mvn test` 全绿。

> ⚠️ Day 2 **不接 LLM、不写 Tool、不引 Spring**。今天只造一层"静态数据契约"，给 Day 3 的 Structured Output 当锚。

## 1. 学习目标

- ✅ 用 Java `record` 把题面 #11/#12/#13 三段 JSON 固化为强类型
- ✅ 摸熟 Jackson 对 `record` 的反序列化习惯（未知字段、递归字段、命名约定）
- ✅ 编写 JSON Schema 2020-12，含 `$defs` / `$ref` 自引用（FieldSpec 递归）
- ✅ 用 [networknt json-schema-validator](https://github.com/networknt/json-schema-validator) 跑校验，把错误转成"人能看懂"的中文提示
- ✅ 单元测试 5 套样本：pass / missing-field / bad-pattern / enum-violation / recursive-bad

## 2. 时间盒（建议 8 学时）

| 阶段 | 时长 | 主题 | 验收 |
|------|------|------|------|
| Phase 0 | 30 min | 资料预读 + 题面回顾 | 能口述 App / Module / DataModel 三层 JSON 字段 |
| Phase 1 | 45 min | Maven 依赖 + `Json` 工具类 | `mvn -U dependency:resolve` 通过、单例 ObjectMapper 可用 |
| Phase 2 | 90 min | 5 个 record + roundtrip 测试 | record ↔ JSON 互转通过 |
| Phase 3 | 90 min | `analysis-result.schema.json` | Schema 自加载无错，`mvn compile` 通过 |
| Phase 4 | 60 min | `SchemaValidator` + 错误中文化 | 给定坏 JSON 拿到结构化的中文错误列表 |
| Phase 5 | 90 min | 5 套测试样本 + 资源加载 | `mvn test` 全绿 |
| Phase 6 | 30 min | 收尾：commit + 笔记 + Day 3 预告 | `day2: ...` commit、文档导航已更新 |

---

## 3. Phase 0 · 资料预读（30 min）

### 3.1 题面三段 JSON 回顾

请把你拿到的 **#11 应用结构 / #12 模块结构 / #13 数据模型结构** 三段 JSON 再过一遍，重点回答 3 个问题：

1. **命名风格**为什么 `App` 用 `name/label/type`，`Module` 用 `moduleName/moduleId/moduleDesc`？
   - 结论：**题面规定的**，不要"统一"。把题面字段当外部契约对待，POJO 字段名与之严格一致
2. **`type` 字段**含义差异：
   - `AppSpec.type` 是 App 分类码（题面给定的字符串，不枚举）
   - `DataModelSpec.type` 是数据模型形态枚举：`TASK_MASTER_SLAVE` / `TASK` / `ENTITY`
   - `FieldSpec.dataType` 是字段数据类型枚举：`long` / `int` / `double` / `string` / `boolean` / `date` / `array`
3. **递归点**：含明细的单据走 `TASK_MASTER_SLAVE`，明细放在 `fields` 数组里某个 `dataType=array` 的字段的 `subs` 里 —— **`FieldSpec` 自引用 `FieldSpec`**，这是 Schema 最容易写错的地方

### 3.2 设计前先回答自己

| 问题 | 我们的答案 | 理由 |
|------|-----------|------|
| `dataType` 用 Java enum 还是 `String`？ | **String** | 留校验给 Schema。LLM 哪天吐出 `"text"`，Jackson 不会因为反序列化就把整条 JSON 砸掉，Schema 校验器仍能给出友好提示 |
| `DataModelSpec.type` 同上？ | **String** | 同理 |
| `subs` 为 `null` 还是 `List.of()`？ | **允许 null** | 题面叶子字段（`name="id"` 那种）没有 subs 字段；空列表会让 JSON 变胖且 LLM 学错"必须输出空数组" |
| `warnings / questions` 必填吗？ | **必填，但可为空 list** | 让"没有问题"被显式表达，避免 LLM 漏字段被当合规 |
| 字段顺序怎么排？ | **跟题面 JSON 顺序一致** | record canonical constructor + Jackson 默认顺序输出 = 跟题面一比对就能定位 |

### 3.3 预读链接（10 min 速浏）

- [JSON Schema getting started step-by-step](https://json-schema.org/learn/getting-started-step-by-step) —— 重点看 §`$defs` 和 §`$ref`
- [networknt validator README](https://github.com/networknt/json-schema-validator) —— 重点看 SpecVersion 部分
- Jackson record 支持公告（[2.12 release notes](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.12)）

### ✅ Phase 0 验收

- [ ] 能在白纸上画出 `AnalysisResult` 的字段树（含 `FieldSpec` 递归）
- [ ] 能解释为什么 `dataType` 用 `String` 而非 enum
- [ ] 知道 `$defs` 和 `$ref` 的语法

---

## 4. Phase 1 · 依赖 + `Json` 工具类（45 min）

### 4.1 在 `pom.xml` 追加依赖

打开 `pom.xml`，在 `<dependencies>` 标签末尾追加：

```xml
<!-- Jackson：JSON ↔ POJO -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.17.0</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jsr310</artifactId>
    <version>2.17.0</version>
</dependency>

<!-- JSON Schema 2020-12 校验 -->
<dependency>
    <groupId>com.networknt</groupId>
    <artifactId>json-schema-validator</artifactId>
    <version>1.4.0</version>
</dependency>
```

> 📌 `jsr310` 现在不用，但 Day 4 的 `TodoItem` 会需要 `Instant`，提前装好省一次依赖切换。

跑一下确保拉到：

```bash
mvn -U dependency:resolve
```

> 国内拉慢的话见 [Day01 附录 A](<Day01_项目骨架 + AS-Java Hello World.md>) 配阿里云镜像。

### 4.2 `util/Json.java`

`src/main/java/space/wlshow/scope/util/Json.java`：

```java
package space.wlshow.scope.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.InputStream;

/**
 * 全局 ObjectMapper 门面：
 * - 宽进严出：反序列化忽略未知字段（LLM 经常多吐），序列化保持紧凑
 * - 注册 JavaTimeModule，禁掉 timestamp 序列化（用 ISO-8601）
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.INDENT_OUTPUT, false);

    public static ObjectMapper mapper() { return MAPPER; }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 反序列化失败: " + e.getOriginalMessage(), e);
        }
    }

    public static JsonNode tree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getOriginalMessage(), e);
        }
    }

    public static JsonNode tree(InputStream in) {
        try {
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("读取 JSON 流失败", e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    public static String writePretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private Json() {}
}
```

### 4.3 关于命名策略

**不要**配 `PropertyNamingStrategies.LOWER_CAMEL_CASE` —— 它是 Jackson 默认行为。题面字段已经是 camelCase，Java record 的 component name 也是 camelCase，**双方天然对齐，不需要任何转换**。

很多人下意识加这一行，反而会在以后想接入下划线字段（比如某些 Open API）时绕远路。**只在两侧不一致时**才设置 naming strategy。

### ✅ Phase 1 验收

- [ ] `pom.xml` 多了 3 个 dependency，且 `mvn -U dependency:resolve` 无错
- [ ] `Json.java` 编译通过
- [ ] 在 IDE 里写一行临时测试 `Json.write(Map.of("hello","世界"))` 能得到 `{"hello":"世界"}`（中文不乱码）

---

## 5. Phase 2 · 5 个 record + roundtrip 测试（90 min）

### 5.1 包结构

```
src/main/java/space/wlshow/scope/
├── spec/                             ← 数据契约 POJO（今天新建）
│   ├── AppSpec.java
│   ├── ModuleSpec.java
│   ├── DataModelSpec.java
│   ├── FieldSpec.java
│   └── AnalysisResult.java
├── schema/                           ← 校验器（下一阶段建）
│   ├── SchemaValidator.java
│   └── ValidationError.java
└── util/
    └── Json.java
```

> 💡 为什么不用 `model/` 装 POJO？因为 `space.wlshow.scope.model.ModelRegistry` 已经被 LLM `Model` 占了。**spec** = specification，比 model / dto / pojo 都更准确地传达"这是外部契约"。

### 5.2 `AppSpec.java`

```java
package space.wlshow.scope.spec;

public record AppSpec(
        String name,    // camelCase 标识，如 "leaveSystem"
        String label,   // 中文显示名，如 "请假管理"
        String type     // App 分类码（题面给定）
) {}
```

### 5.3 `ModuleSpec.java`

```java
package space.wlshow.scope.spec;

public record ModuleSpec(
        String moduleName,   // 中文显示名，如 "请假申请"
        String moduleId,     // camelCase ID，首字母小写
        String moduleDesc    // 描述
) {}
```

### 5.4 `FieldSpec.java`

**先定义 FieldSpec**，因为 DataModelSpec 依赖它。

```java
package space.wlshow.scope.spec;

import java.util.List;

/**
 * 字段定义。subs 用于 dataType=array 的子字段（递归）。
 * 叶子字段的 subs 应该是 null 而不是 List.of()，与题面保持一致。
 */
public record FieldSpec(
        String comment,
        String name,
        String dataType,          // long/int/double/string/boolean/date/array
        String usage,             // primary/foreign/""
        String relateModelType,   // collection/""
        List<FieldSpec> subs      // 仅 dataType=array 时有值；叶子节点为 null
) {}
```

### 5.5 `DataModelSpec.java`

```java
package space.wlshow.scope.spec;

import java.util.List;

public record DataModelSpec(
        String name,
        String type,              // TASK_MASTER_SLAVE / TASK / ENTITY
        String pinyin,
        String tableName,
        String parentId,          // 顶层是 "" 而非 null
        List<FieldSpec> fields
) {}
```

### 5.6 `AnalysisResult.java`

```java
package space.wlshow.scope.spec;

import java.util.List;

/**
 * 一次完整需求分析的结果。
 * warnings：LLM 自己不确定但有默认值的（"假设 type=23 表示业务管理类应用"）
 * questions：必须问用户的（"请假明细是否需要附件字段？"）
 */
public record AnalysisResult(
        AppSpec app,
        List<ModuleSpec> modules,
        List<DataModelSpec> models,
        List<String> warnings,
        List<String> questions
) {}
```

### 5.7 Roundtrip 测试（先于 Schema 写）

为什么先写这个：**当后面 Schema 校验失败时，先 roundtrip 一遍就能区分**「POJO 写错」还是「Schema 写错」。

`src/test/java/space/wlshow/scope/spec/JsonRoundTripTest.java`：

```java
package space.wlshow.scope.spec;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import space.wlshow.scope.util.Json;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonRoundTripTest {

    @Test
    void appSpec_roundTrip() {
        var origin = new AppSpec("leaveSystem", "请假管理", "23");
        var json   = Json.write(origin);
        var back   = Json.read(json, AppSpec.class);
        assertEquals(origin, back);
        assertTrue(json.contains("\"name\":\"leaveSystem\""));
    }

    @Test
    void analysisResult_fullTree_roundTrip() {
        var leaf = new FieldSpec("主键", "id", "long", "primary", "", null);
        var detail = new FieldSpec("明细", "details", "array", "", "collection",
                List.of(new FieldSpec("天数", "days", "double", "", "", null)));
        var model = new DataModelSpec("请假单", "TASK_MASTER_SLAVE",
                "qingjiadan", "t_leave_request", "",
                List.of(leaf, detail));

        var origin = new AnalysisResult(
                new AppSpec("leaveSystem", "请假管理", "23"),
                List.of(new ModuleSpec("请假申请", "leaveRequest", "员工提交请假")),
                List.of(model),
                List.of(),
                List.of("请假明细是否需要附件字段？")
        );

        var json = Json.write(origin);
        var back = Json.read(json, AnalysisResult.class);
        assertEquals(origin, back);
    }

    @Test
    void unknownFields_areIgnored() {
        // LLM 哪天多吐一个 "confidence" 字段，不要直接砸掉
        String dirty = """
                { "name":"x", "label":"X", "type":"23",
                  "confidence": 0.87, "_debug": {"foo":1} }
                """;
        AppSpec back = Json.read(dirty, AppSpec.class);
        assertEquals("x", back.name());
    }

    @Test
    void nullSubs_keptAsNull() {
        var leaf = new FieldSpec("c", "id", "long", "primary", "", null);
        String json = Json.write(leaf);
        // 题面叶子节点不带 subs：默认序列化会输出 "subs":null
        // 我们暂时不做 @JsonInclude(NON_NULL)，因为 Day 3 还要观察 LLM 学习样式
        JsonNode node = Json.tree(json);
        assertTrue(node.has("subs"));
        assertTrue(node.get("subs").isNull());
    }
}
```

### 5.8 跑通

```bash
mvn -q test -Dtest=JsonRoundTripTest
```

四个用例应全绿。如果挂了：

| 现象 | 怀疑 |
|------|------|
| `InvalidDefinitionException: Cannot construct instance of ... no Creators` | Jackson 版本 < 2.12，或没引 jackson-databind 干净。检查 `mvn dependency:tree | grep jackson` |
| `UnrecognizedPropertyException` | `Json.java` 没配 `FAIL_ON_UNKNOWN_PROPERTIES=false` |
| 中文字段值乱码 | 终端编码问题，见 [Day01 附录 B-2](<Day01_项目骨架 + AS-Java Hello World.md>) |

### ✅ Phase 2 验收

- [ ] 5 个 record 文件就位、`mvn compile` 通过
- [ ] `JsonRoundTripTest` 4 个用例全绿
- [ ] 在 IDE 里点开 `AnalysisResult.java`，IntelliJ/VS Code 不报红

---

## 6. Phase 3 · `analysis-result.schema.json`（90 min）

### 6.1 文件位置

`src/main/resources/schemas/analysis-result.schema.json`

> ⚠️ 后面 `SchemaValidator` 用 `getClass().getResourceAsStream("/schemas/...")` 加载，**必须**放在 `src/main/resources/` 下，**不要**放 `src/main/java/`。

### 6.2 整文件

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://wlshow.space/scope/analysis-result.schema.json",
  "title": "AnalysisResult",
  "description": "需求分析智能体的完整输出契约",
  "type": "object",
  "additionalProperties": false,
  "required": ["app", "modules", "models", "warnings", "questions"],
  "properties": {
    "app":       { "$ref": "#/$defs/AppSpec" },
    "modules":   { "type": "array", "items": { "$ref": "#/$defs/ModuleSpec" } },
    "models":    { "type": "array", "items": { "$ref": "#/$defs/DataModelSpec" } },
    "warnings":  { "type": "array", "items": { "type": "string" } },
    "questions": { "type": "array", "items": { "type": "string" } }
  },

  "$defs": {
    "AppSpec": {
      "type": "object",
      "additionalProperties": false,
      "required": ["name", "label", "type"],
      "properties": {
        "name":  { "type": "string", "pattern": "^[a-zA-Z][a-zA-Z0-9]*$" },
        "label": { "type": "string", "minLength": 1 },
        "type":  { "type": "string", "minLength": 1 }
      }
    },

    "ModuleSpec": {
      "type": "object",
      "additionalProperties": false,
      "required": ["moduleName", "moduleId", "moduleDesc"],
      "properties": {
        "moduleName": { "type": "string", "minLength": 1 },
        "moduleId":   { "type": "string", "pattern": "^[a-z][a-zA-Z0-9]*$" },
        "moduleDesc": { "type": "string" }
      }
    },

    "DataModelSpec": {
      "type": "object",
      "additionalProperties": false,
      "required": ["name", "type", "pinyin", "tableName", "parentId", "fields"],
      "properties": {
        "name":      { "type": "string", "minLength": 1 },
        "type":      { "enum": ["TASK_MASTER_SLAVE", "TASK", "ENTITY"] },
        "pinyin":    { "type": "string", "pattern": "^[a-z]+$" },
        "tableName": { "type": "string", "pattern": "^[a-z][a-z0-9_]*$" },
        "parentId":  { "type": "string" },
        "fields":    { "type": "array", "items": { "$ref": "#/$defs/FieldSpec" }, "minItems": 1 }
      }
    },

    "FieldSpec": {
      "type": "object",
      "additionalProperties": false,
      "required": ["name", "dataType"],
      "properties": {
        "comment":         { "type": ["string", "null"] },
        "name":            { "type": "string", "pattern": "^[a-zA-Z][a-zA-Z0-9_]*$" },
        "dataType":        { "enum": ["long", "int", "double", "string", "boolean", "date", "array"] },
        "usage":           { "type": ["string", "null"] },
        "relateModelType": { "type": ["string", "null"] },
        "subs": {
          "oneOf": [
            { "type": "null" },
            { "type": "array", "items": { "$ref": "#/$defs/FieldSpec" } }
          ]
        }
      }
    }
  }
}
```

### 6.3 几处坑要讲清楚

| 坑 | 解释 |
|----|------|
| **`$ref` 必须用 `#/` 起手的绝对 JSON Pointer** | `"#/$defs/FieldSpec"` ✅；`"FieldSpec"` 或 `"/$defs/FieldSpec"` 都 ❌ |
| **递归 `$ref` 自身** | `FieldSpec.subs.items` 引用 `FieldSpec` 自己，networknt 1.4.0 默认支持但会做循环检测 |
| **`additionalProperties: false`** | 显式禁止额外字段，避免 LLM 多吐 `_internal` 之类的字段假装合规。Day 3 视情况放宽 |
| **`comment / usage / relateModelType` 用 `["string","null"]`** | 题面里这些字段允许空字符串，但 LLM 也可能输出 `null`；与 POJO 的 nullable 一致 |
| **`subs` 用 `oneOf: [null, array]`** | 既允许显式 `null`（叶子节点），又允许数组（递归） |
| **`fields.minItems: 1`** | 一个数据模型至少要有一个字段（主键），强约束捕捉 LLM 偷懒 |
| **不写 `tableName` enum** | 表名是开放词汇，只用 pattern 约束格式 |

### 6.4 在 IDE 里看一下能不能加载

写一个临时 main（验完删掉，或者直接写进 Phase 4 的测试）：

```java
package space.wlshow.scope;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import java.io.InputStream;

public class SchemaSmoke {
    public static void main(String[] args) throws Exception {
        try (InputStream in = SchemaSmoke.class
                .getResourceAsStream("/schemas/analysis-result.schema.json")) {
            if (in == null) throw new IllegalStateException("resource not found");
            JsonSchemaFactory factory = JsonSchemaFactory
                    .getInstance(SpecVersion.VersionFlag.V202012);
            JsonSchema schema = factory.getSchema(in);
            System.out.println("Schema 加载成功: " + schema.getSchemaNode().path("title").asText());
        }
    }
}
```

```bash
mvn -q compile exec:java -Dexec.mainClass=space.wlshow.scope.SchemaSmoke
```

期望输出：
```
Schema 加载成功: AnalysisResult
```

加载报错 → 90% 是 `$ref` 写错或者 `enum` 数组里有错别字。

### ✅ Phase 3 验收

- [ ] `analysis-result.schema.json` 在 IDE 里 JSON 高亮无红
- [ ] `SchemaSmoke` 能跑通并打印 title
- [ ] **删掉 `SchemaSmoke`**（验证完不留临时类，规矩同 Day 1 的 `ConfigCheck`）

---

## 7. Phase 4 · `SchemaValidator` + 错误中文化（60 min）

### 7.1 `ValidationError.java`

`src/main/java/space/wlshow/scope/schema/ValidationError.java`：

```java
package space.wlshow.scope.schema;

import com.networknt.schema.ValidationMessage;

/**
 * 校验错误的中文友好封装。
 *
 * @param path      JSON Pointer，如 "$.models[0].fields[1].dataType"
 * @param keyword   触发的关键字（type/enum/required/pattern/additionalProperties/...）
 * @param message   中文描述
 * @param raw       networknt 原始消息（保留兜底）
 */
public record ValidationError(String path, String keyword, String message, String raw) {

    public static ValidationError from(ValidationMessage m) {
        String path = m.getInstanceLocation() == null
                ? m.getPath()
                : m.getInstanceLocation().toString();
        String keyword = m.getType();
        String zh = translate(m);
        return new ValidationError(path, keyword, zh, m.getMessage());
    }

    private static String translate(ValidationMessage m) {
        String kw   = m.getType();
        String path = m.getPath() == null ? "?" : m.getPath();
        Object[] args = m.getArguments() == null ? new Object[0] : m.getArguments();

        return switch (kw) {
            case "required" -> "%s 缺少必填字段 %s".formatted(path, joinArgs(args));
            case "type"     -> "%s 类型不正确：期望 %s".formatted(path, joinArgs(args));
            case "enum"     -> "%s 必须是枚举之一：%s".formatted(path, joinArgs(args));
            case "pattern"  -> "%s 不匹配正则 %s".formatted(path, joinArgs(args));
            case "minItems" -> "%s 元素不足：要求至少 %s 项".formatted(path, joinArgs(args));
            case "minLength" -> "%s 字符串过短：要求至少 %s 字符".formatted(path, joinArgs(args));
            case "additionalProperties" ->
                    "%s 出现了未在 Schema 中声明的字段 %s".formatted(path, joinArgs(args));
            case "oneOf"    -> "%s 不满足任何一个允许的形态".formatted(path);
            default         -> "%s [%s] %s".formatted(path, kw, m.getMessage());
        };
    }

    private static String joinArgs(Object[] args) {
        if (args.length == 0) return "";
        var sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(args[i]);
        }
        return sb.toString();
    }
}
```

> 📌 networknt 1.4.0 同时提供 `getPath()`（带 `$.` 前缀的 instance pointer）和 `getInstanceLocation()`（JSON Pointer 对象）。这里两个都拿，是因为不同版本 / 不同关键字的填法不一样，做防御。

### 7.2 `SchemaValidator.java`

`src/main/java/space/wlshow/scope/schema/SchemaValidator.java`：

```java
package space.wlshow.scope.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.util.Json;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JSON Schema 校验器。
 *
 * 设计：
 * - 构造时一次性加载并编译 Schema（线程安全，可作单例缓存）
 * - validate 永不抛业务异常；空列表 = 通过，非空 = 有错
 * - 资源加载失败 = 配置错误 = fail fast（直接抛 IllegalStateException）
 */
public final class SchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(SchemaValidator.class);

    /** 默认契约：AnalysisResult 全树校验 */
    public static final String ANALYSIS_RESULT = "/schemas/analysis-result.schema.json";

    private final String resourcePath;
    private final JsonSchema schema;

    public SchemaValidator(String classpathResource) {
        this.resourcePath = classpathResource;
        this.schema = load(classpathResource);
        log.info("[Schema] 已加载: {}", classpathResource);
    }

    private static JsonSchema load(String classpathResource) {
        try (InputStream in = SchemaValidator.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("找不到 Schema 资源: " + classpathResource);
            }
            JsonSchemaFactory factory = JsonSchemaFactory
                    .getInstance(SpecVersion.VersionFlag.V202012);
            return factory.getSchema(in);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "加载 Schema 失败: " + classpathResource, e);
        }
    }

    public List<ValidationError> validate(JsonNode node) {
        Set<ValidationMessage> raw = schema.validate(node);
        if (raw.isEmpty()) return List.of();
        List<ValidationError> errors = raw.stream()
                .map(ValidationError::from)
                .collect(Collectors.toList());
        log.debug("[Schema] {} 校验失败 {} 项：{}", resourcePath, errors.size(), errors);
        return errors;
    }

    public List<ValidationError> validate(String json) {
        return validate(Json.tree(json));
    }

    public boolean isValid(JsonNode node) {
        return validate(node).isEmpty();
    }
}
```

### 7.3 关键设计原则要写进注释

- **校验永不抛**：抛了上层得 try/catch，不利于 LLM 自纠错的循环写法（Day 3 会看到）
- **资源缺失要抛**：开发期能立刻发现路径错（IDE 编译没问题，运行时 NPE 才暴露最坑）
- **`JsonSchema` 线程安全**：一次加载、全程复用。不要在每次 `validate` 时新建

### ✅ Phase 4 验收

- [ ] `ValidationError` + `SchemaValidator` 编译通过
- [ ] 在 REPL（或新写一个临时 main）里 `new SchemaValidator(ANALYSIS_RESULT)` 不抛
- [ ] 故意把 resource path 写错一个字，启动时**立刻** `IllegalStateException` 而不是 NPE

---

## 8. Phase 5 · 测试样本（90 min）

### 8.1 资源目录

```
src/test/resources/schema-samples/
├── pass-leave-system.json
├── fail-missing-app.json
├── fail-bad-module-id.json
├── fail-bad-data-type.json
└── fail-recursive-bad.json
```

### 8.2 `pass-leave-system.json`（合规样本）

```json
{
  "app": {
    "name": "leaveSystem",
    "label": "请假管理",
    "type": "23"
  },
  "modules": [
    { "moduleName": "请假申请", "moduleId": "leaveRequest", "moduleDesc": "员工发起请假" },
    { "moduleName": "审批管理", "moduleId": "leaveApproval", "moduleDesc": "主管审批请假" }
  ],
  "models": [
    {
      "name": "请假单",
      "type": "TASK_MASTER_SLAVE",
      "pinyin": "qingjiadan",
      "tableName": "t_leave_request",
      "parentId": "",
      "fields": [
        { "comment": "主键", "name": "id", "dataType": "long", "usage": "primary", "relateModelType": "", "subs": null },
        { "comment": "申请人", "name": "applicantId", "dataType": "long", "usage": "foreign", "relateModelType": "", "subs": null },
        { "comment": "明细", "name": "details", "dataType": "array", "usage": "", "relateModelType": "collection", "subs": [
          { "comment": "天数", "name": "days", "dataType": "double", "usage": "", "relateModelType": "", "subs": null },
          { "comment": "类型", "name": "kind", "dataType": "string", "usage": "", "relateModelType": "", "subs": null }
        ]}
      ]
    },
    {
      "name": "审批记录",
      "type": "TASK",
      "pinyin": "shenpijilu",
      "tableName": "t_leave_approval",
      "parentId": "",
      "fields": [
        { "comment": "主键", "name": "id", "dataType": "long", "usage": "primary", "relateModelType": "", "subs": null },
        { "comment": "审批意见", "name": "remark", "dataType": "string", "usage": "", "relateModelType": "", "subs": null }
      ]
    }
  ],
  "warnings": [],
  "questions": ["请假明细是否需要附件字段？"]
}
```

### 8.3 `fail-missing-app.json`（缺顶层 app）

```json
{
  "modules": [
    { "moduleName": "X", "moduleId": "x", "moduleDesc": "" }
  ],
  "models": [],
  "warnings": [],
  "questions": []
}
```
预期错误：`required` on `app`。

### 8.4 `fail-bad-module-id.json`（moduleId 首字母大写）

```json
{
  "app": { "name": "x", "label": "X", "type": "23" },
  "modules": [
    { "moduleName": "存货", "moduleId": "Inventory", "moduleDesc": "" }
  ],
  "models": [
    { "name": "M", "type": "ENTITY", "pinyin": "m", "tableName": "t_m", "parentId": "",
      "fields": [ { "name": "id", "dataType": "long", "comment": "主键", "usage": "primary", "relateModelType": "", "subs": null } ]
    }
  ],
  "warnings": [],
  "questions": []
}
```
预期错误：`pattern` on `modules[0].moduleId`。

### 8.5 `fail-bad-data-type.json`（dataType 不在枚举）

```json
{
  "app": { "name": "x", "label": "X", "type": "23" },
  "modules": [
    { "moduleName": "M", "moduleId": "m", "moduleDesc": "" }
  ],
  "models": [
    { "name": "M", "type": "ENTITY", "pinyin": "m", "tableName": "t_m", "parentId": "",
      "fields": [
        { "name": "id", "dataType": "long", "comment": "主键", "usage": "primary", "relateModelType": "", "subs": null },
        { "name": "content", "dataType": "longtext", "comment": "正文", "usage": "", "relateModelType": "", "subs": null }
      ]
    }
  ],
  "warnings": [],
  "questions": []
}
```
预期错误：`enum` on `models[0].fields[1].dataType`。

### 8.6 `fail-recursive-bad.json`（递归点出错）

```json
{
  "app": { "name": "x", "label": "X", "type": "23" },
  "modules": [
    { "moduleName": "M", "moduleId": "m", "moduleDesc": "" }
  ],
  "models": [
    { "name": "M", "type": "TASK_MASTER_SLAVE", "pinyin": "m", "tableName": "t_m", "parentId": "",
      "fields": [
        { "name": "id", "dataType": "long", "comment": "主键", "usage": "primary", "relateModelType": "", "subs": null },
        { "name": "details", "dataType": "array", "comment": "明细", "usage": "", "relateModelType": "collection", "subs": [
          { "name": "days", "dataType": "JSON", "comment": "天数", "usage": "", "relateModelType": "", "subs": null }
        ]}
      ]
    }
  ],
  "warnings": [],
  "questions": []
}
```
预期错误：`enum` on `models[0].fields[1].subs[0].dataType`（注意路径深度）。

### 8.7 `SchemaValidatorTest.java`

`src/test/java/space/wlshow/scope/schema/SchemaValidatorTest.java`：

```java
package space.wlshow.scope.schema;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchemaValidatorTest {

    private final SchemaValidator validator =
            new SchemaValidator(SchemaValidator.ANALYSIS_RESULT);

    /** 从 classpath 读样本 JSON 文本，避免硬编码 src/test/... 物理路径 */
    private static String loadSample(String name) {
        String path = "/schema-samples/" + name;
        try (InputStream in = SchemaValidatorTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("找不到样本: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取样本失败: " + path, e);
        }
    }

    @Nested
    class Pass {
        @Test
        void leaveSystem_isValid() {
            List<ValidationError> errors =
                    validator.validate(loadSample("pass-leave-system.json"));
            assertTrue(errors.isEmpty(),
                    "应通过但失败了：" + errors);
        }
    }

    @Nested
    class Fail {

        @Test
        void missingApp() {
            var errors = validator.validate(loadSample("fail-missing-app.json"));
            assertFalse(errors.isEmpty());
            assertTrue(
                    errors.stream().anyMatch(e ->
                            "required".equals(e.keyword()) && e.message().contains("app")),
                    "应检出缺 app，实际错误：" + errors);
        }

        @Test
        void badModuleId() {
            var errors = validator.validate(loadSample("fail-bad-module-id.json"));
            assertTrue(errors.stream().anyMatch(e ->
                            "pattern".equals(e.keyword())
                            && e.path().contains("moduleId")),
                    "应检出 moduleId pattern 违反：" + errors);
        }

        @Test
        void badDataType_enumViolation() {
            var errors = validator.validate(loadSample("fail-bad-data-type.json"));
            assertTrue(errors.stream().anyMatch(e ->
                            "enum".equals(e.keyword())
                            && e.path().contains("dataType")),
                    "应检出 dataType 不在枚举：" + errors);
        }

        @Test
        void recursive_subsDataType() {
            var errors = validator.validate(loadSample("fail-recursive-bad.json"));
            // 关键：错误路径必须包含 subs，证明 $ref 递归生效
            assertTrue(errors.stream().anyMatch(e ->
                            e.path().contains("subs")
                            && e.path().contains("dataType")),
                    "应在 subs 内部检出错误，证明 $ref 递归生效。实际：" + errors);
        }
    }

    @Test
    void resourceMissing_failsFast() {
        var ex = assertThrows(IllegalStateException.class,
                () -> new SchemaValidator("/schemas/not-exist.schema.json"));
        assertTrue(ex.getMessage().contains("找不到 Schema 资源"));
    }
}
```

### 8.8 跑全套

```bash
mvn test
```

期望 5 个用例 + Phase 2 的 4 个 roundtrip + Day 1 留下的 sanity 测试，全绿。

> 💡 用 `mvn -q test -Dtest=SchemaValidatorTest` 只跑今天的测试，更快。

### ✅ Phase 5 验收

- [ ] `src/test/resources/schema-samples/` 5 个 JSON 就位
- [ ] `mvn test` 全绿
- [ ] 故意把 `pass-leave-system.json` 删掉一个 `parentId` 字段重跑，能看到中文 `required` 错误
- [ ] 在 `logs/scope.log` 里能看到 `[Schema] 已加载` 一行

---

## 9. Phase 6 · 收尾（30 min）

### 9.1 提交

```bash
git add pom.xml src/main/java src/main/resources/schemas src/test/java src/test/resources/schema-samples
git status   # 确认没有把 .env / application-local.conf 误带进去
git commit -m "day2: 数据契约 + JSON Schema 校验"
```

### 9.2 更新文档导航

- `docs/learning.md` § Day 2：把"计划"段落顶上加链接行（参考 Day 1 的 `> 📘 详细课程：...`）
- `README.md` 文档导航表 + 项目结构树
- `CLAUDE.md` § 9 表格添加 Day02 一行
- 本文件末尾"我的笔记"填 3 行

### 9.3 收工日记（≤ 5 分钟）

在 § 12 的"我的笔记"区随手写：

- 今天最坑的 1 个错（如：`$ref` 路径忘了 `#/`）
- 最有 aha 时刻的 1 个点（如：oneOf 同时允许 null 和 array 来表达"叶子/枝干"）
- Day 3 之前想搞清楚的 1 个问题（如：LLM 输出包了 ```json``` fence 怎么办？）

---

## 10. 当日完整验收清单（对照 learning.md）

- [x] Phase 0 题面 #11/#12/#13 复述无误
- [x] Phase 1 Jackson + json-schema-validator 依赖就位、`Json` 工具类可用
- [x] Phase 2 5 个 record + `JsonRoundTripTest` 4 用例全绿
- [x] Phase 3 `analysis-result.schema.json` 含 `$defs` + 递归 `$ref`、能加载
- [x] Phase 4 `SchemaValidator` + `ValidationError`，错误已中文化
- [x] Phase 5 5 套测试样本、`mvn test` 全绿
- [x] Phase 6 README + 文档导航更新 + commit

---

## 11. Day 3 预告

明天主题：**需求解析 + Structured Output**。

- 写 `prompts/analyst.md` 系统提示，含 few-shot
- 让 LLM 真的吐出今天 schema 期望的 JSON
- 当输出不合规时，把 `SchemaValidator` 的错误丢回 LLM 让它自纠错（3 次重试）
- 把 #6 要求的 warnings/questions 用一个故意模糊的需求触发

**预读**（晚上 30 分钟）：

- [../agents/03-react-agent.md § Structured Output](../agents/03-react-agent.md)
- [../learning.md § Day 3](../learning.md)
- AS-Java `Msg.getTextContent()` 的 fence-strip 套路（[Day 1 拓展 § stripFence](<Day01_项目骨架 + AS-Java Hello World.md>) 附录 A.2 已经写好）

---

## 附录 A · 速查表

### A.1 JSON Schema 关键字 → 中文映射

| 关键字 | 用法 | 我们用在哪 |
|--------|------|-----------|
| `type` | `"string" / "object" / ["string","null"]` | 全员 |
| `required` | 必填字段列表 | 顶层 + 4 个 spec |
| `enum` | 字面值白名单 | `DataModelSpec.type`、`FieldSpec.dataType` |
| `pattern` | 正则匹配 | `name` / `moduleId` / `pinyin` / `tableName` |
| `minLength` / `minItems` | 最短字符串 / 最少元素 | `fields.minItems: 1` |
| `additionalProperties: false` | 禁止额外字段 | 所有 object |
| `$defs` | 子 schema 字典 | `#/$defs/AppSpec` 等 |
| `$ref` | 引用 | `"#/$defs/FieldSpec"` |
| `oneOf` | 多形态之一 | `FieldSpec.subs`（null 或 array） |

### A.2 networknt `ValidationMessage` 关键 API

```java
m.getType()              // "enum"、"required"、"pattern" ...
m.getPath()              // "$.models[0].fields[1].dataType"
m.getInstanceLocation()  // JsonNodePath 对象（部分版本提供）
m.getArguments()         // 关键字相关的额外参数，比如枚举的 allowed values
m.getMessage()           // 默认英文消息
```

### A.3 把 LLM 的 JSON 拿来校验（Day 3 会用）

```java
SchemaValidator v = new SchemaValidator(SchemaValidator.ANALYSIS_RESULT);
List<ValidationError> errs = v.validate(stripFence(llmText));
if (errs.isEmpty()) {
    AnalysisResult ar = Json.read(stripFence(llmText), AnalysisResult.class);
    // 用起来
} else {
    // 把 errs 丢回 LLM 让它改
}
```

---

## 附录 B · 故障排查速查

| 现象 | 怀疑方向 | 修复 |
|------|---------|------|
| `getResourceAsStream("schemas/...")` 返回 null | 缺前导 `/` 或文件不在 `src/main/resources/` | 一律用 `/schemas/analysis-result.schema.json`，确认资源进了 `target/classes/schemas/` |
| `JsonSchemaException: $ref ... cannot be resolved` | `$ref` 写错 | 必须 `"#/$defs/Xxx"`，不能省 `#/` |
| `Cannot construct instance of FieldSpec ... no Creators` | Jackson < 2.12 | 锁版本 2.17.0，或检查依赖树是否被某传递依赖压成 2.10 |
| `UnrecognizedPropertyException` | `Json` 忘了 `FAIL_ON_UNKNOWN_PROPERTIES=false` | 见 §4.2 |
| `ValidationMessage.getPath()` 是 `$` 空路径 | networknt 在顶层错误时只返回 `$` | 那就是顶层错（如 `required: app`），不是 bug |
| 递归 schema 校验慢 | 嵌套 + `additionalProperties:false` 双重叠加 | Day 2 范围内可忽略；Day 7 性能优化时再说 |
| 中文错误消息乱码 | logback / 控制台编码问题 | 见 [Day01 附录 B-2](<Day01_项目骨架 + AS-Java Hello World.md>) |
| `mvn test` 报 ` Surefire ... 模块路径` 错 | Surefire 与 JDK 17 模块系统冲突 | `pom.xml` 已锁 Surefire 3.2.5，一般不会遇到。遇到加 `--add-opens` |

### 附录 B-1 · `$ref` 递归引用的常见错法

❌ 错法 1：JSON Pointer 缺斜杠
```json
{ "subs": { "items": { "$ref": "$defs/FieldSpec" } } }   // ← 缺 #/
```

❌ 错法 2：错写成 `$id` 引用
```json
{ "subs": { "items": { "$ref": "FieldSpec" } } }
```
这种写法只在配 `$id` 的"定位符"模式下才有效，本文我们没用 `$id` 做相对寻址，networknt 直接报无法解析。

❌ 错法 3：`additionalProperties:false` 卡掉了 `subs`
```json
"FieldSpec": { "additionalProperties": false, "required": ["name","dataType"] }
```
本身没问题，但如果你忘了在 `properties` 里声明 `subs`，那带 `subs` 字段的 JSON 会被 `additionalProperties` 拦截。我们在 §6.2 已经把 `subs` 列出来了。

### 附录 B-2 · 测试样本路径加载错误

如果 `loadSample(...)` 报 `找不到样本`，先检查：

1. 文件是否在 `src/test/resources/schema-samples/` 下（不是 `src/test/java/...`）
2. IDE 是否把 `src/test/resources` 标成了 Test Resources 根（IntelliJ：File → Project Structure → Modules → Sources 标签里 mark as resources）
3. `mvn test` 跑之前先 `mvn process-test-resources`，看 `target/test-classes/schema-samples/` 是否有文件

---

## 附录 C · 我的笔记（学习时填）

> 在这下面随手记，下次回看是金矿。

- 最坑：
- aha：
- 想搞清楚：
