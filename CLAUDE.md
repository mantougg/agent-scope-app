# CLAUDE.md

> 给 **Claude Code** 在本仓库内工作时的工程约定。其他 AI 工具请看 [AGENTS.md](AGENTS.md)。

## 1. 项目一句话定位

基于 **AgentScope-Java 1.0.12** 的需求分析智能体学习项目。当前处于 **Day 4 已落地** 状态（详见 [docs/learning.md](docs/learning.md)）：骨架 + REPL + JSON Schema 数据契约 + `RequirementParser`（3 次自纠错）+ WireMock 离线回放 + `TodoManager` 状态机 + `FrontendCreateTools` 工具集（含工具内 Schema 兜底）+ `/run` 工具调度命令都已就位。Day 5 ~ Day 7 课程文档已写完，代码尚未落地。

**不要**在没有用户明确要求时把项目"补全"到 Day 7 的形态——每一天的代码都对应当天的学习目标，提前堆砌会破坏教学节奏。具体表现为：

- 不要在 Day 4 代码里提前接 Memory / Session（那是 Day 5）
- 不要在仓库里提前出现 Spring Boot / WebFlux / `@ag-ui/client`（那是 Day 6/7）
- 不要在 Day 4 阶段引入 OpenTelemetry / Micrometer（那是 Day 7）

## 2. 技术栈与版本

| 项 | 值 |
|---|---|
| JDK | 17（`maven.compiler.release=17`，不要降级） |
| 包名根 | `space.wlshow.scope`（**重命名时所有出现处必须同步**：源码 package、`logback.xml` logger name、`pom.xml` 的 `<exec.mainClass>`） |
| 构建 | Maven，主入口 `space.wlshow.scope.ScopeApp` |
| Agent 框架 | `io.agentscope:agentscope:1.0.12`（由 `${agentscope.version}` 控制） |
| 日志 | logback-classic 1.5.6 + SLF4J，输出到 `logs/scope.log`（已 gitignored） |
| 配置 | Typesafe Config 1.4.3（HOCON 格式） |
| 模型 | 默认走火山引擎方舟（OpenAI 兼容协议），通过 `OpenAIChatModel` + `baseUrl` 接入；默认模型 `doubao-1-5-pro-32k-250115` |
| JSON | Jackson 2.17.0（databind + jsr310） |
| Schema 校验 | networknt json-schema-validator 2.0.0（Draft 2020-12） |
| 测试 | JUnit Jupiter 5.10.2 + WireMock 3.5.4（离线 mock LLM） |

> Day 5 起会加 `JsonSession` 与 Memory、Day 6 加 `spring-boot-starter-webflux` 和 `agentscope-agui-spring-boot-starter`、Day 7 加 OpenTelemetry + Micrometer。**当前 Day 4 阶段不应在 pom 出现以上任一个**。

## 3. 常用命令

```bash
# 编译
mvn -q compile

# 跑 REPL（默认入口 ScopeApp）
mvn -q compile exec:java

# 跑别的 main（占位类示例）
mvn -q compile exec:java -Dexec.mainClass=space.wlshow.scope.ConfigCheck

# 跑离线测试（默认排除 @Tag("live")，无需真实 API Key）
mvn test

# 跑真实 LLM 集成测试（需要 ARK_API_KEY）
mvn -Dgroups=live test

# 单跑一个测试类
mvn -q test -Dtest=RequirementParserMockTest

# 拉依赖
mvn -U dependency:resolve
```

> ⚠️ `pom.xml` 里的 `<mainClass>${exec.mainClass}</mainClass>` **必须用占位符**，不能写成字面值——否则 `-Dexec.mainClass=...` 会被静默吞掉。详见 [Day01 课程附录 B-1](<docs/lessons/Day01_项目骨架 + AS-Java Hello World.md>)。

## 4. 配置加载机制

`AppConfig.loadLayered()` 三层叠加：

```
系统属性(-D) > application-local.conf（如存在，gitignored）> application.conf
```

- **`application.conf`**：进 git，团队默认值
- **`application-local.conf`**：gitignored，本地覆盖。已在 `.gitignore` 第 102 行排除
- API Key 一律走环境变量 `${?ARK_API_KEY}`，**不要**把 key 写进任何配置文件

## 5. 已就位的核心组件

### Day 1（骨架）

| 类 | 职责 | 备注 |
|---|---|---|
| `ScopeApp` | REPL 入口，支持 `/stream` 流式、`/parse <需求>` 解析、`/run <需求>` 工具调度、`exit` 退出 | |
| `AppConfig` | Typesafe Config 包装，单例静态方法 | API Key 缺失时直接抛 `IllegalStateException` |
| `ModelRegistry` | 以字符串 ID 注册/查找 `Model` 实例 | `ConcurrentHashMap` 实现，提供 `register/resolve/canResolve/reset` |
| `AgentFactory` | `buildAnalyst()`（自由对话）+ `buildParser()`（Day 3 解析专用）；调用 `initModels()` 注册默认模型 | 默认模型 ID 常量 `DEFAULT_MODEL_ID = "primary"` |
| `PromptLengthHook` | 监听 `PreReasoningEvent` / `PostReasoningEvent`，统计 prompt 长度并告警 | 阈值 8000 字符；priority=50 |

### Day 2（数据契约）

| 类 | 职责 |
|---|---|
| `spec.AnalysisResult` | 顶层 record，含 `app/modules/models/warnings/questions` 五字段 |
| `spec.AppSpec` / `ModuleSpec` / `DataModelSpec` / `FieldSpec` | 题面 3 段 JSON 对应的 record；`FieldSpec.subs` 递归 |
| `schema.SchemaValidator` | 加载 classpath 上的 JSON Schema 2020-12，`validate()` 返回 `List<ValidationError>` |
| `schema.ValidationError` | `record(path, keyword, message, raw)`，message 已中文化 |
| `util.Json` | 全局 `ObjectMapper` 门面：宽进严出、`JavaTimeModule`、`tree/read/write/writePretty` |
| `resources/schemas/analysis-result.schema.json` | 顶层契约文件（pattern / enum / required / $defs 递归） |

### Day 3（需求解析 + Structured Output）

| 类 | 职责 |
|---|---|
| `util.Prompts` | 加载 classpath `/prompts/*.md`，双检锁缓存（`analyst()` / `analystWithTools()`） |
| `util.Json.stripFence` | 容错剥离 4 种 fence / 寒暄包裹形态 |
| `agent.RequirementParser` | 主流程：`agent.call → stripFence → Json.tree → SchemaValidator → 不合规自纠错`，最多 3 次 |
| `agent.ParseException` | 重试耗尽时抛出，携带 `lastErrors` 给 REPL 打印 |
| `AgentFactory.buildParser()` | 用 `Prompts.analyst()` 作 system prompt，`maxIters=2` |
| `resources/prompts/analyst.md` | 含 2 个 few-shot（最简 ENTITY + master-slave）；规则带 `warnings/questions` 约束 |
| `wiremock/__files/analyst-{ok,bad-fence,missing-app}.json` | 3 份 LLM 响应 fixture，覆盖正常 / fence / 缺字段三种漂移 |

### Day 4（TodoManager + 业务工具集）

| 类 | 职责 |
|---|---|
| `todo.TodoStatus` | 状态枚举：`PENDING / RUNNING / SUCCESS / FAILED`，含 `isTerminal()` |
| `todo.TodoType` | 工具类型枚举：`CREATE_APP / CREATE_MODULE / CREATE_MODEL` |
| `todo.TodoItem` | 不可变 record（8 字段），`newPending` 工厂 + `withStatus` 衍生 |
| `todo.TodoChangeListener` | `onCreate/onStatusChange/onClear` 三个 default 钩子，给 Day 7 STATE_DELTA 桥用 |
| `todo.TodoManager` | LinkedHashMap 持序待办池 + 5 条非法迁移防御 + `getState/loadState` 风格序列化（Day 5 才接 StateModule） |
| `tool.FrontendCreateTools` | 3 个 `@Tool`：`create_app` / `create_module` / `create_model`；每个工具用对应 Schema 兜底，失败返回 `ERROR: ...` 给 LLM 自纠错，**不**落 TodoManager |
| `AgentFactory.buildAnalystWithTools(TodoManager)` | 工具调度版 Agent：`Toolkit(parallel=true)` + `Prompts.analystWithTools()` + `maxIters=15` |
| `util.Json.readList` | 反序列化 `List<T>`，`FrontendCreateTools.createModel` 解析 `fieldsJson` 字符串用 |
| `resources/prompts/analyst-with-tools.md` | 工具调度 system prompt，强制 LLM 走 `create_*` 工具不要直接吐 JSON |
| `resources/schemas/app-spec.schema.json` | 单独抽出的 AppSpec schema，工具内 `APP_VAL` 加载 |
| `resources/schemas/module-spec.schema.json` | ModuleSpec schema，工具内 `MODULE_VAL` 加载 |
| `resources/schemas/data-model-spec.schema.json` | DataModelSpec schema（含递归 FieldSpec `$defs`），工具内 `MODEL_VAL` 加载 |

## 6. 测试约定

### 当前测试套件（10 个测试类，离线共 36 个测试）

| 测试类 | 覆盖 |
|---|---|
| `HelloTest` | 占位 sanity |
| `WireMockAgentTest` | Day 1：模拟 Ark `/chat/completions`，跑同步 + 多轮 |
| `schema.SchemaValidatorTest` | Day 2：5 个 schema-sample（pass-leave-system / fail-missing-app / fail-bad-data-type / fail-bad-module-id / fail-recursive-bad） |
| `spec.JsonRoundTripTest` | Day 2：POJO ↔ JSON 来回反序列化 |
| `util.JsonStripFenceTest` | Day 3：5 种 fence/寒暄场景 |
| `agent.RequirementParserMockTest` | Day 3：4 个离线场景（ok / fence / 缺字段重试 / 3 次放弃） |
| `agent.RequirementParserLiveTest` | Day 3：3 个真实 LLM 场景，**`@Tag("live")` 默认跳过** |
| `todo.TodoItemTest` | Day 4：3 个用例（newPending / withStatus 不回退 / 终态枚举判定） |
| `todo.TodoManagerTest` | Day 4：7 个用例（增删 / 正常迁移 / PENDING→SUCCESS 拒收 / 终态不可迁 / 监听器事件 / state roundtrip） |
| `tool.FrontendCreateToolsTest` | Day 4：4 个用例（createApp happy-path / fieldsJson 解析 / fieldsJson 异常 / 中文 name 被 Schema 兜底拒收） |

### 约定

- 涉及 LLM 调用的功能**必须**先写 WireMock 离线测试，再视情况配 `@Tag("live")` 真实测试
- `pom.xml` 已设 `<excludedGroups>live</excludedGroups>`：`mvn test` 默认不跑 live，CI 无 `ARK_API_KEY` 也不会红
- 跑 live：`mvn -Dgroups=live test`
- WireMock fixture 放 `src/test/resources/wiremock/__files/`，路径 `usingFilesUnderClasspath("wiremock")` 已固定
- Schema 校验样例放 `src/test/resources/schema-samples/`，命名 `pass-*.json` / `fail-*.json`
- 新增 LLM 相关功能时，优先沿用 `RequirementParserMockTest` 的 stub 风格

## 7. 日志约定

- 业务包 `space.wlshow.scope` 默认 DEBUG，框架包（`io.agentscope` / `reactor` / `okhttp3` / `io.netty`）默认 INFO/WARN
- 控制台 + `logs/scope.log` 滚动 14 天
- **必须保留** logback `<charset>UTF-8</charset>`，否则 Windows 中文版会乱码
- 用户输入用 `[USER]` 前缀，模型回复用 `[BOT]` / `[BOT-STREAM]`，钩子日志用 `[Hook]` 前缀
- Day 3 新增日志前缀：`[Parse]`（attempt / promptHead / raw/stripped chars / schema errors）、`[Schema]`（加载 / 校验失败明细）
- Day 4 新增日志前缀：`[Tool]`（每个 `create_*` 工具调用与 schema 拒收）、`[Todo]`（CREATE / 状态迁移 / CLEAR / LOADED）

## 8. 写代码的几条硬规矩

1. **包名一致**：所有新文件放在 `space.wlshow.scope.*` 下，子包按职责分：
   - 当前已有：`agent/` `config/` `hook/` `model/` `schema/` `spec/` `todo/` `tool/` `util/`
   - 后续将加：`frontend/`（Day 6 AG-UI bridge）
   - **不要**把 POJO 放进 `model/` ——`model/` 当前承担"AS-Java Model 注册表"职责；POJO 一律在 `spec/`
2. **不要往源码里塞 API Key 或个人模型 ID**——这些写进 `application-local.conf`
3. **不要把临时实验类提交进 master**（比如 Day 1 课程里那个验证用的 `ConfigCheck`，验完就删）
4. **遵循当前 Day 的范围**：Day 4 不引 Memory / Session / Spring；Day 5 才加 Memory，Day 6 才加 Spring Boot，Day 7 才加 OpenTelemetry
5. 中文注释 OK，本仓库面向中文学习者；类与方法 Javadoc 用中文也可
6. **Schema 校验先行**：所有 LLM 输出落到 `AnalysisResult` / `AppSpec` / `ModuleSpec` / `DataModelSpec` 前必须过 `SchemaValidator`；不要在 Java 侧用正则修 LLM 输出，让 LLM 自己改（`RequirementParser` 走"回灌错误"重试，`FrontendCreateTools` 走"返回 ERROR 字符串"让 LLM 重调工具）

## 9. 文档体系（不要漏看）

| 路径 | 内容 |
|------|------|
| `docs/learning.md` | 7 天总路线图，每天的学习目标、产出、风险、AG-UI 协议契约 |
| `docs/lessons/Day01_项目骨架 + AS-Java Hello World.md` | Day 1：6 个 Phase（含可拷贝代码 + 故障表 + 附录）|
| `docs/lessons/Day02_数据契约 + JSON Schema 校验.md` | Day 2：POJO record + JSON Schema 2020-12 + networknt 校验器 |
| `docs/lessons/Day03_需求解析 + Structured Output.md` | Day 3：system prompt + few-shot + `RequirementParser` 3 次自纠错 + WireMock 离线回放 |
| `docs/lessons/Day04_TodoManager + 业务工具集.md` | Day 4：状态机 + `create_*` 工具（待落地） |
| `docs/lessons/Day05_多轮对话 + Memory 与 Session + HITL.md` | Day 5：增量更新 + JsonSession + ToolSuspend HITL（待落地） |
| `docs/lessons/Day06_AG-UI 协议集成（基础）.md` | Day 6：Spring Boot starter + 17 事件 + Vue3 客户端（待落地） |
| `docs/lessons/Day07_AG-UI 协议进阶 + 收尾验收.md` | Day 7：STATE_DELTA + HITL on AG-UI + 可观测三件套（待落地） |
| `docs/agents/` | AS-Java 框架笔记 11 篇：overview / core-concepts / react-agent / tool-system / model-providers / memory-state-session / harness / multi-agent / integration / observability-hitl / vs-python |

**回答"如何用 AS-Java 做 X"类问题时，先查 `docs/agents/` 对应章节**，不要凭空回答。

### 9.1 课程文档命名规范

`docs/lessons/` 下的课程文档**必须**遵循：

```
Day[两位数序号]_[文章标题].md
```

- `Day` 首字母大写，序号补零到两位（`Day01`、`Day02` … `Day07`）
- 序号与标题用下划线 `_` 分隔
- 标题保持中文 + 原始空格 / 标点（不要替换为 kebab-case），与文档第一行 `# Day N · ...` 中的标题部分一致

**当前清单**：

| 课程 | 文件名 | 落地状态 |
|------|--------|---------|
| Day 1 | `Day01_项目骨架 + AS-Java Hello World.md` | ✅ 代码 + 文档 |
| Day 2 | `Day02_数据契约 + JSON Schema 校验.md` | ✅ 代码 + 文档 |
| Day 3 | `Day03_需求解析 + Structured Output.md` | ✅ 代码 + 文档 |
| Day 4 | `Day04_TodoManager + 业务工具集.md` | ✅ 代码 + 文档 |
| Day 5 | `Day05_多轮对话 + Memory 与 Session + HITL.md` | 📘 仅文档 |
| Day 6 | `Day06_AG-UI 协议集成（基础）.md` | 📘 仅文档 |
| Day 7 | `Day07_AG-UI 协议进阶 + 收尾验收.md` | 📘 仅文档 |

新建课程文档时同步在 `docs/learning.md` 路线图、`README.md` 文档导航、本文件第 9 节表格里加引用。Markdown 链接因含空格，建议用 `[文本](<带空格的路径.md>)` 角括号形式或将空格转义为 `%20`。

### 9.2 官方 llms.txt 文档索引（兜底）

`docs/agents/` 是本仓库基于 AS-Java 1.0.12 整理的本地笔记。当问题落在笔记没覆盖的范围（新版本特性、补丁说明、API 细节差异）时，再查 AS-Java 官方维护的 LLM-readable 文档索引：

| 文件 | 用途 |
|------|------|
| <https://java.agentscope.io/llms.txt> | 索引版，列出全部官方文档页面 + 简短描述，适合"先扫目录、再点进具体页" |
| <https://java.agentscope.io/llms-full.txt> | 全文版，把所有页面拼成一份巨长 Markdown，适合一次性灌进 LLM 上下文做 RAG |

**Claude Code 的接入方式**（用 MCP server 把 llms.txt 注册成可检索文档源）：

```bash
claude mcp add agentscope-docs -- uvx --from mcpdoc mcpdoc \
  --urls AgentScopeJava:https://java.agentscope.io/llms.txt
```

接入后可在会话里直接问"AS-Java `HarnessAgent` 的 Compaction 怎么配？"等问题，由 MCP 检索官方页面再回答，比盲查可靠。详见官方说明：<https://java.agentscope.io/en/task/ai-coding.html>。

> 优先级：本仓库 `docs/agents/` > 官方 llms.txt > 凭空生成。**不要**跳过前两步直接编 API。

## 10. 已知陷阱速查

| 现象 | 原因 / 解法 |
|------|----|
| `-Dexec.mainClass=...` 不生效 | pom 的 `<mainClass>` 写成了字面值。改成 `${exec.mainClass}` |
| Windows 中文乱码 | logback 缺 UTF-8 charset / 终端 code page 是 GBK；详见 Day01 课程附录 B-2 |
| `block()` 抛 `blocking call` 错误 | 在 Reactor 线程里调了 `.block()`，main 线程不会触发 |
| 重启后 `[primary] 被覆盖` 警告 | 测试或多次 `initModels()` 调用导致；用 `ModelRegistry.reset()` 清理 |
| `IllegalStateException: API key 未配置` | 当前 shell 没有 `ARK_API_KEY`，Windows 用户级变量需新开终端 |
| `[Parse] attempt 1 json broken: Unexpected character ('.')` | LLM 返回的 fence 内容本身是占位符或被裁断；先看 `logs/scope.log` 的 `raw chars=/stripped chars=`，确认 stripFence 后是否还是合法 JSON |
| `LLM 输出连续 3 次不符合 schema` | prompt 漂移或 schema 过严；用 `SchemaValidatorTest` 拿同一份 JSON 跑一遍定位 |
| `@EnabledIfEnvironmentVariable` 让 live test 被 skip | IDE 跑 test 时环境变量未透传；从命令行 `mvn -Dgroups=live test` 验证 |
| WireMock 测试 404 | `usingFilesUnderClasspath("wiremock")` 路径错；确认 `src/test/resources/wiremock/__files/` 存在 |
| `/run` 后 LLM 不调工具直接吐 JSON | system prompt 没生效 / 模型不支持 function calling；确认 `buildAnalystWithTools` 走的是 `Prompts.analystWithTools()`、模型是否启用了工具调用 |
| `/run` 工具调了但 TodoManager 是空的 | `Toolkit.registerTool` 没注入同一个 `TodoManager` 实例；检查 `ScopeApp.todos` 与 `analystWithTools` 是否同源 |
| `[Tool] create_app rejected: ...` 但 LLM 后续不重试 | LLM 解读 ERROR 字符串失败；调高 `maxIters` 或人肉看 `logs/scope.log` 里上下文 |
| `IllegalStateException: 终态不可迁移` | LLM 重复调 mark 类工具（Day 5/6 才会出现）；Day 4 内只应 add，不应 mark |
| `TodoItemTest.withStatus_keepsIdAndTypeAndPayload` 偶发失败 | Windows `Instant.now()` 分辨率约 15ms；断言已改成 `compareTo >= 0` 不回退，命中老版本断言时按 [Day04 课程附录](<docs/lessons/Day04_TodoManager + 业务工具集.md>) 替换 |

## 11. Git 提交风格

仓库里现有 commit 用中文 + Conventional Commits 前缀（`feat(agent):` / `refactor(app):` / `chore(config):`），保持一致。每个 Day 的收尾 commit 可加 `dayN: ...` 前缀便于 `git log --oneline` 回顾。
