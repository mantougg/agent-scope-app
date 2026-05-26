# AGENTS.md

> 给 **AI 编程助手**（Codex、Cursor、Cline、Gemini CLI、Copilot 等）在本仓库内工作时的通用工程指引。
> Claude Code 用户请直接看 [CLAUDE.md](CLAUDE.md)（内容更细，包含 Claude 专属约定）。

## 1. 项目定位

`agent-scope-app` 是一个基于 **AgentScope-Java 1.0.12** 的需求分析智能体**学习项目**，7 天路线图见 [docs/learning.md](docs/learning.md)。

- **当前状态**：Day 4 已落地（REPL + JSON Schema 数据契约 + `RequirementParser` 3 次自纠错 + WireMock 离线回放 + `TodoManager` 状态机 + `FrontendCreateTools` 三个 `@Tool`（含 Schema 兜底）+ `/run` 工具调度命令）。Day 5 ~ Day 7 课程文档已就位，代码尚未落地
- **目标产物**：能把中文需求拆解为「应用 / 模块 / 数据模型」三层 JSON 结构、并通过 AG-UI 协议跟前端联调的 Agent
- **教学性质**：每个 Day 的代码对应当天的学习目标，**不要**自作主张提前实现后续阶段的功能（如 Day 4 阶段不接 Memory / Session / Spring Boot）

## 2. 技术栈

| 项 | 值 |
|---|---|
| 语言 | Java 17（不要降级） |
| 构建 | Maven |
| 根包 | `space.wlshow.scope` |
| 主入口 | `space.wlshow.scope.ScopeApp` |
| Agent 框架 | `io.agentscope:agentscope:1.0.12` |
| 模型协议 | OpenAI 兼容（默认走火山引擎方舟 Ark，模型 `doubao-1-5-pro-32k-250115`） |
| 日志 | logback 1.5.6 + SLF4J |
| 配置 | Typesafe Config 1.4.3（HOCON 格式） |
| JSON | Jackson 2.17.0（databind + jsr310） |
| Schema 校验 | networknt json-schema-validator 2.0.0（Draft 2020-12） |
| 测试 | JUnit Jupiter 5.10.2 + WireMock 3.5.4 |

## 3. 目录约定

```
src/main/java/space/wlshow/scope/
├── ScopeApp.java               入口（REPL，含 /stream /parse /run 命令）
├── agent/                      Agent 构造、RequirementParser、ParseException
├── config/                     配置包装（AppConfig）
├── hook/                       钩子（PromptLengthHook 等）
├── model/                      AS-Java Model 注册表（ModelRegistry）
├── schema/                     JSON Schema 校验器 + ValidationError
├── spec/                       业务 POJO record（AnalysisResult / AppSpec / ModuleSpec / DataModelSpec / FieldSpec）
├── todo/                       Day 4：TodoStatus / TodoType / TodoItem / TodoChangeListener / TodoManager
├── tool/                       Day 4：FrontendCreateTools（@Tool create_app/module/model + 工具内 Schema 兜底）
└── util/                       Json（ObjectMapper 门面 + stripFence + readList）、Prompts（classpath 加载）

src/main/resources/
├── application.conf            主配置（进 git）
├── application-local.conf      本地覆盖（gitignored）
├── logback.xml
├── prompts/
│   ├── analyst.md              Day 3：需求解析 system prompt + few-shot
│   └── analyst-with-tools.md   Day 4：工具调度 system prompt
└── schemas/                    JSON Schema 文件
    ├── analysis-result.schema.json
    ├── app-spec.schema.json    Day 4：工具内 APP_VAL
    ├── module-spec.schema.json Day 4：工具内 MODULE_VAL
    └── data-model-spec.schema.json  Day 4：工具内 MODEL_VAL（含递归 FieldSpec $defs）

src/test/java/space/wlshow/scope/
└── ...Test.java                JUnit 5 测试

src/test/resources/
├── schema-samples/             Day 2：5 个 pass-*/fail-* schema 样例
└── wiremock/__files/           Day 1/3：LLM 响应 fixture（analyst-ok/bad-fence/missing-app）
```

新增文件遵循以上分包。后续 Day 会扩展 `frontend/`（Day 6 AG-UI bridge）等子包——**按职责分**，不要全堆在根包；POJO 一律放 `spec/`，不要塞进 `model/`（那是 AS-Java Model 注册表的位置）。

### 3.1 课程文档命名规范

`docs/lessons/` 下的课程文档**必须**遵循：

```
Day[两位数序号]_[文章标题].md
```

- `Day` 首字母大写，序号补零到两位：`Day01`、`Day02` … `Day07`
- 序号与标题用下划线 `_` 分隔
- 标题保持中文 + 原始空格 / 标点，与文档第一行 `# Day N · ...` 中标题部分一致

**当前清单**：

| 课程 | 文件名 | 落地状态 |
|------|------|---------|
| Day 1 | `Day01_项目骨架 + AS-Java Hello World.md` | ✅ 代码 + 文档 |
| Day 2 | `Day02_数据契约 + JSON Schema 校验.md` | ✅ 代码 + 文档 |
| Day 3 | `Day03_需求解析 + Structured Output.md` | ✅ 代码 + 文档 |
| Day 4 | `Day04_TodoManager + 业务工具集.md` | ✅ 代码 + 文档 |
| Day 5 | `Day05_多轮对话 + Memory 与 Session + HITL.md` | 📘 仅文档 |
| Day 6 | `Day06_AG-UI 协议集成（基础）.md` | 📘 仅文档 |
| Day 7 | `Day07_AG-UI 协议进阶 + 收尾验收.md` | 📘 仅文档 |

新建课程文档时**同时**更新 `docs/learning.md` 路线图与 `README.md` 文档导航里的引用。文件名含空格，markdown 链接用角括号 `[文本](<路径>)` 或 `%20` 转义。

## 4. 常用命令

```bash
mvn -q compile                                     # 编译
mvn -q compile exec:java                           # 跑默认入口 ScopeApp（REPL）
mvn -q compile exec:java -Dexec.mainClass=<FQN>    # 跑指定 main
mvn test                                           # 跑离线测试（默认排除 @Tag("live")）
mvn -Dgroups=live test                             # 跑真实 LLM 集成测试（需 ARK_API_KEY）
mvn -q test -Dtest=<TestClass>                     # 单跑某个测试类
mvn -U dependency:resolve                          # 强制拉依赖
```

REPL 命令：`/stream` 切换流式输出、`/parse <中文需求>` 走 `RequirementParser` 解析（Day 3 链路）、`/run <中文需求>` 走 `buildAnalystWithTools` 工具调度落 `TodoManager`（Day 4 链路）、`exit` 退出。

## 5. 配置与密钥

- 配置加载优先级：`系统属性(-D) > application-local.conf > application.conf`
- API Key **一律走环境变量** `${?ARK_API_KEY}`
- **绝不**把 API Key 写进任何配置文件、源码或日志
- 本地实验性配置（自己的模型名/接入点）放进 `application-local.conf`，该文件已被 gitignored

## 6. 编码规范

1. **包名一致性**：所有源码在 `space.wlshow.scope.*` 下；如需重命名，**所有出现处必须同步**——源码 `package` 声明、`logback.xml` 里 `<logger name="...">`、`pom.xml` 里 `<exec.mainClass>`
2. **依赖只在当前 Day 的范围内增加**：当前（Day 4 阶段）已就位的依赖是 agentscope / logback / typesafe-config / jackson / networknt-json-schema-validator / junit / wiremock。在用户明确要求或路线图指出之前，**不要**提前加 Spring Boot、Reactor 测试库、`@ag-ui/client`、OpenTelemetry 等
3. **使用现有抽象**：
   - 模型实例通过 `ModelRegistry.register/resolve` 管理，不要在新代码里直接 `new OpenAIChatModel(...)`（测试除外）
   - JSON 解析序列化走 `util.Json` 的静态方法，不要直接 `new ObjectMapper()`；`List<T>` 反序列化用 `Json.readList`
   - 加载 prompt / schema 走 `util.Prompts` / `SchemaValidator`，不要散落 `getResourceAsStream`
   - 工具调度场景把 `TodoManager` 当唯一 sink，不要在 `@Tool` 方法里另起本地集合
4. **Reactor 调用约定**：`agent.call(req)` 返回 `Mono<Msg>`，需 `.timeout(AppConfig.timeout()).block()` 才会真正执行；不要在 Reactor 线程内调 `.block()`
5. **日志约定**：用户输入 `[USER]`、模型回复 `[BOT]` / `[BOT-STREAM]`、钩子 `[Hook]`、解析过程 `[Parse]`、Schema 校验 `[Schema]`、工具调用 `[Tool]`、待办状态 `[Todo]`；用 SLF4J 占位符 `log.info("x={}", v)`，不要字符串拼接
6. **Schema 校验先行**：所有 LLM 输出落到业务 POJO 前必须过 `SchemaValidator`；不要在 Java 侧用正则修 LLM 输出，让 LLM 自己改。两条参考链路：
   - `RequirementParser` 走"回灌错误回 LLM 重试"，最多 3 次
   - `FrontendCreateTools` 走"返回 `ERROR: ...` 字符串让 LLM 重调工具"，**不**落 TodoManager 脏数据
7. **TodoManager 状态机不许绕开**：`PENDING → SUCCESS` / `SUCCESS|FAILED → 任何` / `RUNNING → PENDING` 都是非法迁移，由 `transit()` 抛 `IllegalStateException`；如有重试需求，应创建新的 TodoItem 而不是回退状态
8. **中文注释允许**：本仓库面向中文学习者，Javadoc / 注释 / 日志文案用中文 OK
9. **保留 logback `<charset>UTF-8</charset>`**：移除会导致 Windows 中文版控制台乱码

## 7. 测试要求

当前测试套件（10 个测试类，离线共 36 个测试）：

| 测试类 | 覆盖 |
|---|---|
| `HelloTest` | 占位 sanity |
| `WireMockAgentTest` | Day 1：Ark `/chat/completions` 同步 + 多轮 |
| `schema.SchemaValidatorTest` | Day 2：5 个 schema-sample（pass/fail） |
| `spec.JsonRoundTripTest` | Day 2：POJO ↔ JSON 来回 |
| `util.JsonStripFenceTest` | Day 3：5 种 fence/寒暄 |
| `agent.RequirementParserMockTest` | Day 3：4 个离线场景（ok / fence / 缺字段重试 / 3 次放弃） |
| `agent.RequirementParserLiveTest` | Day 3：3 个真实 LLM 场景，**`@Tag("live")` 默认跳过** |
| `todo.TodoItemTest` | Day 4：3 个用例（newPending / withStatus 不回退 / 终态枚举） |
| `todo.TodoManagerTest` | Day 4：7 个用例（增删 / 正常迁移 / 拒收 / 监听器 / state roundtrip） |
| `tool.FrontendCreateToolsTest` | Day 4：4 个用例（happy-path / fieldsJson 解析 / fieldsJson 异常 / Schema 拒收中文 name） |

规则：

- 涉及 LLM 调用的功能**必须**先写 WireMock 离线测试。参考 `RequirementParserMockTest` 的 stub 风格（`WireMockServer.dynamicPort()` + `usingFilesUnderClasspath("wiremock")`）
- 真实 LLM 场景配 `@Tag("live")` + `@EnabledIfEnvironmentVariable(named = "ARK_API_KEY", matches = ".+")`
- `pom.xml` 已设 `<excludedGroups>live</excludedGroups>`：CI 不需要 `ARK_API_KEY` 也能跑
- WireMock fixture 路径固定 `src/test/resources/wiremock/__files/`；Schema 校验样例 `src/test/resources/schema-samples/`
- 新增功能至少配 1 个 happy-path 测试

## 8. 提交风格

仓库现有 commit 使用 **中文 + Conventional Commits**：

```
feat(agent): 添加 RequirementParser 与 3 次自纠错链路
refactor(agent): 重构模型管理为注册表模式
chore(config): 添加 application-local.conf 到忽略列表
test(schema): 补充 schema-samples 覆盖递归字段
```

保持一致；课程节点收尾可加 `dayN:` 前缀（如 `day3: 需求解析 + Structured Output`）。

## 9. 何时查文档

| 情境 | 先看 |
|------|------|
| "如何用 AS-Java 做 X" | [docs/agents/](docs/agents/) 对应章节（11 篇笔记） |
| `docs/agents/` 没覆盖的新特性 / API 细节 | AS-Java 官方 llms.txt 索引（见下） |
| "今天应该做什么" / "Day N 范围" | [docs/learning.md](docs/learning.md) |
| Day 1 步骤 / 命令 / 故障排查 | [Day01 课程文档](<docs/lessons/Day01_项目骨架 + AS-Java Hello World.md>) |
| Day 2：POJO / JSON Schema | [Day02 课程文档](<docs/lessons/Day02_数据契约 + JSON Schema 校验.md>) |
| Day 3：prompt 设计 / 自纠错 / WireMock 离线回放 | [Day03 课程文档](<docs/lessons/Day03_需求解析 + Structured Output.md>) |
| Day 4：TodoManager 状态机 / `@Tool` / Toolkit / 工具内 Schema 兜底 | [Day04 课程文档](<docs/lessons/Day04_TodoManager + 业务工具集.md>) |
| Day 5 ~ Day 7（尚未落地） | 对应 `docs/lessons/Day0N_*.md`，落地前请先读再动代码 |
| 启动/配置/环境变量问题 | [README.md](README.md) |

回答"AS-Java 怎么用 X"类问题时，**先查 `docs/agents/` 对应章节再回答**，不要凭空生成 API。

### 9.1 官方 llms.txt 文档索引（跨工具通用）

AS-Java 官方提供两份 LLM-readable 文档供 AI 编程助手消费：

| 文件 | 用途 |
|------|------|
| <https://java.agentscope.io/llms.txt> | 索引版，列出全部官方文档页面 + 简短描述 |
| <https://java.agentscope.io/llms-full.txt> | 全文版，所有页面拼成的巨长 Markdown，适合一次性灌进 LLM 上下文做 RAG |

**接入参考**（详见 <https://java.agentscope.io/en/task/ai-coding.html>）：

- **Cursor**：Settings → Docs → Add URL，填 `https://java.agentscope.io/llms-full.txt`；或在 Settings → Tools & MCP 加 `mcpdoc` MCP server 指向 `llms.txt`
- **Windsurf**：Settings → MCP，加 `mcpdoc` MCP server 指向 `llms.txt`
- **Claude Code**：`claude mcp add agentscope-docs -- uvx --from mcpdoc mcpdoc --urls AgentScopeJava:https://java.agentscope.io/llms.txt`
- **其他工具**：只要支持 llms.txt 标准或能 ingest URL 的都能用

优先级：本仓库 `docs/agents/` > 官方 llms.txt > 凭空生成。

## 10. 已知陷阱

| 现象 | 原因 / 解法 |
|------|------|
| `-Dexec.mainClass=...` 不生效 | `pom.xml` 的 `<mainClass>` 必须用 `${exec.mainClass}` 占位符，不能是字面值 |
| Windows 控制台中文乱码 | logback 必须配 `<charset>UTF-8</charset>`；cmd 可加 `chcp 65001` |
| `IllegalStateException: API key 未配置` | 当前 shell 没有 `ARK_API_KEY`，Windows 用户级变量需新开终端 |
| `model [primary] 被覆盖` 警告 | `initModels()` 被调多次；测试场景用 `ModelRegistry.reset()` |
| `TimeoutException` | `application.conf` 的 `agent.timeout` 太短，调大到 `300s` |
| `[Parse] LLM 输出连续 3 次不符合 schema` | prompt 漂移或 schema 过严；用 `SchemaValidatorTest` 拿同一份 JSON 跑一遍定位 |
| live 测试莫名 skip | `@EnabledIfEnvironmentVariable` 未通过；IDE 跑 test 时环境变量未透传，改命令行 `mvn -Dgroups=live test` |
| WireMock 测试 404 | `usingFilesUnderClasspath("wiremock")` 路径错；确认 `src/test/resources/wiremock/__files/` 存在 |
| `/run` 时 LLM 不调工具直接吐 JSON | system prompt 没生效或模型不支持 function calling；确认 `buildAnalystWithTools` 真用了 `Prompts.analystWithTools()` |
| `/run` 调了工具但 TodoManager 是空的 | `Toolkit.registerTool` 没注入同一个 `TodoManager` 实例（`ScopeApp.todos` 与 `analystWithTools` 必须同源） |
| `[Tool] create_* rejected` 而 LLM 不重试 | `maxIters` 用尽 / 模型不消化 ERROR 文案；调高 `buildAnalystWithTools` 的 `maxIters` 或精简 schema 错误措辞 |
| `IllegalStateException: 终态不可迁移` | 状态机重复 mark；Day 4 内只 `add` 不 `mark`，Day 5/6 才会触发 |

## 11. 操作边界

- **不要**为了"补完整"提前实现路线图后续 Day 的功能（如 Day 4 阶段加 Memory、Day 5 阶段加 Spring Boot 等）
- **不要**删除 `docs/` 目录下的任何课程文档
- **不要**修改 `.gitignore` 里对 `application-local.conf` 和 AI 工具缓存目录（`.claude/` `.cursor/` 等）的排除
- 修改 `application.conf` 默认值前先确认是否应该改到 `application-local.conf`
- 改 `spec/` 下任何 record 字段时必须**同步四处**：`resources/schemas/analysis-result.schema.json`、`resources/schemas/{app,module,data-model}-spec.schema.json`、`prompts/analyst.md` / `prompts/analyst-with-tools.md`、对应的 `FrontendCreateTools` `@ToolParam` 注解
