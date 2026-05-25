# agent-scope-app

> 基于 **AgentScope-Java** + **火山引擎方舟（Volcengine Ark）** 的需求分析智能体学习项目。
> 7 天内从零搭建一个能把中文需求拆解为「应用 / 模块 / 数据模型」的 ReAct Agent。

当前进度：**Day 3 已落地**（需求解析 + Structured Output：JSON Schema 校验 + 3 次自纠错 + WireMock 离线回放）。Day 4 ~ Day 7 课程文档已就位，代码待落地。详见 [docs/learning.md](docs/learning.md) 的路线图。

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

# Day 3 新增：/parse 把中文需求 → 合法 AnalysisResult JSON
you > /parse 做一个简单员工档案管理，记录姓名、工号、入职日期、部门
[PARSED]
{
  "app":      { "name": "employeeMgr", "label": "员工档案管理", "type": "23" },
  "modules":  [ { ... } ],
  "models":   [ { ..., "fields": [ ... ] } ],
  "warnings": [ "假设 app.type 取 23（业务管理类）" ],
  "questions": []
}

you > exit
bye.
```

`/parse` 走 `RequirementParser`：调用 LLM → `stripFence` 剥 fence → Schema 校验 → 不合规时把错误原文回灌给 LLM 自纠错，最多 3 次。

日志会同时写到 `logs/scope.log`。

### 1.4 跑测试（无需真实 API Key）

```bash
# 离线测试套件（CI 默认）
mvn test
```

当前共 7 个测试类：

| 测试类 | 覆盖 |
|------|------|
| `HelloTest` | 占位 sanity |
| `WireMockAgentTest` | Day 1：模拟 Ark `/chat/completions`，跑同步 + 多轮 |
| `schema/SchemaValidatorTest` | Day 2：5 个 schema-sample 文件，正反样例校验 |
| `spec/JsonRoundTripTest` | Day 2：POJO ↔ JSON 来回反序列化 |
| `util/JsonStripFenceTest` | Day 3：5 种 fence/寒暄场景的剥离 |
| `agent/RequirementParserMockTest` | Day 3：4 个 WireMock 场景（ok / fence / 缺字段重试 / 3 次放弃） |
| `agent/RequirementParserLiveTest` | Day 3：3 个真实 LLM 场景，**`@Tag("live")` 默认跳过** |

跑真实 LLM 集成测试（需要 `ARK_API_KEY`）：

```bash
mvn -Dgroups=live test
```

WireMock fixture 在 `src/test/resources/wiremock/__files/`，Schema 用例在 `src/test/resources/schema-samples/`。

---

## 2. 项目结构

```
agent-scope-app/
├── pom.xml
├── README.md
├── CLAUDE.md                                ← Claude Code 工程指引
├── AGENTS.md                                ← 通用 AI Agent 工程指引
├── mise.toml                                ← 工具链版本（可选）
├── docs/
│   ├── learning.md                          ← 7 天学习路线图
│   ├── lessons/                             ← Day01 ~ Day07 详细课程
│   └── agents/                              ← AS-Java 笔记（11 篇）
├── logs/                                    ← 运行后自动生成
└── src/
    ├── main/
    │   ├── java/space/wlshow/scope/
    │   │   ├── ScopeApp.java                ← 入口（REPL，含 /parse 命令）
    │   │   ├── agent/
    │   │   │   ├── AgentFactory.java        ← buildAnalyst() + buildParser()
    │   │   │   ├── RequirementParser.java   ← Day 3：3 次自纠错
    │   │   │   └── ParseException.java      ← 携带 lastErrors
    │   │   ├── config/AppConfig.java        ← Typesafe Config 包装
    │   │   ├── hook/PromptLengthHook.java   ← Day 1：Prompt 长度监控钩子
    │   │   ├── model/ModelRegistry.java     ← Day 1：模型注册表
    │   │   ├── schema/
    │   │   │   ├── SchemaValidator.java     ← Day 2：JSON Schema 2020-12 校验
    │   │   │   └── ValidationError.java     ← 错误对象（path/keyword/message）
    │   │   ├── spec/                        ← Day 2：5 个 record POJO
    │   │   │   ├── AnalysisResult.java
    │   │   │   ├── AppSpec.java
    │   │   │   ├── ModuleSpec.java
    │   │   │   ├── DataModelSpec.java
    │   │   │   └── FieldSpec.java
    │   │   └── util/
    │   │       ├── Json.java                ← ObjectMapper 门面 + stripFence
    │   │       └── Prompts.java             ← classpath prompt 加载（双检锁缓存）
    │   └── resources/
    │       ├── application.conf             ← 主配置（进 git）
    │       ├── application-local.conf       ← 本地覆盖（gitignored）
    │       ├── logback.xml
    │       ├── prompts/analyst.md           ← Day 3：need-analyst system prompt
    │       └── schemas/
    │           └── analysis-result.schema.json   ← Day 2：AnalysisResult schema
    └── test/
        ├── java/space/wlshow/scope/
        │   ├── HelloTest.java
        │   ├── WireMockAgentTest.java
        │   ├── schema/SchemaValidatorTest.java
        │   ├── spec/JsonRoundTripTest.java
        │   ├── util/JsonStripFenceTest.java
        │   └── agent/
        │       ├── RequirementParserMockTest.java   ← 4 个离线场景
        │       └── RequirementParserLiveTest.java   ← @Tag("live") 默认跳过
        └── resources/
            ├── schema-samples/              ← Day 2：5 个 pass/fail 样例
            └── wiremock/__files/            ← Day 1/3：LLM 响应 fixture
                ├── analyst-ok.json
                ├── analyst-bad-fence.json
                └── analyst-missing-app.json
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

> `/parse` 命令使用的 system prompt 是 `resources/prompts/analyst.md`，不走 `agent.sysPrompt`；后者只服务于自由对话路径。

**切到其他 OpenAI 兼容厂商**（DeepSeek / 月之暗面 / 直连 OpenAI）：在 `application-local.conf` 里覆盖 `model.baseUrl` + `model.name` + 对应的 API Key 环境变量即可，Java 代码不用改。

---

## 4. 文档导航

| 文档 | 用途 |
|------|------|
| [docs/learning.md](docs/learning.md) | 7 天完整学习路线（Day 1 ~ Day 7） |
| [Day01 课程文档](<docs/lessons/Day01_项目骨架 + AS-Java Hello World.md>) | 环境、Maven、日志、配置、REPL、流式 |
| [Day02 课程文档](<docs/lessons/Day02_数据契约 + JSON Schema 校验.md>) | POJO record、JSON Schema 2020-12、networknt 校验器 |
| [Day03 课程文档](<docs/lessons/Day03_需求解析 + Structured Output.md>) | system prompt + few-shot、`RequirementParser` 3 次自纠错、WireMock 离线回放 |
| [Day04 课程文档](<docs/lessons/Day04_TodoManager + 业务工具集.md>) | TodoManager 状态机 + `create_*` 工具集（待落地） |
| [Day05 课程文档](<docs/lessons/Day05_多轮对话 + Memory 与 Session + HITL.md>) | 增量更新 + JsonSession + ToolSuspend HITL（待落地） |
| [Day06 课程文档](<docs/lessons/Day06_AG-UI 协议集成（基础）.md>) | Spring Boot starter + 17 个 AG-UI 事件 + Vue3 客户端（待落地） |
| [Day07 课程文档](<docs/lessons/Day07_AG-UI 协议进阶 + 收尾验收.md>) | STATE_DELTA + HITL on AG-UI + OTel/Jaeger/Micrometer（待落地） |
| [docs/agents/](docs/agents/) | AS-Java 框架笔记 11 篇：Agent / Memory / Tool / Model / Harness 等 |
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
| `/parse` 输出 `[PARSE-FAIL] LLM 输出连续 3 次不符合 schema` | 大概率 prompt 漂移或 schema 太严；用 `SchemaValidatorTest` 拿同一份 JSON 跑一遍定位 |
| Windows 控制台中文乱码 | 已在 `logback.xml` 配置 `<charset>UTF-8</charset>`；cmd 用户可加 `chcp 65001` |

更多排错见 [Day01 课程附录 B](<docs/lessons/Day01_项目骨架 + AS-Java Hello World.md>) 与 [Day03 故障排查表](<docs/lessons/Day03_需求解析 + Structured Output.md>)。

> 📌 `docs/lessons/` 下的课程文档命名规范为 `Day[两位数序号]_[文章标题].md`（例如 `Day03_需求解析 + Structured Output.md`）。详见 [CLAUDE.md §9.1](CLAUDE.md) / [AGENTS.md §3.1](AGENTS.md)。
