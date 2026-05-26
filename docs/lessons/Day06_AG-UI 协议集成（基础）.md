# Day 6 · AG-UI 协议集成（基础）

> 上级文档：[../learning.md](../learning.md)
> 配套笔记：[../agents/09-integration-mcp-a2a.md](../agents/09-integration-mcp-a2a.md) · [../agents/_links.md](../agents/_links.md)
> 前置：[Day 5 · 多轮对话 + Memory 与 Session + HITL](<Day05_多轮对话 + Memory 与 Session + HITL.md>) 已完成
> 官方文档：[https://java.agentscope.io/zh/task/agui.html](https://java.agentscope.io/zh/task/agui.html) · [https://docs.ag-ui.com/concepts/events](https://docs.ag-ui.com/concepts/events)

## 0. 一句话目标

**今天结束时**，你能：

1. `mvn spring-boot:run` 起 Spring Boot WebFlux，进程暴露 `POST http://localhost:8080/agui/run` SSE 端点
2. `curl -N` 命中端点能看到 `data: {"type":"RUN_STARTED"...}` 一路打到 `RUN_FINISHED` 的**完整 17 类标准事件流**
3. `frontend/` 目录下 `npm run dev` 起 Vue3，浏览器输入"做一个员工档案管理"，能看到 LLM 流式回复**逐字符**渲染在聊天框里

> ⚠️ Day 6 只把"Agent ↔ 前端"通道接通，**TodoManager 状态同步**和 **HITL** 留到 Day 7。今天哪怕 LLM 调了工具，前端也只看到 `TOOL_CALL_*` 事件，不会有右侧 Todo 看板。

## 1. 学习目标

- ✅ 理解 AG-UI 协议在整套架构里的位置：**前后端的唯一对外契约**
- ✅ 摸熟 17 个标准事件类型（Lifecycle 5 + TextMessage 3 + ToolCall 4 + State 3 + Special 2）
- ✅ 用 `agentscope-agui-spring-boot-starter` 一键暴露端点（理解它替我们做了什么）
- ✅ 用 `AguiAgentRegistryCustomizer` 或 `@AguiAgentId` 两种方式注册 Agent
- ✅ Vue3 + Vite + `@ag-ui/client` 起一个最小前端，订阅事件回调
- ✅ 处理 Spring WebFlux 跟 Vue3 dev server 的 CORS 跨域

## 2. 时间盒（建议 8 学时）

| 阶段 | 时长 | 主题 | 验收 |
|------|------|------|------|
| Phase 0 | 45 min | AG-UI 概念 + 17 事件预读 | 能口述 5 类事件每类的代表 EventType |
| Phase 1 | 60 min | Spring Boot WebFlux + starter 接入 | `mvn spring-boot:run` 起得来，没冲突 |
| Phase 2 | 60 min | 注册 Agent（两种方式选一） | `GET /agui/agents` 能列出 "analyst" |
| Phase 3 | 60 min | curl 验证 17 事件流 | 能用 `data: ` 行 grep 出完整生命周期 |
| Phase 4 | 60 min | Vue3 + Vite 脚手架 | `npm run dev` 起得来，5173 端口可见空页 |
| Phase 5 | 60 min | 前后端联调 + 打字机渲染 | 浏览器输入文本能看到流式回复逐字符显示 |
| Phase 6 | 30 min | 收尾 commit + GIF 录屏 | `day6: ...` commit、文档导航更新 |

---

## 3. Phase 0 · AG-UI 概念预读（45 min）

### 3.1 为什么需要 AG-UI 协议？

到 Day 5 为止，我们的 Agent 通过 CLI 跟用户交互，所有"显示什么 / 等什么 / 怎么打断"逻辑都写在 `ScopeApp.java` 里。Day 6 接前端后这套**散文化**的协议根本没法跟前端约定 — 谁都不知道下一个字段叫什么。

AG-UI（**Agent-User Interaction Protocol**）就是把这层契约**标准化**：

- 后端：所有事件都是 17 种 `EventType` 之一，结构固定
- 前端：用 `@ag-ui/client` 一个 SDK 通吃所有支持 AG-UI 的后端
- 切换 Agent 框架（Pydantic-AI / LangGraph / AgentScope / CopilotKit）前端零改动

这跟 **MCP 之于工具调用** 是同一类东西：把"两端各自实现一套自定义协议"换成"两端实现同一个标准协议"。

### 3.2 17 个事件的五类速查

| 类别 | EventType | 一句话 |
|------|-----------|--------|
| **Lifecycle** | RUN_STARTED | 一次 run 开始，含 `threadId/runId` |
| (5 个) | RUN_FINISHED | 成功收尾 |
|  | RUN_ERROR | 失败收尾，含 `message` |
|  | STEP_STARTED | 子步骤开始（多步 Agent 用） |
|  | STEP_FINISHED | 子步骤结束 |
| **TextMessage** | TEXT_MESSAGE_START | 一条消息开头，含 `messageId/role` |
| (3 个) | TEXT_MESSAGE_CONTENT | 文本片段 `delta`（按 token 流） |
|  | TEXT_MESSAGE_END | 这条消息流完 |
| **ToolCall** | TOOL_CALL_START | 工具调用开始，含 `toolCallId/toolName` |
| (4 个) | TOOL_CALL_ARGS | 参数 JSON 流式片段 |
|  | TOOL_CALL_END | 工具调用结束 |
|  | TOOL_CALL_RESULT | 工具返回值 |
| **State** | STATE_SNAPSHOT | 状态全量快照（首次同步） |
| (3 个) | STATE_DELTA | 增量补丁（RFC 6902 JSON Patch） |
|  | MESSAGES_SNAPSHOT | 历史消息全量快照 |
| **Special** | RAW | 透传外部系统的原始事件 |
| (2 个) | CUSTOM | 应用自定义事件 |

> 📌 **今天 Day 6 只关心前 12 个**（Lifecycle + TextMessage + ToolCall）。State 类要等 Day 7 接 TodoManager。

### 3.3 一次"做个员工档案管理"的事件流（提前感受）

```
data: {"type":"RUN_STARTED","threadId":"t1","runId":"r1"}
data: {"type":"TEXT_MESSAGE_START","messageId":"m1","role":"assistant"}
data: {"type":"TEXT_MESSAGE_CONTENT","messageId":"m1","delta":"我"}
data: {"type":"TEXT_MESSAGE_CONTENT","messageId":"m1","delta":"先"}
data: {"type":"TEXT_MESSAGE_CONTENT","messageId":"m1","delta":"登"}
... (LLM 流式思考 / 自然语言部分)
data: {"type":"TEXT_MESSAGE_END","messageId":"m1"}
data: {"type":"TOOL_CALL_START","toolCallId":"tc1","toolName":"create_app"}
data: {"type":"TOOL_CALL_ARGS","toolCallId":"tc1","delta":"{\"name\""}
data: {"type":"TOOL_CALL_ARGS","toolCallId":"tc1","delta":":\"employeeMgr\""}
data: {"type":"TOOL_CALL_ARGS","toolCallId":"tc1","delta":",\"label\":\"员工档案管理\",\"type\":\"23\"}"}
data: {"type":"TOOL_CALL_END","toolCallId":"tc1"}
data: {"type":"TOOL_CALL_RESULT","toolCallId":"tc1","content":"APP 待办已登记：id=todo-1 label=员工档案管理"}
... (重复 create_module / create_model)
data: {"type":"TEXT_MESSAGE_START","messageId":"m2","role":"assistant"}
data: {"type":"TEXT_MESSAGE_CONTENT","messageId":"m2","delta":"已完成"}
... (LLM 的最终总结)
data: {"type":"TEXT_MESSAGE_END","messageId":"m2"}
data: {"type":"RUN_FINISHED","threadId":"t1","runId":"r1"}
```

每一行都是一个 SSE 帧。前端拿到帧后按 `type` 路由到不同回调。

### 3.4 starter 帮我们做了什么？

`agentscope-agui-spring-boot-starter` 替你做：

- 注册 `POST /agui/run` 端点（WebFlux Reactive）
- 把 AS-Java 的 `Msg` / `ToolUseBlock` / `ToolResultBlock` 转成 AG-UI EventType
- 处理 SSE 帧编码（`data: <json>\n\n`）
- 管理 `threadId` ↔ Memory 的关联（每个 thread 一个独立的 Agent 实例）
- 处理 `RunAgentInput` 反序列化

你只需要：**告诉 starter 怎么构造 Agent**。这就是 Phase 2 的活儿。

### 3.5 预读链接

- AG-UI 协议官方文档：https://java.agentscope.io/zh/task/agui.html
- AG-UI 事件规范（17 种）：https://docs.ag-ui.com/concepts/events
- `@ag-ui/client` npm：https://www.npmjs.com/package/@ag-ui/client
- AG-UI 官方示例（hitl-chat）：https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-examples/agui

### ✅ Phase 0 验收

- [ ] 能口述 5 类事件每类的代表 EventType
- [ ] 能说出"一次 run 至少会发哪些 Lifecycle 事件"（RUN_STARTED + RUN_FINISHED）
- [ ] 知道 starter 替你做了 SSE 编码和 EventType 转换

---

## 4. Phase 1 · Spring Boot WebFlux + starter 接入（60 min）

### 4.1 `pom.xml` 改造

#### 4.1.1 加 Spring Boot parent

如果原 `pom.xml` 没用 `spring-boot-starter-parent`，建议加一段 dependencyManagement 而不是 parent，避免影响现有依赖：

```xml
<properties>
    <spring-boot.version>3.2.5</spring-boot.version>
    <!-- AG-UI starter 跟主包 agentscope 同步发布；这里直接复用 Day 1 起就有的 ${agentscope.version}（1.0.12） -->
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-dependencies</artifactId>
            <version>${spring-boot.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

> ⚠️ **版本对齐 (重要)**：项目 Day 1 起 `agentscope` 锁在 1.0.12，AG-UI starter 要用**同一个版本**。引入后跑一次 `mvn dependency:tree | grep agentscope` 确认 `agentscope-core` 没有被 starter 反向降级（如果发布滞后只有 1.0.9，要么等 starter 跟上、要么把主包降到 1.0.9 一起回退——但回退会让 Day 5 已落地的 `FileSession`/`runSubmit` 代码出问题，**不推荐**）。

#### 4.1.2 加运行时依赖

```xml
<!-- Spring Boot WebFlux + Reactor -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>

<!-- AS-Java AG-UI Starter（自动 import agentscope 核心） -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-agui-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

> 📌 不要再保留 `logback-classic 1.5.6` 的显式 `<version>` 字段——交给 `spring-boot-dependencies` 接管（详见 4.1.4）。

> ⚠️ **不要同时加 `spring-boot-starter-web`**，它是 servlet（Tomcat），跟 WebFlux 冲突。

#### 4.1.3 加 Spring Boot 插件

```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <version>${spring-boot.version}</version>
</plugin>
```

#### 4.1.4 处理日志冲突

Day 1 我们在 `pom.xml` 显式写了 `logback-classic 1.5.6`。引入 Spring Boot 3.2.5 后，`spring-boot-dependencies` BOM 里管的是 **logback-classic 1.4.14**——也就是说，删掉显式 `<version>` 让 BOM 接管会**降级一档**。

- 1.4.x → 1.5.x 主要差异是日志条件评估和异步 appender，**Day 1 那套 UTF-8 编码器写法两版完全兼容**，可以放心降级
- 如果你想保留 1.5.6，把那段依赖留着 `<version>1.5.6</version>` 即可，BOM 不会强制；只是 SLF4J 桥接 jar 由 BOM 管，不要再显式写

完整改法：把 `<dependency><artifactId>logback-classic</artifactId>` 那一段的 `<version>1.5.6</version>` 整行删除。引入后跑一次 `mvn dependency:tree | grep logback` 确认实际版本。

#### 4.1.5 完整的 pom 末态（关键差异）

为避免学生把片段贴错位置，这里列 Day 6 改完后 pom 与 Day 5 相比的**净增项**：

| 节点 | 内容 |
|------|------|
| `<properties>` 增 | `<spring-boot.version>3.2.5</spring-boot.version>` |
| `<dependencyManagement><dependencies>` **新增** | `spring-boot-dependencies` `pom`/`import` |
| `<dependencies>` 增 | `spring-boot-starter-webflux`、`agentscope-agui-spring-boot-starter` |
| `<dependencies>` 改 | `logback-classic` 删掉 `<version>` 字段 |
| `<build><plugins>` 增 | `spring-boot-maven-plugin` |

如果哪一节定位不到，停下来对照本仓 `pom.xml` 的层级，**不要**把 `<plugin>` 放到 `<dependencies>` 里去。

### 4.2 调整入口

**前置认知**：到 Day 5 为止，`ScopeApp.java` 已经是一个跑了 250 行的 CLI REPL，含 `/parse` `/run` `/submit` `/todos` 四个子命令、`FileSession` 持久化、HITL 挂起回填。**这套代码不能丢**——Day 6 切 Spring Boot 后用 `ScopeReplApp` 这个备份类整体收纳，方便回退调试。

#### 4.2.1 拆分步骤

1. **复制** 当前 `src/main/java/space/wlshow/scope/ScopeApp.java` → 同目录新建 `ScopeReplApp.java`
2. 改新文件首行 `public class ScopeApp` → `public class ScopeReplApp`，其它**一字不动**
3. **重写**原 `ScopeApp.java` 为 Spring Boot 引导：

```java
package space.wlshow.scope;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ScopeApp {
    public static void main(String[] args) {
        SpringApplication.run(ScopeApp.class, args);
    }
}
```

> 📌 不要保留原 `ScopeApp` 里的 `parserAgent / session / analystWithTools` 等静态字段——Spring Boot 引导阶段静态字段会**抢在**配置加载之前初始化，可能踩到 `application.yml` 还没注入的坑。Day 5 的这些会话状态从 Day 6 起改由 `AguiAgentConfig` 在每个 `threadId` 上下文里按需构建（详见 Phase 2）。

#### 4.2.2 需要 CLI 调试时怎么办

```bash
# 跑 Spring Boot 引导（Day 6 起的默认路径）
mvn -q spring-boot:run

# 临时回到 Day 5 的 CLI（不启 8080 端口）
mvn -q compile exec:java -Dexec.mainClass=space.wlshow.scope.ScopeReplApp
```

`pom.xml` 里的 `<exec.mainClass>` 默认仍指向 `ScopeApp`，所以 Spring Boot 是新默认；CLI 走 `-Dexec.mainClass` 覆盖即可。

#### 4.2.3 验证

```bash
mvn -q compile
```

不应有任何编译错误。`ScopeReplApp` 的 `main` 仍然能编（虽然不会被默认入口调用），Spring Boot 引导类也能编。

### 4.3 `src/main/resources/application.yml`

```yaml
server:
  port: 8080

# AG-UI starter 配置（具体 key 看 starter 文档；以下是常见项）
agentscope:
  agui:
    base-path: /agui          # 暴露根：/agui/run / /agui/agents
    default-agent: analyst     # 客户端不传 agentId 时使用

logging:
  level:
    space.wlshow.scope: DEBUG
    io.agentscope: INFO
```

> ⚠️ **关于 CORS**：本课走 5.4 节的 `CorsWebFilter` Bean（编程式）这一条路，所以**不**在这里写 `agentscope.agui.cors.*`。两边同时写一般会让响应头里出现两份 `Access-Control-Allow-Origin`，部分 Chrome 版本直接拒。如果你想完全靠 yml，删掉 5.4 节的 Bean；二选一。
>
> ⚠️ `spring.webflux.base-path` 默认就是 `/`，没必要写。具体的 `agentscope.agui.*` 配置 key **以你拉到的 starter 版本** 实际为准。看 starter 的 `META-INF/spring-configuration-metadata.json` 或者源码的 `@ConfigurationProperties` 类。

### 4.4 沿用 Day 1 起的 `application.conf`

Spring Boot 默认不读 Typesafe Config。两个选择：

- **A**：把 `model { ... agent { ... }` 翻译进 `application.yml`，删 `application.conf`
- **B**：保留 `AppConfig.loadLayered()`，让 Spring Bean 调它（影响小，本课走这条）

不动 `AppConfig`，让它继续负责 model / agent 配置。`application.yml` 只管 Spring/AG-UI/日志。`application-local.conf` 也照样生效（仍然 gitignored，仍然按 `-D > local > 主` 三层叠加）。

### 4.5 启动验证

```bash
mvn -q spring-boot:run
```

期望日志：

```
... INFO  o.s.b.w.embedded.netty.NettyWebServer : Netty started on port 8080
... INFO  s.w.scope.ScopeApp : Started ScopeApp in 2.3 seconds
```

`curl http://localhost:8080/actuator/health` 应返回 404（没装 actuator，正常）。`curl http://localhost:8080/agui/run` 应返回 400（缺 RunAgentInput body，正常）。

### ✅ Phase 1 验收

- [ ] `mvn spring-boot:run` 成功启动
- [ ] 8080 端口监听，进程不退出
- [ ] 日志没有 `BeanCurrentlyInCreationException` 或类似启动错误

---

## 5. Phase 2 · 注册 Agent（60 min）

### 5.1 两种方式对比

| 方式 | 优点 | 缺点 | 适合 |
|------|------|------|------|
| `AguiAgentRegistryCustomizer` | 编程式，能注入 Bean，能动态注册多个 Agent | 多写 1 个 Bean | 今天 Day 6（我们要注入 TodoManager） |
| `@AguiAgentId` | 声明式，1 个注解 | 难注入 ScopedBean，不易做"per-thread Memory" | 简单场景 |

**今天选 A**，因为我们要把 TodoManager 注入到工具里。

### 5.2 `config/AguiAgentConfig.java`

```java
package space.wlshow.scope.config;

import io.agentscope.agui.spring.AguiAgentRegistryCustomizer;
import io.agentscope.core.ReActAgent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.wlshow.scope.agent.AgentFactory;
import space.wlshow.scope.session.FileSession;

@Configuration
public class AguiAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AguiAgentConfig.class);

    /**
     * 模型注册只跑一次：放在 lambda 里会让每次 /agui/run 都触发
     * ModelRegistry "[primary] 被覆盖" 告警，刷屏。
     */
    @PostConstruct
    public void initModelsOnce() {
        AgentFactory.initModels();
        log.info("[AguiConfig] models initialized");
    }

    /**
     * 每个 threadId 一份独立的 FileSession (todos + memory)，直接复用
     * Day 5 已落地的持久化机制——重启后从 data/sessions/&lt;threadId&gt;.json 恢复，
     * 不会丢待办。
     *
     * lambda 入参 ctx 是 starter 提供的运行时上下文（含 threadId/runId/messages），
     * 具体类型让 IDE 帮你补全；下面只用 ctx.threadId()。
     */
    @Bean
    public AguiAgentRegistryCustomizer aguiAgentRegistryCustomizer() {
        return registry -> registry.registerFactory("analyst", ctx -> {
            String threadId = ctx.threadId();
            FileSession session = FileSession.loadOrNew(threadId);
            log.info("[AguiConfig] build agent for thread={}, todos={}",
                    threadId, session.todos.size());

            // 复用 Day 4/5 的同一段装配逻辑：Toolkit + analyst-with-tools.md system prompt
            ReActAgent agent = AgentFactory.buildAnalystWithTools(session.todos, session.memory);

            // shutdown 时把 session 落盘——Day 6 没接 STATE_DELTA，先用粗粒度兜底
            Runtime.getRuntime().addShutdownHook(new Thread(session::save, "agui-save-" + threadId));
            return agent;
        });
    }
}
```

> 📌 **不再需要 `ThreadContext` 临时缓存类**。`FileSession.loadOrNew(threadId)` 内部已经按 sessionId 缓存（详见 Day 5 课程），重复传同一个 threadId 拿回的是同一个实例，状态不会被覆盖。Day 6 这一步等于"把 Day 5 那套 session 机制原样接到 starter 的 thread 上"。

### 5.3 关于 session 持久化路径

`FileSession` 默认把数据落到 `data/sessions/<sessionId>.json`，threadId 由前端按 `'thread-' + Date.now()` 生成，所以每开一次浏览器就会新建一个 session 文件。如果你在 Phase 5 联调反复刷 5173，记得**定期清** `data/sessions/`（或者前端固定 `threadId` 做手工测试时复用同一个文件）。

> ⚠️ Day 6 没在 lambda 里做并发锁：同一个 threadId 同时跑两次 `/agui/run`（用户连点两下发送）时，`FileSession` 内的 `TodoManager` 会出现读-改-写竞态。Day 7 接 STATE_DELTA 时会一并加锁，本课先靠"前端 disable 发送按钮"绕开。

### 5.4 CORS 配置

`AguiAgentConfig` 加一个 Bean：

```java
@Bean
public org.springframework.web.cors.reactive.CorsWebFilter corsWebFilter() {
    var cfg = new org.springframework.web.cors.CorsConfiguration();
    cfg.addAllowedOrigin("http://localhost:5173");
    // Spring 6 / Servlet 6 起，allowCredentials=true 与 addAllowedMethod("*") 同时存在时
    // 部分 Chrome 版本会拒，必须显式列方法
    cfg.addAllowedMethod("GET");
    cfg.addAllowedMethod("POST");
    cfg.addAllowedMethod("OPTIONS");
    cfg.addAllowedHeader("*");
    cfg.setAllowCredentials(true);

    var source = new org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/agui/**", cfg);
    return new org.springframework.web.cors.reactive.CorsWebFilter(source);
}
```

> ⚠️ 别同时在 `application.yml` 里写 `agentscope.agui.cors.*`——Phase 2 一旦同时启用这两套，会出现"`Access-Control-Allow-Origin` 头被设两遍"的怪现象，Chrome 直接报 CORS error。本课只保留这一份 Bean。

### ✅ Phase 2 验收

- [ ] 重启 `mvn spring-boot:run`，日志无 ERROR
- [ ] 看到 starter 日志 "registered agent: analyst" 或类似
- [ ] `curl http://localhost:8080/agui/agents`（如果 starter 提供）能返回 `analyst`

---

## 6. Phase 3 · curl 验证 17 事件流（60 min）

> 🪟 **Windows 前置**：本节所有命令假定在 **Git Bash** 里跑（不是 cmd / PowerShell）。`curl` 跟着 Git for Windows 自带；`jq` 需要单独装：`scoop install jq` 或 `choco install jq`。如果你跑在 WSL / macOS / Linux 上，原生支持，跳过这一段。

### 6.1 最小 RunAgentInput

```bash
curl -N -X POST http://localhost:8080/agui/run \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "threadId": "t-demo-1",
    "runId": "r-demo-1",
    "messages": [
      { "id": "m1", "role": "user", "content": "你好，介绍一下你自己" }
    ]
  }' 2>&1 | head -100
```

期望看到：

```
data: {"type":"RUN_STARTED","threadId":"t-demo-1","runId":"r-demo-1"}

data: {"type":"TEXT_MESSAGE_START","messageId":"m_xxx","role":"assistant"}

data: {"type":"TEXT_MESSAGE_CONTENT","messageId":"m_xxx","delta":"我"}

data: {"type":"TEXT_MESSAGE_CONTENT","messageId":"m_xxx","delta":"是"}

...

data: {"type":"TEXT_MESSAGE_END","messageId":"m_xxx"}

data: {"type":"RUN_FINISHED","threadId":"t-demo-1","runId":"r-demo-1"}
```

`-N` 是关闭 curl 的输出缓冲，必须加，否则你看到的全是 SSE 一次性落地。

### 6.2 触发工具调用

```bash
curl -N -X POST http://localhost:8080/agui/run \
  -H "Content-Type: application/json" \
  -d '{
    "threadId": "t-demo-2",
    "runId": "r-demo-2",
    "messages": [
      { "id": "m1", "role": "user",
        "content": "做一个简单的员工档案管理，含姓名工号入职日期" }
    ]
  }' | tee logs/agui-trace.log
```

完事后用 jq 数一下每类事件：

```bash
grep '^data:' logs/agui-trace.log | sed 's/^data: //' | jq -r '.type' | sort | uniq -c
```

期望（**类型齐不齐**是验收标准，数字仅供参考；模型不同次回复轮次会有偏差）：

```
   1 RUN_STARTED                       # 必有 1
   1 RUN_FINISHED                      # 必有 1（或 RUN_ERROR）
  ≥1 TEXT_MESSAGE_START / END 成对     # LLM 多段思考时会出现多对
 ≥10 TEXT_MESSAGE_CONTENT              # 每个 token 一帧
  ≥1 TOOL_CALL_START / END / RESULT    # 三件套必须一一对应
  ≥1 TOOL_CALL_ARGS                    # 参数 JSON 流式片段
```

> 📌 数字不重要，**类型齐不齐** 重要。如果完全没 TOOL_CALL_*，说明模型本身没真的调工具（看 LLM 是否被配置成了 stream + function calling 兼容形态；火山方舟需要 `stream(true)` 才会推 tool_call 增量）。`logs/` 目录已经在 `.gitignore` 里，trace 文件不会污染 git。

### 6.3 把事件流可视化（可选）

写一个简短脚本：

```bash
#!/usr/bin/env bash
# scripts/agui-tail.sh
curl -sN -X POST "$1" \
  -H "Content-Type: application/json" -d "$2" \
  | grep '^data:' | sed 's/^data: //' \
  | jq -r '"\(.type)\t\(.delta // .toolName // .content // .messageId // "")"'
```

跑（先把 `fixtures/demo-input.json` 准备好——内容用 6.1 节那段 RunAgentInput 的 body 即可）：

```bash
mkdir -p fixtures
cat > fixtures/demo-input.json <<'JSON'
{
  "threadId": "t-demo-3",
  "runId": "r-demo-3",
  "messages": [
    { "id": "m1", "role": "user", "content": "做一个员工档案管理，含姓名工号入职日期" }
  ]
}
JSON

./scripts/agui-tail.sh http://localhost:8080/agui/run "$(cat fixtures/demo-input.json)"
```

输出类似：

```
RUN_STARTED       t-demo-2
TEXT_MESSAGE_START m_001
TEXT_MESSAGE_CONTENT 好
TEXT_MESSAGE_CONTENT 的
TEXT_MESSAGE_CONTENT ，
TEXT_MESSAGE_END  m_001
TOOL_CALL_START   create_app
TOOL_CALL_ARGS    {"name"
TOOL_CALL_ARGS    :"employee
TOOL_CALL_ARGS    Mgr"
TOOL_CALL_END
TOOL_CALL_RESULT  APP 待办已登记：id=todo-1 ...
...
RUN_FINISHED      t-demo-2
```

### ✅ Phase 3 验收

- [ ] 至少 1 次 curl 完整看到 RUN_STARTED → RUN_FINISHED
- [ ] 至少 1 次看到 TOOL_CALL_* 三件套
- [ ] 把上面 grep 的事件分布截图存到 `docs/screenshots/day6-curl-trace.png`

---

## 7. Phase 4 · Vue3 + Vite 脚手架（60 min）

### 7.1 目录就位

```bash
# 在项目根
npm create vite@latest frontend -- --template vue-ts
cd frontend
npm install
npm install @ag-ui/client
```

### 7.2 `frontend/package.json` 关键字段

```json
{
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc -b && vite build"
  },
  "dependencies": {
    "vue": "^3.4.0",
    "@ag-ui/client": "latest"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.0.0",
    "typescript": "~5.4.0",
    "vite": "^5.0.0",
    "vue-tsc": "^2.0.0"
  }
}
```

> 📌 `@ag-ui/client` 在 0.x 期 API **未冻结**，包名版本号每周可能变。本课为简便用 `"latest"`，`npm install` 会把当前最新版写进 `package-lock.json`——**这个 lock 必须 commit**，否则下次同事 install 拉到的版本可能跟你不一样。生产项目里则要换成具体版本号（先 `npm view @ag-ui/client version` 看一眼）。
>
> 📌 下面 7.5 节示例里用的事件回调名（`onTextMessageStartEvent` / `onTextMessageContentEvent` 等）对齐当下 `@ag-ui/client` 0.x。若安装后发现 IDE 提示这些方法不存在，先 `cat node_modules/@ag-ui/client/README.md` 或看 dist 的 `.d.ts`，按实际 API 调整——HttpAgent 在 0.x 期可能改成 EventEmitter 风格 `agent.on('TextMessageContent', cb)`。

### 7.3 加根目录 `frontend/.gitignore`

```
node_modules
dist
.DS_Store
.vite
```

### 7.4 项目根 `.gitignore` 增加

```
frontend/node_modules
frontend/dist
```

### 7.5 写最小 `frontend/src/App.vue`

```vue
<script setup lang="ts">
import { ref, computed } from 'vue'
import { HttpAgent } from '@ag-ui/client'

const threadId = 'thread-' + Date.now()
const agent = new HttpAgent({
  url: 'http://localhost:8080/agui/run',
  threadId,
})

interface UiMsg { id: string; role: 'user' | 'assistant'; text: string }
const messages = ref<UiMsg[]>([])
const input = ref('')
const running = ref(false)

// 当前正在流式的 assistant 消息（用 messageId 索引）
const streamingId = ref<string | null>(null)

agent.subscribe({
  onTextMessageStartEvent: (e) => {
    streamingId.value = e.messageId
    messages.value.push({ id: e.messageId, role: 'assistant', text: '' })
  },
  onTextMessageContentEvent: (e) => {
    const msg = messages.value.find(m => m.id === e.messageId)
    if (msg) msg.text += e.delta
  },
  onTextMessageEndEvent: () => {
    streamingId.value = null
  },
  onToolCallStartEvent: (e) => {
    console.log('[ToolCall START]', e.toolName, e.toolCallId)
  },
  onToolCallEndEvent: (e) => {
    console.log('[ToolCall END]', e.toolCallId)
  },
  onRunFinishedEvent: () => {
    running.value = false
  },
  onRunErrorEvent: (e) => {
    running.value = false
    console.error('[RUN_ERROR]', e)
    messages.value.push({ id: 'err-' + Date.now(), role: 'assistant',
                          text: '[ERROR] ' + (e.message || '未知错误') })
  },
})

async function send() {
  const text = input.value.trim()
  if (!text || running.value) return
  const userMsg: UiMsg = { id: 'u-' + Date.now(), role: 'user', text }
  messages.value.push(userMsg)
  agent.addMessage({ id: userMsg.id, role: 'user', content: text })
  input.value = ''
  running.value = true
  try {
    await agent.runAgent({ runId: 'run-' + Date.now() })
  } catch (e) {
    running.value = false
    console.error(e)
  }
}
</script>

<template>
  <div class="app">
    <header>
      <h1>Scope · Day 6 联调</h1>
      <span class="thread">thread: {{ threadId }}</span>
    </header>

    <div class="log">
      <div v-for="m in messages" :key="m.id" :class="['msg', m.role]">
        <b>{{ m.role === 'user' ? '我' : 'Agent' }}：</b>
        <span class="text">{{ m.text }}</span>
        <span v-if="streamingId === m.id" class="cursor">▍</span>
      </div>
    </div>

    <form @submit.prevent="send" class="input">
      <textarea v-model="input"
                placeholder="例如：做一个简单的员工档案管理"
                :disabled="running"
                rows="2" />
      <button :disabled="running || !input.trim()">{{ running ? '思考中...' : '发送' }}</button>
    </form>
  </div>
</template>

<style scoped>
.app { font-family: -apple-system, BlinkMacSystemFont, sans-serif; max-width: 720px; margin: 24px auto; padding: 16px; }
header { display: flex; justify-content: space-between; align-items: baseline; border-bottom: 1px solid #ddd; padding-bottom: 8px; }
.thread { color: #999; font-size: 12px; }
.log { min-height: 60vh; padding: 12px 0; }
.msg { padding: 6px 0; line-height: 1.5; white-space: pre-wrap; }
.msg.user { color: #333; }
.msg.assistant { color: #0066cc; }
.cursor { animation: blink 1s steps(2) infinite; }
@keyframes blink { 50% { opacity: 0; } }
.input { display: flex; gap: 8px; border-top: 1px solid #ddd; padding-top: 12px; }
textarea { flex: 1; font: inherit; padding: 8px; resize: vertical; }
button { padding: 0 16px; }
</style>
```

### 7.6 启动 Vue3

```bash
cd frontend
npm run dev
# Vite 默认 5173
```

浏览器开 `http://localhost:5173`，看到聊天界面（暂时还不能跟后端通信，下一 Phase 才接）。

### ✅ Phase 4 验收

- [ ] `npm run dev` 起得来
- [ ] 浏览器 5173 端口显示空聊天界面
- [ ] 浏览器 DevTools Console 没红色报错

---

## 8. Phase 5 · 前后端联调 + 打字机渲染（60 min）

### 8.1 三检 CORS

打开 5173 页面，开 DevTools Network。在输入框打"你好"回车，看到的请求应该是：

```
POST http://localhost:8080/agui/run
Request Headers:
  Origin: http://localhost:5173
  Content-Type: application/json
Response Headers:
  Access-Control-Allow-Origin: http://localhost:5173
  Content-Type: text/event-stream
```

如果你看到红色 CORS error：

- **没看到 Access-Control-Allow-Origin**：Phase 2 的 CorsWebFilter Bean 没生效；确认 Spring Boot 真的扫到了 `space.wlshow.scope.config` 包
- **OPTIONS 预检失败**：starter 自己注册了 `POST /agui/run` 但没注册 `OPTIONS`；在 CORS 配置加 `cfg.addAllowedMethod("OPTIONS")` 并重启

### 8.2 流式逐字符渲染验证

打"做一个员工档案管理"，应该看到：

1. 你的消息立即出现在右边（或上方）
2. Agent 回复**逐字符**蹦出（不是一整段砸下来）
3. 每个字符出现后右边出现一个闪烁光标 `▍`
4. LLM 调工具的瞬间 Console 看到 `[ToolCall START] create_app tc1`
5. 最终 LLM 总结流完后，光标消失

如果你看到 Agent 回复是**一整段砸下来**（没流式效果），按下面顺序排查：

1. **模型 stream 没开**（最常见）：检查 `application.conf` / `ModelRegistry` 里 `OpenAIChatModel.builder().stream(true)`；火山方舟在 `stream(false)` 时压根不发增量
2. **starter 没把 stream 透传**：看 starter 配置是否需要 `agentscope.agui.streaming.enabled: true`（具体 key 以 starter 版本为准）
3. **反向代理缓冲**（本课直连不涉及，但部署到生产时会踩）：nginx 默认 `proxy_buffering on` 会把 SSE 攒到 buffer 满才下发；需要 `proxy_buffering off` + `X-Accel-Buffering: no`
4. **DevTools Network 验证**：请求行右边类型应该是 `eventsource`，不是 `xhr`。如果是 xhr，说明前端 `@ag-ui/client` 走的不是 SSE 通道

### 8.3 录制 30 秒 GIF

跑一遍"做个员工档案"，用 Windows `Win+G` 录屏 30 秒，转 GIF 存 `docs/screenshots/day6-end-to-end.gif`。

### ✅ Phase 5 验收

- [ ] 浏览器输入到回复**完全不刷页**
- [ ] 流式打字机效果可见
- [ ] DevTools Console 看到 `TOOL_CALL_*` 三件套
- [ ] CORS 没报错

---

## 9. Phase 6 · 收尾（30 min）

### 9.1 commit

```bash
git add pom.xml \
        src/main/java/space/wlshow/scope/ScopeApp.java \
        src/main/java/space/wlshow/scope/ScopeReplApp.java \
        src/main/java/space/wlshow/scope/config/AguiAgentConfig.java \
        src/main/resources/application.yml \
        frontend/package.json frontend/package-lock.json \
        frontend/index.html frontend/vite.config.ts frontend/tsconfig.json \
        frontend/src/ frontend/.gitignore \
        scripts/agui-tail.sh \
        docs/screenshots/day6-*

# 注意：frontend/node_modules/ 不入库（已在 .gitignore），但 package-lock.json 必须入库锁版本

git commit -m "day6: AG-UI 协议集成（基础）

- 引入 spring-boot-starter-webflux + agentscope-agui-spring-boot-starter（跟 ${agentscope.version}）
- ScopeApp 转 Spring Boot 主类，Day 5 的 CLI REPL 整体搬到 ScopeReplApp 备份
- AguiAgentConfig: @PostConstruct 跑一次 initModels；registerFactory 按 threadId 复用 FileSession
- CorsWebFilter 显式列 GET/POST/OPTIONS，放开 Vue3 dev origin 5173
- frontend/ Vite + Vue3 + @ag-ui/client，订阅 TextMessage / ToolCall 事件流式渲染
- scripts/agui-tail.sh + 截图存档"
```

### 9.2 README 加启动说明

`README.md` 加一段：

```markdown
## 启动（Day 6 起）

```bash
# 后端
mvn spring-boot:run

# 前端（另一终端）
cd frontend
npm install   # 首次
npm run dev
# 浏览器开 http://localhost:5173
```
```

### 9.3 加 Day 6 链接

`README.md` 文档导航和 `CLAUDE.md` 第 9 节表格加 Day 6。

### 9.4 5 分钟回顾

- 为什么用 starter 而不是自己写 SSE Controller？答：starter 替我们处理了 EventType 转换、SSE 帧编码、threadId/Memory 关联，避免错位
- 为什么 `frontend/` 不放进 src 主源？答：是独立的 npm 工程，Vite 工具链跟 Maven 共存麻烦
- 17 个事件里今天用了几个？答：5 个（RUN_STARTED, RUN_FINISHED, TEXT_MESSAGE_START/CONTENT/END）+ 4 个 TOOL_CALL_* 旁观

### ✅ Phase 6 验收

- [ ] `mvn spring-boot:run` + `npm run dev` 双进程跑通
- [ ] commit + 文档更新
- [ ] GIF 录屏入库

---

## 10. 故障排查表

| 现象 | 原因 / 排查 |
|------|------------|
| `mvn spring-boot:run` 报 `BeanCurrentlyInCreationException` | 通常是 `AguiAgentRegistryCustomizer` 注入了循环依赖；把 lambda 内部用到的 bean 改成方法注入或者 `ObjectProvider` |
| `Address already in use: bind` / 8080 起不来 | Windows 上 IIS 或其他 dev 进程占着；`netstat -ano | findstr 8080` 看 PID，`taskkill /PID <pid> /F` 或改 `server.port: 8081` |
| 起得来但 `/agui/run` 404 | starter 没扫到；确认 `@SpringBootApplication` 的包能扫到 `io.agentscope.agui.spring`；启动日志 `Mapped` 行里搜 `agui` |
| 起得来但 `/agui/run` POST 400 | RunAgentInput 反序列化失败；body 必须有 `threadId`/`runId`/`messages`；用本课 6.1 的 curl 模板 |
| SSE 一次性全砸下来 | `curl` 没加 `-N`；浏览器看 Network 应该是逐帧的 |
| `TOOL_CALL_*` 没事件 | 模型没真调工具：1) prompt 不引导 2) 模型版本不支持 function calling 3) stream 没开 |
| Vue3 报 CORS | CorsWebFilter Bean 没注册；或路径模式不对；改成 `/**` 测试。**特别注意**：若 application.yml 也写了 `agentscope.agui.cors`，删掉 yml 那份只留 Bean |
| 启动日志反复刷 `[primary] 被覆盖` | `AgentFactory.initModels()` 被放进了 lambda；按 5.2 节移到 `@PostConstruct` |
| `npm install @ag-ui/client` 装不上 | Node 版本 < 18；用 `nvm use 20` |
| Vue 端 `Cannot find module '@ag-ui/client'` | `tsconfig.json` 的 `moduleResolution` 用 `bundler` 或 `node16` |
| Vue 端编译过了但 `agent.subscribe is not a function` | `@ag-ui/client` 版本 API 不一致；看 `node_modules/@ag-ui/client/dist/*.d.ts` 找真实方法签名 |
| `Access-Control-Allow-Origin` 头出现两次 | application.yml 和 5.4 Bean 同时配了 CORS；二选一 |
| 一连发 5 条消息后浏览器卡顿 | messages 数组无限增长；可以加上限或虚拟滚动，Day 7 优化 |
| 反复刷新 5173 后 `data/sessions/` 越长越大 | 每次新 threadId 写一份 session 文件；手工测试时固定 `threadId = 'thread-dev'` 复用同一份 |

---

## 11. 附录 A · `@AguiAgentId` 替代方案

如果你的场景不需要 per-thread 隔离（只跑一个全局 Agent），更省事的方式：

```java
@Configuration
public class AguiAgentConfig {

    // Memory / TodoManager 不是 Spring 自动 Bean，先用 @Bean 声明出来才能注入下面的 analyst
    @Bean
    public TodoManager globalTodos() {
        return new TodoManager();
    }

    @Bean
    public Memory globalMemory() {
        return new InMemoryMemory();
    }

    @Bean
    @AguiAgentId("analyst")
    public Agent analyst(TodoManager globalTodos, Memory globalMemory) {
        AgentFactory.initModels();
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder().parallel(true).build());
        toolkit.registerTool(new FrontendCreateTools(globalTodos));
        // ... 其余工具同 Day 4 buildAnalystWithTools
        return ReActAgent.builder()
                .name("RequirementAnalyst")
                .sysPrompt(Prompts.analystMultiRound())
                .model(ModelRegistry.resolve(AgentFactory.DEFAULT_MODEL_ID))
                .toolkit(toolkit)
                .memory(globalMemory)
                .build();
    }
}
```

注意这种写法的两个坑：

1. **所有用户共用一个 Memory / TodoManager**：一个浏览器开两个标签会互相串台。演示足够，真做项目不能这么搞——不同用户的待办池必须隔离，否则张三创建的"员工管理"会出现在李四的看板里
2. **不复用 Day 5 的 `FileSession`**：重启进程后 Memory / Todos 全清空。要复用就回到 5.2 节的 `registerFactory` 写法

---

## 12. 附录 B · WebFlux vs WebMvc

AG-UI starter 也支持 servlet 模式（spring-boot-starter-web + Tomcat），但 SSE 在 WebMvc 上是阻塞模型，并发上去性能差。**首选 WebFlux**。

如果你的项目原本就是 WebMvc，要么：

- 整体迁 WebFlux（影响小，因为我们只暴露一个 endpoint）
- 接受 SSE 阻塞代价：每个 thread 占用一个 servlet 线程

---

## 13. 写在 Day 7 之前

Day 7 我们会把 Day 6 的"看得见聊天但看不见状态"补齐：

- 在 TodoManager 上挂 `TodoChangeListener`，把变更通过 `STATE_SNAPSHOT`（首次）+ `STATE_DELTA`（增量 JSON Patch）推到前端
- Vue3 加右侧 Todo 看板，实时显示状态切换
- Day 5 的 `ToolSuspend HITL` 升级为 **AG-UI HITL**：前端弹窗确认 → 用 `role=tool` 消息回填到下一次 `runAgent`
- 全链路 JSON 日志（logstash + MDC `traceId`）
- 8 个异常剧本回归
- 14 条需求逐项打勾，演示视频，README + 架构图

明天结束后这个项目就是一个**可演示、能交付**的小型 Agent 应用了。
