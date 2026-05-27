# AgentScope 1.0.12 → 1.1.0-RC2 改造方案

> 本文档独立可读。下个会话从零开始时，只需读这一份即可完整复现"对比源码 → 设计方案 → 落地与避坑"全过程。

## 0. 背景与边界

- **当前版本**：`io.agentscope:agentscope:1.0.12`（pom.xml 第 20 行 `<agentscope.version>`）
- **目标版本**：`1.1.0-RC2`（预发布版）
- **源码位置**：`.agentscope/`（已 clone 全套源码，`agentscope-core` + `agentscope-extensions-agui` + `agentscope-spring-boot-starters/agentscope-agui-spring-boot-starter`）
- **groupId/artifactId 都未变**：`io.agentscope:agentscope`（distribution 聚合包）和 `io.agentscope:agentscope-agui-spring-boot-starter`，**只升版本号**

CLAUDE.md 第 2 节的硬约束依旧有效：**不要**提前引入 OpenTelemetry / Micrometer / Jaeger（Day 7 §6-§7）；**不要**把 SubmitTool 的 dry-run 改成真发 HTTP。

## 1. 源码对比的关键发现

### 1.1 RC2 新增的、对本项目有用的能力

| 能力 | 入口 | 对应文件 |
|---|---|---|
| **内置 Structured Output** | `ReActAgent extends StructuredOutputCapableAgent`；`agent.call(msgs, Class<?>)` 或 `agent.call(msgs, JsonNode schema)`；结果用 `msg.getStructuredData(Class)` 取出 | `agentscope-core/src/main/java/io/agentscope/core/agent/StructuredOutputCapableAgent.java:127-197`；`Msg.java:269` |
| **Session/State 模块** | `JsonSession`/`InMemorySession`/`SessionManager` + `StateModule` 接口（`saveTo/loadFrom` 都有 default no-op）；`ReActAgent.saveTo/loadIfExists` + `StatePersistence` 分别控制 memory/toolkit/planNotebook | `core/session/*`、`core/state/*`、`ReActAgent.java:281-336` |
| **内置 Tracer** | `Tracer`/`TracerRegistry`/`NoopTracer`；在 callAgent/callModel/callTool/callFormat 四个点织入；`TracerRegistry.register(tracer)` + `enableTracingHook()` 全局生效 | `core/tracing/*` |
| **`enablePendingToolRecovery(boolean)`** | `ReActAgent.Builder` 新增此开关，注册 `PendingToolRecoveryHook` 给孤儿 pending tool call 补合成错误结果 | `ReActAgent.java:1383` |
| **AG-UI 消息转换支持 `role:"tool"`** | `AguiMessageConverter` 把 `role:"tool"` → `MsgRole.TOOL` + `ToolResultBlock` | `agentscope-extensions-agui/.../converter/AguiMessageConverter.java:54-78,149-155` |
| **PlanNotebook** | `ReActAgent.Builder.enablePlan()` / `.planNotebook(PlanNotebook)`；可经 `StatePersistence.planNotebookManaged()` 持久化 | `ReActAgent.java:1476,1593`；`core/plan/*` |

### 1.2 RC2 **没有**修的、必须继续 workaround 的坑

- **`AguiRequestProcessor.extractLatestUserMessage` bug 仍存在**：`agentscope-extensions-agui/.../processor/AguiRequestProcessor.java:170-199` 与 1.0.12 一字不差。`hasMemory(threadId)=true` 时仍只挑 `role="user"`，HITL 续跑前端推的 `role:"tool"` 回填被静默吃掉。
- **`AguiAgentRegistry.registerFactory` 仍是无参 `Supplier<Agent>`**：`agentscope-extensions-agui/.../registry/AguiAgentRegistry.java:79`，拿不到 threadId。
- → **`AguiAgentConfig` 自家 RouterFunction + `ThreadAgentResolver(hasMemory=false)` 整套必须原样保留**（这直接限定了改造 2 Session 的边界，见下）。

### 1.3 项目代码盘点（升级要碰的文件）

```
src/main/java/space/wlshow/scope/
├── ScopeApp.java                          (@SpringBootApplication 引导)
├── ScopeReplApp.java                      (备份 CLI 入口)
├── agent/AgentFactory.java                ★ Step 1
├── agent/RequirementParser.java           ★ Step 2
├── agent/ParseException.java              (保留)
├── config/AguiAgentConfig.java            (保留——RC2 没修 AG-UI 那条死路)
├── session/FileSession.java               ★ Step 3
├── todo/TodoManager.java                  ★ Step 3
├── tool/{FrontendCreateTools,SubmitTool,TodoQueryTools,TodoUpdateTools}.java
├── schema/SchemaValidator.java            (保留，作二次断言)
└── ...
```

## 2. 改造范围（第一章节 + 第二章节）

> 五项 + PlanNotebook 共 6 项，按优先级分三档。

### 2.1 第一章节：5 项

| # | 项 | 收益 | 优先级 |
|---|---|---|---|
| 1 | Structured Output 重写 `RequirementParser` | **最大** —— 干掉 stripFence + 3 次手写自纠错 | P0 |
| 2 | TodoManager→StateModule + JsonSession 即时落盘 | 大 —— 解决 Ctrl+C 退出 sessions 目录为空 | P0 |
| 3 | 内置 Tracer 收敛 `observability.Stage` | 中 —— 降耦合 | P2（可暂缓） |
| 4 | `enablePendingToolRecovery(false)` 加固 HITL | 零成本 —— 防 `IllegalStateException("Pending tool calls exist without results")` | P0（一行） |
| 5 | AG-UI `role:"tool"` 支持（已天然支持） | 无工作量 —— 观察项 | — |

### 2.2 第二章节：PlanNotebook

| # | 项 | 收益 | 优先级 |
|---|---|---|---|
| 6 | `enablePlan()` / `PlanNotebook` 显式建模"1 App + N Module + N Model" | 不确定 —— A/B 验证 | P3（独立分支） |

## 3. 落地步骤（建议顺序）

### Step 0 — 升 pom 并验证可拉取 + 编译

**改动**：`pom.xml:20`
```xml
<agentscope.version>1.1.0-RC2</agentscope.version>
```

**验证**：
```bash
mvn -U dependency:resolve   # RC2 是预发布版，确认本地 Maven 能拉到
mvn -q compile               # 暴露 API 破坏性变更
```

**已知结果**：RC2 包能拉到，无 API 破坏性变更，编译通过。

### Step 1 — 加 `enablePendingToolRecovery(false)`（一行）

**改动**：`AgentFactory.buildAnalystWithTools()` builder 链上加
```java
.enablePendingToolRecovery(false)  // HITL: 由我们自己管 pending tool 生命周期
```

**不动**：`buildAnalyst()` / `buildParser()`（不接 HITL，保持框架默认）。

### Step 2 — Structured Output 重写 `RequirementParser` ⚠️ 已踩坑

**核心 API**：`agent.call(List<Msg>, Class<?>)` 返回 `Mono<Msg>`，结果用 `msg.hasStructuredData()` / `msg.getStructuredData(AnalysisResult.class)` 取出。框架自动注册临时 `generate_response` 工具、按 schema 校验、不合规自动 `gotoReasoning()` 重试。

**⚠️ 关键决策——必须走 `Class<?>` 重载，不要走 `JsonNode schema` 重载**

实测踩过的坑（首次执行时撞到）：

> 项目原 `analysis-result.schema.json` 用 `$ref: "#/$defs/AppSpec"` 等 5 处内部引用。
> 走 `agent.call(msgs, schemaJsonNode)` 重载时：
> - `StructuredOutputCapableAgent.createStructuredOutputTool` 把内层 schema 包到 `properties.response` 下
> - `hoistDefsKey` 把 `$defs` 提升到外层 params 根
> - 但 `ToolExecutor` 的 networknt 校验器对带 `$schema`/`$id` 头的内嵌 schema 解析 `$ref` 时失败：
>   ```
>   Schema validation error: Reference /$defs/AppSpec cannot be resolved
>   ```
> - 工具校验失败 → 框架 reminder 重试 → 模型每次返回同一个 mock 响应 → maxIters 耗尽 → 拿不到 structured data

**正确做法**：走 `Class<AnalysisResult>` 重载。jackson 反射重新生成扁平 schema（无 `$ref`），绕开 hoisting 的坑。`SchemaValidator` 用项目原 schema 做**二次断言**，兜底反射 schema 漏掉的 pattern/enum 约束。

**代码骨架**（`RequirementParser.java`）：
```java
public AnalysisResult parse(String userRequirement) {
    Msg out = agent.call(
            List.of(Msg.builder()
                    .role(MsgRole.USER)
                    .content(TextBlock.builder().text(userRequirement).build())
                    .build()),
            AnalysisResult.class
    ).block();

    if (out == null) throw new ParseException("LLM 返回 null", List.of("agent.call() returned null"));
    if (!out.hasStructuredData()) {
        throw new ParseException("LLM 未产出符合 schema 的结构化输出",
                List.of("framework retry exhausted or model refused to call generate_response"));
    }
    AnalysisResult result = out.getStructuredData(AnalysisResult.class);

    // 二次断言：用项目原 schema（含 pattern/enum）兜底反射 schema 漏掉的约束
    JsonNode tree = Json.mapper().valueToTree(result);
    List<String> errors = validator.validate(tree).stream()
            .map(ValidationError::message).toList();
    if (!errors.isEmpty()) throw new ParseException("结构化输出二次校验未过", errors);

    return result;
}
```

**配套改动**：
- `AgentFactory.buildParser()` 把 `maxIters(2)` 调成 `maxIters(5)`（留余量让框架 schema 不合规时自动重试 `gotoReasoning()`）。
- **删掉**：`RequirementParser` 的三次重试循环、`buildRetryPrompt`、`Json.stripFence` 在 RequirementParser 路径的调用（`stripFence` 本身保留，`FrontendCreateTools` 路径可能仍用）。
- **保留**：`SchemaValidator`、`ParseException.lastErrors` 字段（二次断言失败仍用）。

### Step 2 配套——重写 `RequirementParserMockTest` 与 WireMock 固件 ⚠️ 已踩坑

**⚠️ 关键事实**：1.0.12 的 `analyst-ok.json` / `analyst-bad-fence.json` / `analyst-missing-app.json` 三份固件**完全失效**。RC2 框架走 OpenAI `tool_calls` 通道，固件必须模拟 `tool_calls`-shaped 响应。

**新固件 1**：`analyst-tool-ok.json`（模型正确调 generate_response）
```json
{
  "id": "chatcmpl-tool-ok",
  "object": "chat.completion",
  "created": 1736000000,
  "model": "doubao-pro",
  "choices": [{
    "index": 0,
    "finish_reason": "tool_calls",
    "message": {
      "role": "assistant",
      "content": null,
      "tool_calls": [{
        "id": "call_ok_1",
        "type": "function",
        "function": {
          "name": "generate_response",
          "arguments": "{\"response\":{\"app\":{\"name\":\"employeeMgr\",\"label\":\"员工档案管理\",\"type\":\"23\"},\"modules\":[{\"moduleName\":\"员工管理\",\"moduleId\":\"employeeMgmt\",\"moduleDesc\":\"维护员工档案\"}],\"models\":[{\"name\":\"employee\",\"type\":\"ENTITY\",\"pinyin\":\"yuangong\",\"tableName\":\"t_employee\",\"parentId\":\"\",\"fields\":[{\"comment\":\"主键\",\"name\":\"id\",\"dataType\":\"long\",\"usage\":\"primary\",\"relateModelType\":\"\",\"subs\":null}]}],\"warnings\":[],\"questions\":[]}}"
        }
      }]
    }
  }],
  "usage": { "prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150 }
}
```

**新固件 2**：`analyst-tool-skip.json`（模型未调工具，框架应触发 reminder 重试）
```json
{
  "id": "chatcmpl-tool-skip", "object": "chat.completion", "created": 1736000000, "model": "doubao-pro",
  "choices": [{
    "index": 0, "finish_reason": "stop",
    "message": {
      "role": "assistant",
      "content": "好的，我先来分析一下您的需求……（模型未调用 generate_response 工具）"
    }
  }],
  "usage": { "prompt_tokens": 100, "completion_tokens": 30, "total_tokens": 130 }
}
```

**测试场景调整**：
| 旧场景（1.0.12） | 新场景（RC2） |
|---|---|
| `okOnFirstAttempt` | `okOnFirstAttempt` —— 喂 `analyst-tool-ok.json`，断言只调 **1 次** LLM |
| `recoverFromFence` | **删** —— 结构化输出走 tool 通道，无 fence |
| `retryAfterMissingField` | `retryWhenLLMSkipsTool` —— scenario：先 skip，再 ok，断言 **≥2 次** |
| `giveUpAfterThree` | `throwsWhenLLMNeverCallsTool` —— 永远不调 tool，断言抛 `ParseException` |

**⚠️ 关键事实 2**：1.1.0-RC2 的 `StructuredOutputHook.handlePostActing` 在工具成功时调 `event.stopAgent()` **立即结束 run**，**无需后续 LLM 调用收尾**。所以 happy path 只有 1 次 LLM 请求，不要假设有 2 次。

**⚠️ 关键事实 3**：`OpenAIChatModel` 默认 `.stream(true)`。WireMock 固件是非 SSE 响应（`chat.completion`，不是 `chat.completion.chunk`），所以**测试构造模型时必须显式 `.stream(false)`**：
```java
OpenAIChatModel model = OpenAIChatModel.builder()
        .apiKey("test-key").modelName("doubao-pro")
        .baseUrl(server.baseUrl())
        .stream(false)                  // ← 不能漏
        .build();
```

**清理**：
- 删 `analyst-ok.json` / `analyst-bad-fence.json` / `analyst-missing-app.json`
- 不要写 `analyst-tool-done.json`（happy path 不需要第二次响应）

### Step 3 — TodoManager → `StateModule` + `JsonSession` 即时落盘

**(a) `TodoManager implements StateModule`**

```java
public record TodoState(JsonNode snapshot) implements State {}

public class TodoManager implements StateModule {
    // ...existing fields...
    @Override
    public void saveTo(Session s, SessionKey k) { s.save(k, "todos", new TodoState(getState())); }
    @Override
    public void loadFrom(Session s, SessionKey k) {
        s.get(k, "todos", TodoState.class).ifPresent(st -> loadState(st.snapshot()));
    }
}
```

**(b) `FileSession` → `JsonSession`**
- 用单例 `new JsonSession(Path.of("data","sessions"))`
- 按 threadId 做 `SimpleSessionKey.of(threadId)`
- `AguiAgentConfig.activeSessions` 改为按需 `todos.loadIfExists(session, key)`
- 落盘从"`@PreDestroy` 优雅关停才触发"改成 **`TodoChangeListener.onCreate/onStatusChange/onClear` 即时 `todos.saveTo(session, key)`**

**⚠️ 边界（必须遵守，否则会撞回老 bug）**：
**AG-UI 路径不要启用 memory 持久化。** `AguiAgentConfig.ThreadAgentResolver.hasMemory()` 必须保持 `false`，让 memory 继续由前端 `messages` 重建。否则会重新触发 `AguiRequestProcessor.extractLatestUserMessage` 吃掉 `role:"tool"` 回填的死路（RC2 未修）。
所以 Session 在 AG-UI 路径**只持久化 TodoManager**；memory 持久化只对 **CLI 入口 `ScopeReplApp`** 有意义（用 `agent.saveTo/loadIfExists` + `StatePersistence.memoryOnly()`）。

### Step 4（可选 P2）— `ScopeTracer implements Tracer`

把 `observability.Stage` 里 **LLM_CALL**、**TOOL_CALL** 两阶段的计时/日志收进 `Tracer.callModel` / `Tracer.callTool`。
**保留**手工 Stage 的 `INPUT`、`FRONTEND_DISPATCH`、`FRONTEND_CALLBACK`（在 agent runtime 之外，Tracer 织入点覆盖不到）。

注册：
```java
TracerRegistry.register(new ScopeTracer());
TracerRegistry.enableTracingHook();
```

### Step 5（独立 P3）— `enablePlan()` / `PlanNotebook`

在独立分支上 A/B：开/不开 plan 对比 todo 准确率与工具调用次数，再决定是否合入。**不与 Step 1-4 捆绑上线。**

## 4. 验证策略

```bash
# 编译
mvn -q compile

# 跑所有离线测试（live 测试默认跳过）
mvn -q test

# 只跑某个测试类
mvn -q test -Dtest=RequirementParserMockTest

# 跑真实 LLM 集成测试（需 ARK_API_KEY）
mvn -q -Dgroups=live test

# 起服务在浏览器手验 AG-UI 通道（最重要的回归点）
mvn spring-boot:run
cd frontend && npm run dev
```

**手验清单**（浏览器 5173 → 后端 8080）：
1. 输入需求 → todo 看板正确显示 → 后端日志有 `[AguiConfig] build agent for thread=... todos=N`
2. 点"提交" → 弹 HITL 确认框 → 点"确认" → 后端日志有 `[Submit] dispatched N items`
3. 关停 Ctrl+C → `data/sessions/<threadId>.json` 有内容（Step 3 即时落盘的关键回归）

## 5. 已踩过的坑速查（重点！）

| 坑 | 现象 | 解 |
|---|---|---|
| **Schema `$ref` hoisting** | `Reference /$defs/AppSpec cannot be resolved` | Step 2 走 `Class<?>` 重载，不走 `JsonNode schema` 重载 |
| **WireMock 流式响应** | 5 次 LLM 请求空转到 maxIters | 测试 `OpenAIChatModel.builder()` 加 `.stream(false)` |
| **happy path 请求数预期** | 期望 2 次实际 1 次 | RC2 工具成功即 `stopAgent()`，断言 `==1` |
| **1.0.12 固件全废** | 模型只返回 `content` 不返 `tool_calls` | 三份旧 fixture 必须重写成 `tool_calls`-shaped |
| **PowerShell 转义** | `mvn -Dtest=A#B` 报错 | 改 `mvn '-Dtest=A#B'` 或转义 `#` |
| **AGUI extractLatestUserMessage** | HITL `role:'tool'` 被吃掉 | RC2 没修，`AguiAgentConfig` 自家路由必须保留 |
| **AGUI registerFactory 无参** | 拿不到 threadId | RC2 没修，同上 |

## 6. 不可消除的边界

`AguiAgentConfig.scopeAguiRunRoutes` + `ThreadAgentResolver` 全套保留。Session 改造**不能**让 AG-UI 路径开 server-side memory。

## 7. 参考文件（按需精读的源码位置）

| 关注点 | 文件 |
|---|---|
| Structured Output 实现 | `.agentscope/agentscope-core/src/main/java/io/agentscope/core/agent/StructuredOutputCapableAgent.java:127-280` |
| Structured Output 流程钩子 | `.../core/agent/StructuredOutputHook.java:108-180`（`handlePostReasoning` reminder、`handlePostActing` stopAgent） |
| Msg 结构化数据访问 | `.../core/message/Msg.java:184-302`（hasContentBlocks/getContentBlocks/hasStructuredData/getStructuredData） |
| ReActAgent.Builder 新增项 | `.../core/ReActAgent.java:1207-1672`（含 `enablePendingToolRecovery`/`enablePlan`/`planNotebook`/`statePersistence`） |
| Session/State | `.../core/session/{Session,JsonSession,SessionManager}.java`、`.../core/state/{StateModule,StatePersistence,SessionKey}.java` |
| Tracer | `.../core/tracing/{Tracer,TracerRegistry}.java` |
| AGUI 仍存在的坑 | `.agentscope/agentscope-extensions/agentscope-extensions-agui/src/main/java/io/agentscope/core/agui/processor/AguiRequestProcessor.java:170-199`、`.../registry/AguiAgentRegistry.java:79` |
| AGUI role:"tool" 转换（已支持） | `.../agui/converter/AguiMessageConverter.java:54-78,149-155` |

## 8. 下个会话建议起手

```
@docs/agentscope-1.1.0-RC2-migration-plan.md
照本文档落地，从 Step 0 开始。
```

