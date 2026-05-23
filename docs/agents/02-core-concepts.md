# 02 · 核心概念

AgentScope-Java 的五大抽象：**Msg / Agent / Memory / Toolkit / Model**。理解这五个就掌握了 80%。

## 1. Msg（消息）

所有 Agent 间、Agent ↔ LLM、Agent ↔ Memory 通信的载体。

### 字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String | 唯一 id |
| `name` | String | 发送者名 |
| `role` | `MsgRole` | USER / ASSISTANT / SYSTEM / TOOL |
| `content` | `List<ContentBlock>` | 多模态内容块 |
| `timestamp` | Instant | |
| `metadata` | Map | 自定义元数据 |

### Content Block 类型

| Block | 用途 |
|-------|------|
| `TextBlock` | 纯文本 |
| `ImageBlock` / `AudioBlock` / `VideoBlock` | 多模态 |
| `ThinkingBlock` | 推理痕迹（reasoning models） |
| `ToolUseBlock` | LLM 发起的工具调用 |
| `ToolResultBlock` | 工具执行结果 |

### Response 元数据

- `getGenerateReason()` → `MODEL_STOP` / `TOOL_CALLS` / `STRUCTURED_OUTPUT` / `TOOL_SUSPENDED` / `INTERRUPTED` / `MAX_ITERATIONS`
- `getChatUsage()` → token 消耗

### 示例

```java
// 纯文本
Msg text = Msg.builder()
    .name("user")
    .textContent("What's the weather in Beijing?")
    .build();

// 多模态
Msg multimodal = Msg.builder()
    .name("user")
    .content(List.of(
        TextBlock.builder().text("What is in this image?").build(),
        ImageBlock.builder()
            .source(new URLSource("https://example.com/photo.jpg"))
            .build()
    ))
    .build();
```

## 2. Agent

```java
public interface Agent extends CallableAgent, StreamableAgent, ObservableAgent {
    String getAgentId();
    String getName();
    void interrupt();
    void interrupt(Msg msg);
}
```

- **有状态**：自带 Memory / Toolkit / 配置
- **不可并发**：同一实例同一时刻只能跑一次 `call` / `stream`
- 实现类：`ReActAgent`（主力）、`UserAgent`（人机交互占位）、`HarnessAgent`（生产级，1.1+）

### Mono vs Flux

| 方法 | 返回 | 用途 |
|------|------|------|
| `agent.call(msg)` | `Mono<Msg>` | 拿到最终一条回复 |
| `agent.stream(msg)` | `Flux<Msg>` | 流式增量输出 |
| `.block()` | 同步等待 | 仅适用于非响应式上下文 |

## 3. Memory

存对话历史，让 Agent 跨轮维持上下文。

- **短期记忆**：`InMemoryMemory`（默认）。`ReActAgent` 自动追加 user msg / tool use / tool result / assistant msg。
- **长期记忆**：通过 `longTermMemory` + `longTermMemoryMode`（`AGENT_CONTROL` / `STATIC_CONTROL` / `BOTH`）。
- **Harness 加强**：`MEMORY.md` + 后台 `MemoryConsolidator`（详见 [07-harness](./07-harness.md)）

详细 API 见 [06-memory-state-session](./06-memory-state-session.md)（待补）。

## 4. Toolkit & Tool

让 LLM 调用真实操作（DB / API / 计算 / 文件 / shell）。

### 最简定义

```java
public class WeatherService {
    @Tool(description = "Get weather for a specified city")
    public String getWeather(
        @ToolParam(name = "city", description = "City name") String city
    ) {
        return city + ": Sunny, 25°C";
    }
}

Toolkit toolkit = new Toolkit();
toolkit.registerTool(new WeatherService());
```

> ⚠️ `@ToolParam.name` 是必填的——Java 默认不保留参数名。

完整工具系统见 [04-tool-system](./04-tool-system.md)。

## 5. Model（ChatModel）+ Formatter

`ChatModel` 是各 LLM provider 的统一抽象。`Formatter` 负责把 AgentScope 的 `Msg` 翻译成各家 API 的格式。

### 内置 ChatModel

| Provider | 类 |
|----------|-----|
| 阿里 DashScope | `DashScopeChatModel` |
| OpenAI / 兼容（DeepSeek、vLLM …） | `OpenAIChatModel` |
| Anthropic | `AnthropicChatModel` |
| Google Gemini / Vertex AI | `GeminiChatModel` |
| Ollama 自托管 | `OllamaChatModel` |

### 内置 Formatter

`DashScopeChatFormatter` / `OpenAIChatFormatter` / `AnthropicChatFormatter` / `GeminiChatFormatter` / `OllamaChatFormatter` / `DeepSeekFormatter` / `GLMFormatter`，按 Model 类型自动选择。

多 Agent 场景还有 `*MultiAgentFormatter`，用 `<history>` 标签合并多 Agent 历史。

详见 [05-model-providers](./05-model-providers.md)。

## ReAct 循环（把五个串起来）

```
┌─────────────────────────────────────────────────┐
│  User Msg                                       │
└────────────────────┬────────────────────────────┘
                     ▼
┌─────────────────────────────────────────────────┐
│  Memory.add(userMsg)                            │
└────────────────────┬────────────────────────────┘
                     ▼
┌─────────────────────────────────────────────────┐
│  Reasoning:                                     │
│    Formatter.format(Memory.list())              │
│    → ChatModel.chat(prompt, tools)              │
└────────────────────┬────────────────────────────┘
                     ▼
        ┌──────tool_calls?──────┐
        │                       │
       yes                      no
        │                       │
        ▼                       ▼
┌─────────────────┐   ┌──────────────────────────┐
│ Acting:         │   │ return final Msg          │
│ Toolkit.call()  │   │ (Memory.add(assistant))   │
│ Memory.add()    │   └──────────────────────────┘
└────────┬────────┘
         │
         └────── loop (≤ maxIters) ───────┐
                                          │
                                          ▼
                                   (回 Reasoning)
```

终止条件：`GenerateReason` 命中 `MODEL_STOP` / `STRUCTURED_OUTPUT` / `INTERRUPTED` / `MAX_ITERATIONS` / `TOOL_SUSPENDED`。

## Hook（横切扩展）

`PreReasoningEvent` / `PostReasoningEvent` / `PreActingEvent` / `PostActingEvent` 等事件 → 注入自定义逻辑。Harness 的所有能力都靠 Hook + Toolkit 注入。详见 [10-observability-hitl](./10-observability-hitl.md)（待补）。

## State / Session

- `StateModule`：可序列化的状态接口
- `JsonSession` / `SessionManager`：跨进程持久化
- Harness 的 `SessionPersistenceHook` 自动按 `sessionId` 保存/恢复
