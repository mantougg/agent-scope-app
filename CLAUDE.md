# CLAUDE.md

> 给 **Claude Code** 在本仓库内工作时的工程约定。其他 AI 工具请看 [AGENTS.md](AGENTS.md)。

## 1. 项目一句话定位

基于 **AgentScope-Java 1.0.12** 的需求分析智能体学习项目。当前处于 **Day 6 已落地** 状态（详见 [docs/learning.md](docs/learning.md)）：Day 1-5 全部就位 + Day 6 把对外入口从 CLI 切到 **Spring Boot WebFlux**（`ScopeApp` 成为 `@SpringBootApplication`，Day 5 的 CLI REPL 保留在 `ScopeReplApp` 备份入口）+ `agentscope-agui-spring-boot-starter` 暴露 `POST /agui/run` SSE + `AguiAgentConfig` 通过覆盖 `ThreadSessionManager` 子类按 threadId 复用 `FileSession` + `CorsWebFilter` 放开 5173 跨域 + `frontend/` Vite + Vue3 + `@ag-ui/client` 打字机渲染。Day 7 课程文档已写完，代码尚未落地。

**不要**在没有用户明确要求时把项目"补全"到 Day 7 的形态——每一天的代码都对应当天的学习目标，提前堆砌会破坏教学节奏。具体表现为：

- 不要在 Day 6 代码里提前接 `AguiStateBridge` / `STATE_DELTA` / AG-UI HITL（那是 Day 7）
- 不要提前引入 OpenTelemetry / Micrometer / Jaeger（那是 Day 7）
- Day 6 阶段 `SubmitTool` 的 `ToolSuspendException` 在 AG-UI 通道下**没有**前端回填路径，是 Day 7 §5 才补完的——目前调它会让一次 run 看起来"卡住没反应"，这是预期内

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
| JSON | Jackson 2.17.0（databind + jsr310）— **`<dependencyManagement>` 里 `jackson-bom` 必须排在 `spring-boot-dependencies` 之前**，否则会被 BOM 拉回 2.15.4 触发 `NoSuchMethodError: BufferRecycler.releaseToPool()` |
| Schema 校验 | networknt json-schema-validator 2.0.0（Draft 2020-12） |
| 测试 | JUnit Jupiter 5.10.2 + WireMock 3.5.4（离线 mock LLM） |
| Web 容器（Day 6+） | Spring Boot 3.2.5 + `spring-boot-starter-webflux`（Netty，**不要**同时加 `spring-boot-starter-web`） |
| AG-UI 协议（Day 6+） | `agentscope-agui-spring-boot-starter:1.0.12`（与主包同版本） |
| 前端（Day 6+） | `frontend/` 独立 npm 工程：Vite + Vue3 + `@ag-ui/client` |

> Day 7 起会加 OpenTelemetry + Micrometer + Jaeger（docker）+ HttpDispatcher。**当前 Day 6 阶段不应在 pom 出现以上任一个**。

## 3. 常用命令

```bash
# 编译
mvn -q compile

# 起 Spring Boot 主入口（Day 6 起的默认路径）：暴露 POST http://localhost:8080/agui/run
mvn -q spring-boot:run

# 临时回到 Day 5 的 CLI REPL（不启 8080 端口）
mvn -q compile exec:java -Dexec.mainClass=space.wlshow.scope.ScopeReplApp

# 起前端 Vue3 dev server（另一终端，Vite 默认 5173）
cd frontend && npm install && npm run dev

# Day 6 §6.3：用 curl + jq 直接打 SSE 流，肉眼数事件类型
./scripts/agui-tail.sh http://localhost:8080/agui/run "$(cat fixtures/demo-input.json)"

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

### Day 5（多轮对话 + Memory/Session + HITL）

| 类 | 职责 |
|---|---|
| `session.FileSession` | 单文件会话：只持久化 `TodoManager`，Memory 每次进程启动从空开始；`loadOrNew(id)` 静态加载 / `save()` 落 `data/sessions/<id>.json` |
| `tool.TodoQueryTools` | `@Tool list_todos`：列出当前所有 todo 供 LLM 自查 |
| `tool.TodoUpdateTools` | `@Tool update_app / update_module / update_model`：让 LLM 增量改已存在的 todo（替代清空重建） |
| `tool.SubmitTool` | `@Tool submit_to_frontend(confirmed)`：confirmed=false 抛 `ToolSuspendException` 触发 HITL；CLI 走 `ScopeReplApp.handleSuspend()` 回填，**Day 6 AG-UI 通道暂无回填路径，Day 7 §5 补完** |
| `ScopeReplApp` | Day 5 落地的 CLI REPL（`/parse` `/run` `/submit` `/todos` 四个子命令 + ToolSuspend 回填）；Day 6 切 Spring Boot 后**保留为备份入口**，用 `-Dexec.mainClass=space.wlshow.scope.ScopeReplApp` 触发 |

### Day 6（AG-UI 协议集成 - 基础）

| 类 / 资源 | 职责 |
|---|---|
| `ScopeApp` | 改造成 `@SpringBootApplication` 引导类，`mvn spring-boot:run` 起 Netty WebFlux 暴露 `POST /agui/run` |
| `config.AguiAgentConfig` | 覆盖 starter 自动配置的 `ThreadSessionManager` Bean（`@ConditionalOnMissingBean`）：子类 `getOrCreateAgent` 把默认 `Supplier<Agent>` 换成闭包 threadId 的 Supplier，按 threadId 从 `FileSession.loadOrNew(threadId)` 复用待办/记忆；`@PostConstruct` 跑一次 `AgentFactory.initModels()` 避免 `[primary] 被覆盖` 刷屏；`@PreDestroy` 把 `activeSessions` 全量落盘；同文件还有 `CorsWebFilter` 显式列 GET/POST/OPTIONS 放开 5173 |
| `resources/application.yml` | Spring Boot + starter 配置：`agentscope.agui.path-prefix=/agui` / `default-agent-id=analyst` / `server-side-memory=true`（必须开，否则 `DefaultAgentResolver` 不走 sessionManager 分支） |
| `pom.xml` | 加 `spring-boot-starter-webflux` + `agentscope-agui-spring-boot-starter:${agentscope.version}` + `spring-boot-maven-plugin`；`jackson-bom 2.17.0` import **必须前置**于 `spring-boot-dependencies` |
| `frontend/` | 独立 npm 工程：Vite + Vue3 + `@ag-ui/client`；`src/App.vue` 用 `HttpAgent.subscribe({ onTextMessageStartEvent / onTextMessageContentEvent / onTextMessageEndEvent / onToolCallStartEvent / onToolCallEndEvent / onRunFinishedEvent / onRunErrorEvent })` 渲染打字机回复 |
| `scripts/agui-tail.sh` | curl + jq 把 SSE 帧按 `type \t (delta\|toolCallName\|...)` 打成一行一个事件，肉眼数 17 类型齐不齐 |
| `fixtures/demo-input.json` | 最小 `RunAgentInput` 样例，供 `scripts/agui-tail.sh` 与 `curl` 直接喂入 |
| `docs/screenshots/` | 截图 / GIF 录屏目录（Day 6 起），含 `day6-curl-trace.png` / `day6-end-to-end.gif`（学员录） |

> ⚠️ Day 6 与文档的偏离点（实现是对的，文档之后会同步）：
> - **不是** `AguiAgentRegistryCustomizer` —— starter 1.0.12 的 `registerFactory(String, Supplier<Agent>)` 无参 Supplier 拿不到 threadId，所以走"覆盖 ThreadSessionManager 子类"路线
> - **不是** `agentscope.agui.base-path / default-agent` —— `AguiProperties` 实际字段是 `pathPrefix / defaultAgentId`
> - **不是** `FileSession.loadOrNew` 内置缓存 —— 实际是 `AguiAgentConfig.activeSessions` `ConcurrentHashMap` 外部兜底
> - 前端 `@ag-ui/client` 当前版本回调入参形态是 `({event}) => event.xxx`，工具名字段是 `event.toolCallName`（不是 `toolName`）

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
- Day 5 新增日志前缀：`[Session]`（NEW / LOAD / SAVED）、`[Submit]`（suspend / dispatched）
- Day 6 新增日志前缀：`[AguiConfig]`（models initialized / build agent for thread=...）

## 8. 写代码的几条硬规矩

1. **包名一致**：所有新文件放在 `space.wlshow.scope.*` 下，子包按职责分：
   - 当前已有：`agent/` `config/` `hook/` `model/` `schema/` `session/` `spec/` `todo/` `tool/` `util/`
   - 后续将加：`agui/`（Day 7 `AguiStateBridge` + AG-UI HITL 桥）、`dispatch/`（Day 7 `HttpDispatcher`）
   - **不要**把 POJO 放进 `model/` ——`model/` 当前承担"AS-Java Model 注册表"职责；POJO 一律在 `spec/`
2. **不要往源码里塞 API Key 或个人模型 ID**——这些写进 `application-local.conf`
3. **不要把临时实验类提交进 master**（比如 Day 1 课程里那个验证用的 `ConfigCheck`，验完就删）
4. **遵循当前 Day 的范围**：Day 6 不接 `AguiStateBridge` / `STATE_DELTA` / AG-UI HITL 回填 / OTel/Micrometer——这些全部是 Day 7 的活儿
5. 中文注释 OK，本仓库面向中文学习者；类与方法 Javadoc 用中文也可
6. **Schema 校验先行**：所有 LLM 输出落到 `AnalysisResult` / `AppSpec` / `ModuleSpec` / `DataModelSpec` 前必须过 `SchemaValidator`；不要在 Java 侧用正则修 LLM 输出，让 LLM 自己改（`RequirementParser` 走"回灌错误"重试，`FrontendCreateTools` 走"返回 ERROR 字符串"让 LLM 重调工具）

## 9. 文档体系（不要漏看）

| 路径 | 内容 |
|------|------|
| `docs/learning.md` | 7 天总路线图，每天的学习目标、产出、风险、AG-UI 协议契约 |
| `docs/lessons/Day00_环境准备.md` | Day 0：JDK 17 / Maven 镜像 / Node 20 / Docker / Git Bash + jq / ARK_API_KEY 自检脚本与故障表 |
| `docs/lessons/Day01_项目骨架 + AS-Java Hello World.md` | Day 1：6 个 Phase（含可拷贝代码 + 故障表 + 附录）|
| `docs/lessons/Day02_数据契约 + JSON Schema 校验.md` | Day 2：POJO record + JSON Schema 2020-12 + networknt 校验器 |
| `docs/lessons/Day03_需求解析 + Structured Output.md` | Day 3：system prompt + few-shot + `RequirementParser` 3 次自纠错 + WireMock 离线回放 |
| `docs/lessons/Day04_TodoManager + 业务工具集.md` | Day 4：状态机 + `create_*` 工具 |
| `docs/lessons/Day05_多轮对话 + Memory 与 Session + HITL.md` | Day 5：增量更新 + FileSession + ToolSuspend HITL |
| `docs/lessons/Day06_AG-UI 协议集成（基础）.md` | Day 6：Spring Boot starter + 17 事件 + Vue3 客户端 + 附录 C 版本与 API 兼容性速查 |
| `docs/lessons/Day07_AG-UI 协议进阶 + 收尾验收.md` | Day 7：STATE_DELTA + HITL on AG-UI + HttpDispatcher 真发 HTTP + 可观测三件套 |
| `docs/agents/` | AS-Java 框架笔记 11 篇：overview / core-concepts / react-agent / tool-system / model-providers / memory-state-session / harness / multi-agent / integration / observability-hitl / vs-python |

**回答"如何用 AS-Java 做 X"类问题时，先查 `docs/agents/` 对应章节**，不要凭空回答。

### 9.1 课程文档命名规范

`docs/lessons/` 下的课程文档**必须**遵循：

```
Day[两位数序号]_[文章标题].md
```

- `Day` 首字母大写，序号补零到两位（`Day00`、`Day01` … `Day07`）
- 序号与标题用下划线 `_` 分隔
- 标题保持中文 + 原始空格 / 标点（不要替换为 kebab-case），与文档第一行 `# Day N · ...` 中的标题部分一致

**当前清单**：

| 课程 | 文件名 | 落地状态 |
|------|--------|---------|
| Day 0 | `Day00_环境准备.md` | 📘 文档 |
| Day 1 | `Day01_项目骨架 + AS-Java Hello World.md` | ✅ 代码 + 文档 |
| Day 2 | `Day02_数据契约 + JSON Schema 校验.md` | ✅ 代码 + 文档 |
| Day 3 | `Day03_需求解析 + Structured Output.md` | ✅ 代码 + 文档 |
| Day 4 | `Day04_TodoManager + 业务工具集.md` | ✅ 代码 + 文档 |
| Day 5 | `Day05_多轮对话 + Memory 与 Session + HITL.md` | ✅ 代码 + 文档 |
| Day 6 | `Day06_AG-UI 协议集成（基础）.md` | ✅ 代码 + 文档 |
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
| Windows 中文乱码 | 三层都要打：(1) logback 编码器 `<charset>UTF-8</charset>`；(2) JVM 默认编码——`.mvn/jvm.config` 写 `-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`（JDK 17 必须，JDK 18+ 仍建议）；(3) 终端 code page——cmd 用 `chcp 65001`，Git Bash / Windows Terminal 默认 UTF-8 |
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
| `NoSuchMethodError: BufferRecycler.releaseToPool()` | `jackson-bom 2.17.0` 没在 `<dependencyManagement>` 里前置于 `spring-boot-dependencies`，被 Boot BOM 拉回 2.15.4；按 `pom.xml` 第 35-48 行顺序排（Maven BOM "先声明者胜"） |
| 浏览器 5173 调 `/agui/run` CORS 报错 | `AguiAgentConfig.corsWebFilter()` 没被扫到，或 `application.yml` 里又写了一份 `agentscope.agui.cors.*` 让响应头出现两份 `Access-Control-Allow-Origin`；二选一 |
| 起得来但 `/agui/run` 一直 404 | `application.yml` 漏了 `agentscope.agui.path-prefix=/agui` 或写成 `base-path`；正确 key 是 `pathPrefix`（参看 `AguiProperties`） |
| Day 6 起浏览器对话后 `data/sessions/` 空 | `AguiAgentConfig.@PreDestroy saveAllOnShutdown` 仅在优雅关停时触发，Ctrl+C / 异常退出可能跑不到；现象正常，Day 7 接 `AguiStateBridge` 时顺手把 `session.save()` 挂到 listener 上即时落盘 |
| 浏览器发送后一直转圈、后端日志停在 `[Submit] suspend` | LLM 触发了 `submit_to_frontend(confirmed=false)` → `ToolSuspendException`，Day 6 AG-UI 通道**没有**前端回填路径；Day 7 §5 才补完。临时绕开：在 `AgentFactory.buildAnalystWithTools` 里**先摘掉** `toolkit.registerTool(new SubmitTool(todos))` |
| 浏览器 Agent 回复一整段砸下来不流式 | 模型 `stream` 没开；检查 `application.conf` / `ModelRegistry` 里 `OpenAIChatModel.builder().stream(true)`；火山方舟必须开 stream 才推 tool_call 增量 |

## 11. Git 提交风格

仓库里现有 commit 用中文 + Conventional Commits 前缀（`feat(agent):` / `refactor(app):` / `chore(config):`），保持一致。每个 Day 的收尾 commit 可加 `dayN: ...` 前缀便于 `git log --oneline` 回顾。
