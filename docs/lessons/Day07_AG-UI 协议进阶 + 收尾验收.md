# Day 7 · AG-UI 协议进阶 + 收尾验收

> 上级文档：[../learning.md](../learning.md)
> 配套笔记：[../agents/10-observability-hitl.md](../agents/10-observability-hitl.md) · [../agents/07-harness.md](../agents/07-harness.md)
> 前置：[Day 6 · AG-UI 协议集成（基础）](<Day06_AG-UI 协议集成（基础）.md>) 已完成
> 官方文档：[AG-UI State Events](https://docs.ag-ui.com/concepts/events#state-management-events) · [agentscope-examples/agui](https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-examples/agui)

## 0. 一句话目标

**今天结束时**，你的项目是一个**可演示、可交付**的小型 Agent 应用：

- Vue3 UI **左侧**聊天框（Day 6 已经能）+ **右侧** Todo 看板，TodoManager 任何变化都通过 `STATE_DELTA` 实时镜像，**不刷新页面**
- 用户在浏览器点"确认下发"按钮 → Agent 收到 `role=tool` 消息 → 真正调 `dispatchAll(...)` 完成 dry-run；点"取消"则待办保持 PENDING
- `logs/scope.json.log` 每条日志带 `traceId`，能用 `jq` 把一次会话从输入到下发全过程拎出来
- 8 个异常剧本（E1-E8）全部 WireMock 通过
- `README.md` 含架构图、3 分钟演示视频、14 条需求逐项打勾

> ⚠️ Day 7 把 Day 5 的 CLI HITL 路径**删掉**（保留为附录代码），HITL 完全前端化。如果你想保留 CLI 调试模式，留 `ScopeReplApp` 作为备份入口。

## 1. 学习目标

- ✅ 通过 `TodoChangeListener` + AG-UI event emitter 把 TodoManager 实时镜像到前端
- ✅ 用 `STATE_SNAPSHOT`（连接时一次）+ `STATE_DELTA`（RFC 6902 JSON Patch 增量）的标准模式
- ✅ HITL 完全走 AG-UI：`TOOL_CALL_*` 事件 → 前端弹窗 → `messages: [..., {role:"tool", toolCallId, content}]` 回填
- ✅ Logback JSON + MDC `traceId` 通过 Reactor `contextWrite` 透传
- ✅ 跑通 8 个异常剧本回归
- ✅ 完成需求逐项打勾、架构图、演示视频

## 2. 时间盒（建议 9 学时，分上午 3.5h + 下午 5.5h）

| 阶段 | 时长 | 主题 | 验收 |
|------|------|------|------|
| Phase 0 | 30 min | 资料预读 + 设计选型 | 能说出 STATE_SNAPSHOT/STATE_DELTA 时机差异 + 知道 OTel 和 jq 日志为什么必须 traceId 同步 |
| Phase 1 | 90 min | TodoManager → STATE 事件 | 前端右侧看板实时切换状态 |
| Phase 2 | 90 min | HITL on AG-UI | 浏览器点击 y/n 完成 Day 5 的剧本 |
| **Phase 3a** | **60 min** | **日志骨架 + 7 个关键日志点** | jq 过 traceId 还原会话，7 个 stage 全部出现 |
| **Phase 3b** | **75 min** | **OpenTelemetry + Jaeger** | Jaeger UI 看到一条完整 trace（HTTP → Agent → Tool） |
| **Phase 3c** | **45 min** | **Hook 可观测 + Micrometer** | `/actuator/metrics` 能看到工具调用计数和 LLM 延迟分布 |
| Phase 4 | 60 min | E1-E8 异常剧本 + WireMock | `mvn test` 全绿，含集成测试 |
| Phase 5 | 45 min | 需求打勾 + 演示视频 | 14 项全勾，3 分钟视频入库 |
| Phase 6 | 45 min | README + 架构图 + commit | `day7: ...` commit，PR 描述完整 |

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
浏览器                                Agent 后端
  |                                       |
  |  runAgent(messages=[user:"提交"])     |
  |---POST /agui/run-------------------->|
  |                                       |  LLM 决定调 submit_to_frontend(confirmed=false)
  |                                       |  starter 发 TOOL_CALL_START/ARGS/END
  |<--SSE: TOOL_CALL_END toolCallId=tc1--|
  |                                       |  工具内 throw ToolSuspendException
  |                                       |  starter 发 RUN_FINISHED（带 suspended 标记）
  |<--SSE: RUN_FINISHED-------------------|
  |                                       |
  |  渲染待办预览 + 确认按钮              |
  |                                       |
  |  用户点击「确认」                     |
  |---POST /agui/run------                |
  |  messages=[                           |
  |    ...上下文,                          |
  |    {role:"tool",                      |
  |     toolCallId:"tc1",                 |
  |     content:"USER_CONFIRMED"}         |
  |  ]                                    |
  |--------------------------------------->|
  |                                       |  Agent 接到 tool result 续跑
  |                                       |  LLM 调 submit_to_frontend(confirmed=true)
  |                                       |  工具真发，状态 PENDING→RUNNING→SUCCESS
  |                                       |  TodoChangeListener 发 STATE_DELTA × N
  |<--SSE: STATE_DELTA × N----------------|
  |<--SSE: TEXT_MESSAGE_* (LLM 总结)------|
  |<--SSE: RUN_FINISHED-------------------|
```

**核心**：HITL 不是"Agent 暂停一辈子"，而是"Agent 结束一次 run，等下一次 run 带 tool result 续跑"。

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

### 4.1 设计：`AguiStateBridge`

把 Day 4 写的 `TodoChangeListener` 接到 AG-UI 的 event emitter。

**关键点**：每个 threadId 对应一个 `AguiStateBridge` 实例，因为 `STATE_*` 事件要发到对应 run 的 SSE channel。我们在 `AguiAgentRegistryCustomizer` 里 per-thread 构造它。

### 4.2 `AguiStateBridge.java`

```java
package space.wlshow.scope.agui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.agui.event.StateDeltaEvent;
import io.agentscope.agui.event.StateSnapshotEvent;
import io.agentscope.agui.spring.AguiEventEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.todo.TodoChangeListener;
import space.wlshow.scope.todo.TodoItem;
import space.wlshow.scope.todo.TodoManager;
import space.wlshow.scope.todo.TodoStatus;
import space.wlshow.scope.util.Json;

/**
 * 把 TodoManager 变更映射到 AG-UI STATE_* 事件。
 * 一个 thread 一个实例（per-threadId），保证 emitter 是当前 run 的。
 */
public class AguiStateBridge implements TodoChangeListener {

    private static final Logger log = LoggerFactory.getLogger(AguiStateBridge.class);

    private final TodoManager todos;
    private final AguiEventEmitter emitter;

    public AguiStateBridge(TodoManager todos, AguiEventEmitter emitter) {
        this.todos = todos;
        this.emitter = emitter;
    }

    /** 每次新的 run 启动时调，发一份完整状态。 */
    public void snapshotNow() {
        ObjectNode snap = Json.mapper().createObjectNode();
        ArrayNode arr = snap.putArray("todos");
        todos.snapshot().forEach(it -> arr.add(serialize(it)));
        emitter.emit(StateSnapshotEvent.of(snap));
        log.debug("[Agui] STATE_SNAPSHOT size={}", todos.size());
    }

    @Override
    public void onCreate(TodoItem item) {
        ArrayNode patch = Json.mapper().createArrayNode();
        ObjectNode add = patch.addObject();
        add.put("op", "add");
        add.put("path", "/todos/-");
        add.set("value", serialize(item));
        emitter.emit(StateDeltaEvent.of(patch));
        log.debug("[Agui] STATE_DELTA add id={}", item.id());
    }

    @Override
    public void onStatusChange(String id, TodoStatus from, TodoStatus to, String err) {
        int idx = indexOf(id);
        if (idx < 0) return;
        ArrayNode patch = Json.mapper().createArrayNode();
        ObjectNode op = patch.addObject();
        op.put("op", "replace");
        op.put("path", "/todos/" + idx + "/status");
        op.put("value", to.name());
        if (err != null) {
            ObjectNode op2 = patch.addObject();
            op2.put("op", "add");
            op2.put("path", "/todos/" + idx + "/errorMessage");
            op2.put("value", err);
        }
        emitter.emit(StateDeltaEvent.of(patch));
        log.debug("[Agui] STATE_DELTA {} {}->{}", id, from, to);
    }

    @Override
    public void onClear() {
        ArrayNode patch = Json.mapper().createArrayNode();
        ObjectNode op = patch.addObject();
        op.put("op", "replace");
        op.put("path", "/todos");
        op.set("value", Json.mapper().createArrayNode());
        emitter.emit(StateDeltaEvent.of(patch));
        log.debug("[Agui] STATE_DELTA clear");
    }

    private JsonNode serialize(TodoItem it) {
        ObjectNode n = Json.mapper().createObjectNode();
        n.put("id", it.id());
        n.put("type", it.type().name());
        n.put("targetName", it.targetName());
        n.put("status", it.status().name());
        n.set("payload", it.payload());
        if (it.errorMessage() != null) n.put("errorMessage", it.errorMessage());
        return n;
    }

    private int indexOf(String id) {
        var list = todos.snapshot();
        for (int i = 0; i < list.size(); i++) {
            if (id.equals(list.get(i).id())) return i;
        }
        return -1;
    }
}
```

> 📌 `AguiEventEmitter` / `StateDeltaEvent` / `StateSnapshotEvent` 的全限定名以你的 starter 1.0.9 jar 里实际为准。如果 1.0.9 没有直接暴露 emitter，看 starter 源码里 `AguiRunContext` 或类似抽象，通常会暴露一个 `Sinks.Many<AguiEvent>` 之类。

### 4.3 接入 `AguiAgentConfig`

```java
return registry.registerFactory("analyst", ctx -> {
    TodoManager todos = ThreadContext.todos(ctx.threadId());
    Memory memory = ThreadContext.memory(ctx.threadId());

    // 关键：拿到当前 run 的 emitter，构造 bridge 挂到 TodoManager
    AguiStateBridge bridge = new AguiStateBridge(todos, ctx.eventEmitter());
    todos.addListener(bridge);

    // run 开始时先发一份 snapshot，保证前端跟服务端一致
    bridge.snapshotNow();

    // ... 构造 Agent ...
});
```

### 4.4 Vue3 前端订阅 STATE_*

`frontend/src/App.vue` 加：

```vue
<script setup lang="ts">
// ... 已有 imports
import { applyPatch } from 'fast-json-patch'

interface Todo {
  id: string; type: string; targetName: string; status: string;
  payload?: unknown; errorMessage?: string;
}
const todos = ref<Todo[]>([])

agent.subscribe({
  // ... 已有的 TextMessage / ToolCall 回调
  onStateSnapshotEvent: (e) => {
    todos.value = (e.snapshot.todos ?? []) as Todo[]
    console.log('[STATE_SNAPSHOT] size=', todos.value.length)
  },
  onStateDeltaEvent: (e) => {
    const root = { todos: todos.value }
    applyPatch(root, e.delta as any)
    todos.value = [...root.todos]   // 触发 Vue 响应式
    console.log('[STATE_DELTA] ops=', e.delta.length)
  },
})
</script>
```

安装 patch 库：

```bash
cd frontend && npm install fast-json-patch
```

### 4.5 模板加右侧看板

```vue
<template>
  <div class="layout">
    <div class="chat">
      <!-- 原聊天框 -->
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
.layout { display: grid; grid-template-columns: 1fr 360px; gap: 16px; }
.todos { border-left: 1px solid #ddd; padding-left: 16px; }
.todo { display: grid; grid-template-columns: 70px 100px 1fr 80px; padding: 4px 0; font-size: 13px; }
.todo.pending  { color: #999; }
.todo.running  { color: #0066cc; }
.todo.success  { color: #008844; }
.todo.failed   { color: #cc3333; }
</style>
```

### 4.6 验证

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

- [ ] 第一次发消息后立刻看到 `STATE_SNAPSHOT`
- [ ] 每次工具调用都触发 `STATE_DELTA`（DevTools Console 数得过来）
- [ ] 右侧看板颜色按状态切换
- [ ] 重连不丢状态（断开 vite dev 重连后看板还原）

---

## 5. Phase 2 · HITL on AG-UI（90 min）

### 5.1 SubmitTool 复用 Day 5 的 ToolSuspend

`SubmitTool.java` **不动**。starter 会把 `ToolSuspendException` 转成两个事件：

1. `TOOL_CALL_END toolCallId=tc1`
2. `RUN_FINISHED`（带 suspended 标记，前端能识别）

如果你的 starter 版本不能自动把 suspend 转 RUN_FINISHED，看附录 A 的 Hook 方案。

### 5.2 前端识别"待确认"

`@ag-ui/client` 在 `onToolCallEndEvent` 回调里能拿到 toolName / toolCallId / args。我们维护一个"pending tool call"状态：

```ts
interface PendingConfirm {
  toolCallId: string
  todos: Todo[]    // 拍一份快照展示给用户
}
const pendingConfirm = ref<PendingConfirm | null>(null)

agent.subscribe({
  // ...
  onToolCallEndEvent: (e) => {
    // 这个回调拿到 toolName / args（args 是流式拼起来的）
    if (e.toolName === 'submit_to_frontend') {
      // 检查 args 里 confirmed 是不是 false（表示第一次询问）
      try {
        const args = JSON.parse(e.args ?? '{}')
        if (args.confirmed === false) {
          pendingConfirm.value = {
            toolCallId: e.toolCallId,
            todos: [...todos.value],
          }
        }
      } catch (e) { /* ignore */ }
    }
  },
})
```

> ⚠️ `@ag-ui/client` 不同版本 `onToolCallEndEvent` 的入参字段名可能是 `args` / `arguments` / `delta`，按 IDE 提示对齐。

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
    // 把 tool result 作为下一条 message 追加，runAgent 续跑
    agent.addMessage({
      id: 'tr-' + Date.now(),
      role: 'tool',
      toolCallId: pc.toolCallId,
      content: decision,
    })
    await agent.runAgent({ runId: 'run-' + Date.now() })
  } finally {
    running.value = false
  }
}
```

> 📌 `@ag-ui/client` 的 `addMessage` 对 `role: 'tool'` 的支持视版本而定。如果它拒收，改用 `agent.messages.push(...)` 直接 push，或者用底层 `httpAgent.run(...)` 自己拼 body。`messages` 末尾追加 `{role: 'tool', toolCallId, content}` 是 AG-UI 协议标准做法。

### 5.5 服务端验证

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

- [ ] 弹窗在 LLM 调 `submit_to_frontend(confirmed=false)` 后浮现
- [ ] y 路径：状态变到 SUCCESS
- [ ] n 路径：状态保持 PENDING
- [ ] LLM 收尾消息正确反映用户决定
- [ ] **Day 5 的 CLI HITL 代码完全没被走到**（用 grep 日志确认）

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

把原 `logback.xml` 改名为 `logback-spring.xml`（Spring Boot 会优先加载），加 JSON appender：

```xml
<configuration scan="true" scanPeriod="30 seconds">

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%X{traceId:-NA}] %-5level %logger{36} - %msg%n</pattern>
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
        // 优先从请求头取（前端/上游已分配的 traceId，Phase 3b 接 OTel 后 W3C
        // `traceparent` 会被自动解析并注入），没有就生成 8 位短 id
        String tid = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        if (tid == null || tid.isBlank()) {
            tid = UUID.randomUUID().toString().substring(0, 8);
        }
        exchange.getResponse().getHeaders().add("X-Trace-Id", tid);

        final String finalTid = tid;
        return chain.filter(exchange)
                .contextWrite(Context.of(KEY, finalTid))
                .doFirst(() -> MDC.put(KEY, finalTid))
                .doFinally(s -> MDC.remove(KEY));
    }
}
```

> 📌 `MDC` 在 Reactor 链上**不会自动透传**，正确做法是在每个 operator 上手动 `Context` ↔ `MDC` 同步。简化版（本课用）：在 Filter 起点 put、终点 remove，AS-Java 内部的同步 work 已经在主线程里。
> 📌 Phase 3b 起 OTel 接管 traceId，这里取请求头优先，是为了让 Phase 3b 的 W3C `traceparent` 能直接复用，不会出现"jq 日志和 jaeger trace 对不上"。

#### 6.3.2 AG-UI starter 透传 threadId/runId

starter 内部应该已经在 MDC 里放了 `threadId` / `runId`。如果没有：在 `AguiAgentRegistryCustomizer` 的 lambda 里：

```java
MDC.put("threadId", ctx.threadId());
MDC.put("runId", ctx.runId());
```

> ⚠️ MDC 在 Reactor 调度的 worker thread 里会丢，复杂场景要装 `Hooks.enableContextLossTracking()` 或者用 `MDCWebContextSecurityContextHolderStrategy` 之类。本课程不深入。

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
| **INPUT** | `TraceIdFilter` 末段 或 `AguiAgentRegistryCustomizer` 的 factory 入口 | `INPUT` | threadId, runId, userText 长度 | `收到用户输入 len=42` |
| **LLM_CALL** | `ReActAgent.call(...)` 前后（推荐用 PostReasoningHook，详见 Phase 3c） | `LLM_CALL` | model, promptTokens, completionTokens, latencyMs | `LLM 返回 model=doubao-pro tokens=120/85 latency=820ms` |
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
# 拿 traceId（响应头）
TID=$(curl -sN -X POST http://localhost:8080/agui/run \
    -H "Content-Type: application/json" \
    -D /tmp/h.txt \
    -d '{"threadId":"t1","runId":"r1","messages":[{"id":"m1","role":"user","content":"做一个员工档案管理"}]}' \
    > /dev/null && grep -i 'x-trace-id' /tmp/h.txt | awk '{print $2}' | tr -d '\r')

echo "traceId=$TID"

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
      enabled: true                  # 关键：把 OTel traceId 自动注入 MDC
      add-baggage: false
      trace-id-key: traceId          # 改 key 名跟 Phase 3a 对齐
      span-id-key: spanId
```

### 7.4 关键坑：MDC key 必须对齐

OTel 的 `opentelemetry-logback-mdc-1.0` 默认会注入 `trace_id` / `span_id` 到 MDC，**但默认 key 名是 `trace_id` 不是 `traceId`**，所以原本 jq 用的 `.traceId` 字段对不上 Jaeger 的 trace 列。两条修法：

**A. 让 OTel 注入到 `traceId`**（推荐，已在 7.3 配置）

`trace-id-key: traceId` 就够了。

**B. 改 `TraceIdFilter` 让它先看 OTel Span**

如果你 starter 版本不支持 `trace-id-key` key，自己改：

```java
@Override
public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    // OTel WebFlux instrumentation 已经建了 Span
    String otelTid = io.opentelemetry.api.trace.Span.current()
            .getSpanContext().getTraceId();
    String tid = (otelTid != null && !otelTid.equals("00000000000000000000000000000000"))
            ? otelTid.substring(0, 8)
            : UUID.randomUUID().toString().substring(0, 8);
    // ... 同 Phase 3a
}
```

**两种方式选一种**。课程示范走 A。

### 7.5 Span 命名约定

OTel 默认给 WebFlux 自动产生的 Span 名是 HTTP method，可读性差。在 `AguiAgentRegistryCustomizer` 里手动建子 Span：

```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;

private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("scope-agent");

// factory 内构造 Agent 时手动加 Span（如果想自动化用 @WithSpan）
var span = TRACER.spanBuilder("agent.call")
        .setAttribute("threadId", ctx.threadId())
        .setAttribute("runId", ctx.runId())
        .startSpan();
try (var scope = span.makeCurrent()) {
    return ReActAgent.builder()....build();
} finally {
    span.end();
}
```

`@Tool` 方法用注解版省 boilerplate：

```java
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;

@Tool(name = "create_app", description = "...")
@WithSpan("tool.create_app")
public String createApp(
        @SpanAttribute("name") @ToolParam(name = "name") String name,
        @ToolParam(name = "label") String label,
        @ToolParam(name = "type") String type
) {
    return Stage.call(Stage.TOOL_CALL, () -> {
        // 原逻辑
    });
}
```

`@WithSpan` 会自动 startSpan/makeCurrent/end，`@SpanAttribute` 会把参数当 Span attribute 记下来 — Jaeger UI 里点 Span 就能看到 `name=leaveMgr` 之类。

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

最关键的一步验证：**jq 日志、curl 响应头、Jaeger UI 三处 traceId 一致**。

```bash
TID=$(curl -sN ... -D /tmp/h.txt > /dev/null && grep 'x-trace-id' /tmp/h.txt | awk '{print $2}' | tr -d '\r')

echo "短 id: $TID"
echo "日志行数: $(jq -r --arg t "$TID" 'select(.traceId | startswith($t)) | .' logs/scope.json.log | wc -l)"
# Jaeger 搜 trace 用全长 id：去 http://localhost:16686/trace/<full-traceId> 看
```

如果三处对不上，回头看 7.4 的 key 名配置。

### ✅ Phase 3b 验收

- [ ] `docker ps` 看到 `jaeger` 容器运行
- [ ] Jaeger UI `agent-scope-app` 服务名可选
- [ ] 至少 1 条完整 trace（含 `agent.call` + `tool.*` 子 Span）
- [ ] curl 响应头 `X-Trace-Id` 跟 Jaeger trace 前 8 位一致
- [ ] jq 日志的 `traceId` 字段是 OTel 全 32 位 traceId（或截前 8 位）

---

## 8. Phase 3c · Hook 可观测 + Micrometer（45 min）

### 8.1 思路

AS-Java 的 Hook 体系（[../agents/10-observability-hitl.md](../agents/10-observability-hitl.md)）暴露 4 个生命周期事件：

| Hook | 触发点 |
|------|--------|
| `PreReasoningHook` | LLM call 前 |
| `PostReasoningHook` | LLM call 后（拿到响应） |
| `PreActingHook` | 工具调用前 |
| `PostActingHook` | 工具调用后 |

我们写一个 `ExecutionMetricsHook` 实现这四个接口，把数据喂给 Micrometer：

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

```java
package space.wlshow.scope.observability;

import io.agentscope.core.hook.PostActingHook;
import io.agentscope.core.hook.PostReasoningHook;
import io.agentscope.core.hook.PreActingHook;
import io.agentscope.core.hook.PreReasoningHook;
import io.agentscope.core.hook.event.PostActingEvent;
import io.agentscope.core.hook.event.PostReasoningEvent;
import io.agentscope.core.hook.event.PreActingEvent;
import io.agentscope.core.hook.event.PreReasoningEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 AS-Java 的 4 个 hook 事件汇成 Micrometer 指标。
 * 注意：MeterRegistry 是线程安全的；Tag 必须低基数，不要把 traceId / userId 当 Tag。
 */
@Component
public class ExecutionMetricsHook implements PreReasoningHook, PostReasoningHook,
                                              PreActingHook, PostActingHook {

    private static final Logger log = LoggerFactory.getLogger(ExecutionMetricsHook.class);

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Long> llmStartNs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> toolStartNs = new ConcurrentHashMap<>();

    public ExecutionMetricsHook(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override public int priority() { return 50; }  // 跟 Day 1 的 PromptLengthHook 区分

    // === Reasoning（LLM 调用） ===

    @Override
    public void onPreReasoning(PreReasoningEvent ev) {
        llmStartNs.put(ev.getRunId(), System.nanoTime());
        int promptChars = ev.getPromptText() == null ? 0 : ev.getPromptText().length();
        registry.summary("scope.llm.prompt.chars",
                "model", ev.getModel()).record(promptChars);
        if (promptChars > 8000) {
            log.warn("[Metric] prompt 超长 {} chars (>8000)", promptChars);
        }
    }

    @Override
    public void onPostReasoning(PostReasoningEvent ev) {
        Long start = llmStartNs.remove(ev.getRunId());
        if (start == null) return;
        long latencyNs = System.nanoTime() - start;
        Timer.builder("scope.llm.latency")
                .tag("model", ev.getModel())
                .tag("finishReason", String.valueOf(ev.getFinishReason()))
                .register(registry)
                .record(java.time.Duration.ofNanos(latencyNs));

        // Token 用量（如果 provider 返回）
        if (ev.getCompletionTokens() != null) {
            registry.counter("scope.llm.tokens",
                    "model", ev.getModel(), "type", "completion")
                    .increment(ev.getCompletionTokens());
            registry.counter("scope.llm.tokens",
                    "model", ev.getModel(), "type", "prompt")
                    .increment(ev.getPromptTokens());
        }
    }

    // === Acting（工具调用） ===

    @Override
    public void onPreActing(PreActingEvent ev) {
        toolStartNs.put(ev.getToolCallId(), System.nanoTime());
    }

    @Override
    public void onPostActing(PostActingEvent ev) {
        Long start = toolStartNs.remove(ev.getToolCallId());
        if (start == null) return;
        long latencyNs = System.nanoTime() - start;
        String status = ev.isSuccess() ? "success" :
                (ev.isSuspended() ? "suspended" : "failed");
        Timer.builder("scope.tool.latency")
                .tag("toolName", ev.getToolName())
                .tag("status", status)
                .register(registry)
                .record(java.time.Duration.ofNanos(latencyNs));
        Counter.builder("scope.tool.calls")
                .tag("toolName", ev.getToolName())
                .tag("status", status)
                .register(registry)
                .increment();
    }
}
```

> ⚠️ Hook 接口的具体类名 / 方法名以 AS-Java 1.0.12 jar 里实际为准。如果你 jar 里 `PreReasoningEvent` 没暴露 `getRunId()` / `getPromptText()` / `getModel()`，看 Hook event 的实际字段，调整代码即可，逻辑一致。如果一个类实现 4 个接口太重，拆成 2 个（LlmMetricsHook + ToolMetricsHook）也行。

### 8.4 把 Hook 接入 Agent

`AguiAgentRegistryCustomizer` 的 lambda 里：

```java
@Autowired ExecutionMetricsHook metricsHook;     // 注入 Spring Bean

// factory 内构造 Agent 时：
ReActAgent agent = ReActAgent.builder()
        // ... 其他配置
        .addHook(metricsHook)         // 4 个接口一次性挂上
        .build();
```

> 📌 `addHook` 在不同 AS-Java 版本可能是 `addPreReasoningHook` / `withHooks` 等，按 jar 实际签名替换。

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

### 7.1 E1-E8 清单

| # | 场景 | 期望 |
|---|------|------|
| E1 | 输入"做个系统" | warnings/questions 非空，TodoManager 可能为空或仅 1 APP |
| E2 | LLM 返回非 JSON | （Day 3 RequirementParser 路径已废）今天 LLM 错调工具 → 工具内拒收 |
| E3 | LLM 工具入参 Schema 不合法 | 工具返回 ERROR:，TodoManager 不污染 |
| E4 | submit 时 dispatch 失败（模拟前端 500） | TodoItem→FAILED + errorMessage，STATE_DELTA 推到前端 |
| E5 | 用户连续追加 6 次需求 | Memory 不超 token 上限（Compaction 或截断） |
| E6 | 用户说"全删了" | TodoManager.clear()，STATE_DELTA 把数组清空 |
| E7 | 同 moduleId 重复创建 | 工具拒收（FrontendCreateTools 加去重）+ warning |
| E8 | 进程 kill 重启 | sessionId 恢复（FileSession） |

### 7.2 实现兜底

#### E4：模拟 dispatch 失败

把 SubmitTool 改成可注入 `Dispatcher` 接口：

```java
public interface Dispatcher {
    void dispatch(TodoItem item) throws Exception;
}

@Component
public class DryRunDispatcher implements Dispatcher {
    @Override public void dispatch(TodoItem item) { /* no-op */ }
}

// SubmitTool
private final Dispatcher dispatcher;
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

```java
@Test
void e4_dispatchFailure_agentRetriesViaToolCall() throws Exception {
    // 安排 dispatcher 第一次失败，第二次成功
    when(dispatcher.dispatch(any())).thenThrow(new RuntimeException("timeout"))
                                     .thenReturn(/* no-op */ null);
    // 录制 LLM mock：收到 PARTIAL_FAILURE 后调 retry_failed(ids=[...])
    wireMockLlmResponseRetry();

    var events = collectEvents("做一个员工档案管理\n然后提交");

    // 关键断言
    // 1. 至少一个 TOOL_CALL_RESULT 内容含 "PARTIAL_FAILURE"
    assertTrue(events.stream().anyMatch(e ->
            e.type().equals("TOOL_CALL_RESULT") && e.content().contains("PARTIAL_FAILURE")));
    // 2. LLM 收到失败后又调了 retry_failed 工具
    assertTrue(events.stream().anyMatch(e ->
            e.type().equals("TOOL_CALL_START") && "retry_failed".equals(e.toolName())));
    // 3. 最终所有 todo 是 SUCCESS（含重试出来的新 id）
    assertTrue(todoManager.snapshot().stream()
            .anyMatch(it -> it.status() == TodoStatus.SUCCESS));
}
```

#### E5：Memory 截断

简单版本：在 `AguiAgentRegistryCustomizer` 的 factory 里：

```java
Memory memory = ThreadContext.memory(ctx.threadId());
if (memory.size() > 40) {
    // 保留 system + 最近 20 条
    memory.compactTo(20);    // 假设有这个 API；没有就自己 list -> sublist -> reload
}
```

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

### 7.3 集成测试 `ScenarioTest`

`src/test/java/space/wlshow/scope/scenario/E2ETest.java`：

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "logging.level.io.agentscope=WARN"
})
class E2ETest {

    @LocalServerPort int port;

    @Test
    void e1_vagueInput_putsQuestions() throws Exception {
        // 用 WireMock stub 一次 LLM 响应：只调 create_app + 答里大量 questions
        // ... stub setup
        // 用 WebClient 调 /agui/run，收集事件
        var events = collectEvents("做个系统");
        assertTrue(events.stream().anyMatch(e -> e.type().equals("TEXT_MESSAGE_CONTENT")
                && e.delta().contains("?")));
    }

    @Test
    void e4_dispatcherFails_marksFailed() throws Exception {
        // 替换 Dispatcher Bean 为 FailingDispatcher
        // 跑完整剧本到 confirmed=true 阶段
        // 断言至少一个 STATE_DELTA 里 status=FAILED
    }

    @Test
    void e7_duplicateModuleId_rejected() throws Exception {
        // 第一次创建 employeeMgmt，第二次再创建同 moduleId
        // 断言 TOOL_CALL_RESULT 含 "ERROR: moduleId 已存在"
    }

    // ... E2, E3, E5, E6, E8 类似
}
```

> 📌 把 8 个全跑完是个工程活，建议优先级 E1 / E4 / E7 / E8 必跑，E2 / E3 / E5 / E6 写完保留 `@Disabled` 也行（在 README 标明）。

### 7.4 跑测试

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

### 8.1 14 项需求逐项打勾

把 `learning.md` Day 7 表格复制到 README 里，对每一项打勾并附"在哪验证"：

```markdown
| # | 需求 | 兑现位置 | 验证方式 |
|---|------|---------|---------|
| 1 | 用户输入需求 | Vue3 `<textarea>` → `/agui/run` | 浏览器输入 "做一个员工档案管理" |
| 2 | 解析为 App/Module/Model | FrontendCreateTools + AgentFactory | 看右侧看板出现 1 APP + N Module + N Model |
| 3 | 列出待办列表 | TodoManager + STATE_SNAPSHOT | 右侧看板 |
| 4 | 前端下发 | SubmitTool + Dispatcher | 弹窗确认后看板状态切到 SUCCESS |
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

### 8.2 演示视频脚本

3 分钟，建议分镜：

| 时间 | 内容 |
|------|------|
| 0:00-0:15 | 打开 README，介绍项目名 + 一句话定位 |
| 0:15-0:30 | `mvn spring-boot:run` 起后端 + `npm run dev` 起前端 |
| 0:30-1:00 | 浏览器输入 "做一个简单请假管理" → 看板 + 流式回复 |
| 1:00-1:30 | 输入 "加一个出库审批模块" → 增量补待办 |
| 1:30-2:00 | 输入 "提交" → 弹窗 → 确认 → 看状态切换 |
| 2:00-2:30 | 输入 "再做一个员工档案" → 弹窗 → 取消 → 状态保持 |
| 2:30-3:00 | 终端 `jq` 过 traceId 展示日志，对照 README 14 项打勾 |

录屏工具：Win+G（Game Bar）输出 mp4，用 https://ezgif.com 转 GIF 或上 B 站。

存到 `docs/screenshots/day7-demo.mp4`（或链接）。

### ✅ Phase 5 验收

- [ ] 14 项全勾
- [ ] 演示视频长度 ≈ 3 分钟，可见全部 4 个关键场景

---

## 11. Phase 6 · README + 架构图 + commit（45 min）

### 9.1 架构图

用 https://excalidraw.com 画一张，导出 PNG 存 `docs/screenshots/architecture.png`。

需要画的节点：

```
[ Vue3 / Vite ]  ←─SSE─  [ Spring Boot WebFlux ]
                           │
                           ├─ agentscope-agui-spring-boot-starter
                           │     └─ POST /agui/run (17 EventType)
                           │
                           ├─ AguiAgentRegistryCustomizer
                           │     └─ per-threadId factory
                           │
                           ├─ ReActAgent (RequirementAnalyst)
                           │     ├─ Memory (InMemoryMemory)
                           │     ├─ Toolkit (parallel)
                           │     │   ├─ FrontendCreateTools
                           │     │   ├─ TodoQueryTools
                           │     │   ├─ TodoUpdateTools
                           │     │   └─ SubmitTool (ToolSuspend → AG-UI HITL)
                           │     ├─ Hooks
                           │     │   ├─ PromptLengthHook (Day 1)
                           │     │   └─ ExecutionMetricsHook ──→ Micrometer
                           │     └─ Model (OpenAIChatModel · 火山方舟 / DashScope)
                           │
                           ├─ TodoManager (StateModule)
                           │     └─ AguiStateBridge (TodoChangeListener)
                           │           └─ STATE_SNAPSHOT / STATE_DELTA
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

### 9.2 README 改写

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

（粘 8.1 的表格）

## 7 天学习路线

- [Day 1](docs/lessons/Day01...)
- [Day 2](docs/lessons/Day02...)
- ...
- [Day 7](docs/lessons/Day07_AG-UI%20协议进阶%20%2B%20收尾验收.md)

## 已知限制

- Memory 没接 Harness Compaction，超 40 轮可能撞 token 上限
- FileSession 是文件持久化，并发写有竞态（多 thread 同 sessionId 时）
- Dispatcher 当前是 DryRunDispatcher，真接业务前端要补真实 HTTP 客户端
```

### 9.3 commit

```bash
git add src/main/java/space/wlshow/scope/agui/AguiStateBridge.java \
        src/main/java/space/wlshow/scope/observability/ \
        src/main/java/space/wlshow/scope/dispatch/ \
        src/main/java/space/wlshow/scope/tool/FrontendCreateTools.java \
        src/main/java/space/wlshow/scope/tool/SubmitTool.java \
        src/main/java/space/wlshow/scope/config/AguiAgentConfig.java \
        src/main/resources/logback-spring.xml \
        src/main/resources/application.yml \
        src/test/java/space/wlshow/scope/scenario/ \
        frontend/src/App.vue \
        frontend/package.json \
        docs/screenshots/ \
        pom.xml \
        README.md

git commit -m "day7: AG-UI 协议进阶 + 可观测三件套 + 收尾验收

- AguiStateBridge：TodoManager 变更通过 STATE_SNAPSHOT / STATE_DELTA 镜像到前端
- HITL on AG-UI：替换 Day 5 CLI 实现，前端弹窗 + role=tool 回填续跑
- Phase 3a 日志：LogstashEncoder + TraceIdFilter + Stage helper +
                7 个关键日志点（INPUT/LLM_CALL/TOOL_CALL/SCHEMA_VALIDATE/
                TODO_UPDATE/FRONTEND_DISPATCH/FRONTEND_CALLBACK）
- Phase 3b OTel：opentelemetry-spring-boot-starter + Jaeger 本地 docker，
                @WithSpan 注解化 + traceId 三处对得上
- Phase 3c Hook：ExecutionMetricsHook（4 个 Hook 接口）+ Micrometer
                + Actuator/Prometheus 端点
- 8 个异常剧本 E1-E8（核心 4 个测试通过，余 4 个 @Disabled）
- Vue3 右侧 Todo 看板 + 确认弹窗
- README 14 项需求逐项打勾 + 架构图 + 3 分钟演示"
```

### 9.4 收尾自检（最后 5 分钟）

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
| 前端没收到 STATE_SNAPSHOT | `snapshotNow()` 没在 factory 里调；或 emitter 不是当前 run 的 |
| STATE_DELTA 索引错乱（前后端不同步） | 服务端 indexOf 拿的是 snapshot 时间点的 index，再发出去时数组已经变；改成发完整 status 字段 + path 用 `/todos/<id>` 风格 |
| `applyPatch` 报 "operation path out of range" | RFC 6902 stricter，path 不存在就报错；改用 `applyPatch(root, patch, false, false)`（不严格） |
| HITL 弹窗不出 | `onToolCallEndEvent` 拿不到 args；某些 starter 版本要先聚合 `TOOL_CALL_ARGS` delta 才有完整 args |
| 点确认后 Agent 不续跑 | `addMessage` 不支持 `role:'tool'`；改用 `httpAgent.run({messages:[...current,{role:'tool',...}]})` |
| TraceId 在工具日志里丢了 | MDC 在 Reactor worker 线程不透传；用 `Schedulers.onScheduleHook` 注册 MDC 同步，或者用 Phase 3b 的 OTel logback-mdc 桥（推荐） |
| logs/scope.json.log 中文乱码 | encoder 没指定 UTF-8；LogstashEncoder 默认 UTF-8，看终端 / IDE 显示设置 |
| jq 日志 traceId 跟 Jaeger trace 对不上 | Phase 3b 的 7.4 — MDC key 默认是 `trace_id`，配 `trace-id-key: traceId` 对齐 |
| Jaeger UI 一片空白 | OTLP exporter 没连上：检查 `otel.exporter.otlp.endpoint`；端口 4317 防火墙；docker 在 wsl2 上时用 `host.docker.internal` |
| `agent.call` Span 没有子 Span | AS-Java 内部 `OkHttpClient` 没接 OTel instrumentation；在 OTel agent jar 模式下自动支持，starter 模式可能需要加 `opentelemetry-okhttp-3.0` 依赖 |
| `/actuator/metrics` 空 | `management.endpoints.web.exposure.include` 没配 `metrics`；Spring Boot 3 默认只开 `health`/`info` |
| `scope.*` 指标都不出现 | `ExecutionMetricsHook` 没加到 Agent；或 Hook 接口的方法名拼错（编译期没报错因为是 `default` 方法） |
| `scope.tool.calls` 计数偏多 | Toolkit `parallel(true)` 下同一工具被多线程并发记多次 — 实际就是这么多次调用，正常 |
| Prometheus exporter 报 "high cardinality" 警告 | 你把 traceId / userId 放进 Tag 了；只用低基数（toolName / status / model） |
| E4 测试时 dispatcher 没换成 failing | Spring Test 没覆盖 Bean；用 `@MockBean` 或 `@TestConfiguration` |

---

## 13. 附录 A · 如果 starter 不自动转 ToolSuspend → SSE

某些 starter 版本可能不识别 `ToolSuspendException`，会直接当 RUN_ERROR 发出去。临时方案：写个 `PostActingHook` 拦截 `submit_to_frontend` 工具调用前的状态，并自己 emit `CUSTOM` 事件代表"awaiting confirm"。

```java
public class HitlCustomEventHook implements PostActingHook {
    @Override
    public void after(ToolCallEvent ev, AguiEventEmitter emitter) {
        if ("submit_to_frontend".equals(ev.toolName())
                && ev.toolResult().contains("AWAITING_USER_CONFIRMATION")) {
            emitter.emit(CustomEvent.named("hitl-await").withData(...));
        }
    }
}
```

前端再监听 `onCustomEvent`，type=`hitl-await` 时弹窗。代价是脱离了 17 标准事件，但仍然在 AG-UI 协议的扩展机制（CUSTOM）内。

---

## 14. 附录 B · 升级 Harness（Day 7 加分项，建议 30 min）

把 `ReActAgent` 换成 `HarnessAgent`（1.1.0-RC1+）能直接拿走 5 个能力（详细看 [../agents/07-harness.md § 用 Harness 构建 Coding Agent](../agents/07-harness.md)）：

| # | 能力 | 替代我们自己写的 |
|---|------|----------------|
| 1 | **自动 Compaction** | E5 的 `memory.compactTo(20)` 兜底 |
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

### B.3 让 `AguiAgentRegistryCustomizer` 按 profile 切

```java
@Bean
public AguiAgentRegistryCustomizer aguiAgentRegistryCustomizer(
        @Autowired(required = false) HarnessAgentFactory harnessFactory) {
    return registry -> registry.registerFactory("analyst", ctx -> {
        TodoManager todos = ThreadContext.todos(ctx.threadId());
        if (harnessFactory != null) {
            return harnessFactory.build(ctx.threadId(), todos);   // profile=harness 走这条
        }
        // 默认走 Day 6/7 的 ReActAgent
        return AgentFactory.buildAnalystWithTools(todos, ThreadContext.memory(ctx.threadId()));
    });
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
- **真实前端集成**：把 `DryRunDispatcher` 换成真的 HTTP 客户端，对接业务前端的 `create_app / create_module` 接口
- **可观测性**：接 OpenTelemetry，traceId 串到 Jaeger / SkyWalking
- **认证授权**：starter `/agui/run` 加 Spring Security 鉴权
- **生产化**：Harness 接入、Compaction 调优、模型多 provider 故障转移

恭喜你完成 7 天学习。把 PR 链接发给我，我们一起 review 演示视频。
