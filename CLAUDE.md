# CLAUDE.md

> 给 **Claude Code** 在本仓库内工作时的工程约定。其他 AI 工具请看 [AGENTS.md](AGENTS.md)。

## 1. 项目一句话定位

基于 **AgentScope-Java 1.0.12** 的需求分析智能体学习项目。当前处于 **Day 1 阶段**（详见 [docs/learning.md](docs/learning.md)），骨架 + REPL 已通，后续 6 天会逐步加上数据契约、结构化输出、TodoManager、HITL、前端联调等。

**不要**在没有用户明确要求时把项目"补全"到 Day 7 的形态——每一天的代码都对应当天的学习目标，提前堆砌会破坏教学节奏。

## 2. 技术栈与版本

| 项 | 值 |
|---|---|
| JDK | 17（`maven.compiler.release=17`，不要降级） |
| 包名根 | `space.wlshow.scope`（**重命名时所有出现处必须同步**：源码 package、`logback.xml` logger name、`pom.xml` 的 `<exec.mainClass>`） |
| 构建 | Maven，主入口 `space.wlshow.scope.ScopeApp` |
| Agent 框架 | `io.agentscope:agentscope:1.0.12`（由 `${agentscope.version}` 控制） |
| 日志 | logback-classic 1.5.6 + SLF4J，输出到 `logs/scope.log`（已 gitignored） |
| 配置 | Typesafe Config 1.4.3（HOCON 格式） |
| 模型 | 默认走火山引擎方舟（OpenAI 兼容协议），通过 `OpenAIChatModel` + `baseUrl` 接入 |
| 测试 | JUnit Jupiter 5.10.2 + WireMock 3.5.4（离线 mock LLM） |

## 3. 常用命令

```bash
# 编译
mvn -q compile

# 跑 REPL（默认入口 ScopeApp）
mvn -q compile exec:java

# 跑别的 main（占位类示例）
mvn -q compile exec:java -Dexec.mainClass=space.wlshow.scope.ConfigCheck

# 跑测试（无需真实 API Key，WireMock 兜底）
mvn test

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

| 类 | 职责 | 备注 |
|---|---|---|
| `ScopeApp` | REPL 入口，支持 `/stream` 切换流式与 `exit` 退出 | |
| `AppConfig` | Typesafe Config 包装，单例静态方法 | API Key 缺失时直接抛 `IllegalStateException` |
| `ModelRegistry` | 以字符串 ID 注册/查找 `Model` 实例 | `ConcurrentHashMap` 实现，提供 `register/resolve/canResolve/reset` |
| `AgentFactory` | 构造 `ReActAgent`；调用 `initModels()` 注册默认模型 | 默认模型 ID 常量 `DEFAULT_MODEL_ID = "primary"` |
| `PromptLengthHook` | 监听 `PreReasoningEvent` / `PostReasoningEvent`，统计 prompt 长度并告警 | 阈值 8000 字符；priority=50 |

## 6. 测试约定

- `HelloTest`：占位 sanity 测试
- `WireMockAgentTest`：用 `WireMockServer.dynamicPort()` 模拟 Ark `/chat/completions`，跑同步 + 多轮两个场景。**新增 LLM 相关功能时，优先沿用这种 mock 风格**，不要在单测里依赖真实 API Key
- 跑测试用 `mvn test`，CI 上不应有外网依赖

## 7. 日志约定

- 业务包 `space.wlshow.scope` 默认 DEBUG，框架包（`io.agentscope` / `reactor` / `okhttp3` / `io.netty`）默认 INFO/WARN
- 控制台 + `logs/scope.log` 滚动 14 天
- **必须保留** logback `<charset>UTF-8</charset>`，否则 Windows 中文版会乱码
- 用户输入用 `[USER]` 前缀，模型回复用 `[BOT]` / `[BOT-STREAM]`，钩子日志用 `[Hook]` 前缀

## 8. 写代码的几条硬规矩

1. **包名一致**：所有新文件放在 `space.wlshow.scope.*` 下，子包按职责分（`agent/` / `config/` / `model/` / `hook/` / 未来的 `tool/` / `todo/` / `schema/` 等）
2. **不要往源码里塞 API Key 或个人模型 ID**——这些写进 `application-local.conf`
3. **不要把临时实验类提交进 master**（比如 Day 1 课程里那个验证用的 `ConfigCheck`，验完就删）
4. **遵循当前 Day 的范围**：Day 1 还没引入 Jackson / JSON Schema / Spring，不要为了"完整"提前加依赖
5. 中文注释 OK，本仓库面向中文学习者；类与方法 Javadoc 用中文也可

## 9. 文档体系（不要漏看）

| 路径 | 内容 |
|------|------|
| `docs/learning.md` | 7 天总路线图，每天的学习目标、产出、风险 |
| `docs/lessons/Day01_项目骨架 + AS-Java Hello World.md` | Day 1 完整课程（6 个 Phase，含可拷贝代码 + 故障表 + 附录） |
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

**示例**：

| 课程 | 文件名 |
|------|--------|
| Day 1 | `Day01_项目骨架 + AS-Java Hello World.md` |
| Day 2 | `Day02_数据契约 + JSON Schema 校验.md`（计划） |
| Day 3 | `Day03_需求解析 + Structured Output.md`（计划） |

新建课程文档时同步在 `docs/learning.md` 路线图、`README.md` 文档导航、本文件第 9 节表格里加引用。Markdown 链接因含空格，建议用 `[文本](<带空格的路径.md>)` 角括号形式或将空格转义为 `%20`。

## 10. 已知陷阱速查

| 现象 | 原因 / 解法 |
|------|----|
| `-Dexec.mainClass=...` 不生效 | pom 的 `<mainClass>` 写成了字面值。改成 `${exec.mainClass}` |
| Windows 中文乱码 | logback 缺 UTF-8 charset / 终端 code page 是 GBK；详见 Day01 课程附录 B-2 |
| `block()` 抛 `blocking call` 错误 | 在 Reactor 线程里调了 `.block()`，main 线程不会触发 |
| 重启后 `[primary] 被覆盖` 警告 | 测试或多次 `initModels()` 调用导致；用 `ModelRegistry.reset()` 清理 |
| `IllegalStateException: API key 未配置` | 当前 shell 没有 `ARK_API_KEY`，Windows 用户级变量需新开终端 |

## 11. Git 提交风格

仓库里现有 commit 用中文 + Conventional Commits 前缀（`feat(agent):` / `refactor(app):` / `chore(config):`），保持一致。每个 Day 的收尾 commit 可加 `dayN: ...` 前缀便于 `git log --oneline` 回顾。
