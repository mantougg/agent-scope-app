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
| 3 | 需求解析 + Structured Output | 能从中文需求生成结构化草稿（CLI） | #1、#2、#6 |
| 4 | TodoManager + 业务工具集 | 待办状态机 + create_* 工具 | #3、#5 |
| 5 | 多轮对话 + Memory/Session + HITL (CLI) | 增量更新 + JsonSession + ToolSuspend 确认 | #8、#9 |
| 6 | AG-UI 协议集成（基础） | Spring Boot starter 接入 + 17 事件 + Vue3 客户端 demo | #4、#14 |
| 7 | AG-UI 协议进阶 + 收尾验收 | STATE_DELTA 同步 TodoManager + HITL on AG-UI + 可观测三件套（日志/Jaeger/Micrometer）+ 验收 | #4、#9、#10、#14、收口 |

> 📌 **关于需求 #14**：AG-UI（[官方文档](https://java.agentscope.io/zh/task/agui.html)）是本路线图新加入的一条对外契约：所有 Agent ↔ 前端交互必须走 AG-UI 标准事件流（17 种 EventType），不再自定义 SSE payload。Day 6 起 Spring Boot 入口、Day 7 起 TodoManager 状态同步全部对齐这一规范。

### 0.3 知识前置（建议先翻一遍）

| 主题 | 资料 |
|------|------|
| AS-Java 总览 | [docs/agents/01-overview.md](./agents/01-overview.md) |
| 核心抽象（Msg/Agent/Memory/Toolkit/Model） | [docs/agents/02-core-concepts.md](./agents/02-core-concepts.md) |
| ReActAgent 全套参数 | [docs/agents/03-react-agent.md](./agents/03-react-agent.md) |
| 工具系统 | [docs/agents/04-tool-system.md](./agents/04-tool-system.md) |
| 模型 Provider | [docs/agents/05-model-providers.md](./agents/05-model-providers.md) |
| Harness（生产级 runtime） | [docs/agents/07-harness.md](./agents/07-harness.md) |
| AG-UI 协议官方文档 | https://java.agentscope.io/zh/task/agui.html |
| AG-UI 17 个事件规范 | https://docs.ag-ui.com/concepts/events |
| @ag-ui/client（前端 SDK） | https://www.npmjs.com/package/@ag-ui/client |
| OpenTelemetry · Jaeger · Micrometer（Day 7 可观测三件套） | https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/ · https://www.jaegertracing.io/docs/latest/getting-started/ · https://micrometer.io/docs |
| Project Reactor | https://projectreactor.io/docs/core/release/reference/ |
| JSON Schema 2020-12 | https://json-schema.org/draft/2020-12/release-notes.html |

### 0.4 验收基线（一周结束时应满足）

- ✅ `mvn test` 全绿
- ✅ Spring Boot 一条命令启动，浏览器访问 Vue3 前端可输入中文需求
- ✅ 输入"做一个库存管理系统"能输出 1 个 App + ≥2 个 Module + ≥2 个 Model 的合法 JSON
- ✅ JSON 通过 Schema 校验
- ✅ 待办状态可查询，失败可重试
- ✅ 二次追加"再加个出库审批模块"能增量更新而不是全量重生
- ✅ 用户确认前不会真正下发前端
- ✅ 前后端**完全**通过 AG-UI 标准事件流通信（17 种 EventType，至少使用其中 10 种）
- ✅ TodoManager 通过 `STATE_SNAPSHOT` + `STATE_DELTA` 镜像到前端，不通过自定义 payload
- ✅ `logs/` 下能查到完整一次会话的输入/中间结果/输出（**7 个 stage 一个不少**：INPUT/LLM_CALL/TOOL_CALL/SCHEMA_VALIDATE/TODO_UPDATE/FRONTEND_DISPATCH/FRONTEND_CALLBACK）
- ✅ Jaeger UI 至少 1 条完整 trace（含 `agent.call` + `tool.*` + `frontend_dispatch.*` 子 Span），traceId 跟 jq 日志、curl 响应头**三处对得上**
- ✅ `/actuator/metrics` 列出 ≥ 5 个 `scope.*` 指标（LLM 延迟、工具调用计数 + 延迟、Prompt 字符直方图等）

---

## Day 1 · 项目骨架 + AS-Java Hello World

> 📘 **详细课程**：[lessons/Day01_项目骨架 + AS-Java Hello World.md](<./lessons/Day01_项目骨架 + AS-Java Hello World.md>) — 6 个 Phase 时间盒 + 完整可拷贝代码 + 故障排查表

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

> 📘 **详细课程**：[lessons/Day02_数据契约 + JSON Schema 校验.md](<./lessons/Day02_数据契约 + JSON Schema 校验.md>) — 6 个 Phase 时间盒 + 完整可拷贝代码 + 故障表 + 附录

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

> 📘 **详细课程**：[lessons/Day03_需求解析 + Structured Output.md](<./lessons/Day03_需求解析 + Structured Output.md>) — 6 个 Phase 时间盒 + Few-shot 设计 + 重试自纠错 + WireMock 离线回放

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

> 📘 **详细课程**：[lessons/Day04_TodoManager + 业务工具集.md](<./lessons/Day04_TodoManager + 业务工具集.md>) — 6 个 Phase 时间盒 + 状态机单测 + Toolkit 注册 + dry-run 端到端

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

## Day 5 · 多轮对话 + Memory/Session + HITL (CLI)

> 📘 **详细课程**：[lessons/Day05_多轮对话 + Memory 与 Session + HITL.md](<./lessons/Day05_多轮对话 + Memory 与 Session + HITL.md>) — 6 个 Phase 时间盒 + ToolSuspend HITL + JsonSession 持久化 + 增量剧本回归

### 学习目标

- 满足需求 #8：用户补充需求时，**增量更新** TodoManager 而非全量重生
- 满足需求 #9：在真正下发前**插入用户确认**（CLI 版，Day 7 升级到 AG-UI 事件流）
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

跑这个剧本（**Day 5 在 CLI 内**完成；Day 7 把同一剧本搬到 AG-UI 事件流上验证）：

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

## Day 6 · AG-UI 协议集成（基础）

> 📘 **详细课程**：[lessons/Day06_AG-UI 协议集成（基础）.md](<./lessons/Day06_AG-UI 协议集成（基础）.md>) — 6 个 Phase 时间盒 + Spring Boot starter + 17 事件 + Vue3 + @ag-ui/client 客户端 demo

### 学习目标

- 把 Day 1-5 的 CLI 入口替换成 **Spring Boot WebFlux** + `agentscope-agui-spring-boot-starter`
- 摸清 **AG-UI 17 个标准事件**：Lifecycle 5 / TextMessage 3 / ToolCall 4 / State 3 / Special 2
- 能用 `curl` 直接打 SSE 流，看到 `data: {"type":"TEXT_MESSAGE_CONTENT", ...}` 形态
- 启一个 **Vue3** 前端（Vite 脚手架 + `@ag-ui/client` `HttpAgent`），把流式回复渲染成打字机

### 上午 · Spring Boot 起步 + starter 注入

1. **依赖追加**（关键坐标，详细 BOM 选型见课程文档）

   ```xml
   <dependency>
     <groupId>io.agentscope</groupId>
     <artifactId>agentscope-agui-spring-boot-starter</artifactId>
     <version>1.0.9</version>
   </dependency>
   <dependency>
     <groupId>org.springframework.boot</groupId>
     <artifactId>spring-boot-starter-webflux</artifactId>
   </dependency>
   ```

2. **注册 Agent**（两种方式选一种，课程演示 A，附录给 B）

   **方式 A：`AguiAgentRegistryCustomizer`**

   ```java
   @Configuration
   public class AguiAgentConfig {
       @Bean
       public AguiAgentRegistryCustomizer aguiCustomizer(TodoManager todos) {
           return registry -> registry.registerFactory("analyst",
               () -> AgentFactory.buildAnalystWithTools(todos));
       }
   }
   ```

   **方式 B：`@AguiAgentId` 注解 Bean**

   ```java
   @Bean @AguiAgentId("analyst")
   public Agent analyst() { return AgentFactory.buildAnalystWithTools(todos); }
   ```

3. **启动后 starter 自动暴露 `POST /agui/run`**（SSE）

### 下午 · 17 个事件 + Vue3 客户端

1. **事件五类速查**（详细 JSON payload 见 [docs/agents/_links.md](./agents/_links.md) 引用的 AG-UI 官方文档）

   | 类别 | EventType | 用途 |
   |------|-----------|------|
   | Lifecycle | RUN_STARTED / RUN_FINISHED / RUN_ERROR / STEP_STARTED / STEP_FINISHED | 一次 run 的边界、子步骤 |
   | TextMessage | TEXT_MESSAGE_START / _CONTENT / _END | 流式聊天文本（按 token） |
   | ToolCall | TOOL_CALL_START / _ARGS / _END / _RESULT | 工具调用全生命周期 |
   | State | STATE_SNAPSHOT / STATE_DELTA / MESSAGES_SNAPSHOT | TodoManager / 历史消息同步（Day 7 重点） |
   | Special | RAW / CUSTOM | 透传/扩展事件 |

2. **`curl` 直连验证**

   ```bash
   curl -N -X POST http://localhost:8080/agui/run \
     -H "Content-Type: application/json" \
     -d '{"threadId":"t1","runId":"r1","messages":[{"id":"m1","role":"user","content":"做一个简单请假系统"}]}'
   ```

3. **Vue3 前端最小可见**（`frontend/` 目录，Vite + Vue3 + `@ag-ui/client`）

   ```ts
   import { HttpAgent } from '@ag-ui/client';

   const agent = new HttpAgent({ url: 'http://localhost:8080/agui/run', threadId: 'thread-' + Date.now() });
   agent.subscribe({
     onTextMessageContentEvent: (e) => { /* push 到 reactive 的消息流 */ },
     onRunFinishedEvent: () => { /* 收尾 */ },
   });
   agent.addMessage({ id: 'm1', role: 'user', content: userInput.value });
   await agent.runAgent({ runId: 'run-' + Date.now() });
   ```

### 准备资料

- [docs/agents/05-model-providers.md](./agents/05-model-providers.md)（流式开启复习）
- AG-UI 协议官方页：https://java.agentscope.io/zh/task/agui.html
- AG-UI 事件规范（17 种）：https://docs.ag-ui.com/concepts/events
- `@ag-ui/client` SDK：https://www.npmjs.com/package/@ag-ui/client
- Vite + Vue3：https://cn.vuejs.org/guide/quick-start.html

### 当日产出

- [ ] `mvn spring-boot:run` 起 Spring Boot，`/agui/run` 端点 200
- [ ] `curl -N` 能看到 `RUN_STARTED → TEXT_MESSAGE_* → RUN_FINISHED` 完整事件流
- [ ] `frontend/` 下 `npm run dev` 起 Vue3，浏览器输入需求能看到打字机回复
- [ ] 截图 / 录屏一段 30 秒前后端联调 GIF

### 常见坑

- AG-UI starter 走 WebFlux，不能同时再加 `spring-boot-starter-web`（会冲突）
- 浏览器跨域：Vue3 dev server 在 5173，后端在 8080，要在 Spring Boot 配 `WebFluxConfigurer` 加 CORS
- `@ag-ui/client` 是 ESM-only，Node 版本 ≥ 18
- 模型必须 `stream(true)` 才能让 starter 流式吐 `TEXT_MESSAGE_CONTENT`，否则一次性 dump

---

## Day 7 · AG-UI 协议进阶 + 收尾验收

> 📘 **详细课程**：[lessons/Day07_AG-UI 协议进阶 + 收尾验收.md](<./lessons/Day07_AG-UI 协议进阶 + 收尾验收.md>) — 9 个 Phase 时间盒（含 Phase 3a/3b/3c 可观测三连）+ STATE_DELTA 实战 + HITL on AG-UI + 日志/OTel/Micrometer/异常/验收
> ⏱ **本日 9 学时**（其他天 8 学时），分上午 3.5h + 下午 5.5h；可观测三件套（日志/追踪/指标）一次性打齐

### 学习目标

- 把 TodoManager 通过 `STATE_SNAPSHOT` + `STATE_DELTA` 实时镜像到 Vue3 UI（替代 Day 6 末尾的"前端自己查 `/todos`"）
- 把 Day 5 的 **ToolSuspend HITL 升级为 AG-UI HITL**：前端弹窗确认 → 用 `role=tool` 消息回填到下一次 run
- 可观测三件套全部打齐（需求 #10）：
  - **日志**：Logback JSON + 7 个关键 stage（INPUT/LLM_CALL/TOOL_CALL/SCHEMA_VALIDATE/TODO_UPDATE/FRONTEND_DISPATCH/FRONTEND_CALLBACK）
  - **追踪**：OpenTelemetry + Jaeger（本地 docker），traceId 跟 jq 日志、curl 响应头**三处对得上**
  - **指标**：`ExecutionMetricsHook`（4 个 AS-Java Hook 接口）+ Micrometer + Actuator/Prometheus 端点
- 跑通异常剧本 + WireMock 集成测试 + 录制演示 + 需求逐项打勾

### 上午 · STATE 同步 + HITL on AG-UI（3.5h）

1. **`AguiStateBridge`**：监听 TodoManager 变更，向当前 run 推 `STATE_DELTA`（RFC 6902 JSON Patch）

   ```java
   public class AguiStateBridge implements TodoChangeListener {
       private final AguiEventEmitter emitter;
       public void onCreate(TodoItem it) {
           emitter.emit(StateDeltaEvent.of(JsonPatch.add("/todos/-", it)));
       }
       public void onStatusChange(String id, TodoStatus from, TodoStatus to) {
           emitter.emit(StateDeltaEvent.of(JsonPatch.replace("/todos/" + idx(id) + "/status", to)));
       }
   }
   ```

2. **HITL on AG-UI**（替换 Day 5 的 CLI 确认）

   - Agent 调 `submit_to_frontend` 时，starter 自动发 `TOOL_CALL_START / _ARGS / _END`
   - 前端拿到 `TOOL_CALL_END` 后渲染确认弹窗
   - 用户点"确认"，前端发下一次 `runAgent`，`messages` 末尾追加 `{role: 'tool', toolCallId, content: 'USER_CONFIRMED'}`
   - Agent `await toolResult` 续跑，调 `bridge.dispatchAll(...)` 真正下发

   关键收益：**HITL 链路完全前端化**，CLI 时代要自己 `readLine()` 的代码全部删掉

### 下午 · 可观测三连 + 异常 + 验收（5.5h）

1. **Phase 3a · 日志骨架（60 min）**：`logstash-logback-encoder` + `TraceIdFilter`（从 `X-Trace-Id` 请求头优先取）+ `Stage` helper（INPUT/LLM_CALL/.../FRONTEND_CALLBACK 7 个常量）。落地 7 个关键日志点，`jq` 按 traceId 过滤能看到 7 个 stage 一个不少
2. **Phase 3b · OpenTelemetry + Jaeger（75 min）**：`docker run jaegertracing/all-in-one`；`opentelemetry-spring-boot-starter` + OTLP gRPC exporter；用 `opentelemetry-logback-mdc-1.0` 把 OTel traceId 同步到 MDC（**`trace-id-key: traceId` 必踩的坑**）；`@WithSpan("tool.create_app")` 注解化 Span；Jaeger UI 看 `agent.call → tool.* → frontend_dispatch` 完整 trace
3. **Phase 3c · Hook 可观测 + Micrometer（45 min）**：`ExecutionMetricsHook` 同时实现 `PreReasoning/PostReasoning/PreActing/PostActing` 四个 hook，把数据喂给 `MeterRegistry`：`scope.llm.latency`（Timer，按 model 切片）、`scope.tool.calls`（Counter，按 toolName + status 切片）、`scope.llm.prompt.chars`（DistributionSummary）；`/actuator/prometheus` 直接被 Prometheus 抓
4. **Phase 4 · 异常剧本**（E1-E8 全跑通，详见课程文档）
5. **Phase 5-6 · 演示录制 + 需求打勾**（14 项含新增的 AG-UI 合规 #14）

### 准备资料

- AG-UI State 事件：https://docs.ag-ui.com/concepts/events#state-management-events
- AG-UI HITL（hitl-chat 示例）：https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-examples/agui
- Logstash Logback Encoder：https://github.com/logfellow/logstash-logback-encoder
- OpenTelemetry Spring Boot Starter：https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/
- Jaeger all-in-one：https://www.jaegertracing.io/docs/latest/getting-started/
- Micrometer 文档：https://micrometer.io/docs
- Spring Boot Actuator Metrics：https://docs.spring.io/spring-boot/reference/actuator/metrics.html
- [docs/agents/07-harness.md § RuntimeContext](./agents/07-harness.md)
- [docs/agents/10-observability-hitl.md](./agents/10-observability-hitl.md)
- WireMock：https://wiremock.org/docs/

### 当日产出

- [ ] Vue3 UI 左侧聊天 / 右侧 Todo 看板，Todo 状态实时切换无刷新
- [ ] 用户点"确认"后 Agent 真正下发，点"取消"待办保持 PENDING
- [ ] `logs/scope.json.log` 能用 `jq 'select(.traceId=="...")'` 还原全过程；**7 个 stage 一个不少**
- [ ] `docker ps` 看到 `jaeger`，Jaeger UI `http://localhost:16686` 至少 1 条完整 trace（含 `agent.call` + `tool.*` 子 Span）
- [ ] curl 响应头 `X-Trace-Id`、jq 日志 `.traceId`、Jaeger UI traceId **三处对得上**
- [ ] `/actuator/metrics` 列出至少 5 个 `scope.*` 指标；`/actuator/prometheus` 含 `scope_tool_calls_total`
- [ ] 8 个异常剧本全部测试通过
- [ ] 3 分钟演示视频
- [ ] 14 条需求（#1-#13 + #14 AG-UI）逐项打勾的清单（贴 PR 描述）

### 收尾打勾

| # | 需求 | 兑现位置 |
|---|------|---------|
| 1 | 用户输入需求 | Vue3 输入框 → `/agui/run` |
| 2 | 解析为 App/Module/Model | RequirementParser + FrontendCreateTools |
| 3 | 列出待办列表 | TodoManager + STATE_SNAPSHOT 推送 |
| 4 | 前端下发 | FrontendCreateTools → bridge.dispatchAll（HITL 通过后） |
| 5 | 状态管理 | TodoStatus 状态机 + STATE_DELTA |
| 6 | 异常 warnings/questions | AnalysisResult + Schema 校验 |
| 7 | JSON Schema 校验 | SchemaValidator |
| 8 | 多轮增量 | Memory + list_todos / update_* 工具 |
| 9 | 结果确认 | submit_to_frontend → TOOL_CALL 事件 → 前端弹窗 → role=tool 回填 |
| 10 | 日志 | logback JSON + MDC traceId |
| 11 | 模块结构 | ModuleSpec |
| 12 | 模型结构 | DataModelSpec + FieldSpec |
| 13 | 应用结构 | AppSpec |
| 14 | AG-UI 协议合规 | starter `/agui/run` + 17 事件 + Vue3 `@ag-ui/client` |

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

### A.3 AG-UI 事件速查（前端约定）

Day 6/7 起前后端**只走 AG-UI 标准事件**。SSE 帧形如 `data: {"type":"<EventType>", ...payload}`，前端用 `@ag-ui/client` 的回调即可消费。

```json
// Lifecycle
{ "type": "RUN_STARTED",          "threadId": "t1", "runId": "r1" }
{ "type": "RUN_FINISHED",         "threadId": "t1", "runId": "r1" }
{ "type": "RUN_ERROR",            "message": "..." }

// TextMessage（流式聊天）
{ "type": "TEXT_MESSAGE_START",   "messageId": "m1", "role": "assistant" }
{ "type": "TEXT_MESSAGE_CONTENT", "messageId": "m1", "delta": "你好" }
{ "type": "TEXT_MESSAGE_END",     "messageId": "m1" }

// ToolCall（HITL 也走这一路）
{ "type": "TOOL_CALL_START",      "toolCallId": "tc1", "toolName": "submit_to_frontend" }
{ "type": "TOOL_CALL_ARGS",       "toolCallId": "tc1", "delta": "{\"confirmed\":" }
{ "type": "TOOL_CALL_END",        "toolCallId": "tc1" }
{ "type": "TOOL_CALL_RESULT",     "toolCallId": "tc1", "content": "..." }

// State（TodoManager 镜像）
{ "type": "STATE_SNAPSHOT",       "snapshot": { "todos": [...] } }
{ "type": "STATE_DELTA",          "delta": [{ "op": "replace", "path": "/todos/0/status", "value": "SUCCESS" }] }
{ "type": "MESSAGES_SNAPSHOT",    "messages": [...] }

// Special
{ "type": "RAW",                  "event": {...}, "source": "..." }
{ "type": "CUSTOM",               "name": "...", "value": {...} }
```

> **HITL 时序提示**：`TOOL_CALL_END` 之后前端弹窗确认；用户点确认 → 发下一次 `runAgent`，`messages` 末尾追加 `{role:"tool", toolCallId:"tc1", content:"USER_CONFIRMED"}` 即可让 Agent 续跑。详见 Day 7 课程。

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
- Day 1-5 不依赖 Spring，方便聚焦 Agent 本体；Day 6 起切到 Spring Boot WebFlux + `agentscope-agui-spring-boot-starter`，是为了对齐 AG-UI 协议这一新增需求 #14。
- 前端固定 Vue3 + `@ag-ui/client`（Vite 脚手架）。如果你团队栈是 React，把客户端换成 CopilotKit 即可，事件协议完全一样。
- Day 7 末尾把 `ReActAgent` 升级到 `HarnessAgent`（1.1.0-RC1）是加分项，主要解决 Compaction 与大 ToolResult 污染上下文的问题；非必须。
- 任何一天卡住超过 2 小时，回头查 [docs/agents/](./agents/) 对应章节，或在群里贴上下文 + 报错 + 已尝试。
