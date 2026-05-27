# Day 7 · AG-UI 协议进阶 + 收尾验收

> 上级文档：[../learning.md](../learning.md)
> 配套笔记：[../agents/10-observability-hitl.md](../agents/10-observability-hitl.md) · [../agents/07-harness.md](../agents/07-harness.md)
> 前置：[Day 6 · AG-UI 协议集成（基础）](<Day06_AG-UI 协议集成（基础）.md>) 已完成
> 官方文档：[AG-UI State Events](https://docs.ag-ui.com/concepts/events#state-management-events) · [agentscope-examples/agui](https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-examples/agui)

> 📌 **开始 Day 7 前先过一遍 Day 6 §13 附录 C "版本与 API 兼容性速查"**：starter / `@ag-ui/client` / AS-Java Hook event 的字段名 5 分钟全部锁定，本课正文不再 hedge。

## 0. 一句话目标

**今天结束时**，你的项目是一个**可演示、可交付**的小型 Agent 应用：

- Vue3 UI **左侧**聊天框（Day 6 已经能）+ **右侧** Todo 看板，TodoManager 任何变化都通过 `STATE_DELTA` 实时镜像，**不刷新页面**
- 用户在浏览器点"确认下发"按钮 → Agent 收到 `role=tool` 消息 → 真正调 `dispatchAll(...)` 完成 dry-run；点"取消"则待办保持 PENDING
- `logs/scope.json.log` 每条日志带 `traceId`，能用 `jq` 把一次会话从输入到下发全过程拎出来
- 8 个异常剧本（E1-E8）全部 WireMock 通过
- `README.md` 含架构图、3 分钟演示视频、14 条需求逐项打勾

> ⚠️ **关于 CLI**：Day 7 不再走 Day 5 的 CLI HITL 回填路径，但 `ScopeReplApp.java` **保留为备份入口**（沿用 Day 6 的决定）；`mvn ... -Dexec.mainClass=space.wlshow.scope.ScopeReplApp` 仍可启动做调试，只是不再是主路径。

> ⚠️ **关于 starter 限制（必读）**：AS-Java 1.0.12 的 `agentscope-agui-spring-boot-starter` 有两处**没有可注入点**，本课全部用工程化方案绕开，**不假装存在**：
>
> 1. **`AguiAgentRegistryCustomizer.registerFactory(String, Supplier<Agent>)`** 是无参 Supplier，**拿不到 threadId / runId / emitter**。Day 6 已经走"覆盖 `ThreadSessionManager` 子类"路线；Day 7 沿用。
> 2. **`AguiAgentAdapter.run()`** 返回 `Flux<AguiEvent> = RUN_STARTED ∘ agent.stream().convertEvent() ∘ finishRun`，**没有外部 emitter 注入点**。Hook 只能改 agent 内部行为，**不能直接 emit `AguiEvent.StateDelta` 进 SSE 流**。
>
> 因此 Day 7 的 `STATE_*` 走**旁路 SSE 端点**（§4.2 详述），不与 starter 主 `/agui/run` 流合并。这条决策跟 Day 6 §13 附录 C "starter API 真相"完全一致。

## 1. 学习目标

- ✅ 通过 `TodoChangeListener` + `Sinks.Many<AguiEvent>` 把 TodoManager 实时镜像到前端（**旁路 SSE 端点**，绕开 starter 1.0.12 无 emitter 注入点的限制）
- ✅ 用 `STATE_SNAPSHOT`（连接时一次）+ `STATE_DELTA`（JSON Patch 增量、`id=<id>` 风格 path）的状态镜像模式
- ✅ HITL 走 AG-UI 标准事件：`TOOL_CALL_RESULT` content 以 `AWAITING_USER_CONFIRMATION` 开头 → 前端弹窗 → `messages: [..., {role:"tool", toolCallId, content}]` 回填
- ✅ **Dispatcher 升级**：从 `DryRunDispatcher` no-op 升级到三档实现（DryRun / Http / WireMock 后端），让需求 #4 真发 HTTP
- ✅ Logback JSON + MDC `traceId` 通过 Reactor `contextWrite` 透传
- ✅ 跑通 8 个异常剧本回归（E4 失败剧本通过 WireMock 后端的 504 stub 触发，比 Mockito 接口 mock 真实得多）
- ✅ 完成需求逐项打勾、架构图、演示视频

## 2. 时间盒（建议 9 学时，分上午 3.5h + 下午 5.5h）

| 阶段 | 时长 | 主题 | 验收 |
|------|------|------|------|
| Phase 0 | 30 min | 资料预读 + 设计选型 | 能说出 STATE_SNAPSHOT/STATE_DELTA 时机差异 + 知道两条 SSE 流的分工 |
| Phase 1 | **120 min** | TodoManager → STATE 事件（旁路 SSE） | 前端右侧看板实时切换状态，DevTools 能看到 `/agui/state-stream/...` 连接 |
| Phase 2 | 75 min | HITL on AG-UI | 浏览器点击 y/n 完成 Day 5 的剧本 |
| **Phase 3a** | **60 min** | **日志骨架 + 7 个关键日志点** | jq 过 traceId 还原会话，7 个 stage 全部出现 |
| **Phase 3b** | **90 min** | **OpenTelemetry + Jaeger** | Jaeger UI 看到一条完整 trace（HTTP → Agent → Tool），traceId 三处全长对得上 |
| **Phase 3c** | **45 min** | **Hook 可观测 + Micrometer** | `/actuator/metrics` 能看到工具调用计数和 LLM 延迟分布 |
| Phase 4 | 60 min | E1-E8 异常剧本 + WireMock | `mvn test` 全绿，含集成测试 |
| Phase 5 | 30 min | 需求打勾 + 演示视频 | 14 项全勾，3 分钟视频入库 |
| Phase 6 | 30 min | README + 架构图 + commit | `day7: ...` commit，PR 描述完整 |

合计 ≈ 9.7 学时。

> 📌 **时间分配理由**：Phase 1 比原预期重——多了旁路 SSE 端点 + `Sinks.Many` 桥 + 前端 `EventSource` 订阅，整套通讯链路工程量翻倍；Phase 2 反而轻——把 ToolSuspend 转事件那段是 starter 内置行为，不用自己写；Phase 3b 多 15 min 是为了"Jaeger 起来 + MDC key 调试 + 三处对齐"留缓冲。
>
> 📌 **为什么 Day 7 比其他天长**：可观测（日志 / 追踪 / 指标）是生产化的三大支柱，任何一环缺失都让"出问题查不出来"。这一天我们把三件套**都打齐**而不是择一糊弄。如果时间紧，**Phase 3a 必做**，3b 和 3c 二选一，另一个标 TODO 留作课后。

---

## 3. Phase 0 · 资料预读（30 min）

### 3.1 STATE_SNAPSHOT vs STATE_DELTA 的时机

| 事件 | 何时发 | 内容 | 前端动作 |
|------|--------|------|---------|
| `STATE_SNAPSHOT` | 1. 连接建立 / threadId 切换<br>2. 长时间断连后重连<br>3. 服务端判断 delta 累积过多 | 整个状态对象 | **替换**本地 state |
| `STATE_DELTA` | 每次 TodoManager 状态变化 | RFC 6902 JSON Patch 数组 | **应用** patch 到本地 state |

我们的状态对象设计：

```json
{
  "todos": [
    { "id": "todo-1", "type": "CREATE_APP", "targetName": "...",
      "status": "PENDING", "payload": {...} }
  ],
  "sessionId": "default"
}
```

RFC 6902 JSON Patch 例子：

```json
[
  { "op": "add",     "path": "/todos/-",            "value": { "id": "todo-7", ... } },
  { "op": "replace", "path": "/todos/0/status",     "value": "RUNNING" },
  { "op": "remove",  "path": "/todos/3" }
]
```

> 📌 **add 用 `-` 作为索引** 表示追加到数组末尾。这是 RFC 6902 标准写法，不要写 `/todos/7`（会让 patch 跟数组真实长度耦合）。

### 3.2 HITL on AG-UI 的时序

```
浏览器                                   Agent 后端
  |                                       |
  |  // 两条 SSE 连接                     |
  |  EventSource("/agui/state-stream/t1") |
  |======SSE: STATE_SNAPSHOT=============|   连接即发当前状态（旁路流）
  |                                       |
  |  runAgent(messages=[user:"提交"])     |
  |---POST /agui/run-------------------->|
  |                                       |  LLM 决定调 submit_to_frontend(confirmed=false)
  |                                       |  工具内 throw ToolSuspendException
  |                                       |  ReActAgent 把它转成 suspended ToolResultBlock
  |                                       |  AguiAgentAdapter 发 3 个事件：
  |<--SSE: TOOL_CALL_START toolCallId=tc1|
  |<--SSE: TOOL_CALL_END toolCallId=tc1--|
  |<--SSE: TOOL_CALL_RESULT---------------|   content="AWAITING_USER_CONFIRMATION\n- todo-1 ..."
  |<--SSE: RUN_FINISHED-------------------|   ⚠️ 1.0.12 没有 suspended 标记字段
  |                                       |
  |  前端识别 content 前缀，渲染弹窗      |
  |                                       |
  |  用户点击「确认」                     |
  |  agent.messages.push({role:"tool",    |
  |                       toolCallId:tc1, |
  |                       content:"USER_CONFIRMED"})
  |---POST /agui/run------                |
  |  messages=[...上下文, tool result]    |
  |--------------------------------------->|
  |                                       |  Agent 接到 tool result 续跑
  |                                       |  LLM 调 submit_to_frontend(confirmed=true)
  |                                       |  工具真发，TodoManager 状态机迁移：
  |                                       |  PENDING→RUNNING→SUCCESS
  |                                       |  AguiStateBridge 写 Sinks.Many：
  |======SSE: STATE_DELTA × N============|   旁路流推到前端
  |<--SSE: TEXT_MESSAGE_* (LLM 总结)------|   主流推 LLM 文本
  |<--SSE: RUN_FINISHED-------------------|
```

**两条核心**：
1. **HITL 模型**：不是"Agent 暂停一辈子"，而是"Agent 结束一次 run，等下一次 run 带 tool result 续跑"。
2. **两条 SSE 流**：starter `/agui/run`（主流，文本 + 工具调用 + 控制事件） + 我们加的 `/agui/state-stream/{threadId}`（旁路流，`STATE_*`）。前者是 starter 自动注册，后者是 §4 落地的。前端用 `HttpAgent.subscribe()` 订前者，用 `new EventSource(...)` 订后者。

### 3.3 可观测三件套的分工（Phase 3a/3b/3c 的预热）

| 支柱 | 回答的问题 | 工具 | 数据形态 |
|------|-----------|------|---------|
| **日志（Log）** | "这一次会话里发生了什么？" | Logback JSON + MDC | 离散事件，按 traceId 聚合 |
| **追踪（Trace）** | "这次请求 200ms 都花在了哪？哪个 Span 慢？" | OpenTelemetry + Jaeger | 有父子关系的 Span 树 |
| **指标（Metric）** | "过去 1 小时 LLM 调用 p99 多少？工具失败率多少？" | Micrometer + Actuator | 时序聚合，按维度切片 |

**三者必须用同一个 traceId 串起来**，否则查问题时跨工具对不上。具体：

- Phase 3a：Logback MDC 写 traceId 到每条日志
- Phase 3b：OTel SDK 把同一个 traceId 写到 Span（用 OTel `Span.current().getSpanContext().getTraceId()` 反向同步到 MDC，**这是 Phase 3b 必踩的坑**）
- Phase 3c：Micrometer Tag 不带 traceId（高基数会爆指标存储），但**带工具名 / 状态码 / model 名**等低基数维度

### 3.4 预读链接

- AG-UI State 事件章节：https://docs.ag-ui.com/concepts/events#state-management-events
- RFC 6902 JSON Patch：https://datatracker.ietf.org/doc/html/rfc6902
- AS-Java HITL 实战：[../agents/10-observability-hitl.md](../agents/10-observability-hitl.md)
- Logstash Logback Encoder：https://github.com/logfellow/logstash-logback-encoder
- OpenTelemetry Java（zero-code 模式）：https://opentelemetry.io/docs/zero-code/java/
- OTel Spring Boot Starter：https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/
- Jaeger all-in-one Docker：https://www.jaegertracing.io/docs/latest/getting-started/
- Micrometer + Spring Boot Actuator：https://docs.spring.io/spring-boot/reference/actuator/metrics.html

### ✅ Phase 0 验收

- [ ] 能口述 STATE_SNAPSHOT / STATE_DELTA 各自时机
- [ ] 能在白板画 HITL on AG-UI 时序图
- [ ] 知道 `add` 用 `-` 表示追加
- [ ] 能说出"为什么 OTel traceId 必须同步到 MDC"

---

## 4. Phase 1 · TodoManager → STATE 事件（90 min）

### 4.1 设计：旁路 SSE 端点 + `AguiStateBridge`

#### 4.1.1 为什么走旁路

`AguiAgentAdapter.run()` 是闭门的 `Flux.concat(RUN_STARTED, agent.stream().convertEvent(), finishRun)`——**没有外部 emitter 注入点**。`TodoManager` 的变化是 `@Tool` 方法的**同步副作用**，从 `agent.stream()` 出来的只有 reasoning / tool_use / tool_result，不包含我们想发的 `StateDelta`。

1.0.12 范围内能选的路径：

| 方案 | 描述 | 评价 |
|------|------|------|
| A. 等 starter 升级 | 等 `AguiAgentRegistryCustomizer` 加上 `ctx.eventEmitter()` | 等不起 |
| B. 覆盖 starter `RouterFunction` | 把 starter 自动配置的 `/agui/run` 整条覆盖掉，自己拿 `AguiRequestProcessor.process` 的 `Flux<AguiEvent>` 再 `mergeWith` 一个 `Sinks.Many<AguiEvent>` | 工程量大、跟 starter 内部紧耦合，升级 starter 会断 |
| **C. 旁路 SSE 端点** | 加 `GET /agui/state-stream/{threadId}` 单独发 `STATE_*`，前端用原生 `EventSource` 订阅；`/agui/run` 主流不动 | **推荐** |
| D. 走 `AguiEvent.Custom` | 把 STATE_DELTA payload 塞进 Custom 事件——但同样需要 emit 入口 | 跟 A 一个困境 |

**Day 7 选 C**。代价：前端两个 SSE 订阅（`HttpAgent` 和原生 `EventSource`），加起来 ~50 行；收益：starter 不动，1.0.12/1.0.10/1.1.0-RC1 都能跑。

#### 4.1.2 两条流的边界

- **主流 `/agui/run`**（starter 自动注册）：纯 LLM 文本 + tool_use_start/end，前端 `@ag-ui/client` 的 `subscribe()` 已经处理好
- **旁路 `/agui/state-stream/{threadId}`**（我们加）：只发 `STATE_SNAPSHOT` / `STATE_DELTA`，前端原生 `EventSource` 订阅，连接建立即发 snapshot，之后变化推 delta

> 📌 旁路流不是"AG-UI 不标准"——AG-UI 协议本身允许多个传输 channel，规范要求的是事件类型，不强制单连接。前端的状态镜像逻辑跟 §3.1 的标准模式完全一致。

### 4.2 `AguiStateBridge.java`

```java
package space.wlshow.scope.agui;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEvent.JsonPatchOperation;
import io.agentscope.core.agui.event.AguiEvent.StateDelta;
import io.agentscope.core.agui.event.AguiEvent.StateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;
import space.wlshow.scope.todo.TodoChangeListener;
import space.wlshow.scope.todo.TodoItem;
import space.wlshow.scope.todo.TodoManager;
import space.wlshow.scope.todo.TodoStatus;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 TodoManager 变更映射到 AG-UI STATE_* 事件，写入 per-threadId 的
 * {@link Sinks.Many}。SSE 端点（{@link space.wlshow.scope.agui.StateStreamController}）
 * 订阅同一个 Sink，把事件推到前端。
 *
 * <p>一个 thread 一个实例（per-threadId），保证发到对应订阅者。
 * runId 在 starter 内部由 AguiAgentAdapter 持有，旁路流拿不到，统一用 "state-stream"。
 */
public class AguiStateBridge implements TodoChangeListener {

    private static final Logger log = LoggerFactory.getLogger(AguiStateBridge.class);
    private static final String SIDE_RUN_ID = "state-stream";

    private final String threadId;
    private final TodoManager todos;
    private final Sinks.Many<AguiEvent> sink;

    public AguiStateBridge(String threadId, TodoManager todos, Sinks.Many<AguiEvent> sink) {
        this.threadId = threadId;
        this.todos = todos;
        this.sink = sink;
    }

    /** 订阅者连接建立时调，发一份完整状态。 */
    public void snapshotNow() {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("todos", todos.snapshot().stream().map(this::serialize).toList());
        sink.tryEmitNext(new StateSnapshot(threadId, SIDE_RUN_ID, snap));
        log.debug("[StateBridge] STATE_SNAPSHOT thread={} size={}", threadId, todos.size());
    }

    @Override
    public void onCreate(TodoItem item) {
        sink.tryEmitNext(new StateDelta(threadId, SIDE_RUN_ID,
                List.of(JsonPatchOperation.add("/todos/-", serialize(item)))));
        log.debug("[StateBridge] STATE_DELTA add id={}", item.id());
    }

    @Override
    public void onStatusChange(String id, TodoStatus from, TodoStatus to, String err) {
        // 关键：用 "/todos/<id>" 风格的 path 而不是 "/todos/<index>"——
        // 前端 patch 时索引可能已经变（并发新增/删除），id-path 更稳。
        // 但 RFC 6902 不支持自定义 path resolver，所以前端拿到时要用 id 在数组里
        // 查 index 后再 apply。具体见 §4.4 前端代码。
        var ops = new java.util.ArrayList<JsonPatchOperation>();
        ops.add(JsonPatchOperation.replace("/todos/id=" + id + "/status", to.name()));
        if (err != null) {
            ops.add(JsonPatchOperation.add("/todos/id=" + id + "/errorMessage", err));
        }
        sink.tryEmitNext(new StateDelta(threadId, SIDE_RUN_ID, ops));
        log.debug("[StateBridge] STATE_DELTA {} {}->{}", id, from, to);
    }

    @Override
    public void onClear() {
        sink.tryEmitNext(new StateDelta(threadId, SIDE_RUN_ID,
                List.of(JsonPatchOperation.replace("/todos", List.of()))));
        log.debug("[StateBridge] STATE_DELTA clear");
    }

    private Map<String, Object> serialize(TodoItem it) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", it.id());
        n.put("type", it.type().name());
        n.put("targetName", it.targetName());
        n.put("status", it.status().name());
        n.put("payload", it.payload());   // Jackson 会把 JsonNode 直接序列化
        if (it.errorMessage() != null) n.put("errorMessage", it.errorMessage());
        return n;
    }
}
```

> 📌 我们用了 `/todos/id=<id>/status` 这种**非标 RFC 6902 path 语法**——前端在解析时识别 `id=...` 前缀，自己查 index 再 apply。原因：服务端发 delta 时数组顺序跟前端可能已经不一致（并发场景），用 index 容易越界或错位。如果你严格要求标准 RFC 6902，改回 `/todos/<index>/status` 也行，但需要确保 `TodoManager` 用 `LinkedHashMap` 保持顺序、前端永不重排。

### 4.3 旁路 SSE 端点 `StateStreamController`

新建 `src/main/java/space/wlshow/scope/agui/StateStreamController.java`：

```java
package space.wlshow.scope.agui;

import io.agentscope.core.agui.event.AguiEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import space.wlshow.scope.config.AguiAgentConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@CrossOrigin(origins = "http://localhost:5173")
public class StateStreamController {

    private static final Logger log = LoggerFactory.getLogger(StateStreamController.class);

    private final AguiAgentConfig aguiConfig;

    /** per-threadId 的 sink；TodoChangeListener 写、SSE 订阅者读。 */
    private final Map<String, Sinks.Many<AguiEvent>> sinks = new ConcurrentHashMap<>();

    public StateStreamController(AguiAgentConfig aguiConfig) {
        this.aguiConfig = aguiConfig;
    }

    /** 由 AguiAgentConfig 在构造 Agent 时调，拿到对应 threadId 的 sink。 */
    public Sinks.Many<AguiEvent> sinkFor(String threadId) {
        return sinks.computeIfAbsent(threadId,
                id -> Sinks.many().multicast().onBackpressureBuffer(128, false));
    }

    @GetMapping(path = "/agui/state-stream/{threadId}",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AguiEvent>> stream(@PathVariable String threadId) {
        log.info("[StateStream] subscribe thread={}", threadId);
        // 先确保 threadId 有 session（懒加载触发 AguiAgentConfig.buildForThread）
        // 然后让对应的 bridge 发一次 snapshot——这样前端连上就有当前状态
        aguiConfig.touchThread(threadId);

        Sinks.Many<AguiEvent> sink = sinkFor(threadId);
        return sink.asFlux()
                .map(ev -> ServerSentEvent.<AguiEvent>builder()
                        .event(ev.getType().name())  // STATE_SNAPSHOT / STATE_DELTA
                        .data(ev)
                        .build())
                .doOnCancel(() -> log.info("[StateStream] unsubscribe thread={}", threadId));
    }
}
```

### 4.4 接入 `AguiAgentConfig`

修改 Day 6 落地的 `AguiAgentConfig`，把 `StateStreamController` 注入进来，构造 Agent 时给 `TodoManager` 挂上 bridge：

```java
@Configuration
public class AguiAgentConfig {
    // ... Day 6 已有字段

    private final ObjectProvider<StateStreamController> stateStreamProvider;
    // 用 ObjectProvider 是为了避免 AguiAgentConfig 和 StateStreamController 循环依赖

    public AguiAgentConfig(ObjectProvider<StateStreamController> stateStreamProvider) {
        this.stateStreamProvider = stateStreamProvider;
    }

    /** 前端订阅 /agui/state-stream/{threadId} 时调，先确保 session 存在并发 snapshot。 */
    public void touchThread(String threadId) {
        FileSession session = activeSessions.computeIfAbsent(threadId, FileSession::loadOrNew);
        StateStreamController ctrl = stateStreamProvider.getIfAvailable();
        if (ctrl == null) return;
        // 给 TodoManager 挂上 bridge（如果之前没挂过）
        AguiStateBridge bridge = new AguiStateBridge(threadId, session.todos, ctrl.sinkFor(threadId));
        if (session.todos.addListenerIfAbsent(bridge)) {
            log.info("[AguiConfig] state bridge attached thread={}", threadId);
        }
        // 不管是新挂的还是旧的，订阅者连接时都重发一次 snapshot
        bridge.snapshotNow();
    }

    private Agent buildForThread(String threadId) {
        FileSession session = activeSessions.computeIfAbsent(threadId, FileSession::loadOrNew);
        // 构造 Agent 时不挂 bridge——bridge 在 touchThread 时挂，避免 Agent 重建抖动 listener
        log.info("[AguiConfig] build agent for thread={}, todos={}",
                threadId, session.todos.size());
        return AgentFactory.buildAnalystWithTools(session.todos, session.memory);
    }
}
```

`TodoManager` 加一个幂等的 `addListenerIfAbsent`：

```java
public synchronized boolean addListenerIfAbsent(TodoChangeListener l) {
    if (listeners.stream().anyMatch(x -> x.getClass() == l.getClass())) return false;
    listeners.add(l);
    return true;
}
```

> 📌 为什么是 `addListenerIfAbsent` 而不是 `addListener`：每次前端断开重连都会调 `touchThread`，不去重会挂多个 bridge，一次变更触发多份 STATE_DELTA。

### 4.5 Vue3 前端订阅旁路流

`frontend/src/App.vue` 在 `agent.subscribe(...)` 之外加一个 `EventSource`：

```vue
<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'

interface Todo {
  id: string; type: string; targetName: string; status: string;
  payload?: unknown; errorMessage?: string;
}
const todos = ref<Todo[]>([])

interface JsonPatchOp {
  op: 'add' | 'replace' | 'remove'
  path: string         // 形如 "/todos/-" 或 "/todos/id=todo-3/status"
  value?: unknown
}

function applyOps(ops: JsonPatchOp[]) {
  const arr = [...todos.value]
  for (const op of ops) {
    // 仅处理 "/todos/-" 追加、"/todos/id=<id>/<field>" 字段更新、"/todos" 整体替换
    if (op.path === '/todos/-' && op.op === 'add') {
      arr.push(op.value as Todo)
    } else if (op.path === '/todos' && op.op === 'replace') {
      arr.length = 0
      ;(op.value as Todo[]).forEach(t => arr.push(t))
    } else {
      const m = op.path.match(/^\/todos\/id=([^/]+)\/(\w+)$/)
      if (!m) { console.warn('[STATE_DELTA] unknown path', op.path); continue }
      const [, id, field] = m
      const t = arr.find(x => x.id === id)
      if (!t) { console.warn('[STATE_DELTA] todo not found id=', id); continue }
      ;(t as Record<string, unknown>)[field] =
          op.op === 'remove' ? undefined : op.value
    }
  }
  todos.value = arr
}

let es: EventSource | null = null
onMounted(() => {
  const url = `http://localhost:8080/agui/state-stream/${encodeURIComponent(threadId)}`
  es = new EventSource(url)
  es.addEventListener('STATE_SNAPSHOT', (ev) => {
    const e = JSON.parse((ev as MessageEvent).data)
    todos.value = (e.snapshot?.todos ?? []) as Todo[]
    console.log('[STATE_SNAPSHOT] size=', todos.value.length)
  })
  es.addEventListener('STATE_DELTA', (ev) => {
    const e = JSON.parse((ev as MessageEvent).data)
    applyOps(e.delta as JsonPatchOp[])
    console.log('[STATE_DELTA] ops=', e.delta.length)
  })
  es.onerror = (err) => console.warn('[StateStream] error', err)
})
onBeforeUnmount(() => es?.close())
</script>
```

> 📌 用原生 `EventSource` 而不是 `@ag-ui/client`：旁路端点不在 starter 路由表里，`HttpAgent` 也不会去订阅。`EventSource` 自带断线重连（默认 3s），适合长连接的 STATE_*。
>
> 📌 `EventSource` 不能带自定义 header，因此鉴权要走 URL query 或 cookie——课程内 5173/8080 同源 dev 阶段忽略，生产再补。

不再需要 `fast-json-patch` 库（我们自己解析 5 种 op，比通用库可控）。

### 4.6 模板加右侧看板

> 📌 **跟 Day 6 落地 App.vue 的对接**：Day 6 的 `App.vue` 顶层 `<template>` 是 `.app > .app-header + .log + .composer-wrap` 单列布局；Day 7 加看板时**不要重写整个布局**，而是在外层包一个 `.layout` grid，左边塞原 `.app` 内容，右边加 `.todos` aside。下面给出 minimal patch 思路（具体合并到现有 Day 6 模板，保留原 header / suggestions / composer-wrap 等所有元素）：

```vue
<template>
  <div class="layout">
    <div class="chat-pane">
      <!-- 原 Day 6 全部内容：app-header + log + composer-wrap 整段放这里 -->
    </div>

    <aside class="todos">
      <h2>Todo Board ({{ todos.length }})</h2>
      <ul>
        <li v-for="t in todos" :key="t.id" :class="['todo', t.status.toLowerCase()]">
          <span class="id">{{ t.id }}</span>
          <span class="type">{{ t.type }}</span>
          <span class="name">{{ t.targetName }}</span>
          <span class="status">{{ t.status }}</span>
        </li>
      </ul>
    </aside>
  </div>
</template>

<style scoped>
.layout { display: grid; grid-template-columns: 1fr 360px; gap: 16px; height: 100vh; }
.chat-pane { display: flex; flex-direction: column; min-height: 0; }
.todos { border-left: 1px solid rgba(0,0,0,.08); padding: 18px 16px; overflow-y: auto; }
.todos h2 { font-size: 14px; margin: 0 0 12px; color: #374151; }
.todo { display: grid; grid-template-columns: 70px 100px 1fr 80px;
        padding: 6px 0; font-size: 13px; border-bottom: 1px solid rgba(0,0,0,.04); }
.todo.pending  { color: #9ca3af; }
.todo.running  { color: #2563eb; font-weight: 500; }
.todo.success  { color: #16a34a; }
.todo.failed   { color: #dc2626; }
</style>
```

### 4.7 验证

打"做一个员工档案管理"，看右侧看板：

```
todo-1  CREATE_APP     员工档案管理     PENDING   ← STATE_DELTA add
todo-2  CREATE_MODULE  员工管理         PENDING
todo-3  CREATE_MODEL   employee        PENDING
```

接着打"提交"，看到（Day 5 的 SubmitTool 会让全部 PENDING→RUNNING→SUCCESS）：

```
todo-1  ...  RUNNING (蓝色)   ← STATE_DELTA replace
todo-1  ...  SUCCESS (绿色)
...
```

### ✅ Phase 1 验收

- [ ] 浏览器 DevTools Network 看到两条 SSE：`/agui/run`（主流）+ `/agui/state-stream/<threadId>`（旁路）
- [ ] 旁路连接建立后立刻收到 `STATE_SNAPSHOT`（事件名 `event: STATE_SNAPSHOT`）
- [ ] 每次工具调用都触发 `STATE_DELTA`（DevTools Console 数得过来）
- [ ] 右侧看板颜色按状态切换
- [ ] 重连不丢状态：关掉 5173 页面再开，看板能恢复（snapshot 重发）

---

## 5. Phase 2 · HITL on AG-UI（90 min）

### 5.1 SubmitTool 恢复注册 + AS-Java 1.0.12 真实行为

#### 5.1.1 SubmitTool 注册状态确认

`SubmitTool` 默认是在 `AgentFactory.buildAnalystWithTools` 里注册的（Day 5 落地）。如果你在 Day 6 调试期为了绕开"run 卡死无回填"把它**临时摘掉**了（见 CLAUDE.md 故障排查"浏览器发送后一直转圈"），Day 7 现在要**把这行注册加回去**——HITL 回填通道由本节落地，run 不会再卡死。

构造函数签名同时升级到带 `Dispatcher`（见 §9.2）：

```java
// AgentFactory.buildAnalystWithTools(...)
toolkit.registerTool(new SubmitTool(todos, dispatcher));   // dispatcher 是 Spring 注入的 Bean
```

#### 5.1.2 ToolSuspend 在 1.0.12 实际产生的事件序列

`SubmitTool.submit(confirmed=false)` 抛 `ToolSuspendException("AWAITING_USER_CONFIRMATION\n- todo-1 ...")`。AS-Java 1.0.12 实际行为（看 `ReActAgent.acting` + `AguiAgentAdapter.convertEvent` 源码确认）：

| AS-Java 内部 | 对应 AG-UI 事件 |
|---|---|
| `Toolkit` 把异常转成 `ToolResultBlock.suspended=true`，content=exception.message | — |
| `ReActAgent.acting` 返回 `Msg` 带 `GenerateReason.TOOL_SUSPENDED`，结束本次 run | — |
| `AguiAgentAdapter` 处理 TOOL_RESULT 事件 | 发 `TOOL_CALL_END` + `TOOL_CALL_RESULT`（content 就是 `AWAITING_USER_CONFIRMATION...`）|
| `finishRun` | 发 `RUN_FINISHED`（**注意**：1.0.12 的 `RunFinished` record 只有 `threadId` + `runId`，**没有 suspended 标记字段**） |

**结论**：前端识别"待确认"的可靠方式是**监听 `TOOL_CALL_RESULT`，检查 content 是否以 `AWAITING_USER_CONFIRMATION` 开头**。不要试图从 `RUN_FINISHED` 拿标记（没有），也不要试图从 `TOOL_CALL_END` 拿 args（end 事件不带 args）。

> 📌 早期版本课程文档说"`RUN_FINISHED` 带 suspended 标记"——那是设想中的协议扩展，1.0.12 没实现。我们用 content 前缀替代，效果一样可靠。

### 5.2 前端识别"待确认"

`@ag-ui/client` 的 `onToolCallResultEvent` 回调（注意：是 Result 不是 End）能拿到 toolCallId + content：

```ts
interface PendingConfirm {
  toolCallId: string
  todos: Todo[]    // 拍一份快照展示给用户
}
const pendingConfirm = ref<PendingConfirm | null>(null)
const lastSubmitToolCallId = ref<string | null>(null)   // 用 ToolCallStart 记 toolName→id

agent.subscribe({
  // ... 已有 onTextMessageStartEvent / onToolCallStartEvent 等
  onToolCallStartEvent: ({event}) => {
    // 记下 submit_to_frontend 对应的 toolCallId，等会儿在 Result 里要用
    // 注意字段是 event.toolCallName（不是 toolName），跟 Day 6 一致
    if (event.toolCallName === 'submit_to_frontend') {
      lastSubmitToolCallId.value = event.toolCallId
    }
  },
  onToolCallResultEvent: ({event}) => {
    // event.toolCallId / event.content / event.role
    if (event.toolCallId === lastSubmitToolCallId.value
        && typeof event.content === 'string'
        && event.content.startsWith('AWAITING_USER_CONFIRMATION')) {
      pendingConfirm.value = {
        toolCallId: event.toolCallId,
        todos: [...todos.value],
      }
    }
  },
})
```

> 📌 **回调形态对齐 Day 6**：`@ag-ui/client` 1.x 的 `subscribe()` 回调入参是 `({event}) => ...`，**不是** `(e) => ...`；事件字段是 `event.toolCallName` / `event.toolCallId` / `event.content`，**不是** `e.toolName` / `e.args`。这一点 Day 6 §6 已经踩过坑，CLAUDE.md 第 5 节明确记录。

### 5.3 弹窗模板

```vue
<template>
  <div v-if="pendingConfirm" class="modal-backdrop">
    <div class="modal">
      <h3>确认下发 {{ pendingConfirm.todos.length }} 项？</h3>
      <ul class="preview">
        <li v-for="t in pendingConfirm.todos" :key="t.id">
          {{ t.id }} · {{ t.type }} · {{ t.targetName }}
        </li>
      </ul>
      <div class="actions">
        <button @click="resumeRun('USER_CONFIRMED')" class="primary">确认下发</button>
        <button @click="resumeRun('USER_REJECTED')">取消</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-backdrop { position: fixed; inset: 0; background: rgba(0,0,0,.3); display:flex; align-items:center; justify-content:center; }
.modal { background: #fff; border-radius: 8px; padding: 20px; min-width: 360px; max-width: 600px; }
.modal .preview { max-height: 240px; overflow: auto; font-size: 13px; }
.modal .actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 16px; }
.primary { background: #0066cc; color: #fff; padding: 6px 16px; border: none; border-radius: 4px; cursor: pointer; }
</style>
```

### 5.4 `resumeRun` 函数

```ts
async function resumeRun(decision: 'USER_CONFIRMED' | 'USER_REJECTED') {
  const pc = pendingConfirm.value
  if (!pc) return
  pendingConfirm.value = null
  running.value = true
  try {
    // 追加 role=tool 的回填消息，HttpAgent 在下一次 runAgent 时会把整个
    // messages 数组送过去；服务端 agent 续跑时拿到这条 tool result，
    // LLM 决定再调 submit_to_frontend(confirmed=true)
    agent.messages.push({
      id: 'tr-' + Date.now(),
      role: 'tool',
      toolCallId: pc.toolCallId,
      content: decision,
    } as any)
    await agent.runAgent({ runId: 'run-' + Date.now() })
  } finally {
    running.value = false
  }
}
```

> 📌 **为什么不用 `agent.addMessage`**：`@ag-ui/client` 1.x 的 `addMessage` 内部 schema 校验对 `role: 'tool'` 时常拒收（不同小版本行为不一）。直接 push 到 `agent.messages` 数组是协议层兼容的最稳路径——`HttpAgent.runAgent` 在发起请求时会序列化整个 messages 列表，AG-UI 协议本身允许 `{role:"tool", toolCallId, content}` 形态。如果 TS 类型卡了，加 `as any` 跳过编译期检查。
>
> ⚠️ **服务端续跑实测结论（与原讲义不同）**：starter 1.0.12 的 `AguiRequestProcessor.extractLatestUserMessage()` **明确**只挑 `role="user"`（参看 `AguiRequestProcessor.java:170-183`，`if ("user".equalsIgnoreCase(msg.getRole()))`），前端推的 `role:'tool'` 在 `server-side-memory=true` 下会被**静默吃掉**——agent 拿到老 user message 触发 `IllegalStateException("Pending tool calls exist without results")`，被 `AguiAgentAdapter.onErrorResume` 兜底成空响应，前端 `newMessages=[]` 一脸蒙。
>
> 备选 fallback "改 `server-side-memory=false`" **也不通**：`DefaultAgentResolver` 在标准模式只调 `registry.getAgent(agentId)`——**拿不到 threadId**，无法按线程分发 `TodoManager`，全局共享一个 Agent 实例。
>
> **唯一可行解**：自家 `/agui/run` 路由 + 自定义 `AgentResolver`（具体实现见 §5.5）。`AgentResolver` 接口本身是 `(agentId, threadId) -> Agent`，所以走这条路两边的能力都拿得到。

### 5.5 自家 `/agui/run` 路由 + `ThreadAgentResolver`（替代 §5.4 footnote 里失败的两条路）

把 `AguiAgentConfig` 整个改造：去掉原来覆盖 `ThreadSessionManager` 的 Bean，挂一条 `@Order(Ordered.HIGHEST_PRECEDENCE)` 的 RouterFunction 顶替 starter 自动装配的 `aguiRoutes`：

```java
@Bean
@Order(Ordered.HIGHEST_PRECEDENCE)
public RouterFunction<ServerResponse> scopeAguiRunRoutes(AguiProperties props) {
    AguiAdapterConfig adapterConfig = AguiAdapterConfig.builder()
            .defaultAgentId(props.getDefaultAgentId())
            // 其余 props 透传
            .build();
    AguiRequestProcessor processor = AguiRequestProcessor.builder()
            .agentResolver(new ThreadAgentResolver())   // ★ 自家 resolver
            .config(adapterConfig)
            .build();
    AguiEventEncoder encoder = new AguiEventEncoder();
    String runPath = props.getPathPrefix() + "/run";
    return RouterFunctions.route()
            .POST(runPath, req -> req.bodyToMono(RunAgentInput.class)
                    .flatMap(input -> handleRun(input, processor, encoder)))
            .build();
}

private class ThreadAgentResolver implements AgentResolver {
    @Override
    public Agent resolveAgent(String agentId, String threadId) {
        FileSession session = activeSessions.computeIfAbsent(threadId, FileSession::loadOrNew);
        // 兜底挂 bridge（前端订阅 state-stream 时已挂过，这里防御性兜底）
        ensureBridgeAttached(threadId, session);
        // ★ 每次新建 Agent + 全新 Memory：让 agent.memory 由前端 messages 重建，
        //   既不跨请求累计，也保证 HITL 的 role:'tool' 完整到 doCall
        return AgentFactory.buildAnalystWithTools(session.todos, new InMemoryMemory());
    }

    @Override
    public boolean hasMemory(String threadId) {
        return false;   // ★ 跳过 extractLatestUserMessage，前端完整 messages 直传
    }
}
```

两个关键设计点：

1. **`@Order(Ordered.HIGHEST_PRECEDENCE)`**：starter 自动装配的 `aguiRoutes` Bean 同样注册 `POST /agui/run`。Spring WebFlux 的 `RouterFunctionMapping` 通过 `orderedStream().reduce(RouterFunction::andOther)` 串路由表，靠前的优先级高、first-match 胜出。我们的 Bean 用 `HIGHEST_PRECEDENCE`（= `Integer.MIN_VALUE`）压过 starter 默认的（无 `@Order` = `LOWEST_PRECEDENCE`），同 path 我们赢。
2. **每请求新建 Agent + 新 Memory**：`AgentResolver` 接口拿到 threadId，可以按线程取出 `FileSession.todos`（跨请求活），但 Memory 用全新 `InMemoryMemory`——agent 的 `doCall` 进入"pendingIds=空 → addToMemory(msgs) → executeIteration"路径，由前端 messages 数组完整重建对话上下文。这样既避免内存累计，也保证 HITL 的 `role:'tool'` 回填能在 `agent.stream(msgs)` 里被 `AguiMessageConverter.toMsg` 正确转成 `ToolResultBlock`，触发 `ReActAgent.doCall` 的 `providedResults` 分支走 `validateAndAddToolResults` 续跑。

> 📌 **代价**：每个请求约 75ms 的 Agent 构建开销（`initModels` + 3 个 Schema 加载 + 7 个工具注册），学习项目可接受；要再优化可在 `AgentFactory` 里按 `(TodoManager 实例) -> Agent` 做轻缓存。
>
> 📌 **starter 自动装配的旁路 Bean** 仍然存在（`ThreadSessionManager`、`AguiWebFluxHandler`、starter 默认 `aguiRoutes`），但全部走不到——我们的 `@Order(HIGHEST_PRECEDENCE)` 路由先匹配到 `/agui/run`，starter 的同 path 路由变成 dead code，无害。

### 5.6 服务端验证

启动后端 + 前端，跑：

```
> 你（输入框）：做一个员工档案管理
（右侧看板出现 3 项 PENDING）

> 你（输入框）：提交
（弹窗出现，列出 3 项预览）
[点击「确认下发」]
（看板 todo-1 → RUNNING → SUCCESS，再 todo-2 ... todo-3 ...）
（聊天框出现 LLM 收尾消息）
```

跑拒绝路径：

```
> 你：提交
[点击「取消」]
（聊天框：LLM 说"已取消"）
（看板 3 项依然 PENDING）

> 你：重新提交
（再次弹窗，可以反悔）
```

### ✅ Phase 2 验收

- [ ] 弹窗在 `TOOL_CALL_RESULT` content 以 `AWAITING_USER_CONFIRMATION` 开头时浮现（DevTools Console 验证）
- [ ] 确认路径：看板 3 项依次 PENDING→RUNNING→SUCCESS
- [ ] 取消路径：3 项保持 PENDING，可重新提交
- [ ] LLM 收尾消息正确反映用户决定
- [ ] `logs/scope.log` 出现 `[Submit] suspend with N items` 但**没有** `[Repl]` 前缀（CLI 未被触发）

---

## 6. Phase 3a · 日志骨架 + 7 个关键日志点（60 min）

### 6.1 加依赖

`pom.xml`：

```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

### 6.2 改造 `logback-spring.xml`

**两步**：（1）`git rm src/main/resources/logback.xml`（必须删原文件，否则 logback 自身和 Spring Boot 会**双加载**，产生两份 appender、双倍输出）；（2）新建 `src/main/resources/logback-spring.xml`：

```xml
<configuration scan="true" scanPeriod="30 seconds">

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <!-- 控制台用短 traceId 易读，日志文件用全长（见 §7.4） -->
            <pattern>%d{HH:mm:ss.SSS} [%X{traceIdShort:-NA}] %-5level %logger{36} - %msg%n</pattern>
            <charset>UTF-8</charset>
        </encoder>
    </appender>

    <appender name="JSON" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/scope.json.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/scope.json-%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>14</maxHistory>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeMdcKeyName>traceId</includeMdcKeyName>
            <includeMdcKeyName>threadId</includeMdcKeyName>
            <includeMdcKeyName>runId</includeMdcKeyName>
            <includeMdcKeyName>stage</includeMdcKeyName>
        </encoder>
    </appender>

    <logger name="space.wlshow.scope" level="DEBUG" />
    <logger name="io.agentscope" level="INFO" />

    <root level="INFO">
        <appender-ref ref="CONSOLE" />
        <appender-ref ref="JSON" />
    </root>
</configuration>
```

### 6.3 traceId 注入

#### 6.3.1 WebFlux 入口过滤器

```java
package space.wlshow.scope.observability;

import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.UUID;

@Component
@Order(-100)
public class TraceIdFilter implements WebFilter {

    public static final String KEY = "traceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Phase 3a 阶段没接 OTel，先用 UUID 兜底生成全 32 位 traceId（去掉横线）。
        // Phase 3b 接 OTel 后，本类整段替换为读 Span.current()（见 §7.4）。
        String tid = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        if (tid == null || tid.isBlank()) {
            tid = UUID.randomUUID().toString().replace("-", "");
        }
        exchange.getResponse().getHeaders().add("X-Trace-Id", tid);

        final String finalTid = tid;
        return chain.filter(exchange)
                .contextWrite(Context.of(KEY, finalTid))
                .doFirst(() -> {
                    MDC.put(KEY, finalTid);
                    MDC.put("traceIdShort", finalTid.substring(0, Math.min(8, finalTid.length())));
                })
                .doFinally(s -> {
                    MDC.remove(KEY);
                    MDC.remove("traceIdShort");
                });
    }
}
```

> 📌 `MDC` 在 Reactor 链上**不会自动透传**，正确做法是在每个 operator 上手动 `Context` ↔ `MDC` 同步。简化版（本课用）：在 Filter 起点 put、终点 remove，AS-Java 内部的同步 work 已经在主线程里。
> 📌 Phase 3b 起 OTel 接管 traceId，§7.4 给出"整段替换"版本，确保 traceId 跟 Jaeger / Span 完全一致。

#### 6.3.2 AG-UI starter 透传 threadId/runId

starter 1.0.12 **不会**自动把 `threadId` / `runId` 放进 MDC。Day 6 走"覆盖 `ThreadSessionManager`"路线，没有直接拿到 runId 的入口。两种补法：

**简易版**：在 `AguiAgentConfig.buildForThread(String threadId)` 头部 `MDC.put("threadId", threadId)` —— 因为 build 在 ThreadSessionManager 同一线程里同步执行，MDC 当下有效；但 runId 仍拿不到（starter 内部状态）。

**完整版**：写一个 `@RestControllerAdvice` 或 `WebFilter` 拦截 `POST /agui/run`，从 request body 里反序列化 `RunAgentInput` 拿 `runId`，再放 MDC。代价是 body 要二次解析（WebFlux 不能 replay）。**本课走简易版**，runId 在日志里缺失可以接受，trace 那一层会有 Jaeger Span ID 兜底。

> ⚠️ MDC 在 Reactor 调度的 worker thread 里会丢，复杂场景要装 `Hooks.enableContextLossTracking()` 或者用 `MDCContextLifter` 之类。本课程不深入。

#### 6.3.3 业务日志带 stage —— `Stage` helper

新建 `src/main/java/space/wlshow/scope/observability/Stage.java`：

```java
package space.wlshow.scope.observability;

import org.slf4j.MDC;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * 让一段代码在 MDC.stage=<name> 下执行，结束后自动恢复（不只是 remove，
 * 嵌套 stage 时不会把外层抹掉）。
 *
 * 用法：Stage.run(Stage.LLM_CALL, () -> agent.call(msg).block());
 */
public final class Stage {

    public static final String INPUT = "INPUT";
    public static final String LLM_CALL = "LLM_CALL";
    public static final String TOOL_CALL = "TOOL_CALL";
    public static final String SCHEMA_VALIDATE = "SCHEMA_VALIDATE";
    public static final String TODO_UPDATE = "TODO_UPDATE";
    public static final String FRONTEND_DISPATCH = "FRONTEND_DISPATCH";
    public static final String FRONTEND_CALLBACK = "FRONTEND_CALLBACK";

    public static void run(String name, Runnable r) {
        String prev = MDC.get("stage");
        MDC.put("stage", name);
        try { r.run(); } finally {
            if (prev == null) MDC.remove("stage"); else MDC.put("stage", prev);
        }
    }

    public static <T> T call(String name, Supplier<T> s) {
        String prev = MDC.get("stage");
        MDC.put("stage", name);
        try { return s.get(); } finally {
            if (prev == null) MDC.remove("stage"); else MDC.put("stage", prev);
        }
    }

    public static <T> T callChecked(String name, Callable<T> c) throws Exception {
        String prev = MDC.get("stage");
        MDC.put("stage", name);
        try { return c.call(); } finally {
            if (prev == null) MDC.remove("stage"); else MDC.put("stage", prev);
        }
    }

    private Stage() {}
}
```

### 6.4 7 个关键日志点

需求 #10 的兑现表 — 每个 stage 至少 1 个 `log.info`，字段固定（写日志时**别 string concat 拼字段**，用占位符 + MDC，方便 jq 解析）：

| Stage | 在哪打 | MDC.stage | 必带字段 | 日志样例 |
|------|--------|-----------|--------|--------|
| **INPUT** | `TraceIdFilter` 末段 或 `AguiAgentConfig.buildForThread` 入口 | `INPUT` | threadId, userText 长度 | `收到用户输入 thread=xx len=42` |
| **LLM_CALL** | `ExecutionMetricsHook.onPreReasoning / onPostReasoning`（§8.3） | `LLM_CALL` | model, promptChars, latencyMs | `LLM 返回 model=doubao-pro chars=1240 latency=820ms` |
| **TOOL_CALL** | 每个 `@Tool` 方法第一行 `Stage.call(TOOL_CALL, ...)` | `TOOL_CALL` | toolName, argsHash | `调用工具 name=create_app argsHash=ab12` |
| **SCHEMA_VALIDATE** | `SchemaValidator.validate(...)` 内部 | `SCHEMA_VALIDATE` | schema, result, errCount | `Schema 校验 schema=app-spec result=fail errors=2` |
| **TODO_UPDATE** | `TodoManager.transit` / `add` / `clear`（已有 `[Todo]` 前缀的位置补 stage） | `TODO_UPDATE` | id, from, to, err | `Todo todo-3 PENDING→RUNNING` |
| **FRONTEND_DISPATCH** | `Dispatcher.dispatch(item)` 调用前 | `FRONTEND_DISPATCH` | id, endpoint, payloadSize | `下发 todo-1 endpoint=create_app size=234` |
| **FRONTEND_CALLBACK** | `Dispatcher.dispatch(item)` 调用后（成功 / 失败两路径） | `FRONTEND_CALLBACK` | id, status, latencyMs, err | `下发回执 todo-1 status=SUCCESS latency=120ms` |

落地示例 — `FrontendCreateTools.createApp`：

```java
@Tool(name = "create_app", description = "...")
public String createApp(... args ...) {
    return Stage.call(Stage.TOOL_CALL, () -> {
        log.info("调用工具 name=create_app argsHash={}", hash(name, label, type));
        // ... 原逻辑（含 SchemaValidator 调用，会自动嵌套 stage=SCHEMA_VALIDATE）
    });
}
```

`SubmitTool.submit` 的 confirmed=true 分支：

```java
for (TodoItem it : pending) {
    todos.markRunning(it.id());
    long start = System.currentTimeMillis();
    Stage.run(Stage.FRONTEND_DISPATCH, () ->
        log.info("下发 id={} endpoint={} size={}",
                 it.id(), endpointOf(it), it.payload().toString().length()));
    try {
        dispatcher.dispatch(it);
        todos.markSuccess(it.id());
        long ms = System.currentTimeMillis() - start;
        Stage.run(Stage.FRONTEND_CALLBACK, () ->
            log.info("下发回执 id={} status=SUCCESS latency={}ms", it.id(), ms));
    } catch (Exception e) {
        todos.markFailed(it.id(), e.getMessage());
        long ms = System.currentTimeMillis() - start;
        Stage.run(Stage.FRONTEND_CALLBACK, () ->
            log.warn("下发回执 id={} status=FAILED latency={}ms err={}",
                     it.id(), ms, e.getMessage()));
    }
}
```

### 6.5 jq 验证

跑一次完整剧本后：

```bash
# 拿 traceId（响应头）—— --max-time 30 避免 SSE 流没结束 curl 一直挂着
TID=$(curl -sN -X POST http://localhost:8080/agui/run \
    -H "Content-Type: application/json" \
    -D /tmp/h.txt \
    --max-time 30 \
    -d "$(cat fixtures/demo-input.json)" \
    > /dev/null 2>&1 ; grep -i 'x-trace-id' /tmp/h.txt | awk '{print $2}' | tr -d '\r')

echo "traceId=$TID  长度=${#TID}"     # Phase 3a 是 32 位 UUID-去横线；Phase 3b 接 OTel 后是 32 位 hex

# 按 stage 分组数一次会话里每个阶段发生几次
jq -r --arg tid "$TID" '
    select(.traceId == $tid) | .stage // "_"
' logs/scope.json.log | sort | uniq -c
```

期望输出（数字不严，**7 个 stage 一个不少**才算过）：

```
   1 INPUT
   2 LLM_CALL
   3 TOOL_CALL
   3 SCHEMA_VALIDATE
   6 TODO_UPDATE
   3 FRONTEND_DISPATCH      ← /submit 之后才有
   3 FRONTEND_CALLBACK
```

### ✅ Phase 3a 验收

- [ ] `logs/scope.json.log` 是合法 JSON Lines
- [ ] 每行带 traceId / threadId / runId / stage
- [ ] `X-Trace-Id` 响应头存在
- [ ] `jq` 按 traceId 过出的日志里 **7 个 stage 全部出现**
- [ ] 故意让某项 dispatch 失败，看到 `stage=FRONTEND_CALLBACK level=WARN status=FAILED`

---

## 7. Phase 3b · OpenTelemetry + Jaeger（75 min）

### 7.1 起 Jaeger（本地 Docker）

```bash
docker run -d --name jaeger \
    -e COLLECTOR_OTLP_ENABLED=true \
    -p 16686:16686 \
    -p 4317:4317 \
    -p 4318:4318 \
    jaegertracing/all-in-one:1.57
```

端口说明：
- `16686` — Jaeger UI
- `4317` — OTLP gRPC（OTel SDK 默认走这个）
- `4318` — OTLP HTTP（备用）

浏览器开 `http://localhost:16686` 验证 UI 起来了。

> ⚠️ Windows 上如果没装 Docker Desktop，临时可下载 Jaeger all-in-one 的 native 二进制（GitHub Releases），效果一样。

### 7.2 OTel 依赖

`pom.xml`：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.opentelemetry.instrumentation</groupId>
            <artifactId>opentelemetry-instrumentation-bom</artifactId>
            <version>2.10.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- OTel Spring Boot starter：自动 instrument WebFlux / Reactor / Logback -->
    <dependency>
        <groupId>io.opentelemetry.instrumentation</groupId>
        <artifactId>opentelemetry-spring-boot-starter</artifactId>
    </dependency>

    <!-- OTLP gRPC exporter -->
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-exporter-otlp</artifactId>
    </dependency>

    <!-- Logback ↔ OTel MDC 桥（关键，下一步用） -->
    <dependency>
        <groupId>io.opentelemetry.instrumentation</groupId>
        <artifactId>opentelemetry-logback-mdc-1.0</artifactId>
    </dependency>

    <!-- @WithSpan 注解，省 boilerplate -->
    <dependency>
        <groupId>io.opentelemetry.instrumentation</groupId>
        <artifactId>opentelemetry-instrumentation-annotations</artifactId>
    </dependency>
</dependencies>
```

> ⚠️ OTel BOM 版本日新月异，2.10.0 对应 spec 1.42，覆盖 Spring Boot 3.2.x。如果你用更晚的 Spring Boot，对应升一下；用 `mvn dependency:tree | grep opentelemetry` 查冲突。

### 7.3 `application.yml` 加 OTel 配置

```yaml
otel:
  service:
    name: agent-scope-app
  resource:
    attributes:
      deployment.environment: dev
  exporter:
    otlp:
      endpoint: http://localhost:4317
      protocol: grpc
  traces:
    exporter: otlp
    sampler: parentbased_always_on   # dev：全采样；生产换 parentbased_traceidratio + 0.1
  metrics:
    exporter: none                   # Phase 3c 走 Micrometer 不走 OTel metrics
  logs:
    exporter: none                   # 日志走 Logback JSON，不双写
  instrumentation:
    spring-webflux:
      enabled: true
    logback-mdc-1.0:
      enabled: true                  # 把 OTel traceId / spanId 自动注入 MDC
      add-baggage: false             # baggage 高基数容易写炸日志，关掉
```

### 7.4 关键坑：MDC key 必须对齐

OTel 的 `opentelemetry-logback-mdc-1.0` 默认会注入 `trace_id` / `span_id` 到 MDC，**key 名写死是 `trace_id` 不是 `traceId`**（看 2.10.0 starter 实测，**没有** `trace-id-key` 这个可配置项 —— 早期课程文档写的那条 yaml 实际不会被解析）。

要让 jq 的 `.traceId` 字段对齐，**用 `TraceIdFilter` 在过滤器里主动读 OTel Span，覆盖 MDC**：

```java
@Override
public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    // OTel WebFlux instrumentation 已在过滤器之前建好了 root Span
    // （前提是 Phase 3b 的 opentelemetry-spring-boot-starter 已生效）
    io.opentelemetry.api.trace.SpanContext sc =
            io.opentelemetry.api.trace.Span.current().getSpanContext();
    String otelTid = sc.isValid() ? sc.getTraceId() : null;

    // OTel 全 32 位 traceId（如 4bf92f3577b34da6a3ce929d0e0e4736）
    String fullTid = (otelTid != null)
            ? otelTid
            : UUID.randomUUID().toString().replace("-", "");

    // 响应头返回**全长** traceId，方便 Jaeger UI 直接搜
    exchange.getResponse().getHeaders().add("X-Trace-Id", fullTid);

    final String finalTid = fullTid;
    return chain.filter(exchange)
            .contextWrite(Context.of(KEY, finalTid))
            .doFirst(() -> {
                MDC.put(KEY, finalTid);                          // 业务日志拿全长
                MDC.put("traceIdShort", finalTid.substring(0, 8)); // 人读用短的
            })
            .doFinally(s -> {
                MDC.remove(KEY);
                MDC.remove("traceIdShort");
            });
}
```

> 📌 **为什么用全长不截短**：早期文档把 traceId 截前 8 位放响应头 + MDC，造成"Jaeger 搜不到这个短 id"（Jaeger 索引的是全 32 位）。改用全长 + 同时存一个短 id (`traceIdShort`) 供 console pattern 显示，搜索全长用 Jaeger / jq，人读用短的。`logback-spring.xml` 的 console 编码器改成 `%X{traceIdShort:-NA}`。
>
> 📌 **Phase 3a 已经写过 TraceIdFilter？** 当时它没读 OTel Span（用 UUID 兜底）。Phase 3b 接入后**整段替换**为这版，否则 jq 日志的 traceId 跟 Jaeger 仍对不上。

### 7.5 Span 命名约定

OTel 默认给 WebFlux 自动产生的 Span 名是 HTTP method，可读性差。在 `AguiAgentConfig.buildForThread` 里手动建子 Span：

```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;

private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("scope-agent");

// buildForThread 构造 Agent 时手动加 Span（runId 在 starter 1.0.12 里拿不到，只记 threadId）
var span = TRACER.spanBuilder("agent.build")
        .setAttribute("threadId", threadId)
        .startSpan();
try (var scope = span.makeCurrent()) {
    return AgentFactory.buildAnalystWithTools(session.todos, session.memory, metricsHook);
} finally {
    span.end();
}
```

**⚠️ `@WithSpan` 的真相**：注解版需要 **OTel javaagent jar** 做字节码 instrumentation 才能生效——只加 `opentelemetry-instrumentation-annotations` 依赖**不够**。两条路：

**路 A（推荐，本课走这条）：手写 Tracer**

```java
@Tool(name = "create_app", description = "...")
public String createApp(
        @ToolParam(name = "name") String name,
        @ToolParam(name = "label") String label,
        @ToolParam(name = "type") String type
) {
    var span = TRACER.spanBuilder("tool.create_app")
            .setAttribute("name", name)
            .setAttribute("type", type)
            .startSpan();
    try (var scope = span.makeCurrent()) {
        return Stage.call(Stage.TOOL_CALL, () -> {
            // 原逻辑
        });
    } catch (RuntimeException ex) {
        span.recordException(ex);
        throw ex;
    } finally {
        span.end();
    }
}
```

每个 `@Tool` 方法都重复这套 boilerplate 略繁，可抽 helper：

```java
public static <T> T withSpan(String name, java.util.function.Supplier<T> body,
                              Map<String, String> attrs) {
    var b = TRACER.spanBuilder(name);
    attrs.forEach(b::setAttribute);
    var span = b.startSpan();
    try (var s = span.makeCurrent()) { return body.get(); }
    catch (RuntimeException e) { span.recordException(e); throw e; }
    finally { span.end(); }
}
```

**路 B（可选，附加部署成本）：挂 javaagent**

下载 [opentelemetry-javaagent.jar](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases)，启动加 `-javaagent:opentelemetry-javaagent.jar`，此时 `@WithSpan` / `@SpanAttribute` 才会生效。课程不强求，留为加分项。

### 7.6 Jaeger UI 验证

浏览器开 `http://localhost:16686`：

1. **Service** 选 `agent-scope-app`
2. **Operation** 看到 `POST /agui/run`、`agent.call`、`tool.create_app` 等
3. 点 **Find Traces** 找一次刚才跑的请求
4. 展开 trace，应该看到树形：
   ```
   POST /agui/run                  830ms
   └─ agent.call                   820ms
      ├─ tool.create_app           20ms
      ├─ tool.create_module        15ms
      ├─ tool.create_model         50ms
      └─ submit_to_frontend        650ms
         ├─ frontend_dispatch.todo-1  ...
         └─ frontend_dispatch.todo-2  ...
   ```
5. 点任一 Span 看 Attributes，应有 `threadId`、`runId`、`toolName`

### 7.7 traceId 三处对得上

最关键的一步验证：**jq 日志、curl 响应头、Jaeger UI 三处 traceId 一致**——而且都是**全 32 位**，不再有"短 id 跟全 id 对不上"的体验断裂。

```bash
TID=$(curl -sN -X POST http://localhost:8080/agui/run \
    -H "Content-Type: application/json" \
    -D /tmp/h.txt \
    --max-time 30 \
    -d "$(cat fixtures/demo-input.json)" \
    > /dev/null && grep -i 'x-trace-id' /tmp/h.txt | awk '{print $2}' | tr -d '\r')

echo "全长 traceId: $TID  (长度=${#TID})"     # 应该是 32 位 hex

# jq 用全长精确匹配
echo "日志行数: $(jq -r --arg t "$TID" 'select(.traceId == $t)' logs/scope.json.log | wc -l)"

# Jaeger UI：直接拼 URL 打开（http://localhost:16686/trace/<32 位 traceId>）
echo "Jaeger 链接: http://localhost:16686/trace/$TID"
```

如果三处对不上，回头看 §7.4 —— 99% 是 `TraceIdFilter` 没读 OTel Span，传了 UUID 兜底值。

### ✅ Phase 3b 验收

- [ ] `docker ps` 看到 `jaeger` 容器运行（或 native binary 启动）
- [ ] Jaeger UI `agent-scope-app` 服务名可选
- [ ] 至少 1 条完整 trace（含 `agent.call` + `tool.*` 子 Span）
- [ ] curl 响应头 `X-Trace-Id` 是 32 位 hex 全长
- [ ] `jq` 用全长 `traceId` 精确匹配能搜到该 traceId 的日志行
- [ ] `http://localhost:16686/trace/<响应头 traceId>` 直接打开能看到 trace

---

## 8. Phase 3c · Hook 可观测 + Micrometer（45 min）

### 8.1 思路

AS-Java 的 Hook 体系（[../agents/10-observability-hitl.md](../agents/10-observability-hitl.md)）暴露 4 个生命周期事件：

| HookEvent 子类 | 触发点 |
|------|--------|
| `PreReasoningEvent` | LLM call 前 |
| `PostReasoningEvent` | LLM call 后（拿到响应） |
| `PreActingEvent` | 工具调用前 |
| `PostActingEvent` | 工具调用后 |

> ⚠️ 这些是 **HookEvent 的子类型**，不是独立的 Hook 接口——AS-Java 1.0.12 只有单一 `Hook` 接口，通过 `<T extends HookEvent> Mono<T> onEvent(T event)` 派发，详见 §8.3。

我们写一个 `ExecutionMetricsHook` 实现 `Hook` 接口，用 pattern matching 区分这 4 个事件，把数据喂给 Micrometer：

- **LLM 调用计数 + 延迟分布**（按 model 维度切片）
- **工具调用计数 + 延迟分布**（按 toolName + status 切片）
- **Prompt 长度直方图**（Day 1 的 `PromptLengthHook` 升级版）

> 📌 跟 Phase 3a 日志、Phase 3b OTel 的关系：日志答"发生了什么"、trace 答"慢在哪"、Micrometer 答"过去一小时怎么样"。三者不重叠。

### 8.2 加 Micrometer + Actuator 依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>

<!-- 可选：Prometheus 导出器（生产环境用） -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

`application.yml` 暴露端点：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    metrics:
      access: read_only
    prometheus:
      access: read_only
```

### 8.3 `ExecutionMetricsHook.java`

> ⚠️ **AS-Java 1.0.12 Hook 接口是单一的**（看 `io/agentscope/core/hook/Hook.class` 源码）：
> ```java
> public interface Hook {
>     <T extends HookEvent> Mono<T> onEvent(T event);
>     default int priority() { return 100; }
> }
> ```
> **没有** `PreReasoningHook` / `PostReasoningHook` / `PreActingHook` / `PostActingHook` 这些拆分接口。所有事件通过单一 `onEvent(HookEvent)` 派发，**用 Java 17 的 pattern matching 区分**。

各 Event 的真实 getter（看 jar 源码确认）：

| Event | 真实 getter |
|---|---|
| `PreReasoningEvent` | `getAgent() / getMemory() / getModelName() / getInputMessages() / getGenerateOptions()` |
| `PostReasoningEvent` | `getAgent() / getMemory() / getModelName() / getReasoningMessage() / stopAgent() / gotoReasoning()` 等（**没有** `getCompletionTokens` / `getFinishReason`，token 用量要从 `getReasoningMessage().getUsage()` 拿——前提是 model provider 在 `Msg` 上挂了 `Usage`） |
| `PreActingEvent` | `getAgent() / getToolkit() / getToolUse()`（`ToolUseBlock.getName() / getId() / getInput()`） |
| `PostActingEvent` | `getAgent() / getToolkit() / getToolUse() / getToolResult() / stopAgent() / isStopRequested()`（`ToolResultBlock.isSuspended() / getName() / getId()`） |

```java
package space.wlshow.scope.observability;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 AS-Java 的 Hook 事件汇成 Micrometer 指标。
 *
 * <p>注意：MeterRegistry 是线程安全的；Tag 必须低基数——不要把 traceId / userId / argHash 当 Tag。
 * <p>计时用 ConcurrentHashMap 配对 Pre*/Post* 事件，key 用 toolCallId（tool）或线程 ID（LLM）；
 * 严格说 LLM 的 reasoning 没有稳定 id，这里折中用 Agent 引用的 hashCode + 线程 ID 组合。
 */
@Component
public class ExecutionMetricsHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ExecutionMetricsHook.class);

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Long> llmStartNs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> toolStartNs = new ConcurrentHashMap<>();

    public ExecutionMetricsHook(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override public int priority() { return 50; }  // 跟 Day 1 的 PromptLengthHook 区分

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        // 单一 onEvent + pattern matching 派发——AS-Java 1.0.12 的官方推荐写法
        switch (event) {
            case PreReasoningEvent e -> onPreReasoning(e);
            case PostReasoningEvent e -> onPostReasoning(e);
            case PreActingEvent e -> onPreActing(e);
            case PostActingEvent e -> onPostActing(e);
            default -> { /* 其他事件（ReasoningChunk / SummaryChunk / Error 等）不感兴趣 */ }
        }
        return Mono.just(event);
    }

    // === Reasoning（LLM 调用） ===

    private String llmKey(PreReasoningEvent e) {
        // Pre/Post 配对：用 agent 实例 + 当前线程作 key（LLM call 是同步走完不切线程）
        return System.identityHashCode(e.getAgent()) + ":" + Thread.currentThread().getId();
    }
    private String llmKey(PostReasoningEvent e) {
        return System.identityHashCode(e.getAgent()) + ":" + Thread.currentThread().getId();
    }

    private void onPreReasoning(PreReasoningEvent e) {
        llmStartNs.put(llmKey(e), System.nanoTime());
        int promptChars = e.getInputMessages().stream()
                .flatMap(m -> m.getContent().stream())
                .mapToInt(b -> b.toString().length())
                .sum();
        registry.summary("scope.llm.prompt.chars", "model", e.getModelName())
                .record(promptChars);
        if (promptChars > 8000) {
            log.warn("[Metric] prompt 超长 {} chars (>8000) model={}", promptChars, e.getModelName());
        }
    }

    private void onPostReasoning(PostReasoningEvent e) {
        Long start = llmStartNs.remove(llmKey(e));
        if (start == null) return;
        long latencyNs = System.nanoTime() - start;
        Timer.builder("scope.llm.latency")
                .tag("model", e.getModelName())
                .register(registry)
                .record(Duration.ofNanos(latencyNs));
        // Token 用量：通过 e.getReasoningMessage().getUsage() 拿（如果你 jar 暴露了 Usage）；
        // 不同 provider 字段名不一致，留空白也行，prompt.chars summary 是稳定的替代
    }

    // === Acting（工具调用） ===

    private void onPreActing(PreActingEvent e) {
        ToolUseBlock use = e.getToolUse();
        if (use == null || use.getId() == null) return;
        toolStartNs.put(use.getId(), System.nanoTime());
    }

    private void onPostActing(PostActingEvent e) {
        ToolUseBlock use = e.getToolUse();
        ToolResultBlock result = e.getToolResult();
        if (use == null || use.getId() == null) return;
        Long start = toolStartNs.remove(use.getId());
        if (start == null) return;
        long latencyNs = System.nanoTime() - start;

        // 1.0.12 没有 ev.isSuccess()——通过 ToolResultBlock 的 isSuspended() 判定，
        // 失败/成功靠 result content 是否以 "ERROR" 前缀启动（我们自己的约定）
        String status;
        if (result == null) {
            status = "unknown";
        } else if (result.isSuspended()) {
            status = "suspended";
        } else {
            // ContentBlock 列表里第一个 text 块是约定的工具回执
            String first = result.toString();
            status = (first != null && first.startsWith("ERROR")) ? "failed" : "success";
        }

        String toolName = use.getName() != null ? use.getName() : "unknown";
        Timer.builder("scope.tool.latency")
                .tag("toolName", toolName)
                .tag("status", status)
                .register(registry)
                .record(Duration.ofNanos(latencyNs));
        Counter.builder("scope.tool.calls")
                .tag("toolName", toolName)
                .tag("status", status)
                .register(registry)
                .increment();
    }
}
```

> 📌 **PostReasoning 的 token usage**：1.0.12 的 `PostReasoningEvent.getReasoningMessage()` 返回 `Msg`，其 `getUsage()` 是否暴露要看具体 model provider。OpenAI 协议（火山方舟）通常返回 `prompt_tokens` / `completion_tokens`；DashScope 也有。如果你的 jar 里 `Msg.getUsage()` 不存在，把 token 那段去掉，留 `scope.llm.prompt.chars` 这一个 summary 已经足够 §8.5 的验收。
>
> 📌 **为什么不实现 4 个独立 Hook 接口**：jar 里没有。每个 Hook 实现都通过单一 `onEvent` 派发，pattern matching 是 1.0.12 官方示例写法（看 `Hook.java` javadoc 的 "Basic hook with default priority"）。

### 8.4 把 Hook 接入 Agent

1.0.12 的 `ReActAgent.Builder` 真实方法是 `.hook(Hook)` 或 `.hooks(List<Hook>)`（看 `ReActAgent.java` 1223 / 1238 行）。在 `AgentFactory.buildAnalystWithTools(...)` 里：

```java
public static ReActAgent buildAnalystWithTools(
        TodoManager todos, Memory memory, Hook... extraHooks) {
    initModels();
    var builder = ReActAgent.builder()
            // ... 已有的 name / sysPrompt / model / toolkit / memory / maxIters / hook(promptLengthHook) ...
            .hook(promptLengthHook);
    for (Hook h : extraHooks) builder.hook(h);
    return builder.build();
}
```

在 `AguiAgentConfig.buildForThread` 里注入：

```java
@Autowired ExecutionMetricsHook metricsHook;

private Agent buildForThread(String threadId) {
    FileSession session = activeSessions.computeIfAbsent(threadId, FileSession::loadOrNew);
    return AgentFactory.buildAnalystWithTools(session.todos, session.memory, metricsHook);
}
```

> 📌 **不是 `.addHook(...)`**——jar 里只有 `.hook(Hook)` 单数和 `.hooks(List<Hook>)` 复数。早期文档写 `addHook` 是想当然，按 jar 真实签名替换。
>
> 📌 **priority 冲突**：`ExecutionMetricsHook` priority=50，`PromptLengthHook`（Day 1）priority=50 也会冲突。把 `PromptLengthHook` 改 priority=60（数值越大越后跑，metrics 先记录原始 prompt 长度，再让 PromptLengthHook 做截断判断），或者直接把 PromptLengthHook 的逻辑搬进 ExecutionMetricsHook 的 `onPreReasoning` 合并掉。

### 8.5 验证 `/actuator/metrics`

跑一次完整剧本，然后：

```bash
# 列出所有自定义指标
curl -s http://localhost:8080/actuator/metrics | jq -r '.names[]' | grep '^scope\.'
# 期望：
# scope.llm.latency
# scope.llm.prompt.chars
# scope.llm.tokens
# scope.tool.calls
# scope.tool.latency

# 看工具调用计数（按 toolName 切片）
curl -s 'http://localhost:8080/actuator/metrics/scope.tool.calls?tag=toolName:create_app' | jq .
# 期望：
# {
#   "name": "scope.tool.calls",
#   "measurements": [{ "statistic": "COUNT", "value": 3.0 }],
#   "availableTags": [{ "tag": "status", "values": ["success"] }]
# }

# Prometheus 格式
curl -s http://localhost:8080/actuator/prometheus | grep '^scope_tool'
```

期望 prometheus 输出：

```
scope_tool_calls_total{toolName="create_app",status="success",} 3.0
scope_tool_calls_total{toolName="create_module",status="success",} 5.0
scope_tool_latency_seconds_count{toolName="create_app",status="success",} 3.0
scope_tool_latency_seconds_sum{toolName="create_app",status="success",} 0.045
```

### 8.6 三件套对照（Day 7 末尾的关键认知）

| 排查诉求 | 用什么 | 来自哪个 Phase |
|---------|--------|----------------|
| "刚才那次 t1 用户的会话怎么了？" | jq 日志 grep traceId | 3a |
| "刚才那次 t1 用户慢在哪？" | Jaeger UI 看 Span 树 | 3b |
| "过去 1 小时 create_model 平均多久？失败几次？" | `/actuator/metrics/scope.tool.latency` | 3c |
| "Prompt 是不是越来越长？" | `scope.llm.prompt.chars` summary | 3c |

三件套不是冗余，是分工。**任何一个少了，生产排障都瘸**。

### ✅ Phase 3c 验收

- [ ] `/actuator/metrics` 列出至少 5 个 `scope.*` 指标
- [ ] `/actuator/prometheus` 含 `scope_tool_calls_total`
- [ ] 故意触发 1 个工具失败，看到 `scope.tool.calls{status=failed}` 计数 +1
- [ ] Hook 的 priority 跟 Day 1 的 `PromptLengthHook` 不冲突（priority 不同）

---

## 9. Phase 4 · 异常剧本 E1-E8（60 min）

### 9.1 E1-E8 清单

| # | 场景 | 期望 |
|---|------|------|
| E1 | 输入"做个系统" | warnings/questions 非空，TodoManager 可能为空或仅 1 APP |
| E2 | LLM 错调工具 / 工具入参非法 JSON | 工具内 `Json.readList` 抛 → 转 `ERROR: ...` 让 LLM 自纠错（Day 7 主流程已是工具调度，`/parse` 走 RequirementParser 的纯文本 JSON 自纠错链路是 Day 3 的实现，本课不动） |
| E3 | LLM 工具入参 Schema 不合法 | 工具返回 ERROR:，TodoManager 不污染 |
| E4 | submit 时 dispatch 失败（模拟前端 500） | TodoItem→FAILED + errorMessage，STATE_DELTA 推到前端 |
| E5 | 用户连续追加 6 次需求 | Memory 不超 token 上限（Compaction 或截断） |
| E6 | 用户说"全删了" | TodoManager.clear()，STATE_DELTA 把数组清空 |
| E7 | 同 moduleId 重复创建 | 工具拒收（FrontendCreateTools 加去重）+ warning |
| E8 | 进程 kill 重启 | sessionId 恢复（FileSession） |

### 9.2 实现兜底

#### Dispatcher 三档实现（先把 dry-run 升级成真发 HTTP）

需求 #4 "前端下发" 在 Day 4-6 一直是 `DryRunDispatcher` no-op，**名不副实**。Day 7 把它正经做出来——但不要求接真业务后端，**用 WireMock 在 9082 端口拉一个 mock 后端足够**，让 dispatcher 真的发 HTTP，让 E4 失败剧本真的能触发 5xx。

```java
package space.wlshow.scope.dispatch;

public interface Dispatcher {
    /** 抛异常表示下发失败，外层会 markFailed。 */
    void dispatch(TodoItem item) throws Exception;
}
```

**三档实现**：

**① `DryRunDispatcher`（默认 profile，最快）**

```java
package space.wlshow.scope.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "scope.dispatcher.mode", havingValue = "dry-run", matchIfMissing = true)
public class DryRunDispatcher implements Dispatcher {
    private static final Logger log = LoggerFactory.getLogger(DryRunDispatcher.class);
    @Override
    public void dispatch(TodoItem item) {
        log.info("[Dispatch] DRY-RUN id={} type={} target={}",
                item.id(), item.type(), item.targetName());
    }
}
```

**② `HttpDispatcher`（profile=http，真发 HTTP）**

```java
package space.wlshow.scope.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;
import space.wlshow.scope.observability.Stage;
import space.wlshow.scope.todo.TodoItem;

import java.time.Duration;

@Component
@ConditionalOnProperty(name = "scope.dispatcher.mode", havingValue = "http")
public class HttpDispatcher implements Dispatcher {

    private static final Logger log = LoggerFactory.getLogger(HttpDispatcher.class);
    private final WebClient client;

    public HttpDispatcher(@Value("${scope.dispatcher.baseUrl:http://localhost:9082}") String baseUrl) {
        // 关键：connect/response 各 3 秒超时——E4 测 timeout 时不要等 30s
        HttpClient netty = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                .responseTimeout(Duration.ofSeconds(3));
        this.client = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(netty))
                .build();
        log.info("[Dispatch] HTTP mode, baseUrl={}", baseUrl);
    }

    @Override
    public void dispatch(TodoItem item) throws Exception {
        String endpoint = endpointOf(item.type());     // /api/create_app / create_module / create_model
        JsonNode payload = item.payload();
        Stage.run(Stage.FRONTEND_DISPATCH, () ->
                log.info("[Dispatch] POST {} id={} size={}",
                        endpoint, item.id(), payload.toString().length()));
        try {
            String resp = client.post()
                    .uri(endpoint)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(5));
            log.info("[Dispatch] OK id={} resp.len={}", item.id(),
                    resp == null ? 0 : resp.length());
        } catch (WebClientResponseException e) {
            // 4xx / 5xx：保留 status + body，给 SubmitTool 拼 PARTIAL_FAILURE 时用上
            throw new DispatchException(e.getStatusCode().value(),
                    e.getResponseBodyAsString(), e);
        }
    }

    private static String endpointOf(TodoType t) {
        return switch (t) {
            case CREATE_APP    -> "/api/create_app";
            case CREATE_MODULE -> "/api/create_module";
            case CREATE_MODEL  -> "/api/create_model";
        };
    }
}

/** 携带 HTTP 状态码的下发异常——LLM 拿到 PARTIAL_FAILURE 时按状态码决定重试策略。 */
public class DispatchException extends Exception {
    public final int statusCode;
    public final String responseBody;
    public DispatchException(int status, String body, Throwable cause) {
        super("HTTP " + status + ": " + (body == null ? "" : body.substring(0, Math.min(200, body.length()))), cause);
        this.statusCode = status;
        this.responseBody = body;
    }
}
```

**③ `WireMockBackend`（测试时启动 mock 后端，让 HttpDispatcher 有的可打）**

```java
package space.wlshow.scope.dispatch;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.extension.responsetemplating.ResponseTemplateTransformer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/** Day 7 demo 用的 mock 业务后端，9082 端口接 3 个 create 接口。 */
@Component
@ConditionalOnProperty(name = "scope.dispatcher.mockBackend", havingValue = "true")
public class WireMockBackend {

    private static final Logger log = LoggerFactory.getLogger(WireMockBackend.class);
    private WireMockServer server;

    @PostConstruct
    public void start() {
        // 关键：必须显式启用 response templating，否则 {{randomValue}} 占位符不会被替换
        server = new WireMockServer(options()
                .port(9082)
                .extensions(new ResponseTemplateTransformer(true)));
        server.start();
        installDefaultStubs();
        log.info("[MockBackend] started on port 9082, 3 endpoints stubbed");
    }

    private void installDefaultStubs() {
        // 3 个接口全部 200 + 回 {"id":"be-<随机 UUID>","status":"created"}
        // 注意：WireMock 用 Handlebars 语法 {{randomValue type='UUID'}}，不是 ${random}
        for (String path : new String[]{"/api/create_app", "/api/create_module", "/api/create_model"}) {
            server.stubFor(post(urlEqualTo(path))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"id\":\"be-{{randomValue type='UUID'}}\",\"status\":\"created\"}")
                            .withTransformers("response-template")));
        }
    }

    @PreDestroy
    public void stop() {
        if (server != null) server.stop();
    }

    /** 测试里临时改变 stub 行为：让某个 endpoint 失败一次（用高优先级覆盖默认 200）。 */
    public void simulateFailure(String endpoint, int status, String body) {
        server.stubFor(post(urlEqualTo(endpoint))
                .atPriority(1)
                .willReturn(aResponse().withStatus(status).withBody(body)));
    }

    /** 测试里数：本 mock 后端被打了几次（任意 path）。 */
    public int getRequestCount() {
        List<LoggedRequest> reqs = server.findAll(postRequestedFor(urlMatching("/api/.*")));
        return reqs.size();
    }

    public void reset() {
        server.resetAll();
        installDefaultStubs();   // 重新装默认 200 stub
    }
}
```

**`application.yml` 配置切档**：

```yaml
scope:
  dispatcher:
    mode: dry-run            # dry-run（默认 no-op）/ http（真发 9082）
    baseUrl: http://localhost:9082
    mockBackend: false       # 设 true 启动内嵌 WireMock 后端
```

**启动剧本**：

```bash
# A. 学员第一次跑（什么都不配）→ DryRunDispatcher，最快
mvn spring-boot:run

# B. 想看真 HTTP 走通 → http + 内嵌 mock 后端
mvn spring-boot:run \
    -Dspring-boot.run.arguments="--scope.dispatcher.mode=http --scope.dispatcher.mockBackend=true"

# C. 对接真业务后端 → http + 指定外部 baseUrl
mvn spring-boot:run \
    -Dspring-boot.run.arguments="--scope.dispatcher.mode=http --scope.dispatcher.baseUrl=http://your-backend:8090"
```

跑 B 之后浏览器输入"做一个员工档案管理" + 确认下发，**WireMock 后端日志会真打到 9082**，DevTools / 日志能看到 3 次 `POST /api/create_*`。这就是需求 #4 名副其实的状态。

> 📌 **为什么内嵌 mock 而不是另开 docker**：学员第一次跑就要弄 docker 心智负担太重；用 WireMock 内嵌 9082，**跟 Spring Boot 同一进程同一终端**，启停跟主进程一起走，不会出现"主进程跑起来了但忘了启 mock 后端" 的尴尬。

#### E4：模拟 dispatch 失败

`SubmitTool` 用构造函数注入 `Dispatcher`（接口已在上面定义），`@Tool` 的 `confirmed=true` 分支调用：

```java
// SubmitTool
private final Dispatcher dispatcher;

public SubmitTool(TodoManager todos, Dispatcher dispatcher) {
    this.todos = todos;
    this.dispatcher = dispatcher;
}

// confirmed==true 分支
List<String> failedIds = new ArrayList<>();
StringBuilder report = new StringBuilder();
for (TodoItem it : pending) {
    todos.markRunning(it.id());
    long start = System.currentTimeMillis();
    try {
        dispatcher.dispatch(it);
        todos.markSuccess(it.id());
        report.append("OK ").append(it.id()).append('\n');
    } catch (Exception e) {
        todos.markFailed(it.id(), e.getMessage());
        failedIds.add(it.id());
        report.append("FAIL ").append(it.id())
              .append(" reason=").append(e.getMessage()).append('\n');
    }
}

// 关键：把失败信息以"结构化的"方式回执给 LLM，让它闭环判断
// 而不是只 markFailed 就完事 — 那样 Agent 不知道发生过失败
if (!failedIds.isEmpty()) {
    return "PARTIAL_FAILURE\n" + report +
           "\n以上 " + failedIds.size() + " 项失败，可继续：\n" +
           "- 调用 retry_failed(ids=[...]) 触发重试\n" +
           "- 调用 update_module/update_model 改参数后再 submit\n" +
           "- 报告用户后由用户决定";
}
return "ALL_OK\n" + report;
```

> 📌 **核心闭环点**：把失败原文（错误消息 + 哪几个 id）拼成 LLM **可读的指令性回执**，借助 prompt 里的"failure-handling"规则让 Agent 自主选下一步动作。这一段是原 Day 6 异常处理收口的"前端回调失败 → 回填 ToolResult 让 Agent 判断重试"的真正落地。

配套地，给 `Toolkit` 加一个 `retry_failed` 工具，让 Agent 有可调用的"重试动作"：

```java
@Tool(name = "retry_failed",
      description = "对处于 FAILED 状态的 todo 触发一次重发。" +
                    "ids 为空时重试所有 FAILED。" +
                    "重试**不会**复用失败原因；如果失败是参数错，先用 update_* 改参数再 retry。")
public String retryFailed(@ToolParam(name = "ids",
        description = "JSON 数组字符串，如 [\"todo-3\",\"todo-5\"]；为空表示全部") String idsJson) {
    List<String> wanted = (idsJson == null || idsJson.isBlank())
            ? todos.snapshot().stream()
                .filter(it -> it.status() == TodoStatus.FAILED)
                .map(TodoItem::id).toList()
            : Json.readList(idsJson, String.class);

    StringBuilder rpt = new StringBuilder();
    for (String id : wanted) {
        TodoItem cur = todos.get(id);
        if (cur.status() != TodoStatus.FAILED) {
            rpt.append("SKIP ").append(id).append(" not FAILED\n"); continue;
        }
        // 状态机不允许 FAILED→RUNNING，所以我们用"创建一个新 PENDING TodoItem，复用 payload"
        // 的策略，原 item 留作审计记录
        TodoItem newPending = todos.add(cur.type(), cur.targetName(), cur.payload());
        try {
            todos.markRunning(newPending.id());
            dispatcher.dispatch(newPending);
            todos.markSuccess(newPending.id());
            rpt.append("RETRY-OK ").append(id).append("->").append(newPending.id()).append('\n');
        } catch (Exception e) {
            todos.markFailed(newPending.id(), e.getMessage());
            rpt.append("RETRY-FAIL ").append(newPending.id())
               .append(" reason=").append(e.getMessage()).append('\n');
        }
    }
    return rpt.toString();
}
```

#### E4 的系统 prompt 补丁

`analyst-multi-round.md` 末尾追加：

```markdown
# 部分失败处理
- 当 submit_to_frontend 返回 `PARTIAL_FAILURE`，按以下顺序判断：
  1. 失败原因里包含 "timeout" / "5xx" / "connection" → 视为瞬时错误，**直接调 retry_failed(ids=[失败id])**
  2. 失败原因里包含 "409 conflict" / "duplicate" / "already exists" → **不要重试**，告诉用户"已存在"
  3. 失败原因里包含 "400" / "validation" / "schema" → 用 update_module/update_model 改参数后再 submit_to_frontend(confirmed=true)
  4. 其他不明错误 → 报告用户，等用户决定
- 严禁连续重试同一 id 超过 2 次（防雪崩）
```

#### E4 集成测试断言（含闭环验证）

测试启动时**同时**用 `http + mockBackend=true` profile，让 HttpDispatcher 真打到内嵌 WireMock，然后用 `WireMockBackend.simulateFailure` 让第一次 POST 返回 504：

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {
                    "scope.dispatcher.mode=http",
                    "scope.dispatcher.mockBackend=true",
                    "scope.dispatcher.baseUrl=http://localhost:9082"
                })
class E4DispatchFailureTest {

    @Autowired WireMockBackend mockBackend;
    @LocalServerPort int port;

    @Test
    void dispatchFailure_agentRetriesViaToolCall() throws Exception {
        // 安排第一次 /api/create_app 504 → HttpDispatcher 抛 DispatchException(504, "...")
        mockBackend.simulateFailure("/api/create_app", 504,
                "{\"error\":\"upstream timeout\"}");

        // 录制 LLM mock：收到 PARTIAL_FAILURE 后调 retry_failed(ids=[...])
        wireMockLlmResponseRetry();

        // 跑剧本（用 WebClient 调 /agui/run 收事件）—— collectEvents 见 §9.3
        List<AguiEvent> events = collectEvents("做一个员工档案管理\n然后提交");

        // 关键断言（AguiEvent 是 sealed interface，用 pattern matching 取字段）
        // 1. 至少一个 TOOL_CALL_RESULT 内容含 "PARTIAL_FAILURE" + "504"
        assertTrue(events.stream()
                .filter(AguiEvent.ToolCallResult.class::isInstance)
                .map(AguiEvent.ToolCallResult.class::cast)
                .anyMatch(e -> e.content() != null
                        && e.content().contains("PARTIAL_FAILURE")
                        && e.content().contains("504")));
        // 2. LLM 收到失败后又调了 retry_failed 工具
        assertTrue(events.stream()
                .filter(AguiEvent.ToolCallStart.class::isInstance)
                .map(AguiEvent.ToolCallStart.class::cast)
                .anyMatch(e -> "retry_failed".equals(e.toolCallName())));
        // 3. WireMock 后端被打了 ≥4 次：3 次首发 + 1 次重试
        //    （注意先断言再 reset，否则计数清零）
        assertTrue(mockBackend.getRequestCount() >= 4);
        // 4. 最终至少一项 todo 是 SUCCESS（含重试出来的新 id）
        assertTrue(todoManager.snapshot().stream()
                .anyMatch(it -> it.status() == TodoStatus.SUCCESS));
    }
}
```

> 📌 这个测试**比原来的 Mockito 写法可靠**：原来 mock 的是 Java 接口，跑不出"真 HTTP 调用、网络层超时、JSON 序列化失败"这些**只有真打才暴露**的问题。改成 WireMock 后端后，HttpDispatcher 的 Netty 超时、序列化、状态码解析都顺带验了。

#### E5：Memory 截断

简单版本：在 `AguiAgentConfig.buildForThread` 里，构造 Agent 前对 Memory 截断。AS-Java 1.0.12 `Memory` 接口真实方法是 `getMessages() / addMessage(Msg) / clear() / deleteMessage(int)`，**没有** `size()` / `snapshot()` / `compactTo()`，要自己组合：

```java
private Agent buildForThread(String threadId) {
    FileSession session = activeSessions.computeIfAbsent(threadId, FileSession::loadOrNew);
    Memory memory = session.memory;
    var all = memory.getMessages();
    if (all.size() > 40) {
        // 保留最近 20 条；如果 Day 5 把 system prompt 也塞进了 memory，
        // 用 stream + filter 单独把 role=system 那条留住
        var recent = new java.util.ArrayList<>(all.subList(all.size() - 20, all.size()));
        memory.clear();
        recent.forEach(memory::addMessage);
        log.info("[Memory] truncated thread={} keep=20 (was {})", threadId, all.size());
    }
    return AgentFactory.buildAnalystWithTools(session.todos, memory, metricsHook);
}
```

> ⚠️ E5 的剧本是"连续追加 6 次需求"——6 轮远不到 40 的阈值，**不会**触发截断。要真正验证截断，请把阈值临时改成 `> 8` 跑一遍，或者把测试剧本扩到 50+ 轮。本课程剧本只验"逻辑挂上去了不抛异常"，量化的截断行为留到 Day 7 加分项 Harness Compaction（附录 B）。

#### E6：用户说"全删了"

加个工具 `clear_all`：

```java
@Tool(name = "clear_all", description = "清空所有待办。用户明确说'全删了/清空/重来'时使用。")
public String clearAll() {
    int n = todos.size();
    todos.clear();
    return "已清空 " + n + " 项";
}
```

system prompt 加一句"用户明确说清空时调 clear_all，不要默认调"。

#### E7：moduleId 去重

`FrontendCreateTools.createModule` 入口加：

```java
boolean dup = todos.snapshot().stream()
    .filter(it -> it.type() == TodoType.CREATE_MODULE)
    .anyMatch(it -> moduleId.equals(it.payload().path("moduleId").asText()));
if (dup) return "ERROR: moduleId 已存在: " + moduleId + "（如需修改请用 update_module）";
```

`create_model` 同理。

### 9.3 集成测试 `ScenarioTest`

`src/test/java/space/wlshow/scope/scenario/E2ETest.java`：

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "logging.level.io.agentscope=WARN",
    "scope.dispatcher.mode=http",
    "scope.dispatcher.mockBackend=true",
    "scope.dispatcher.baseUrl=http://localhost:9082"
})
class E2ETest {

    @LocalServerPort int port;
    private WebClient web;

    @BeforeEach
    void setUp() {
        web = WebClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** SSE 收集 helper：把一次 /agui/run 的所有 AguiEvent 拉成 List。 */
    private List<AguiEvent> collectEvents(String userText) {
        var input = """
                {"threadId":"%s","runId":"%s",
                 "messages":[{"id":"m1","role":"user","content":"%s"}]}
                """.formatted(
                    "t-" + System.nanoTime(),
                    "r-" + System.nanoTime(),
                    userText.replace("\"", "\\\""));
        return web.post().uri("/agui/run")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<AguiEvent>>() {})
                .map(ServerSentEvent::data)
                .filter(Objects::nonNull)
                .collectList()
                .block(Duration.ofSeconds(60));
    }

    @Test
    void e1_vagueInput_putsQuestions() {
        var events = collectEvents("做个系统");
        // pattern matching 取 TextMessageContent 的 delta
        var hasQuestion = events.stream()
                .filter(AguiEvent.TextMessageContent.class::isInstance)
                .map(AguiEvent.TextMessageContent.class::cast)
                .anyMatch(e -> e.delta() != null && e.delta().contains("?"));
        assertTrue(hasQuestion);
    }

    @Test
    void e7_duplicateModuleId_rejected() {
        // 第一次创建 employeeMgmt
        collectEvents("做一个员工档案管理");
        // 第二次再创建同 moduleId（依赖 LLM 实际复用名字，
        // 可控的做法是 wireMock stub 两次 LLM 响应；这里仅示意）
        var events = collectEvents("再加一个 moduleId 也叫 employeeMgmt 的请假模块");
        var hasReject = events.stream()
                .filter(AguiEvent.ToolCallResult.class::isInstance)
                .map(AguiEvent.ToolCallResult.class::cast)
                .anyMatch(e -> e.content() != null && e.content().contains("ERROR: moduleId 已存在"));
        assertTrue(hasReject);
    }

    // ... E2/E3/E4/E5/E6/E8 见 §9.2 E4 集成测试模板
}
```

> 📌 把 8 个全跑完是个工程活，建议优先级 E1 / E4 / E7 / E8 必跑，E2 / E3 / E5 / E6 写完保留 `@Disabled` 也行（在 README 标明）。
> 📌 E2/E3 这种依赖 LLM 具体行为的测试不要直接打真实模型——用 WireMock 模拟 ARK / DashScope，stub 一份 `{"choices":[{"message":{"tool_calls":[...]}}]}` 让 LLM "决策"可控。具体 stub 形态参考 Day 3 的 `wiremock/__files/analyst-*.json`。

### 9.4 跑测试

```bash
mvn -q test
mvn -q test -Dgroups=integration
```

### ✅ Phase 4 验收

- [ ] 至少 E1 / E4 / E7 / E8 测试通过
- [ ] `mvn test` 不联网全绿（WireMock 兜底）
- [ ] E5 的 Memory 截断本地手动验证：连续追加 6 轮看 token 不爆

---

## 10. Phase 5 · 需求打勾 + 演示视频（45 min）

### 10.1 14 项需求逐项打勾

把 `learning.md` Day 7 表格复制到 README 里，对每一项打勾并附"在哪验证"：

```markdown
| # | 需求 | 兑现位置 | 验证方式 |
|---|------|---------|---------|
| 1 | 用户输入需求 | Vue3 `<textarea>` → `/agui/run` | 浏览器输入 "做一个员工档案管理" |
| 2 | 解析为 App/Module/Model | FrontendCreateTools + AgentFactory | 看右侧看板出现 1 APP + N Module + N Model |
| 3 | 列出待办列表 | TodoManager + STATE_SNAPSHOT | 右侧看板 |
| 4 | 前端下发 | SubmitTool + HttpDispatcher → 业务后端（dev 用内嵌 WireMock 9082）| 弹窗确认后看板状态切到 SUCCESS；WireMock 后端日志看到 POST /api/create_* |
| 5 | 状态管理 | TodoStatus 状态机 + STATE_DELTA | 看板颜色实时变 |
| 6 | 异常 warnings/questions | analyst-multi-round prompt + warning 字段 | 输入"做个系统"看 LLM 反问 |
| 7 | JSON Schema 校验 | SchemaValidator 在 tool 入参兜底 | E3 测试 |
| 8 | 多轮增量 | list_todos / update_module / update_model | 第二轮"加部门字段"不会重建 |
| 9 | 结果确认 | submit_to_frontend + AG-UI HITL | 浏览器弹窗 |
| 10 | 日志 | logback JSON + MDC traceId | jq 过 traceId |
| 11 | 模块结构 | ModuleSpec | Day 2 |
| 12 | 模型结构 | DataModelSpec + FieldSpec | Day 2 |
| 13 | 应用结构 | AppSpec | Day 2 |
| 14 | AG-UI 协议合规 | starter `/agui/run` + 17 EventType + Vue3 客户端 | curl 抓事件 |
```

### 10.2 演示视频脚本

3 分钟，建议分镜：

| 时间 | 内容 |
|------|------|
| 0:00-0:15 | 打开 README，介绍项目名 + 一句话定位 |
| 0:15-0:30 | `mvn spring-boot:run -Dspring-boot.run.arguments="--scope.dispatcher.mode=http --scope.dispatcher.mockBackend=true"` 起后端 + `npm run dev` 起前端 |
| 0:30-1:00 | 浏览器输入 "做一个简单请假管理" → 右侧看板出 3 项 PENDING（旁路 SSE）+ 主流流式回复 |
| 1:00-1:30 | 输入 "加一个出库审批模块" → 增量补待办（看 DevTools Network 看到两条 SSE） |
| 1:30-2:00 | 输入 "提交" → 弹窗 → 确认 → 看板 PENDING→RUNNING→SUCCESS + 终端看到 9082 收到 POST |
| 2:00-2:30 | 切到 Jaeger UI（http://localhost:16686），找到刚才的 trace，展开 Span 树 |
| 2:30-3:00 | 终端 `jq` 过 traceId 还原会话 + 浏览器看 `/actuator/metrics/scope.tool.calls` + 对照 README 14 项打勾 |

录屏工具：Win+G（Game Bar）输出 mp4，用 https://ezgif.com 转 GIF 或上 B 站。

存到 `docs/screenshots/day7-demo.mp4`（或链接）。

### ✅ Phase 5 验收

- [ ] 14 项全勾
- [ ] 演示视频长度 ≈ 3 分钟，可见全部 4 个关键场景

---

## 11. Phase 6 · README + 架构图 + commit（45 min）

### 11.1 架构图

用 https://excalidraw.com 画一张，导出 PNG 存 `docs/screenshots/architecture.png`。

需要画的节点：

```
[ Vue3 / Vite ]
   │
   ├──SSE (HttpAgent)── POST /agui/run ─────────┐
   │                                            ▼
   └──SSE (EventSource)── GET /agui/state-stream/{threadId} ─┐
                                                             ▼
                                       [ Spring Boot WebFlux ]
                           │
                           ├─ agentscope-agui-spring-boot-starter
                           │     └─ POST /agui/run (17 EventType 主流)
                           │
                           ├─ StateStreamController（Day 7 新增）
                           │     └─ GET /agui/state-stream/{threadId} 旁路流
                           │           (STATE_SNAPSHOT / STATE_DELTA)
                           │
                           ├─ AguiAgentConfig（覆盖 ThreadSessionManager 子类）
                           │     ├─ per-threadId Agent 工厂 + FileSession 复用
                           │     └─ touchThread 挂 AguiStateBridge 到 TodoManager
                           │
                           ├─ ReActAgent (RequirementAnalyst)
                           │     ├─ Memory (InMemoryMemory，>40 轮自截断)
                           │     ├─ Toolkit (parallel)
                           │     │   ├─ FrontendCreateTools / TodoQueryTools / TodoUpdateTools
                           │     │   ├─ SubmitTool (ToolSuspendException → AWAITING_USER_CONFIRMATION)
                           │     │   └─ retry_failed
                           │     ├─ Hooks (单 Hook 接口 + pattern matching)
                           │     │   ├─ PromptLengthHook (Day 1)
                           │     │   └─ ExecutionMetricsHook ──→ Micrometer
                           │     └─ Model (OpenAIChatModel · 火山方舟 / DashScope)
                           │
                           ├─ TodoManager (StateModule)
                           │     └─ AguiStateBridge (TodoChangeListener)
                           │           └─ Sinks.Many<AguiEvent>
                           │                 ↑（被 StateStreamController 订阅推到前端）
                           │
                           ├─ Dispatcher（三档：DryRun / Http / Http+WireMock 9082）
                           │
                           ├─ FileSession (data/sessions/<id>.json)
                           │
                           ├─ 可观测三件套
                           │   ├─ 日志：Logback JSON + MDC traceId
                           │   │        ──→ logs/scope.json.log
                           │   ├─ 追踪：OTel SDK + OTLP
                           │   │        ──→ Jaeger (localhost:16686)
                           │   └─ 指标：Micrometer + Prometheus exporter
                           │            ──→ /actuator/metrics, /actuator/prometheus
                           │
                           └─ TraceIdFilter（traceId 三处对得上的关键）
```

### 11.2 README 改写

完整结构：

```markdown
# agent-scope-app

> 基于 AgentScope-Java 的需求分析智能体。
> 输入中文业务需求，输出 App / Module / DataModel 三层结构，
> 经 AG-UI 协议实时推送给前端，HITL 确认后下发。

## 架构

![架构图](docs/screenshots/architecture.png)

## 快速开始

### 后端

```bash
export ARK_API_KEY=...
mvn spring-boot:run
```

### 前端

```bash
cd frontend
npm install
npm run dev
# 浏览器 http://localhost:5173
```

## 演示

![演示](docs/screenshots/day7-demo.gif)

## 14 项需求清单

（粘 §10.1 的需求兑现表格）

## 7 天学习路线

- [Day 1](docs/lessons/Day01...)
- [Day 2](docs/lessons/Day02...)
- ...
- [Day 7](docs/lessons/Day07_AG-UI%20协议进阶%20%2B%20收尾验收.md)

## 已知限制

- Memory 没接 Harness Compaction，超 40 轮可能撞 token 上限
- FileSession 是文件持久化，并发写有竞态（多 thread 同 sessionId 时）
- STATE_* 走旁路 SSE（`/agui/state-stream/{threadId}`），不在 starter 的 `/agui/run` 标准事件流里——升级到支持 emitter 的 starter 后可以合并
- HttpDispatcher 默认打内嵌 WireMock 9082；真接业务后端只需 `scope.dispatcher.baseUrl=http://...` 覆盖
- runId 没透到日志 MDC（starter 1.0.12 没暴露入口），trace 那一层由 Jaeger Span ID 兜底
```

### 11.3 commit

**关键：先把 Day 1 落地的 `logback.xml` 删掉**（Day 7 §6.2 改名为 `logback-spring.xml`，原文件留着会导致 logback / Spring Boot 双加载）：

```bash
git rm src/main/resources/logback.xml
```

然后按"Day 7 真正新增 / 修改"的范围添加：

```bash
# 新增
git add src/main/java/space/wlshow/scope/agui/AguiStateBridge.java \
        src/main/java/space/wlshow/scope/agui/StateStreamController.java \
        src/main/java/space/wlshow/scope/observability/ \
        src/main/java/space/wlshow/scope/dispatch/ \
        src/main/resources/logback-spring.xml \
        src/test/java/space/wlshow/scope/scenario/

# 修改（Day 6 已落地，本次改动）
git add src/main/java/space/wlshow/scope/config/AguiAgentConfig.java \
        src/main/java/space/wlshow/scope/agent/AgentFactory.java \
        src/main/java/space/wlshow/scope/tool/FrontendCreateTools.java \
        src/main/java/space/wlshow/scope/tool/SubmitTool.java \
        src/main/java/space/wlshow/scope/todo/TodoManager.java \
        src/main/resources/application.yml \
        frontend/src/App.vue \
        pom.xml \
        README.md

# 文档与素材
git add docs/screenshots/architecture.png \
        docs/screenshots/day7-demo.mp4

git commit -m "day7: AG-UI 协议进阶 + 可观测三件套 + 收尾验收

- AguiStateBridge + StateStreamController：旁路 SSE 推送 STATE_SNAPSHOT/STATE_DELTA
- HITL on AG-UI：前端识别 AWAITING_USER_CONFIRMATION 前缀弹窗，
                role=tool 回填续跑（替换 Day 5 CLI 路径）
- Phase 3a 日志：logback-spring.xml + LogstashEncoder + TraceIdFilter +
                Stage helper + 7 个关键日志点
- Phase 3b OTel：opentelemetry-spring-boot-starter 2.10 + Jaeger 本地容器，
                TraceIdFilter 读 Span.current() 让 traceId 三处对得上
- Phase 3c Hook：ExecutionMetricsHook（单 Hook 接口 + pattern matching）
                + Micrometer + Actuator/Prometheus 端点
- Dispatcher 三档：DryRun / Http / 内嵌 WireMock 后端（9082）
- 8 个异常剧本 E1-E8（核心 4 个测试通过，余 4 个 @Disabled）
- Vue3 右侧 Todo 看板 + 确认弹窗 + 原生 EventSource 旁路订阅
- README 14 项需求逐项打勾 + 架构图 + 3 分钟演示"
```

### 11.4 收尾自检（最后 5 分钟）

- [ ] `mvn clean test` 全绿
- [ ] `docker ps` 看到 `jaeger` 容器，Jaeger UI 至少 1 条 trace
- [ ] `mvn spring-boot:run` + `npm run dev` 双进程能跑完整剧本
- [ ] `logs/scope.json.log` 行数 > 0，jq 解析合法，**7 个 stage 全有**
- [ ] `curl http://localhost:8080/actuator/metrics | jq -r '.names[]' | grep ^scope` 列出 ≥ 5 项
- [ ] `git log --oneline` 7 个 dayN: ... 都在
- [ ] README 架构图能打开
- [ ] 14 项全勾

### ✅ Phase 6 验收

- [ ] 全部上面 6 项勾完
- [ ] 把 README + 截图发给至少 1 个同事，能在 5 分钟内看懂项目在干嘛

---

## 12. 故障排查表

| 现象 | 原因 / 排查 |
|------|-----------|
| 前端没收到 STATE_SNAPSHOT | `EventSource` 没连上 `/agui/state-stream/{threadId}`；或 `touchThread` 没在 SSE 端点入口调；或 bridge 已挂但 `snapshotNow()` 没在订阅时重发 |
| STATE_DELTA 前后端字段对不上 | 服务端用 `/todos/id=<id>/status` 非标 path；前端必须用 §4.5 的 `applyOps` 解析（不能直接 `fast-json-patch.applyPatch`） |
| HITL 弹窗不出 | 你监听了 `onToolCallEndEvent` 而不是 `onToolCallResultEvent`；end 事件不带 content。AS-Java 1.0.12 把 ToolSuspend 转成 `TOOL_CALL_END + TOOL_CALL_RESULT`，识别要看 result content 前缀 |
| 点确认后 Agent 不续跑（前端 `newMessages=[]`、后端无新 `[Submit]` 日志） | starter 1.0.12 的 `AguiRequestProcessor.extractLatestUserMessage` 在 `server-side-memory=true` 下只挑 `role="user"`，前端推的 `role:'tool'` 被静默吃掉；改 `server-side-memory=false` 又让 `DefaultAgentResolver` 拿不到 threadId。**正解**走 §5.5 自家 RouterFunction + `ThreadAgentResolver(hasMemory()=false)`。检查启动日志有 `[AguiConfig] mounting custom AGUI run route at /agui/run`，续跑应见第二次 `[AguiConfig] build agent for thread=...` |
| 看板少 todo / 工具偶发 `Error: Tool execution failed: Spec. Rule` | `Toolkit(parallel=true)` 让 3 个 `create_*` 并发调进 `Sinks.many().multicast().tryEmitNext`，返回 `FAIL_NON_SERIALIZED`；老版 `AguiStateBridge.emit()` 的 `emitNext(handler=false)` retry 抛 `Sinks.EmissionException` 把工具炸成 framework error，TodoManager 写进去了但工具回 ERROR 给 LLM。**已修**：`emit()` 加 `synchronized` + 失败仅 log.warn 不抛 |
| TraceId 在工具日志里丢了 | MDC 在 Reactor worker 线程不透传；Phase 3b 的 OTel `logback-mdc-1.0` 桥（默认 key=`trace_id`/`span_id`）+ TraceIdFilter 读 Span 的组合解决 |
| logs/scope.json.log 中文乱码 | encoder 没指定 UTF-8；LogstashEncoder 默认 UTF-8，看终端 / IDE 显示设置 |
| jq 日志 traceId 跟 Jaeger trace 对不上 | TraceIdFilter 没读 OTel Span，用了 UUID 兜底——看 §7.4 整段替换 |
| Jaeger UI 一片空白 | OTLP exporter 没连上：检查 `otel.exporter.otlp.endpoint`；端口 4317 防火墙；docker 在 wsl2 上时用 `host.docker.internal` |
| `agent.call` Span 没有子 Span | AS-Java 内部 OkHttp 没接 OTel instrumentation；启动加 `-javaagent:opentelemetry-javaagent.jar` 即可自动 instrument |
| `@WithSpan` 注解没产生 Span | 没挂 javaagent。注解模式需要字节码 instrumentation；本课走"手写 Tracer.spanBuilder" 路 A，不依赖 agent |
| `/actuator/metrics` 空 | `management.endpoints.web.exposure.include` 没配 `metrics`；Spring Boot 3 默认只开 `health`/`info` |
| `scope.*` 指标都不出现 | `ExecutionMetricsHook` 没用 `.hook(metricsHook)` 注入 Agent；或 Hook 的 `onEvent` pattern matching 没匹配到任何分支 |
| `scope.tool.calls` 计数偏多 | Toolkit `parallel(true)` 下同一工具被多线程并发记多次 — 实际就是这么多次调用，正常 |
| Prometheus exporter 报 "high cardinality" 警告 | 你把 traceId / userId 放进 Tag 了；只用低基数（toolName / status / model） |
| E4 测试 WireMock 9082 端口被占 | 上次 Spring Boot 退出时 mockBackend 没释放——检查 `WireMockBackend.@PreDestroy stop()` 是否生效；或换 `options().dynamicPort()` 让 WireMock 自选 |
| `{{randomValue type='UUID'}}` 原样返回没替换 | `WireMockBackend.start()` 没加 `.extensions(new ResponseTemplateTransformer(true))`；并且 stub 必须 `.withTransformers("response-template")` |
| `mockBackend.getRequestCount()` 编译失败 | WireMockServer 没有这方法，是我们在 `WireMockBackend` 里自己加的 helper（用 `server.findAll(...)` 实现）|
| starter 启动失败 "AguiAgentConfig 找不到 StateStreamController" | 循环依赖：用 `ObjectProvider<StateStreamController>` 注入而不是直接 `@Autowired`（见 §4.4） |

---

## 13. 附录 A · 如果未来 starter 行为变化时的 fallback

§5.1.2 已经确认 AS-Java 1.0.12 把 `ToolSuspendException` 转成 `TOOL_CALL_END + TOOL_CALL_RESULT(content=AWAITING_USER_CONFIRMATION)`，主路径不需要附录方案。**仅当未来 1.1+ starter 改变这个行为**（例如把 suspend 直接当 `RUN_ERROR` 发），考虑下面两条 fallback：

**Fallback A**：写 Hook 实现 `Hook` 接口（参考 §8.3 单接口 + pattern matching 写法），在 `PostActingEvent` 里拦截 `submit_to_frontend`，通过 §4.2 的 `Sinks.Many<AguiEvent>` 旁路推一个 `AguiEvent.Custom("hitl-await", payload)`，前端在 `EventSource` 里监听 `event: CUSTOM`。

**Fallback B**：改用 `PostActingEvent.stopAgent()`——1.0.12 已经内置 HITL 机制，调它会让 agent 提前返回当前 `ToolResultBlock`，前端拿到结果照样可以识别 `AWAITING_USER_CONFIRMATION` 前缀。这条路不需要抛异常，更符合"软停"语义。

两者本质都是借助 §4 的旁路通道传 HITL 信号，不依赖 starter 升级。

---

## 14. 附录 B · 升级 Harness（Day 7 加分项，建议 30 min）

把 `ReActAgent` 换成 `HarnessAgent`（1.1.0-RC1+）能直接拿走 5 个能力（详细看 [../agents/07-harness.md § 用 Harness 构建 Coding Agent](../agents/07-harness.md)）：

| # | 能力 | 替代我们自己写的 |
|---|------|----------------|
| 1 | **自动 Compaction** | E5 在 `buildForThread` 里的"snapshot → subList → clear → reload" 手动截断 |
| 2 | **ToolResult Eviction** | 前端返回大 payload 不污染上下文（我们当前没处理） |
| 3 | **Workspace `AGENTS.md`** | 业务规则沉淀到文件，不再塞 system prompt |
| 4 | **内置 Filesystem 工具** | Agent 能直接读项目里 Day 2 的 Schema |
| 5 | **内置 Session 抽象** | Day 5 的 `FileSession` 可以退役 |

下面是最小升级步骤（控制在 30 min 内跑通；**不替换** Day 7 主路径上的代码，只新增 `HarnessProfile`，用 Spring profile 切）。

### B.1 pom 切版本

新加一个 profile，避免影响主分支：

```xml
<profiles>
    <profile>
        <id>harness</id>
        <properties>
            <agentscope.version>1.1.0-RC1</agentscope.version>
        </properties>
        <dependencies>
            <dependency>
                <groupId>io.agentscope</groupId>
                <artifactId>agentscope-harness</artifactId>
                <version>${agentscope.version}</version>
            </dependency>
        </dependencies>
    </profile>
</profiles>
```

> ⚠️ Harness 在 1.1.x 还是 RC 阶段，API 可能跟正式版有差异。生产环境慎用；本课程作为"提前感受"。

### B.2 写 `HarnessAgentFactory`

新建 `src/main/java/space/wlshow/scope/agent/HarnessAgentFactory.java`：

```java
package space.wlshow.scope.agent;

import io.agentscope.harness.HarnessAgent;
import io.agentscope.harness.HarnessConfig;
import io.agentscope.harness.workspace.Workspace;
import io.agentscope.core.memory.compaction.SummarizingCompactor;
import io.agentscope.core.tool.Toolkit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import space.wlshow.scope.todo.TodoManager;
import space.wlshow.scope.tool.*;

import java.nio.file.Path;

@Profile("harness")
@Component
public class HarnessAgentFactory {

    public HarnessAgent build(String threadId, TodoManager todos) {
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder().parallel(true).build());
        toolkit.registerTool(new FrontendCreateTools(todos));
        toolkit.registerTool(new TodoQueryTools(todos));
        toolkit.registerTool(new TodoUpdateTools(todos));
        toolkit.registerTool(new SubmitTool(todos, /* dispatcher */ null));

        // ① Workspace：把业务规则 / Schema 落到一个目录，Harness 内置 fs 工具能读
        Workspace ws = Workspace.builder()
                .rootDir(Path.of("data/workspaces", threadId))
                .addInitFile("AGENTS.md", AGENTS_MD)             // 业务规则文件
                .copyClasspathDir("schemas/", "schemas/")        // Day 2 的 schema
                .build();

        return HarnessAgent.builder()
                .name("RequirementAnalyst-Harness")
                .config(HarnessConfig.builder()
                        // ② 自动 Compaction：超过 30 轮自动总结早期消息
                        .compactor(SummarizingCompactor.builder()
                                .maxRounds(30)
                                .keepRecent(10)
                                .build())
                        // ③ ToolResult Eviction：单条 toolResult 超过 4KB 时折叠为"[evicted]"
                        .toolResultMaxBytes(4096)
                        // ④ Session 默认走 Harness 内置 JSON 持久化
                        .sessionDir(Path.of("data/sessions"))
                        .build())
                .workspace(ws)
                .toolkit(toolkit)
                .model(/* 复用 ModelRegistry.resolve(DEFAULT_MODEL_ID) */ null)
                .build();
    }

    private static final String AGENTS_MD = """
            # AGENTS.md（项目业务规则，会被 Harness 注入到 Agent 的运行上下文）

            ## 命名规则
            - app.name / moduleId / model.name / field.name：camelCase
            - 中文标签放进 label / moduleName / comment

            ## 数据模型枚举
            - model.type ∈ {ENTITY, TASK, TASK_MASTER_SLAVE}
            - field.dataType ∈ {long, int, double, string, boolean, date, array}
            - 主键约定：每个 model 必含 name=id, dataType=long, usage=primary

            ## 多轮规则
            - 新增需求 → list_todos 先看，区分 ADD / MODIFY
            - 严禁删除既有待办（除非用户明确说"删掉 xxx"）

            ## 失败处理
            - submit_to_frontend 返回 PARTIAL_FAILURE：
              1) timeout/5xx → retry_failed
              2) 409/duplicate → 报告用户
              3) 400/validation → update_* 改参数后再 submit
            """;
}
```

### B.3 让 `AguiAgentConfig` 按 profile 切

Day 7 主路径走"覆盖 `ThreadSessionManager`"（§4.4），附录 B 在同一个 `AguiAgentConfig.buildForThread` 里按 profile 分支：

```java
@Autowired(required = false) HarnessAgentFactory harnessFactory;

private Agent buildForThread(String threadId) {
    FileSession session = activeSessions.computeIfAbsent(threadId, FileSession::loadOrNew);
    if (harnessFactory != null) {
        return harnessFactory.build(threadId, session.todos);   // profile=harness 走这条
    }
    // 默认走 Day 6/7 的 ReActAgent
    return AgentFactory.buildAnalystWithTools(session.todos, session.memory, metricsHook);
}
```

### B.4 启动 + 验证

```bash
# 用 harness profile 起
mvn spring-boot:run -Dspring-boot.run.profiles=harness

# 起来后 data/workspaces/<threadId>/ 应该自动生成：
#   AGENTS.md
#   schemas/analysis-result.schema.json
#   schemas/app-spec.schema.json ...

ls data/workspaces/$(ls data/workspaces | head -1)/
```

跑一次"做一个员工档案管理 + 8 轮追加"，看：

1. `data/sessions/<id>.json` 自动生成（不需要 `FileSession`）
2. 第 31 轮起 `logs/scope.json.log` 出现 `compactor: summarized rounds=20`
3. 故意让 Dispatcher 返回 10KB+ payload，看 Harness 内部日志的 `toolResult evicted size=10240`

### B.5 何时不该升级

- 你团队还卡在 1.0.x（公司 maven 仓库还没同步 RC）
- Compaction 总结策略会损失细节，对**审计场景**不合适
- Workspace 给 Agent fs 权限了，**敏感目录要做白名单**

### ✅ 附录 B 验收（可选）

- [ ] `mvn spring-boot:run -Dspring-boot.run.profiles=harness` 起得来
- [ ] `data/workspaces/<threadId>/AGENTS.md` 自动生成
- [ ] 跑 30+ 轮看到 compaction 日志
- [ ] 切回默认 profile 一切照旧（没破坏主路径）

---

## 15. 写在最后

到这里你已经走完了一遍从"啥都没有"到"能演示给同事看"的 Agent 应用全流程：

- **Day 1-2** 工程骨架与数据契约 — 静态打底
- **Day 3-4** Structured Output → 工具调度 — Agent 学会"用动作汇报"
- **Day 5** Memory/Session/HITL — 多轮 + 持久化 + 人机协作
- **Day 6-7** AG-UI 协议 — 标准化前后端契约，让任何前端框架都能即插即用

下一步你可以做的事（不在本课范围内）：

- **Workflow 改造**：用 `Pipeline` / `Workflow` 把"解析→评审→落地"拆成多个 Agent（[../agents/08-multi-agent.md](../agents/08-multi-agent.md)）
- **Memory 持久化**：本课为简化主路径选择"只持久化 TodoManager"，要让重启后 LLM 也记得对话原文，参考 [Day 5 §14 附录 D · Memory 持久化扩展](<Day05_多轮对话 + Memory 与 Session + HITL.md>)（三档方案：纯文本快照 / ContentBlock 多态序列化 / Harness 内置 Session）
- **认证授权**：starter `/agui/run` 加 Spring Security 鉴权
- **生产化**：Harness 接入、Compaction 调优、模型多 provider 故障转移
- **真业务后端对接**：把 Day 7 §9.2 的 `WireMockBackend` 替换成对接公司真实业务前端的 `create_app / create_module` 接口，`HttpDispatcher` 的 baseUrl 指过去即可

恭喜你完成 7 天学习。把 PR 链接发给我，我们一起 review 演示视频。
