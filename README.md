# agent-scope-app

> 基于 **AgentScope-Java** + **火山引擎方舟（Volcengine Ark）** 的需求分析智能体学习项目。
> 7 天内从零搭建一个能把中文需求拆解为「应用 / 模块 / 数据模型」的 ReAct Agent。

当前进度：**Day 2**（数据契约 + JSON Schema 校验 课程文档已就位，待落地）。详见 [docs/learning.md](docs/learning.md) 的路线图。

---

## 1. 快速开始

### 1.1 前置环境

| 软件 | 版本 | 说明 |
|------|------|------|
| JDK  | 17+  | 推荐 Eclipse Temurin 17 |
| Maven | 3.8+ | |
| API Key | `ARK_API_KEY` | 火山引擎方舟控制台生成 |

### 1.2 配置 API Key

到 [火山引擎方舟控制台](https://console.volcengine.com/ark) 开通服务、生成 API Key，然后设置环境变量：

```bash
# Linux / macOS / Git Bash
export ARK_API_KEY=apikey-xxxxxxxx

# Windows PowerShell（永久）
[System.Environment]::SetEnvironmentVariable('ARK_API_KEY','apikey-xxxxxxxx','User')
```

> 设完后**新开一个终端**才能让 JVM 读到该变量。

### 1.3 启动 REPL

```bash
mvn -q compile exec:java
```

进入交互界面后：

```
Scope REPL - 输入 'exit' 退出， '/stream' 切换流式。

you > 你好，请用一句话介绍你自己
bot > 你好！我是需求分析助手 RequirementAnalyst，可以帮你拆解需求。

you > /stream
you (stream) > 用中文逐字解释什么是 ReAct
bot > ... (逐字吐出)

you > exit
bye.
```

日志会同时写到 `logs/scope.log`。

### 1.4 跑测试（无需真实 API Key）

```bash
mvn test
```

`WireMockAgentTest` 用 WireMock 模拟了 Ark `/chat/completions` 接口，可离线验证 ReActAgent 全链路。

---

## 2. 项目结构

```
agent-scope-app/
├── pom.xml
├── README.md
├── CLAUDE.md                                ← Claude Code 工程指引
├── AGENTS.md                                ← 通用 AI Agent 工程指引
├── docs/
│   ├── learning.md                          ← 7 天学习路线图
│   ├── lessons/Day01_项目骨架 + AS-Java Hello World.md  ← Day 1 详细课程
│   └── agents/                              ← AS-Java 笔记（11 篇）
├── logs/                                    ← 运行后自动生成
└── src/
    ├── main/
    │   ├── java/space/wlshow/scope/
    │   │   ├── ScopeApp.java                ← 入口（REPL）
    │   │   ├── agent/AgentFactory.java      ← Agent 构造
    │   │   ├── config/AppConfig.java        ← Typesafe Config 包装
    │   │   ├── model/ModelRegistry.java     ← 模型注册表
    │   │   └── hook/PromptLengthHook.java   ← Prompt 长度监控钩子
    │   └── resources/
    │       ├── application.conf             ← 主配置
    │       ├── application-local.conf       ← 本地覆盖（gitignored）
    │       └── logback.xml
    └── test/java/space/wlshow/scope/
        ├── HelloTest.java
        └── WireMockAgentTest.java           ← 离线集成测试
```

---

## 3. 配置说明

配置采用 [HOCON](https://github.com/lightbend/config/blob/main/HOCON.md) 格式，加载优先级：

```
系统属性(-D) > application-local.conf（如存在，gitignored）> application.conf
```

**`src/main/resources/application.conf`**

```hocon
model {
  provider = "volcengine-ark"
  name     = "doubao-1-5-pro-32k-250115"  # 模型 ID 或接入点 ID (ep-xxx)
  baseUrl  = "https://ark.cn-beijing.volces.com/api/v3"
  apiKey   = ${?ARK_API_KEY}              # 从环境变量读取
}

agent {
  name      = "RequirementAnalyst"
  maxIters  = 15
  timeout   = 120s
  sysPrompt = """..."""
}
```

**切到其他 OpenAI 兼容厂商**（DeepSeek / 月之暗面 / 直连 OpenAI）：在 `application-local.conf` 里覆盖 `model.baseUrl` + `model.name` + 对应的 API Key 环境变量即可，Java 代码不用改。

---

## 4. 文档导航

| 文档 | 用途 |
|------|------|
| [docs/learning.md](docs/learning.md) | 7 天完整学习路线（Day 1 ~ Day 7） |
| [Day01 课程文档](<docs/lessons/Day01_项目骨架 + AS-Java Hello World.md>) | Day 1 详细课程：环境、Maven、日志、配置、REPL、流式 |
| [Day02 课程文档](<docs/lessons/Day02_数据契约 + JSON Schema 校验.md>) | Day 2 详细课程：POJO record、JSON Schema 2020-12、networknt 校验器 |
| [docs/agents/](docs/agents/) | AS-Java 框架笔记：Agent / Memory / Tool / Model / Harness 等 |
| [CLAUDE.md](CLAUDE.md) | Claude Code 在本仓库内的工程约定 |
| [AGENTS.md](AGENTS.md) | 通用 AI Agent 工程指引（Codex / Cursor 等） |

---

## 5. 常见问题

| 现象 | 处理 |
|------|------|
| `IllegalStateException: API key 未配置` | 当前 shell 没有 `ARK_API_KEY`，重新设置后**重开终端** |
| `401 Unauthorized` | API key 错误，到控制台重新生成 |
| `404 model not found` | `model.name` 与控制台不一致；接入点 ID 是 `ep-xxx`，模型 ID 形如 `doubao-x-x-xx-xxxxxx` |
| `TimeoutException` | 把 `application.conf` 里 `agent.timeout` 调大到 `300s` |
| Windows 控制台中文乱码 | 已在 `logback.xml` 配置 `<charset>UTF-8</charset>`；cmd 用户可加 `chcp 65001` |

更多排错见 [Day01 课程附录 B](<docs/lessons/Day01_项目骨架 + AS-Java Hello World.md>)。

> 📌 `docs/lessons/` 下的课程文档命名规范为 `Day[两位数序号]_[文章标题].md`（例如 `Day01_项目骨架 + AS-Java Hello World.md`）。详见 [CLAUDE.md §9.1](CLAUDE.md) / [AGENTS.md §3.1](AGENTS.md)。
