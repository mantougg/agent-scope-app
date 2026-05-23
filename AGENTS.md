# AGENTS.md

> 给 **AI 编程助手**（Codex、Cursor、Cline、Gemini CLI、Copilot 等）在本仓库内工作时的通用工程指引。
> Claude Code 用户请直接看 [CLAUDE.md](CLAUDE.md)（内容更细，包含 Claude 专属约定）。

## 1. 项目定位

`agent-scope-app` 是一个基于 **AgentScope-Java 1.0.12** 的需求分析智能体**学习项目**，7 天路线图见 [docs/learning.md](docs/learning.md)。

- **当前状态**：Day 1（项目骨架 + REPL Hello World 已通）
- **目标产物**：能把中文需求拆解为「应用 / 模块 / 数据模型」三层 JSON 结构的 Agent
- **教学性质**：每个 Day 的代码对应当天的学习目标，**不要**自作主张提前实现后续阶段的功能

## 2. 技术栈

| 项 | 值 |
|---|---|
| 语言 | Java 17（不要降级） |
| 构建 | Maven |
| 根包 | `space.wlshow.scope` |
| 主入口 | `space.wlshow.scope.ScopeApp` |
| Agent 框架 | `io.agentscope:agentscope:1.0.12` |
| 模型协议 | OpenAI 兼容（默认走火山引擎方舟 Ark） |
| 日志 | logback 1.5.6 + SLF4J |
| 配置 | Typesafe Config 1.4.3（HOCON 格式） |
| 测试 | JUnit Jupiter 5.10.2 + WireMock 3.5.4 |

## 3. 目录约定

```
src/main/java/space/wlshow/scope/
├── ScopeApp.java               入口（REPL）
├── agent/                      Agent 构造
├── config/                     配置包装
├── model/                      模型注册表（未来还会放 POJO/DataModel）
└── hook/                       钩子（Prompt 监控等）

src/main/resources/
├── application.conf            主配置（进 git）
├── application-local.conf      本地覆盖（gitignored）
└── logback.xml

src/test/java/space/wlshow/scope/
└── *Test.java                  JUnit 5 测试
```

新增文件遵循以上分包；后续 Day 会扩展 `tool/` / `todo/` / `schema/` / `frontend/` 等子包，**按职责分**，不要全堆在根包。

### 3.1 课程文档命名规范

`docs/lessons/` 下的课程文档**必须**遵循：

```
Day[两位数序号]_[文章标题].md
```

- `Day` 首字母大写，序号补零到两位：`Day01`、`Day02` … `Day07`
- 序号与标题用下划线 `_` 分隔
- 标题保持中文 + 原始空格 / 标点，与文档第一行 `# Day N · ...` 中标题部分一致

示例：

| 课程 | 文件名 |
|------|------|
| Day 1 | `Day01_项目骨架 + AS-Java Hello World.md` |
| Day 2 | `Day02_数据契约 + JSON Schema 校验.md`（计划） |

新建课程文档时**同时**更新 `docs/learning.md` 路线图与 `README.md` 文档导航里的引用。文件名含空格，markdown 链接用角括号 `[文本](<路径>)` 或 `%20` 转义。

## 4. 常用命令

```bash
mvn -q compile                                     # 编译
mvn -q compile exec:java                           # 跑默认入口 ScopeApp
mvn -q compile exec:java -Dexec.mainClass=<FQN>    # 跑指定 main
mvn test                                           # 跑测试（无需真实 API Key）
mvn -U dependency:resolve                          # 强制拉依赖
```

## 5. 配置与密钥

- 配置加载优先级：`系统属性(-D) > application-local.conf > application.conf`
- API Key **一律走环境变量** `${?ARK_API_KEY}`
- **绝不**把 API Key 写进任何配置文件、源码或日志
- 本地实验性配置（自己的模型名/接入点）放进 `application-local.conf`，该文件已被 gitignored

## 6. 编码规范

1. **包名一致性**：所有源码在 `space.wlshow.scope.*` 下；如需重命名，**所有出现处必须同步**——源码 `package` 声明、`logback.xml` 里 `<logger name="...">`、`pom.xml` 里 `<exec.mainClass>`
2. **不引入未授权的依赖**：当前阶段（Day 1）只有 agentscope / logback / typesafe-config / junit / wiremock。在用户明确要求或路线图明确指出之前，**不要**添加 Jackson、Spring、Reactor 测试库、JSON Schema validator 等
3. **使用现有抽象**：模型实例通过 `ModelRegistry.register/resolve` 管理，不要在新代码里直接 `new OpenAIChatModel(...)`
4. **Reactor 调用约定**：`agent.call(req)` 返回 `Mono<Msg>`，需 `.timeout(AppConfig.timeout()).block()` 才会真正执行；不要在 Reactor 线程内调 `.block()`
5. **日志约定**：用户输入 `[USER]`、模型回复 `[BOT]` / `[BOT-STREAM]`、钩子 `[Hook]`；用 SLF4J 占位符 `log.info("x={}", v)`，不要字符串拼接
6. **中文注释允许**：本仓库面向中文学习者，Javadoc / 注释 / 日志文案用中文 OK
7. **保留 logback `<charset>UTF-8</charset>`**：移除会导致 Windows 中文版控制台乱码

## 7. 测试要求

- 涉及 LLM 调用的功能**必须**用 WireMock 写离线测试，参考 `WireMockAgentTest`
- CI 不应有外网依赖；不要在测试里读 `ARK_API_KEY`
- 新增功能至少配 1 个 happy-path 测试

## 8. 提交风格

仓库现有 commit 使用 **中文 + Conventional Commits**：

```
feat(agent): 添加 Prompt 长度监控钩子和 WireMock 测试
refactor(agent): 重构模型管理为注册表模式
chore(config): 添加 application-local.conf 到忽略列表
```

保持一致；课程节点收尾可加 `dayN:` 前缀。

## 9. 何时查文档

| 情境 | 先看 |
|------|------|
| "如何用 AS-Java 做 X" | [docs/agents/](docs/agents/) 对应章节（11 篇笔记） |
| "今天应该做什么" / "Day N 范围" | [docs/learning.md](docs/learning.md) |
| Day 1 具体步骤、命令、故障排查 | [Day01 课程文档](<docs/lessons/Day01_项目骨架 + AS-Java Hello World.md>) |
| 启动/配置/环境变量问题 | [README.md](README.md) |

回答"AS-Java 怎么用 X"类问题时，**先查 `docs/agents/` 对应章节再回答**，不要凭空生成 API。

## 10. 已知陷阱

| 现象 | 原因 / 解法 |
|------|------|
| `-Dexec.mainClass=...` 不生效 | `pom.xml` 的 `<mainClass>` 必须用 `${exec.mainClass}` 占位符，不能是字面值 |
| Windows 控制台中文乱码 | logback 必须配 `<charset>UTF-8</charset>`；cmd 可加 `chcp 65001` |
| `IllegalStateException: API key 未配置` | 当前 shell 没有 `ARK_API_KEY`，Windows 用户级变量需新开终端 |
| `model [primary] 被覆盖` 警告 | `initModels()` 被调多次；测试场景用 `ModelRegistry.reset()` |
| `TimeoutException` | `application.conf` 的 `agent.timeout` 太短，调大到 `300s` |

## 11. 操作边界

- **不要**为了"补完整"提前实现路线图后续 Day 的功能（如 Day 1 阶段加 JSON Schema 校验、Spring WebFlux 等）
- **不要**删除 `docs/` 目录下的任何课程文档
- **不要**修改 `.gitignore` 里对 `application-local.conf` 和 AI 工具缓存目录（`.claude/` `.cursor/` 等）的排除
- 修改 `application.conf` 默认值前先确认是否应该改到 `application-local.conf`
