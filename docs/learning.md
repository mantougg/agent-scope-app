# AgentScope-Java 实战：7 天打造"需求拆解 → 应用/模块/模型生成"智能体

> 目标：在 7 天内，基于 AgentScope-Java 1.0.12+（必要时引入 1.1.0-RC1 的 Harness）从零搭建一个 **需求分析 Agent**，能够：
> 1. 接收中文需求 / 需求文档
> 2. 拆分为 App / Module / DataModel
> 3. 生成可执行待办列表
> 4. 通过前端通道下发 JSON
> 5. 具备多轮对话、JSON Schema 校验、状态管理、HITL 确认、日志记录等生产级能力

## 0. 总览

### 0.1 最终成品形态

```
┌─────────────────┐       ┌──────────────────────────────────────────────┐       ┌──────────────┐
│  用户            │       │       Requirement Analyst Agent              │       │  前端         │
│  (CLI/Web)       │──────▶│  ReActAgent + Harness + 自定义工具集         │──────▶│  通过 SSE/    │
│                  │◀──────│  - 解析 → App/Module/Model                   │       │  WebSocket    │
│  追加需求        │  HITL │  - 校验 → JSON Schema                        │       │  接收/回调    │
│  确认/取消       │       │  - 待办 → TodoManager (pending/running/      │       │              │
│                  │       │           success/failed)                    │       │              │
└─────────────────┘       │  - 日志 → SLF4J + OpenTelemetry               │       └──────────────┘
                          │  - 状态 → Session 持久化                      │
                          └──────────────────────────────────────────────┘
```

### 0.2 一周路线图

| Day | 主题 | 关键交付 | 对应需求 |
|-----|------|---------|----------|
| 1 | 项目骨架 + AS-Java Hello World | 跑通最小 ReActAgent，工程结构定型 | 基础 |
| 2 | 数据契约 + JSON Schema 校验 | App/Module/Model POJO + Schema 校验器 | #7、#11、#12、#13 |
| 3 | 需求解析 + Structured Output | 能从中文需求生成结构化草稿 | #1、#2、#6 |
| 4 | TodoManager + 业务工具集 | 待办状态机 + create_* 工具 | #3、#5 |
| 5 | 多轮对话 + 增量修改 + HITL | Memory/Session + 用户确认环节 | #8、#9 |
| 6 | 前端联调 + 全链路日志 | SSE/WebSocket + 结构化日志 | #4、#10 |
| 7 | 异常处理 + 测试 + 演示 | E2E 测试、Hook 可观测、Demo 录制 | #6、#10、收口 |

### 0.3 知识前置（建议先翻一遍）

| 主题 | 资料 |
|------|------|
| AS-Java 总览 | [docs/agents/01-overview.md](./agents/01-overview.md) |
| 核心抽象（Msg/Agent/Memory/Toolkit/Model） | [docs/agents/02-core-concepts.md](./agents/02-core-concepts.md) |
| ReActAgent 全套参数 | [docs/agents/03-react-agent.md](./agents/03-react-agent.md) |
| 工具系统 | [docs/agents/04-tool-system.md](./agents/04-tool-system.md) |
| 模型 Provider | [docs/agents/05-model-providers.md](./agents/05-model-providers.md) |
| Harness（生产级 runtime） | [docs/agents/07-harness.md](./agents/07-harness.md) |
| Project Reactor | https://projectreactor.io/docs/core/release/reference/ |
| JSON Schema 2020-12 | https://json-schema.org/draft/2020-12/release-notes.html |

### 0.4 验收基线（一周结束时应满足）

- ✅ `mvn test` 全绿
- ✅ 一条命令启动，CLI 或 HTTP 接口可输入中文需求
- ✅ 输入"做一个库存管理系统"能输出 1 个 App + ≥2 个 Module + ≥2 个 Model 的合法 JSON
- ✅ JSON 通过 Schema 校验
- ✅ 待办状态可查询，失败可重试
- ✅ 二次追加"再加个出库审批模块"能增量更新而不是全量重生
- ✅ 用户确认前不会真正下发前端
- ✅ `logs/` 下能查到完整一次会话的输入/中间结果/输出

---

## Day 1 · 项目骨架 + AS-Java Hello World

> 📘 **详细课程**：[lessons/day-01.md](./lessons/day-01.md) — 6 个 Phase 时间盒 + 完整可拷贝代码 + 故障排查表

### 学习目标

- 掌握 AS-Java 工程组织最小集
- 理解 `ReActAgent.builder()` 主要参数
- 完成一个能跟 LLM 对话的 Hello World
- 决定日志、配置、测试框架

### 上午 · 工程搭建

1. **新建 Maven 工程**（包名建议 `com.yourorg.scope`，artifactId `agent-scope-app`）

   `pom.xml` 关键依赖：

   ```xml
   <properties>
     <java.version>17</java.version>
     <agentscope.version>1.0.12</agentscope.version>
     <reactor.version>3.6.0</reactor.version>
   </properties>

   <dependencies>
     <!-- AS-Java 核心 -->
     <dependency>
       <groupId>io.agentscope</groupId>
       <artifactId>agentscope</artifactId>
       <version>${agentscope.version}</version>
     </dependency>

     <!-- 日志 -->
     <dependency>
       <groupId>ch.qos.logback</groupId>
       <artifactId>logback-classic</artifactId>
       <version>1.5.6</version>
     </dependency>

     <!-- 配置 -->
     <dependency>
       <groupId>com.typesafe</groupId>
       <artifactId>config</artifactId>
       <version>1.4.3</version>
     </dependency>

     <!-- 测试 -->
     <dependency>
       <groupId>org.junit.jupiter</groupId>
       <artifactId>junit-jupiter</artifactId>
       <version>5.10.2</version>
       <scope>test</scope>
     </dependency>
     <dependency>
       <groupId>io.projectreactor</groupId>
       <artifactId>reactor-test</artifactId>
       <version>${reactor.version}</version>
       <scope>test</scope>
     </dependency>
   </dependencies>
   ```

2. **目录结构**

   ```
   src/main/java/com/yourorg/scope/
   ├── ScopeApp.java                # 入口
   ├── config/                      # Typesafe Config 包装
   ├── model/                       # POJO：App / Module / DataModel / TodoItem
   ├── agent/                       # AgentFactory / 自定义 Agent 配置
   ├── tool/                        # 业务工具（create_app 等）
   ├── schema/                      # JSON Schema 校验
   ├── todo/                        # TodoManager
   ├── frontend/                    # 前端通道
   └── util/

   src/main/resources/
   ├── application.conf             # Typesafe Config
   ├── schemas/                     # JSON Schema 文件
   ├── prompts/                     # System Prompt 模板
   └── logback.xml

   src/test/java/...
   ```

3. **配置文件 `application.conf`**

   ```hocon
   model {
     provider = "dashscope"
     name     = "qwen-max"
     apiKey   = ${?DASHSCOPE_API_KEY}
   }
   agent {
     name      = "RequirementAnalyst"
     maxIters  = 15
     timeout   = 120s
   }
   ```

### 下午 · Hello World

1. **写一个 `AgentFactory`**

   ```java
   public final class AgentFactory {
       public static ReActAgent buildAnalyst() {
           var cfg = AppConfig.load();
           return ReActAgent.builder()
               .name(cfg.agentName())
               .sysPrompt("你是需求分析助手，回答尽量简洁。")
               .model(DashScopeChatModel.builder()
                   .apiKey(cfg.modelApiKey())
                   .modelName(cfg.modelName())
                   .build())
               .maxIters(cfg.maxIters())
               .build();
       }
   }
   ```

2. **写 `ScopeApp.java` 跑通**

   ```java
   public class ScopeApp {
       public static void main(String[] args) {
           ReActAgent agent = AgentFactory.buildAnalyst();
           Msg out = agent.call(Msg.builder()
               .textContent("用 1 句话介绍你自己")
               .build()).block();
           System.out.println(out.getTextContent());
       }
   }
   ```

3. **配置 logback**：控制台 + `logs/scope.log` 滚动，分别为 INFO 和 DEBUG。

### 准备资料

- [docs/agents/01-overview.md](./agents/01-overview.md)
- [docs/agents/02-core-concepts.md § Agent / Msg](./agents/02-core-concepts.md)
- [docs/agents/05-model-providers.md](./agents/05-model-providers.md)
- Reactor 入门：https://projectreactor.io/docs/core/release/reference/#getting-started
- Logback 模板：https://logback.qos.ch/manual/configuration.html

### 当日产出

- [ ] `mvn compile` 通过
- [ ] `mvn exec:java -Dexec.mainClass=...ScopeApp` 能跟 LLM 对话
- [ ] `logs/scope.log` 有 DEBUG 输出
- [ ] Git 初始 commit，含 README 写明启动方式

### 常见坑

- DashScope key 没设：用 `System.getenv` 之前确认环境变量已加载
- Windows 下 Reactor 的 `block()` 超时：开发期把超时拉长到 2 min
- JDK 版本：必须 17+，IDE 与 `pom.xml` 一致

---

## Day 2 · 数据契约 + JSON Schema 校验

### 学习目标

- 把需求 #11、#12、#13 的三个 JSON 形态固化为 Java POJO
- 写出对应 JSON Schema（用于校验 LLM 输出）
- 提供"反序列化 + 校验"二合一的工具类
- 单元测试覆盖正反样例

### 上午 · POJO 与 Jackson

1. **添加 Jackson + Schema 库**

   ```xml
   <dependency>
     <groupId>com.fasterxml.jackson.core</groupId>
     <artifactId>jackson-databind</artifactId>
     <version>2.17.0</version>
   </dependency>
   <dependency>
     <groupId>com.networknt</groupId>
     <artifactId>json-schema-validator</artifactId>
     <version>1.4.0</version>
   </dependency>
   ```

2. **POJO 设计**（使用 Java record，字段对齐题目）

   ```java
   // App
   public record AppSpec(String name, String label, String type) {}

   // Module
   public record ModuleSpec(String moduleName, String moduleId, String moduleDesc) {}

   // DataModel
   public record DataModelSpec(
       String name,
       String type,                // TASK_MASTER_SLAVE 等
       String pinyin,
       String tableName,
       String parentId,
       List<FieldSpec> fields
   ) {}

   public record FieldSpec(
       String comment,
       String name,
       String dataType,            // long/double/string/array...
       String usage,               // primary/foreign/""
       String relateModelType,     // collection/""
       List<FieldSpec> subs
   ) {}

   // 一次完整需求分析的结果
   public record AnalysisResult(
       AppSpec app,
       List<ModuleSpec> modules,
       List<DataModelSpec> models,
       List<String> warnings,
       List<String> questions
   ) {}
   ```

3. **配置 Jackson 全局 ObjectMapper**（`util/Json.java`）：忽略未知字段、`PROPERTY_NAMING_STRATEGY=LOWER_CAMEL_CASE`、`FAIL_ON_NULL_CREATOR_PROPERTIES=false`。

### 下午 · JSON Schema 与校验器

1. **`resources/schemas/analysis-result.schema.json`**（关键骨架）

   ```json
   {
     "$schema": "https://json-schema.org/draft/2020-12/schema",
     "type": "object",
     "required": ["app", "modules", "models"],
     "properties": {
       "app": { "$ref": "#/$defs/AppSpec" },
       "modules": { "type": "array", "items": { "$ref": "#/$defs/ModuleSpec" } },
       "models":  { "type": "array", "items": { "$ref": "#/$defs/DataModelSpec" } },
       "warnings": { "type": "array", "items": { "type": "string" } },
       "questions": { "type": "array", "items": { "type": "string" } }
     },
     "$defs": {
       "AppSpec": { "type": "object",
         "required": ["name", "label", "type"],
         "properties": {
           "name":  { "type": "string", "pattern": "^[a-zA-Z][a-zA-Z0-9]*$" },
           "label": { "type": "string", "minLength": 1 },
           "type":  { "type": "string" }
         }
       },
       "ModuleSpec": { "type": "object",
         "required": ["moduleName", "moduleId", "moduleDesc"],
         "properties": {
           "moduleName": { "type": "string" },
           "moduleId":   { "type": "string", "pattern": "^[a-z][a-zA-Z0-9]*$" },
           "moduleDesc": { "type": "string" }
         }
       },
       "DataModelSpec": { "type": "object",
         "required": ["name", "type", "pinyin", "tableName", "parentId", "fields"],
         "properties": {
           "name": { "type": "string" },
           "type": { "enum": ["TASK_MASTER_SLAVE", "TASK", "ENTITY"] },
           "pinyin": { "type": "string" },
           "tableName": { "type": "string" },
           "parentId": { "type": "string" },
           "fields": { "type": "array", "items": { "$ref": "#/$defs/FieldSpec" } }
         }
       },
       "FieldSpec": { "type": "object",
         "required": ["name", "dataType"],
         "properties": {
           "comment": { "type": "string" },
           "name": { "type": "string" },
           "dataType": { "enum": ["long", "int", "double", "string", "boolean", "date", "array"] },
           "usage": { "type": "string" },
           "relateModelType": { "type": "string" },
           "subs": { "type": "array", "items": { "$ref": "#/$defs/FieldSpec" } }
         }
       }
     }
   }
   ```

2. **`schema/SchemaValidator.java`**

   ```java
   public class SchemaValidator {
       private final JsonSchema schema;
       public SchemaValidator(String resource) {
           JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
           this.schema = factory.getSchema(getClass().getResourceAsStream(resource));
       }
       public List<String> validate(JsonNode node) {
           return schema.validate(node).stream()
                   .map(ValidationMessage::getMessage)
                   .toList();
       }
   }
   ```

3. **单元测试**：构造合法 / 缺字段 / 数据类型错误三种样本，确认校验结果符合预期。

### 准备资料

- JSON Schema 教程：https://json-schema.org/learn/getting-started-step-by-step
- networknt schema validator：https://github.com/networknt/json-schema-validator
- Jackson record 支持：https://github.com/FasterXML/jackson-modules-base/tree/master/blackbird
- 你的需求文档 #11、#12、#13（题面的三段 JSON）

### 当日产出

- [ ] `AppSpec` / `ModuleSpec` / `DataModelSpec` / `FieldSpec` / `AnalysisResult` 5 个 record
- [ ] `analysis-result.schema.json`
- [ ] `SchemaValidator` 工具类
- [ ] `SchemaValidatorTest` 至少 3 个用例（pass / missing-field / type-mismatch）
- [ ] `mvn test` 全绿

---

## Day 3 · 需求解析 + Structured Output

### 学习目标

- 编写第一个能输出**结构化结果**的 Prompt
- 引入 AS-Java 的 Structured Output（直接映射 POJO）
- 处理 LLM 输出不合规时的**重试 / 自纠错**
- 用 #6 要求的 warnings/questions 标记不确定信息

### 上午 · Prompt 设计

1. **System Prompt 文件 `resources/prompts/analyst.md`**

   ```
   你是一名资深业务分析师，专长是把中文需求拆解为「应用 / 模块 / 数据模型」三层结构。

   严格遵守：
   1. 只输出 JSON，禁止任何额外文字。
   2. JSON 结构遵循下面的 schema（见 examples）。
   3. 模块 moduleId 必须为 camelCase，与中文名一一对应。
   4. 数据模型 type 必须从枚举 ["TASK_MASTER_SLAVE","TASK","ENTITY"] 中选。
   5. 含明细的单据用 TASK_MASTER_SLAVE，并把明细放进 fields 里 dataType=array 的子节点。
   6. 不确定的部分写进 warnings；需要用户回答的问题写进 questions。
   7. 主键字段必须存在，name=id，usage=primary，dataType=long。

   示例输入：
     "做一个请假管理系统，员工可以提交请假申请，主管审批"

   示例输出：
     { "app": {...}, "modules": [...], "models": [...],
       "warnings": [...], "questions": [...] }
   ```

2. **少量样例**塞进 prompt（Few-shot）：1 个简单（小工具），1 个复杂（含主从表），确保 LLM 学到 master-slave 的写法。

### 下午 · Structured Output

AS-Java 的 `ReActAgent` 支持把最终输出直接反序列化为 POJO（参考 `Structured Output` 任务文档，本周内 [docs/agents/10-observability-hitl.md](./agents/10-observability-hitl.md) 也会扩展）。两种实现选其一：

**方案 A（推荐）：让 LLM 输出 JSON 文本，自己反序列化 + 校验**

```java
public class RequirementParser {
    private final ReActAgent agent;
    private final SchemaValidator validator;
    private final ObjectMapper mapper;

    public AnalysisResult parse(String userInput) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            Msg out = agent.call(Msg.builder()
                .textContent(userInput)
                .build()).block();

            String json = stripFence(out.getTextContent());
            JsonNode node = mapper.readTree(json);

            List<String> errors = validator.validate(node);
            if (errors.isEmpty()) {
                return mapper.treeToValue(node, AnalysisResult.class);
            }
            // 把错误丢回 LLM，让它自纠错
            userInput = "上一次输出不合 schema：" + errors + "\n请修正后重新输出 JSON。";
            log.warn("Attempt {} failed: {}", attempt, errors);
        }
        throw new ParseException("LLM 输出 3 次都不合规");
    }
}
```

**方案 B：使用 `generateOptions(...)` 的 `toolChoice(Specific("submit_analysis"))`**——强制 LLM 调用一个名为 `submit_analysis` 的工具，参数本身就是 `AnalysisResult` 的 JSON Schema。这种做法天然带类型，但需要 #4 阶段串起来。

> 建议 Day 3 走方案 A，Day 4 切到方案 B 是自然演进。

### Warnings / Questions 的用法

- **Warning**：LLM 自己不确定但有默认值（"假设 `type=23` 表示业务管理类应用"）
- **Question**：必须问用户的（"请假明细是否需要附件字段？"）
- 在 Day 5 的 HITL 流程中，questions 会被原样回显给用户

### 准备资料

- [docs/agents/02-core-concepts.md § Msg](./agents/02-core-concepts.md)
- [docs/agents/03-react-agent.md](./agents/03-react-agent.md)
- Prompt Engineering 基础：https://www.promptingguide.ai/
- Structured Output 文档：https://java.agentscope.io/_sources/en/task/structured-output.md（待你和我一起读，可同时补到 [docs/agents/10-observability-hitl.md](./agents/10-observability-hitl.md)）
- 你的题目 #1、#2、#6

### 当日产出

- [ ] `prompts/analyst.md` 至少 2 个 few-shot 示例
- [ ] `RequirementParser` 实现"调用 → 校验 → 重试"
- [ ] 测试用例：3 个真实中文需求（库存 / 请假 / 报销）全部能输出合法 JSON
- [ ] LLM 给出 warnings/questions 的至少 1 个样例（用一个故意模糊的需求触发）

---

## Day 4 · TodoManager + 业务工具集

### 学习目标

- 实现需求 #3、#5：把 AnalysisResult 转成 TodoList 并跟踪状态
- 编写需求 #4 的下发工具：`create_app`、`create_module`、`create_model`
- 把工具挂载到 ReActAgent，让 LLM 学会调度

### 上午 · TodoManager

1. **数据结构**

   ```java
   public enum TodoStatus { PENDING, RUNNING, SUCCESS, FAILED }

   public record TodoItem(
       String id,
       TodoType type,            // CREATE_APP / CREATE_MODULE / CREATE_MODEL
       String targetName,        // 便于人看
       JsonNode payload,         // 即将发给前端的 JSON
       TodoStatus status,
       String errorMessage,
       Instant createdAt,
       Instant updatedAt
   ) { ... withStatus(...) ... }
   ```

2. **TodoManager**

   ```java
   public class TodoManager implements StateModule {
       private final Map<String, TodoItem> items = new LinkedHashMap<>();

       public TodoItem add(TodoType t, String name, JsonNode payload) { ... }
       public Optional<TodoItem> next() { /* 取第一个 PENDING */ }
       public void markRunning(String id) { ... }
       public void markSuccess(String id) { ... }
       public void markFailed(String id, String err) { ... }
       public List<TodoItem> snapshot() { ... }

       @Override public JsonNode getState() { ... }
       @Override public void loadState(JsonNode s) { ... }
   }
   ```

   实现 `StateModule` 是为 Day 5 的 Session 持久化打基础（见 [docs/agents/02-core-concepts.md § State / Session](./agents/02-core-concepts.md)）。

3. **状态机约束**：`PENDING → RUNNING → SUCCESS|FAILED`；其他迁移抛 `IllegalStateException`。

### 下午 · 业务工具

```java
public class FrontendCreateTools {

    private final TodoManager todos;
    private final FrontendBridge bridge;          // Day 6 才填，先打 stub

    @Tool(name = "create_app",
          description = "把分析得到的应用信息排入待办，等用户确认后下发前端创建。")
    public String createApp(
        @ToolParam(name = "name") String name,
        @ToolParam(name = "label") String label,
        @ToolParam(name = "type") String type
    ) {
        var spec = new AppSpec(name, label, type);
        var item = todos.add(TodoType.CREATE_APP, label, mapper.valueToTree(spec));
        return "APP 待办已创建：" + item.id();
    }

    @Tool(name = "create_module", description = "...")
    public String createModule(...) { ... }

    @Tool(name = "create_model", description = "...")
    public String createModel(...) { ... }
}
```

注册到 Toolkit：

```java
Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
    .parallel(true)
    .build());
toolkit.registerTool(new FrontendCreateTools(todos, bridge));

ReActAgent agent = ReActAgent.builder()
    .name("Analyst")
    .model(model)
    .toolkit(toolkit)
    .sysPrompt(systemPrompt + "\n请用工具落地每一项分析结果。")
    .build();
```

> 这一步用方案 B 替换 Day 3 的纯文本输出：让 LLM **通过工具调用** 来汇报每一个 App/Module/Model，TodoManager 就是天然的 sink。

### 工具内 Schema 校验

工具里二次校验：每个工具入参组成 POJO 后，用 `SchemaValidator` 兜底，确保即使 prompt 漂移也不会污染待办列表。

### 准备资料

- [docs/agents/04-tool-system.md](./agents/04-tool-system.md) （重点：注解、Toolkit、ToolExecutionContext）
- [docs/agents/02-core-concepts.md § State / Session](./agents/02-core-concepts.md)
- 你的题目 #3、#4、#5

### 当日产出

- [ ] `TodoManager` + 5 个状态机单元测试
- [ ] `FrontendCreateTools` 3 个工具实现并注册成功
- [ ] 跑一条端到端：输入"做一个最简请假系统" → TodoManager 中有 1 APP + N Module + N Model 全部 PENDING
- [ ] 故意构造 Schema 不合法的工具入参，确认拒绝并写入 warning

---

## Day 5 · 多轮对话 + 增量修改 + HITL

### 学习目标

- 满足需求 #8：用户补充需求时，**增量更新** TodoManager 而非全量重生
- 满足需求 #9：在真正下发前**插入用户确认**
- 用 Session 持久化让进程重启后能续跑

### 上午 · Memory 与 Session

1. **接入 Memory**（默认 `InMemoryMemory` 即可）

   ```java
   ReActAgent agent = ReActAgent.builder()
       .name("Analyst")
       .model(model)
       .toolkit(toolkit)
       .memory(new InMemoryMemory())     // 显式声明便于 Day 7 替换
       .build();
   ```

2. **接入 Session**：把 `TodoManager` 注册成 `StateModule` 后，用 `JsonSession`/`SessionManager` 按 `sessionId` 落到 `data/sessions/<id>.json`。

3. **增量更新的 Prompt 补丁**

   在 system prompt 中追加：

   ```
   ## 多轮规则
   - 当用户追加需求时，先用工具 list_todos 看现有待办，再判断是 ADD 还是 MODIFY。
   - 严禁删除现有待办除非用户明确说"删掉 xxx"。
   - 修改一个 Module/Model 时，调用 update_module / update_model 工具，而不是 create_*。
   ```

   补两个工具：

   ```java
   @Tool(name = "list_todos", description = "列出当前所有待办及状态")
   public String listTodos() { ... }

   @Tool(name = "update_module", description = "修改一个已存在的模块")
   public String updateModule(...) { ... }
   ```

### 下午 · HITL 用户确认

两条路线，二选一（推荐路线 A 先跑通）：

**路线 A：Tool Suspend（最贴合 AS-Java 设计）**

```java
@Tool(name = "submit_to_frontend",
      description = "把所有待办发送给前端创建。必须先获得用户确认。")
public ToolResultBlock submitToFrontend(@ToolParam(name = "confirmed") boolean confirmed) {
    if (!confirmed) {
        // 抛出挂起异常，让上游展示待办给用户
        throw new ToolSuspendException("AWAITING_USER_CONFIRMATION");
    }
    // confirmed==true 才真的下发
    return bridge.dispatchAll(todos.snapshot());
}
```

调用端：

```java
Msg out = agent.call(userMsg).block();

if (out.getGenerateReason() == GenerateReason.TOOL_SUSPENDED) {
    // 展示待办，等用户回复 yes/no
    showTodoTable(todos.snapshot());
    boolean ok = readUserConfirm();

    // 把用户答复作为 tool_result 回填
    for (ToolUseBlock tu : out.getContentBlocks(ToolUseBlock.class)) {
        agent.call(Msg.builder().role(MsgRole.TOOL)
            .content(ToolResultBlock.of(tu.getId(), tu.getName(),
                TextBlock.builder().text(ok ? "USER_CONFIRMED" : "USER_REJECTED").build()))
            .build()).block();
    }
}
```

**路线 B：Hook 拦截**

写一个 `PreActingHook`，对 `submit_to_frontend` 的调用先弹出 GUI/CLI 确认。优点是工具实现纯净；缺点是 Hook API 你还没完全摸透（[docs/agents/10-observability-hitl.md](./agents/10-observability-hitl.md) 待补）。Day 7 可以做这个切换练手。

### 增量场景验证

跑这个剧本：

1. 用户："做一个库存管理系统"
2. Agent：输出 App + 2 个 Module（监控/入库）+ 2 个 Model，问"要不要出库？"
3. 用户："要，加上出库审批"
4. **关键**：Agent 应该只新增 1 个 Module + 1 个 Model 的 PENDING 待办，原有待办保持原状
5. 用户："确认"
6. Agent 调 `submit_to_frontend(confirmed=true)`，把 4 个 Module + 4 个 Model 一起发出

### 准备资料

- [docs/agents/03-react-agent.md § 安全中断](./agents/03-react-agent.md)
- [docs/agents/04-tool-system.md § 工具挂起 / 恢复](./agents/04-tool-system.md)
- [docs/agents/06-memory-state-session.md](./agents/06-memory-state-session.md)（这一天可以把它从 🚧 升级到 ✅）
- 你的题目 #8、#9

### 当日产出

- [ ] 多轮对话能基于上一次结果增量补 Module
- [ ] 用户拒绝 confirm 时不会下发前端
- [ ] 进程 kill 重启后凭 `sessionId` 恢复 TodoManager 状态
- [ ] 至少 1 个完整剧本回归测试

---

## Day 6 · 前端联调 + 全链路日志

### 学习目标

- 实现需求 #4：把待办下发给前端
- 实现需求 #10：完整日志，能从 traceId 还原一次会话
- 暴露 HTTP 接口给前端调用 Agent

### 上午 · 前端通道

1. **传输方式选择**（按你的前端栈选）

   | 方式 | 适合 | 集成成本 |
   |------|------|---------|
   | **SSE** | 前端只接收 / 单向流 | 低（Spring Boot WebFlux） |
   | **WebSocket** | 双向 | 中 |
   | **HTTP 同步** | 简单 RPC | 最低 |
   | **MQ（Kafka/RocketMQ）** | 异步、可重放 | 高 |

   建议先用 SSE：天然契合 Reactor，前端 EventSource 一行接入。

2. **加 Spring Boot WebFlux**（如果项目还没用 Spring）

   ```xml
   <dependency>
     <groupId>org.springframework.boot</groupId>
     <artifactId>spring-boot-starter-webflux</artifactId>
     <version>3.2.5</version>
   </dependency>
   ```

3. **Controller**

   ```java
   @RestController
   @RequestMapping("/api/agent")
   public class AgentController {

       @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
       public Flux<ServerSentEvent<Object>> chat(@RequestBody ChatReq req) {
           return agentService.chat(req.sessionId(), req.text())
               .map(event -> ServerSentEvent.builder().data(event).build());
       }

       @PostMapping("/confirm")
       public Mono<Void> confirm(@RequestBody ConfirmReq req) {
           return agentService.confirm(req.sessionId(), req.confirmed());
       }

       @GetMapping("/todos/{sessionId}")
       public Mono<List<TodoItem>> todos(@PathVariable String sessionId) {
           return agentService.todos(sessionId);
       }
   }
   ```

4. **FrontendBridge** 真正实现：把 TodoManager 中的项目，按 #11、#12、#13 的形态序列化推到 SSE channel。前端拿到 JSON 自己调它的创建接口。

### 下午 · 日志与可观测

1. **结构化日志**：用 logback `JsonEncoder` 或手写 MDC，每条日志带 `sessionId` + `traceId` + `stage`。

   ```xml
   <appender name="JSON" class="ch.qos.logback.core.rolling.RollingFileAppender">
     <file>logs/scope.json.log</file>
     <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
   </appender>
   ```

2. **关键日志点**（满足需求 #10）

   | 阶段 | 字段 |
   |------|------|
   | `INPUT` | sessionId, text, attachments |
   | `LLM_CALL` | promptHash, tokens, latency |
   | `TOOL_CALL` | toolName, args(脱敏后) |
   | `SCHEMA_VALIDATE` | result, errors |
   | `TODO_UPDATE` | id, status, before→after |
   | `FRONTEND_DISPATCH` | itemId, payload |
   | `FRONTEND_CALLBACK` | itemId, success, err |

3. **MDC traceId**：在 Controller 入口生成 traceId 塞 MDC，Reactor 用 `contextWrite(Context.of("traceId", id))` 透传。AS-Java 自身可用 `RuntimeContextAwareHook` 接管（[docs/agents/07-harness.md](./agents/07-harness.md)）。

4. **可选：OpenTelemetry**（Day 7 也可以做）

   ```xml
   <dependency>
     <groupId>io.opentelemetry.instrumentation</groupId>
     <artifactId>opentelemetry-spring-boot-starter</artifactId>
     <version>2.x</version>
   </dependency>
   ```

### 异常处理收口（呼应需求 #6）

- LLM 解析失败 → 把 errors 加到 warnings、提示用户重述
- Schema 校验失败 → 工具直接失败 + warning
- 前端回调失败 → TodoItem 标 FAILED + errorMessage，并把 ToolResult 回填给 Agent，让它判断是否重试
- 用户拒绝 confirm → 维持 PENDING，等下一轮指令

### 准备资料

- Spring WebFlux SSE：https://docs.spring.io/spring-framework/reference/web/webflux.html
- Logstash Logback Encoder：https://github.com/logfellow/logstash-logback-encoder
- OpenTelemetry Java：https://opentelemetry.io/docs/zero-code/java/
- [docs/agents/07-harness.md § RuntimeContext](./agents/07-harness.md)
- 你的题目 #4、#10

### 当日产出

- [ ] 启动 Spring Boot，前端 / curl 能通过 SSE 与 Agent 对话
- [ ] 前端 mock 接到 4 类事件（chat-msg / todo-update / await-confirm / dispatch）
- [ ] `logs/scope.json.log` 能用 jq 过 sessionId 还原全过程
- [ ] 关掉前端服务 → 看到 TodoItem 走到 FAILED 并有清晰原因

---

## Day 7 · 异常处理强化 + 测试 + 演示

### 学习目标

- 把前 6 天的脆弱点全部加上回归测试
- 用 Hook 完成一次"Coding Agent 风格"的可观测增强
- 录制一段演示，并对照 11 条需求逐项打勾

### 上午 · 强化场景

1. **异常场景清单**（必跑）

   | # | 场景 | 期望 |
   |---|------|------|
   | E1 | 需求里只写 1 句话："做个系统" | warnings + questions 非空，待办为空 |
   | E2 | LLM 返回非 JSON | 自纠错最多 3 次后抛 `ParseException` |
   | E3 | LLM 输出 Schema 不合法 | 工具拒绝 + 写入 warning |
   | E4 | 前端 500 | TodoItem→FAILED，可重试 |
   | E5 | 用户连续追加 5 次需求 | 上下文不超长（启用 Compaction，或 1.1 Harness） |
   | E6 | 用户中途说"算了，全删了" | TodoManager 清空 + 友好回执 |
   | E7 | 同名 Module 重复创建 | 拒绝 + warning 提示已存在 |
   | E8 | 进程 kill 重启 | sessionId 恢复 |

2. **测试金字塔**

   - 单测：SchemaValidator / TodoManager 状态机 / FrontendBridge 序列化
   - 集成：用 `WireMock` 替代真实 LLM，断言完整流程
   - E2E：录一个真实 LLM 的剧本（带 `@Tag("e2e")`，CI 默认不跑）

### 下午 · 升级到 Harness（可选但推荐）

如果时间允许，把 `ReActAgent` 换成 `HarnessAgent`（1.1.0-RC1），收益：

- 自动 Compaction（解决 E5）
- 自动 Session 持久化（部分替代 Day 5 自己写的）
- Tool Result Eviction（前端返回大 payload 不污染上下文）
- 可加 Workspace `AGENTS.md` 沉淀业务规则

参考 [docs/agents/07-harness.md § 用 Harness 构建 Coding Agent](./agents/07-harness.md)。

### 收尾

1. **写 README**：项目结构、启动方式、示例请求、已知限制
2. **画一张架构图**：把 Controller / AgentService / Toolkit / TodoManager / FrontendBridge / Memory / Session 之间的箭头画清楚
3. **录制演示**：3 分钟视频或 GIF，跑完一个完整剧本
4. **对照需求逐项打勾**：

   | # | 需求 | 兑现位置 |
   |---|------|---------|
   | 1 | 用户输入需求 | Controller `/chat` |
   | 2 | 解析为 App/Module/Model | RequirementParser + FrontendCreateTools |
   | 3 | 列出待办列表 | TodoManager |
   | 4 | 前端下发 | FrontendBridge + SSE |
   | 5 | 状态管理 | TodoStatus 状态机 |
   | 6 | 异常 warnings/questions | AnalysisResult + Schema 校验 |
   | 7 | JSON Schema 校验 | SchemaValidator |
   | 8 | 多轮增量 | Memory + list_todos / update_* 工具 |
   | 9 | 结果确认 | submit_to_frontend Tool Suspend |
   | 10 | 日志 | logback JSON + traceId |
   | 11 | 模块结构 | ModuleSpec |
   | 12 | 模型结构 | DataModelSpec + FieldSpec |
   | 13 | 应用结构 | AppSpec |

### 准备资料

- [docs/agents/07-harness.md](./agents/07-harness.md)
- [docs/agents/10-observability-hitl.md](./agents/10-observability-hitl.md)
- WireMock：https://wiremock.org/docs/

### 当日产出

- [ ] 所有异常场景测试通过
- [ ] README + 架构图入库
- [ ] 演示视频
- [ ] 需求逐项打勾的清单（可贴在 PR 描述）

---

## 附录 A · 常用代码片段

### A.1 工具最小骨架

```java
public class XxxTool {
    @Tool(name = "xxx", description = "...", strict = true)
    public ToolResultBlock xxx(@ToolParam(name = "param") String p) {
        try {
            ...
            return ToolResultBlock.text("OK: " + result);
        } catch (Exception e) {
            return ToolResultBlock.text("ERROR: " + e.getMessage());
        }
    }
}
```

### A.2 把 LLM 输出剥 fence

```java
private static String stripFence(String s) {
    s = s.trim();
    if (s.startsWith("```")) {
        int first = s.indexOf('\n');
        int last  = s.lastIndexOf("```");
        s = s.substring(first + 1, last).trim();
    }
    return s;
}
```

### A.3 SSE 事件结构（前端约定）

```json
{ "type": "chat",            "data": { "text": "..." } }
{ "type": "todo-update",     "data": { "id": "...", "status": "RUNNING" } }
{ "type": "await-confirm",   "data": { "todos": [...] } }
{ "type": "dispatch",        "data": { "endpoint": "create_app", "payload": {...} } }
{ "type": "error",           "data": { "message": "..." } }
```

---

## 附录 B · 风险与规避

| 风险 | 规避 |
|------|------|
| LLM 输出漂移导致 Schema 不合 | 三次重试 + 工具二次校验 + warnings 兜底 |
| 同一 Agent 实例并发调用 | 按 sessionId 池化 Agent，或每会话新建 |
| Session 状态过大 | 启用 Harness Compaction，TodoManager 限制大小 |
| Prompt 调整后旧用例失效 | Day 7 把 LLM 调用录像（snapshot test）+ WireMock 回放 |
| 前端拒绝大 payload | Day 6 在 FrontendBridge 加拆分策略，单个 dispatch 上限 ≤ 1MB |
| API Key 泄漏 | 全部走环境变量，Logger 出参前脱敏 |

---

## 附录 C · 每日自检清单

- [ ] 今日代码可独立 `mvn test` 跑通
- [ ] `logs/` 下 ≥ 1 次完整会话留痕
- [ ] 至少 1 个新增测试用例
- [ ] 把本日新学的点补到 [docs/agents/](./agents/) 对应章节，🚧 升级为 ✅
- [ ] 当日凌晨/收工前 git commit，commit message 用 `dayN: 简述`

---

## 备注

- 本计划假设你有 **中级 Java 经验** + **了解 Spring Boot/Reactor 基础**。如果你没用过 Reactor，Day 1 多花半天看官方 reference 第 1-3 章。
- 计划中没强行用 1.1 Harness，是为了 Day 1-6 保持稳定栈。Day 7 升级是"加分项"。
- 若你的前端栈是 React / Vue，附录 A.3 的事件协议可直接复用；若是 PC 客户端，把 SSE 换成 WebSocket 即可。
- 任何一天卡住超过 2 小时，回头查 [docs/agents/](./agents/) 对应章节，或在群里贴上下文 + 报错 + 已尝试。
