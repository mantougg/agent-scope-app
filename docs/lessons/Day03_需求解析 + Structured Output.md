# Day 3 · 需求解析 + Structured Output

> 上级文档：[../learning.md](../learning.md)
> 配套笔记：[../agents/02-core-concepts.md § Msg](../agents/02-core-concepts.md) · [../agents/03-react-agent.md § Structured Output](../agents/03-react-agent.md)
> 前置：[Day 2 · 数据契约 + JSON Schema 校验](<Day02_数据契约 + JSON Schema 校验.md>) 已完成

## 0. 一句话目标

**今天结束时**，你能在 CLI 里输入一句"做一个简单请假系统"，回车后 LLM 输出**合法的 `AnalysisResult` JSON**（通过 Day 2 的 `SchemaValidator`），不合规时 Agent 会**自动修正最多 3 次**；故意写一句"做个系统"还能在 `warnings` / `questions` 里看到 LLM 对模糊点的标记；`mvn test` 在不联网的情况下也能跑过（WireMock 兜底）。

> ⚠️ Day 3 **不引 Spring、不写 Tool、不接前端**。只在 Day 1 的 REPL 入口上加一条 `parse` 命令，把 Day 2 的契约真正"喂"给 LLM 用。

## 1. 学习目标

- ✅ 写出第一个**只输出 JSON** 的 system prompt（含 few-shot）
- ✅ 摸熟 LLM 三种典型 JSON 漂移：fence 包裹 / 多余文字 / 字段缺失
- ✅ 实现 `RequirementParser`：调用 → fence 剥离 → Schema 校验 → 不合规自纠错 → 最多 3 次
- ✅ 用 `warnings` / `questions` 让 LLM 把不确定信息显式化（呼应需求 #6）
- ✅ 用 WireMock 录三段真实响应，做到无 API Key 也能跑测试

## 2. 时间盒（建议 8 学时）

| 阶段 | 时长 | 主题 | 验收 |
|------|------|------|------|
| Phase 0 | 30 min | 资料预读 + LLM 漂移样本回顾 | 能说出至少 3 种典型漂移 |
| Phase 1 | 60 min | System Prompt + Few-shot 落盘 | `prompts/analyst.md` 跟 schema 字段名一一对应 |
| Phase 2 | 45 min | `Json.stripFence` 与 JSON 修复 | 单元测试覆盖 4 种 fence 形态 |
| Phase 3 | 90 min | `RequirementParser` 三次重试 | 合法/不合法各跑一次，重试日志完整 |
| Phase 4 | 60 min | warnings/questions 触发 | 故意模糊需求能拿到 ≥1 条 question |
| Phase 5 | 90 min | WireMock 离线回放 | `mvn test` 拔网线全绿 |
| Phase 6 | 30 min | REPL 加 `parse` 命令 + commit | `day3: ...` commit、文档导航更新 |

---

## 3. Phase 0 · 资料预读（30 min）

### 3.1 LLM JSON 漂移的三个典型病灶

回顾你 Day 2 凭直觉就能写出来的 5 个 Schema 错位，再加上 **LLM 实际生成** 的额外问题：

| 漂移类型 | 真实样例 | 出现概率 | 我们的兜底 |
|---------|---------|---------|-----------|
| **fence 包裹** | ` ```json\n{...}\n``` ` 或 ` ```\n{...}\n``` ` | 极高（部分模型默认就吐 fence） | `stripFence()` 统一剥 |
| **前后多余说明** | `好的，以下是 JSON:\n{...}\n\n如果还需要修改请告诉我` | 高 | 取第一个 `{` 到最后一个 `}` |
| **字段缺失 / 多余** | 漏 `parentId`，或多吐 `description` 字段 | 中 | Day 2 SchemaValidator 拒收 + 重试 |
| **枚举漂移** | `dataType: "text"` 而 schema 定义只允许 `string` | 中 | Schema 校验报错 → 把 errors 灌回 prompt |
| **递归断层** | `dataType: "array"` 但没有 `subs` 数组 | 低（少 prompt 时常见） | few-shot 示范 + warning |
| **类型字符串化** | `"parentId": null` 写成 `"parentId": "null"` | 低 | Schema 拒收，让 LLM 自己修 |

> 📌 **设计原则**：**不在 Java 侧"猜"LLM 想说什么**。能用 Schema 直接拒掉的就拒掉，把错误原文喂回给 LLM 让它自己改 — 这比写 5 个正则修复都稳定。

### 3.2 Few-shot 的本质：让 LLM "见过"才会"画对"

- LLM 不会从 schema 文字 reasoning 出递归 master-slave 结构，**它需要看过一个"含 subs 的 fields 数组"才知道怎么写**
- 我们 prompt 给两条 few-shot：
  - **示例 A（最简）**：1 App + 1 Module + 1 ENTITY 模型，5 个字段全是叶子
  - **示例 B（master-slave）**：1 App + 1 Module + 1 TASK_MASTER_SLAVE 模型，含 1 个 `dataType=array` 字段嵌 3 个子字段
- 不给 3+ 个示例的原因：**token 越多漂移越多**，2 个示例打底足够，剩下交给 schema 强制

### 3.3 自纠错回路

```
+-------------------+
| 用户中文需求       |
+--------+----------+
         | call(userText)
         v
+-------------------+        合法
|   ReActAgent      |---------> AnalysisResult（POJO）
+--------+----------+
         | 输出 JSON 文本
         v
+-------------------+        不合法
|   stripFence      |
+--------+----------+
         |
         v
+-------------------+
|  SchemaValidator  |
+--------+----------+
         | errors 非空
         v
+-------------------+
| retry prompt:      |
| "上一次输出错了：   |
|  $errors          |
|  请只输出修正后的   |
|  JSON"            |
+--------+----------+
         |
         v  最多 3 次
       中止
```

### 3.4 预读链接

- AS-Java 流式 / 非流式调用差异：[../agents/03-react-agent.md § 调用模式](../agents/03-react-agent.md)
- Anthropic Prompt Engineering Guide §JSON output：https://docs.anthropic.com/zh-CN/docs/build-with-claude/prompt-engineering/json-mode（思路通用）
- 火山方舟 / OpenAI 的 `response_format=json_object`：本课不开（依赖 provider，且不解决字段缺失问题）

### ✅ Phase 0 验收

- [ ] 能口述 4 种 LLM JSON 漂移类型
- [ ] 能解释为什么只给 2 个 few-shot 而不是 5 个
- [ ] 在白纸上画出 3 次重试的状态机

---

## 4. Phase 1 · System Prompt + Few-shot（60 min）

### 4.1 目录就位

```
src/main/resources/
└── prompts/
    └── analyst.md
```

### 4.2 编写 `prompts/analyst.md`

完整文件（直接拷贝，跟 Day 2 的字段名严格一致）：

````markdown
你是「需求分析助手」，专长是把中文业务需求拆解为「应用 / 模块 / 数据模型」三层结构。

# 强制规则
1. **只输出 JSON**，不要任何解释、寒暄、Markdown fence、思考过程。
2. JSON 结构遵循下方 `# Schema 字段`。字段名严格区分大小写。
3. `moduleId` 必须为 camelCase；`name` 字段为英文单词，跟中文 `label/moduleName/comment` 一一对应。
4. 数据模型 `type` 仅允许：`TASK_MASTER_SLAVE`（含明细）/ `TASK`（无明细的单据）/ `ENTITY`（主数据/字典）。
5. 字段 `dataType` 仅允许：`long`、`int`、`double`、`string`、`boolean`、`date`、`array`。
6. **主键约定**：每个数据模型必须包含 `name=id, dataType=long, usage=primary` 的字段。
7. **明细约定**：含明细的单据用 `TASK_MASTER_SLAVE`，明细放在 `fields` 数组里 `dataType=array` 的字段的 `subs` 里。
8. **不确定信息**：
   - 用户没说但你做了假设的，写进 `warnings` 数组。例：`"假设 app.type 取 23（业务管理类）"`
   - 用户需要明确回答你才能继续的，写进 `questions` 数组。例：`"请假是否需要附件上传？"`
   - 没有时也必须返回空数组 `[]`，不要漏字段。

# Schema 字段（顶层即 AnalysisResult）
```
{
  "app":      { "name": "...", "label": "...", "type": "..." },
  "modules":  [ { "moduleName": "...", "moduleId": "...", "moduleDesc": "..." } ],
  "models":   [
    {
      "name": "...", "type": "ENTITY|TASK|TASK_MASTER_SLAVE",
      "pinyin": "...", "tableName": "...", "parentId": "",
      "fields": [
        { "comment": "...", "name": "id", "dataType": "long", "usage": "primary",
          "relateModelType": "", "subs": null }
      ]
    }
  ],
  "warnings":  [ "..." ],
  "questions": [ "..." ]
}
```

# 示例 A（最简：员工管理）
用户输入：
"做一个简单的员工档案管理，记录姓名、工号、入职日期、部门。"

输出：
```
{
  "app": { "name": "employeeMgr", "label": "员工档案管理", "type": "23" },
  "modules": [
    { "moduleName": "员工管理", "moduleId": "employeeMgmt", "moduleDesc": "维护员工档案" }
  ],
  "models": [
    {
      "name": "employee", "type": "ENTITY", "pinyin": "yuangong",
      "tableName": "t_employee", "parentId": "",
      "fields": [
        { "comment": "主键", "name": "id", "dataType": "long", "usage": "primary", "relateModelType": "", "subs": null },
        { "comment": "姓名", "name": "name", "dataType": "string", "usage": "", "relateModelType": "", "subs": null },
        { "comment": "工号", "name": "empCode", "dataType": "string", "usage": "", "relateModelType": "", "subs": null },
        { "comment": "入职日期", "name": "hireDate", "dataType": "date", "usage": "", "relateModelType": "", "subs": null },
        { "comment": "部门", "name": "deptName", "dataType": "string", "usage": "", "relateModelType": "", "subs": null }
      ]
    }
  ],
  "warnings": ["假设 app.type 取 23（业务管理类）"],
  "questions": []
}
```

# 示例 B（master-slave：请假申请，含明细）
用户输入：
"做请假管理，员工提交多条请假明细（每条带请假类型、开始结束日期），主管审批整张单。"

输出：
```
{
  "app": { "name": "leaveMgr", "label": "请假管理", "type": "23" },
  "modules": [
    { "moduleName": "请假申请", "moduleId": "leaveApply", "moduleDesc": "员工提交请假单与明细" }
  ],
  "models": [
    {
      "name": "leaveBill", "type": "TASK_MASTER_SLAVE", "pinyin": "qingjiadan",
      "tableName": "t_leave_bill", "parentId": "",
      "fields": [
        { "comment": "主键", "name": "id", "dataType": "long", "usage": "primary", "relateModelType": "", "subs": null },
        { "comment": "申请人", "name": "applicantId", "dataType": "long", "usage": "foreign", "relateModelType": "", "subs": null },
        { "comment": "申请日期", "name": "applyDate", "dataType": "date", "usage": "", "relateModelType": "", "subs": null },
        { "comment": "请假明细", "name": "details", "dataType": "array", "usage": "", "relateModelType": "collection",
          "subs": [
            { "comment": "明细主键", "name": "id", "dataType": "long", "usage": "primary", "relateModelType": "", "subs": null },
            { "comment": "请假类型", "name": "leaveType", "dataType": "string", "usage": "", "relateModelType": "", "subs": null },
            { "comment": "开始日期", "name": "startDate", "dataType": "date", "usage": "", "relateModelType": "", "subs": null },
            { "comment": "结束日期", "name": "endDate", "dataType": "date", "usage": "", "relateModelType": "", "subs": null }
          ]
        }
      ]
    }
  ],
  "warnings": [],
  "questions": ["明细日期是否允许同一天（按小时算）？"]
}
```

# 现在请处理用户的新需求
````

### 4.3 读取 prompt 的工具方法

新建 `src/main/java/space/wlshow/scope/util/Prompts.java`：

```java
package space.wlshow.scope.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 加载 classpath 下 prompts/*.md，并缓存避免重复读盘。 */
public final class Prompts {

    private static volatile String analyst;

    public static String analyst() {
        if (analyst == null) {
            synchronized (Prompts.class) {
                if (analyst == null) {
                    analyst = read("/prompts/analyst.md");
                }
            }
        }
        return analyst;
    }

    private static String read(String resource) {
        try (InputStream in = Prompts.class.getResourceAsStream(resource)) {
            Objects.requireNonNull(in, "prompt 资源不存在: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 prompt 失败: " + resource, e);
        }
    }

    private Prompts() {}
}
```

### 4.4 把 prompt 接到 `AgentFactory`

打开 `src/main/java/space/wlshow/scope/agent/AgentFactory.java`，加一个**专门的解析 Agent 构造方法**：

```java
/**
 * 构造"需求解析"专用 Agent：
 * - 强制 system prompt
 * - 关闭 tool（Day 3 还不接 Toolkit）
 * - 关闭 stream（Day 3 要拿完整 JSON 一次解析）
 */
public static ReActAgent buildParser() {
    initModels();
    return ReActAgent.builder()
            .name("RequirementAnalyst")
            .sysPrompt(Prompts.analyst())
            .model(ModelRegistry.resolve(DEFAULT_MODEL_ID))
            .maxIters(2)             // 解析任务一次性回答，不需要多步推理
            .build();
}
```

> 📌 **maxIters=2**：Day 3 没工具，理论上 1 次就够。给 2 是留一次模型自纠错预算（如有 1.1 Harness 的话）。

### ✅ Phase 1 验收

- [ ] `prompts/analyst.md` 存在，文件编码 UTF-8
- [ ] `Prompts.analyst()` 第二次调用毫秒返回（命中缓存）
- [ ] `mvn compile` 通过

---

## 5. Phase 2 · `stripFence` 与 JSON 修复（45 min）

### 5.1 给 `util/Json.java` 加 fence 剥离

打开 `src/main/java/space/wlshow/scope/util/Json.java`，追加：

```java
    /**
     * 剥离 Markdown 代码 fence 包裹的 JSON。容错：
     * - ```json\n{...}\n```
     * - ```\n{...}\n```
     * - 前后有寒暄文字
     * - 没有 fence 直接返回
     */
    public static String stripFence(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // 优先：找到第一段 fence
        int fenceStart = s.indexOf("```");
        if (fenceStart >= 0) {
            int contentStart = s.indexOf('\n', fenceStart);
            int fenceEnd = s.lastIndexOf("```");
            if (contentStart > 0 && fenceEnd > contentStart) {
                return s.substring(contentStart + 1, fenceEnd).trim();
            }
        }
        // 兜底：截取第一个 { 到最后一个 }
        int objStart = s.indexOf('{');
        int objEnd = s.lastIndexOf('}');
        if (objStart >= 0 && objEnd > objStart) {
            return s.substring(objStart, objEnd + 1);
        }
        return s;
    }
```

### 5.2 单元测试 `JsonStripFenceTest`

新建 `src/test/java/space/wlshow/scope/util/JsonStripFenceTest.java`：

```java
package space.wlshow.scope.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonStripFenceTest {

    @Test
    void plainJson_returnAsIs() {
        String s = "{\"a\":1}";
        assertEquals(s, Json.stripFence(s));
    }

    @Test
    void fencedWithLang_strip() {
        String raw = "```json\n{\"a\":1}\n```";
        assertEquals("{\"a\":1}", Json.stripFence(raw));
    }

    @Test
    void fencedNoLang_strip() {
        String raw = "```\n{\"a\":1}\n```";
        assertEquals("{\"a\":1}", Json.stripFence(raw));
    }

    @Test
    void chitchatBeforeAndAfter_strip() {
        String raw = "好的，以下是 JSON：\n{\"a\":1}\n如有疑问请告诉我";
        assertEquals("{\"a\":1}", Json.stripFence(raw));
    }

    @Test
    void multilineJson_keep() {
        String raw = "```json\n{\n  \"a\": 1,\n  \"b\": [1,2]\n}\n```";
        String got = Json.stripFence(raw);
        assertEquals("{\n  \"a\": 1,\n  \"b\": [1,2]\n}", got);
    }
}
```

跑一遍：

```bash
mvn -q test -Dtest=JsonStripFenceTest
```

### ✅ Phase 2 验收

- [ ] 5 个用例全过
- [ ] `mvn test` 全集仍然全绿（没破坏 Day 2 的测试）

---

## 6. Phase 3 · `RequirementParser`（90 min）

### 6.1 异常类

新建 `src/main/java/space/wlshow/scope/agent/ParseException.java`：

```java
package space.wlshow.scope.agent;

import java.util.List;

public class ParseException extends RuntimeException {
    private final List<String> lastErrors;

    public ParseException(String message, List<String> lastErrors) {
        super(message);
        this.lastErrors = lastErrors == null ? List.of() : List.copyOf(lastErrors);
    }

    public List<String> lastErrors() { return lastErrors; }
}
```

### 6.2 解析器主体

新建 `src/main/java/space/wlshow/scope/agent/RequirementParser.java`：

```java
package space.wlshow.scope.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.model.AnalysisResult;
import space.wlshow.scope.schema.SchemaValidator;
import space.wlshow.scope.util.Json;

import java.util.List;

/**
 * 把用户中文需求 -> 合法的 AnalysisResult。
 * 失败时最多重试 3 次，每次把上一轮 schema 错误回灌给 LLM。
 */
public class RequirementParser {

    private static final Logger log = LoggerFactory.getLogger(RequirementParser.class);
    private static final int MAX_ATTEMPTS = 3;

    private final ReActAgent agent;
    private final SchemaValidator validator;

    public RequirementParser(ReActAgent agent, SchemaValidator validator) {
        this.agent = agent;
        this.validator = validator;
    }

    public AnalysisResult parse(String userRequirement) {
        String prompt = userRequirement;
        List<String> lastErrors = List.of();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            log.info("[Parse] attempt={} promptHeadChars={}", attempt,
                    prompt.substring(0, Math.min(80, prompt.length())));

            Msg out = agent.call(Msg.builder()
                    .role(io.agentscope.core.message.MsgRole.USER)
                    .content(TextBlock.builder().text(prompt).build())
                    .build()).block();

            if (out == null) {
                throw new ParseException("LLM 返回 null", List.of("agent.call() returned null"));
            }

            String raw = out.getTextContent();
            String json = Json.stripFence(raw);
            log.debug("[Parse] raw chars={} stripped chars={}", raw.length(), json.length());

            try {
                JsonNode node = Json.tree(json);
                lastErrors = validator.validate(node);
                if (lastErrors.isEmpty()) {
                    AnalysisResult result = Json.mapper().treeToValue(node, AnalysisResult.class);
                    log.info("[Parse] success on attempt {}", attempt);
                    return result;
                }
                log.warn("[Parse] attempt {} schema errors: {}", attempt, lastErrors);
            } catch (Exception e) {
                lastErrors = List.of("JSON 解析失败: " + e.getMessage());
                log.warn("[Parse] attempt {} json broken: {}", attempt, e.getMessage());
            }

            prompt = buildRetryPrompt(userRequirement, json, lastErrors);
        }

        throw new ParseException(
                "LLM 输出连续 " + MAX_ATTEMPTS + " 次不符合 schema，已放弃。",
                lastErrors);
    }

    private static String buildRetryPrompt(String original, String lastJson, List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("上一次你的输出不符合 schema，错误如下：\n");
        for (int i = 0; i < errors.size(); i++) {
            sb.append((i + 1)).append(". ").append(errors.get(i)).append("\n");
        }
        sb.append("\n上一次的 JSON（节选前 500 字）：\n");
        sb.append(lastJson.substring(0, Math.min(500, lastJson.length())));
        sb.append("\n\n原始用户需求：\n");
        sb.append(original);
        sb.append("\n\n请只输出修正后的完整 JSON，不要解释。");
        return sb.toString();
    }
}
```

### 6.3 REPL 加 `parse` 命令

打开 `src/main/java/space/wlshow/scope/ScopeApp.java`，在 REPL 循环里加分支：

```java
} else if (input.startsWith("/parse ")) {
    String req = input.substring("/parse ".length()).trim();
    if (req.isEmpty()) {
        System.out.println("用法：/parse <中文需求>");
        continue;
    }
    try {
        AnalysisResult result = parser.parse(req);
        System.out.println("[PARSED]\n" + Json.writePretty(result));
    } catch (ParseException e) {
        System.out.println("[PARSE-FAIL] " + e.getMessage());
        e.lastErrors().forEach(err -> System.out.println("  - " + err));
    }
}
```

并在 main 顶部初始化：

```java
ReActAgent parserAgent = AgentFactory.buildParser();
SchemaValidator validator = new SchemaValidator("/schemas/analysis-result.schema.json");
RequirementParser parser = new RequirementParser(parserAgent, validator);
```

### 6.4 跑一次

```bash
mvn -q compile exec:java
> /parse 做一个简单的员工档案管理，记录姓名、工号、入职日期、部门
[PARSED]
{
  "app": { ... },
  ...
}
```

> ⚠️ 如果你看到 `LLM 返回 null` 或者超时，先看 `logs/scope.log`。常见原因：模型流式开关、API Key 未注入、网络抖动。Day 1 附录 B-2 有故障表。

### ✅ Phase 3 验收

- [ ] `/parse` 命令能跑通至少 1 个真实需求
- [ ] 故意把 system prompt 改成只输出 `{"foo":1}` 让 schema 必然报错，确认看到 3 次重试日志
- [ ] 改回 prompt，确认正常

---

## 7. Phase 4 · warnings / questions 触发（60 min）

### 7.1 故意模糊的输入

构造 3 类输入，验证 LLM 把不确定信息显式化：

| 输入 | 期望 |
|------|------|
| "做个系统" | `warnings` 应说明强假设；`questions` ≥ 2 条 |
| "做请假管理" | `warnings` 描述明细字段假设；`questions` 含"附件/审批人/类型清单"之一 |
| "做一个员工档案" | `warnings` 描述字段假设；`questions` 可能为空（题目已较清晰） |

### 7.2 测试类 `RequirementParserLiveTest`

新建 `src/test/java/space/wlshow/scope/agent/RequirementParserLiveTest.java`：

```java
package space.wlshow.scope.agent;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import space.wlshow.scope.agent.AgentFactory;
import space.wlshow.scope.model.AnalysisResult;
import space.wlshow.scope.schema.SchemaValidator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 LLM 测试，依赖 ARK_API_KEY。CI 默认不跑：用 @Tag("live") 标识。
 * 本地手动跑：mvn -Dgroups=live test
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ARK_API_KEY", matches = ".+")
class RequirementParserLiveTest {

    private final RequirementParser parser = new RequirementParser(
            AgentFactory.buildParser(),
            new SchemaValidator("/schemas/analysis-result.schema.json"));

    @Test
    void vagueInput_shouldFillQuestions() {
        AnalysisResult r = parser.parse("做个系统");
        assertFalse(r.questions().isEmpty(),
                "极模糊的需求应至少触发 1 个 question，实际 questions=" + r.questions());
    }

    @Test
    void mediumInput_shouldProduceValidSpec() {
        AnalysisResult r = parser.parse("做一个简单的员工档案管理，字段：姓名、工号、入职日期、部门");
        assertNotNull(r.app());
        assertFalse(r.modules().isEmpty());
        assertFalse(r.models().isEmpty());
        // 主键必须存在
        assertTrue(r.models().get(0).fields().stream()
                .anyMatch(f -> "id".equals(f.name()) && "primary".equals(f.usage())));
    }

    @Test
    void masterSlaveInput_shouldUseMasterSlaveType() {
        AnalysisResult r = parser.parse("做请假管理，员工提交多条请假明细，主管审批整张单");
        assertTrue(r.models().stream().anyMatch(m -> "TASK_MASTER_SLAVE".equals(m.type())),
                "含明细的单据应至少有一个 TASK_MASTER_SLAVE 模型，实际 types=" +
                        r.models().stream().map(m -> m.type()).toList());
    }
}
```

### 7.3 在 `pom.xml` 配 JUnit Tags

`maven-surefire-plugin` 段补：

```xml
<configuration>
    <argLine>-Dfile.encoding=UTF-8</argLine>
    <excludedGroups>live</excludedGroups>     <!-- CI 默认不跑 live -->
</configuration>
```

> 📌 这样 `mvn test` 不会因为没设 `ARK_API_KEY` 报红，跑 live 用 `mvn -Dgroups=live test`。

### ✅ Phase 4 验收

- [ ] 本地有 `ARK_API_KEY` 时 `mvn -Dgroups=live test` 三条测试全过
- [ ] CI（没 key 的环境）`mvn test` 也不报红

---

## 8. Phase 5 · WireMock 离线回放（90 min）

### 8.1 思路

- Day 1 的 `WireMockAgentTest` 已经能 mock 一次 chat completion。今天扩展：**录三段真实响应**（一段合法、一段缺字段、一段含 fence）回放，验证 `RequirementParser` 的重试链路在**完全离线**下能正确收敛。

### 8.2 录响应（一次性，手工）

跑一次真实调用，把 chat completion 的 response body 落到 `src/test/resources/wiremock/__files/`：

```
src/test/resources/wiremock/__files/
├── analyst-ok.json           # 完全合法的 AnalysisResult
├── analyst-bad-fence.json    # 用 ```json 包裹的合法 JSON（验证 stripFence）
└── analyst-missing-app.json  # 缺 app 字段的不合法 JSON（验证重试）
```

样例 `analyst-ok.json`（火山方舟兼容 OpenAI 格式）：

```json
{
  "id": "chatcmpl-xxx",
  "object": "chat.completion",
  "created": 1736000000,
  "model": "doubao-pro",
  "choices": [{
    "index": 0,
    "finish_reason": "stop",
    "message": {
      "role": "assistant",
      "content": "{\"app\":{\"name\":\"employeeMgr\",\"label\":\"员工档案管理\",\"type\":\"23\"},\"modules\":[{\"moduleName\":\"员工管理\",\"moduleId\":\"employeeMgmt\",\"moduleDesc\":\"维护员工档案\"}],\"models\":[{\"name\":\"employee\",\"type\":\"ENTITY\",\"pinyin\":\"yuangong\",\"tableName\":\"t_employee\",\"parentId\":\"\",\"fields\":[{\"comment\":\"主键\",\"name\":\"id\",\"dataType\":\"long\",\"usage\":\"primary\",\"relateModelType\":\"\",\"subs\":null}]}],\"warnings\":[],\"questions\":[]}"
    }
  }],
  "usage": { "prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150 }
}
```

样例 `analyst-bad-fence.json`：把 content 改成 ` "```json\n{...}\n```" `。

样例 `analyst-missing-app.json`：把 content 改成 `"{\"modules\":[],\"models\":[],\"warnings\":[],\"questions\":[]}"`。

### 8.3 测试类 `RequirementParserMockTest`

新建 `src/test/java/space/wlshow/scope/agent/RequirementParserMockTest.java`：

```java
package space.wlshow.scope.agent;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.agentscope.core.agent.ReActAgent;
import io.agentscope.core.model.OpenAIChatModel;
import org.junit.jupiter.api.*;
import space.wlshow.scope.model.AnalysisResult;
import space.wlshow.scope.schema.SchemaValidator;
import space.wlshow.scope.util.Prompts;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

class RequirementParserMockTest {

    static WireMockServer server;
    static SchemaValidator validator;

    @BeforeAll
    static void setup() {
        server = new WireMockServer(wireMockConfig()
                .dynamicPort()
                .usingFilesUnderClasspath("wiremock"));
        server.start();
        validator = new SchemaValidator("/schemas/analysis-result.schema.json");
    }

    @AfterAll
    static void tearDown() { server.stop(); }

    @AfterEach
    void reset() { server.resetRequests(); server.resetMappings(); }

    @Test
    void okOnFirstAttempt() {
        stubChat("analyst-ok.json");
        AnalysisResult r = newParser().parse("做一个员工档案管理");
        assertEquals("employeeMgr", r.app().name());
        assertEquals(1, server.getAllServeEvents().size(), "只应调用 1 次");
    }

    @Test
    void recoverFromFence() {
        stubChat("analyst-bad-fence.json");
        AnalysisResult r = newParser().parse("做一个员工档案管理");
        assertNotNull(r.app());        // fence 被 stripFence 剥掉，第一次就成功
    }

    @Test
    void retryAfterMissingField() {
        // 第一次返回缺 app，第二次返回正常
        server.stubFor(post(urlPathMatching(".*/chat/completions"))
                .inScenario("retry")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("analyst-missing-app.json"))
                .willSetStateTo("got-bad"));
        server.stubFor(post(urlPathMatching(".*/chat/completions"))
                .inScenario("retry")
                .whenScenarioStateIs("got-bad")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("analyst-ok.json")));

        AnalysisResult r = newParser().parse("做一个员工档案管理");
        assertNotNull(r.app());
        assertEquals(2, server.getAllServeEvents().size(), "应该是 1 次失败 + 1 次成功");
    }

    @Test
    void giveUpAfterThree() {
        stubChat("analyst-missing-app.json");
        ParseException ex = assertThrows(ParseException.class,
                () -> newParser().parse("做一个员工档案管理"));
        assertEquals(3, server.getAllServeEvents().size(), "应该调用 3 次后放弃");
        assertFalse(ex.lastErrors().isEmpty());
    }

    private void stubChat(String fileName) {
        server.stubFor(post(urlPathMatching(".*/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile(fileName)));
    }

    private RequirementParser newParser() {
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey("test-key")
                .modelName("doubao-pro")
                .baseUrl(server.baseUrl())     // 指向 WireMock
                .stream(false)
                .build();
        ReActAgent agent = ReActAgent.builder()
                .name("AnalystTest")
                .sysPrompt(Prompts.analyst())
                .model(model)
                .maxIters(1)
                .build();
        return new RequirementParser(agent, validator);
    }
}
```

### 8.4 跑测试

```bash
mvn -q test -Dtest=RequirementParserMockTest
```

> ⚠️ 如果 baseUrl 注入不生效，看 Day 1 `WireMockAgentTest` 的写法对齐。AS-Java 不同 minor 版本的 builder 字段名可能略不同。

### ✅ Phase 5 验收

- [ ] 4 个 mock 用例全过
- [ ] **拔掉网线** `mvn test` 仍然全绿（关掉 ARK_API_KEY 也行）

---

## 9. Phase 6 · 收尾（30 min）

### 9.1 commit

```bash
git add src/main/resources/prompts/ \
        src/main/java/space/wlshow/scope/util/Prompts.java \
        src/main/java/space/wlshow/scope/agent/RequirementParser.java \
        src/main/java/space/wlshow/scope/agent/ParseException.java \
        src/main/java/space/wlshow/scope/agent/AgentFactory.java \
        src/main/java/space/wlshow/scope/ScopeApp.java \
        src/main/java/space/wlshow/scope/util/Json.java \
        src/test/java/space/wlshow/scope/util/JsonStripFenceTest.java \
        src/test/java/space/wlshow/scope/agent/RequirementParserMockTest.java \
        src/test/java/space/wlshow/scope/agent/RequirementParserLiveTest.java \
        src/test/resources/wiremock/__files/ \
        pom.xml
git commit -m "day3: 需求解析 + Structured Output

- 编写 analyst.md 含 2 个 few-shot
- 实现 RequirementParser 三次自纠错
- stripFence 处理 fence / 寒暄包裹
- WireMock 离线回放 4 个用例，CI 不依赖真实 LLM
- LiveTest 标 @Tag(live) 本地手动验证 warnings/questions"
```

### 9.2 把课程加入文档导航

打开根目录 `README.md`，"文档导航"一节加：

```
- [Day 3 · 需求解析 + Structured Output](docs/lessons/Day03_需求解析%20%2B%20Structured%20Output.md)
```

打开 `CLAUDE.md` 第 9 节表格，加一行。

### 9.3 给自己 5 分钟回顾

回答这 3 个问题（口头即可）：

1. 为什么不在 Java 侧用正则修 LLM 输出？答：见 Phase 0 设计原则
2. 重试上限定 3 次的依据？答：经验值。多于 3 次大多是 prompt/模型本身的问题，重试再多也不会收敛
3. WireMock 测试覆盖了哪几条故障路径？答：fence、缺字段重试、连续 3 次失败

### ✅ Phase 6 验收

- [ ] `git log --oneline -1` 看到 `day3: ...`
- [ ] `mvn test` 全绿
- [ ] README / CLAUDE.md 导航更新

---

## 10. 故障排查表

| 现象 | 原因 / 排查方向 |
|------|-----------------|
| `LLM 返回 null` | `agent.call(...).block()` 超时；调大 `cfg.timeout`，或确认模型 `stream(false)` |
| 第一次就过但内容是英文 | system prompt 没生效；检查 `AgentFactory.buildParser()` 是否调用了 `Prompts.analyst()` |
| `JSON 解析失败: Unexpected character` | LLM 输出有非 JSON 前缀；`Json.stripFence` 没生效，看 `logs/scope.log` 的 raw 头部 |
| 3 次重试都失败但日志看起来 JSON 是对的 | 八成是 schema 太严（如 `pattern` 严苛）；用 Day 2 的 `SchemaValidatorTest` 拿同一份 JSON 跑一遍定位 |
| WireMock 测试 404 | `usingFilesUnderClasspath("wiremock")` 路径错了；确认 `src/test/resources/wiremock/__files/` 存在 |
| `ARK_API_KEY` 已设但 LiveTest 仍 skip | 看 `@EnabledIfEnvironmentVariable` 的 matches 写法；IntelliJ 跑 test 时环境变量未透传，从命令行试 |
| 重试 prompt 太长导致 token 超限 | `buildRetryPrompt` 里 lastJson 截了 500 字。如还超，把 errors 也截短 |

---

## 11. 附录 A · `analyst.md` 维护提醒

随着课程推进，**不要在 Day 3 的 analyst.md 上不断加规则**。Day 4 开始 LLM 走工具调用路径，prompt 会换成"用工具汇报每一项分析结果"，本文件就会被弃用。

记住这条原则：**Prompt 是任务专属的**，不要妄图维护一个"什么都能干"的 system prompt。

---

## 12. 附录 B · 为什么 Day 3 不直接用 Structured Output API

OpenAI 兼容协议（包括火山方舟）有两条 structured 路径：

1. `response_format: { "type": "json_object" }` — **只保证返回是 JSON**，不保证字段
2. `response_format: { "type": "json_schema", "json_schema": {...} }` — **保证符合给定 schema**

第 2 条在火山方舟、DashScope 支持度不稳定（不同模型版本表现不同），而且**绕过它我们也能拿到同样的结果**（重试 + schema 校验）。

Day 3 我们坚持 **provider-agnostic** 路径：依赖的只是"模型能输出 JSON 文本"这一最低能力。这样 Day 7 切到 Harness 或换模型时，本日代码无需改动。

Day 4 开始切到 **工具调用** 路径：工具入参本身就是结构化 schema，由 LLM 框架（AS-Java）保证；那时候才是真正意义的 "structured output"。

---

## 13. 写在 Day 4 之前

明天 Day 4 我们会：

- 把 Day 3 的 `RequirementParser` 拆掉，让 LLM **不再吐 JSON 文本**
- 改用 **工具调用**：LLM 调 `create_app(name, label, type)` / `create_module(...)` / `create_model(...)`，每个工具入参就是 Day 2 的 POJO
- `TodoManager` 把工具调用结果收成 PENDING 待办，跟前端解耦
- 工具内部仍然用 `SchemaValidator` 兜底（防止 prompt 漂移），但这次校验对象是单个 spec 而不是整个 AnalysisResult

可以把 Day 3 的 `analyst.md` 留着对比，明天对比一下"自由文本"vs"工具调度"两种 prompt 风格的差异。
