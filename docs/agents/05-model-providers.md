# 05 · Model Provider 与 ChatModel

## 内置 Provider

5 个一线 provider，全部支持 **Streaming / Tools / Vision / Reasoning**：

| Provider | 类 | 说明 |
|----------|-----|------|
| 阿里 DashScope | `DashScopeChatModel` | 通义 Qwen 系列 |
| OpenAI | `OpenAIChatModel` | **兼容 DeepSeek / vLLM / 任何 OpenAI 协议端点** |
| Anthropic | `AnthropicChatModel` | Claude |
| Google Gemini | `GeminiChatModel` | 同时支持 Gemini API & Vertex AI |
| Ollama | `OllamaChatModel` | 自托管 |

## 构造示例

### DashScope

```java
DashScopeChatModel model = DashScopeChatModel.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("qwen3-max")
    .build();
```

### OpenAI 兼容（DeepSeek 示例）

```java
OpenAIChatModel model = OpenAIChatModel.builder()
    .apiKey("...")
    .modelName("deepseek-chat")
    .baseUrl("https://api.deepseek.com")
    .build();
```

### Anthropic

```java
AnthropicChatModel model = AnthropicChatModel.builder()
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .modelName("claude-sonnet-4-5-20250929")
    .build();
```

### Gemini（Vertex AI 模式）

```java
GeminiChatModel model = GeminiChatModel.builder()
    .modelName("gemini-2.0-flash")
    .project("your-gcp-project")
    .location("us-central1")
    .vertexAI(true)
    .credentials(GoogleCredentials.getApplicationDefault())
    .build();
```

### Ollama

```java
OllamaChatModel model = OllamaChatModel.builder()
    .modelName("qwen3-max")
    .baseUrl("http://localhost:11434")
    .build();
```

## 流式控制

各 builder 上的 `.stream(boolean)` 字段，多数厂商默认 true。Gemini 用 `.streamEnabled(boolean)`。
DashScope 开启 thinking 模式时自动 `stream=true`。

## GenerateOptions（跨厂商生成参数）

```java
GenerateOptions options = GenerateOptions.builder()
    .temperature(0.7)
    .topP(0.9)
    .topK(40)
    .maxTokens(2000)
    .maxCompletionTokens(...)
    .thinkingBudget(...)             // 推理预算
    .reasoningEffort(...)            // OpenAI o1 系列
    .frequencyPenalty(...)
    .presencePenalty(...)
    .seed(42L)
    .toolChoice(new ToolChoice.Auto())
    .executionConfig(execConfig)
    .build();
```

> ⚠️ **API 不一致**：OpenAI 用 `.generateOptions(...)`，DashScope/Ollama 用 `.defaultOptions(...)`。
> Ollama 还可走原生 `OllamaOptions`（`numCtx` / `repeatPenalty` 等 40+ 参数），或用 `OllamaOptions.fromGenerateOptions(opts)` 转换。

## ToolChoice 策略

```java
new ToolChoice.Auto()                  // 模型自己决定
new ToolChoice.None()                  // 不允许调用工具
new ToolChoice.Required()              // 必须调用某个工具
new ToolChoice.Specific("tool_name")   // 指定调用某工具
```

## ModelRegistry（字符串 ID 解析）

避免把具体 builder 写死在业务代码里：

```java
ModelRegistry.register("my-gpt4o", tunedModel);

HarnessAgent agent = HarnessAgent.builder()
    .name("demo")
    .model("dashscope:qwen-max")    // ← 字符串 id
    .workspace(workspace)
    .build();
```

### 内置前缀

| 前缀 | 解析 |
|------|------|
| `openai:xxx` | `OpenAIChatModel` |
| `dashscope:xxx` | `DashScopeChatModel` |
| `anthropic:xxx` | `AnthropicChatModel` |
| `gemini:xxx` | `GeminiChatModel` |
| `ollama:xxx` | `OllamaChatModel` |
| `qwen-*` | 裸名直通 DashScope |

### 主要 API

- `register(id, model)`
- `registerFactory(regex, factory)` — 按正则匹配前缀
- `resolve(id)` — 工厂模式有缓存；命名注册无缓存
- `canResolve(id)`
- `reset()`

## Formatter

每个 provider 都有两套 Formatter：

- **Single-Agent**：`DashScopeChatFormatter` 等
- **Multi-Agent**：`DashScopeMultiAgentFormatter` 等
  - 合并多 Agent 历史
  - 用 `<history></history>` 标签包装
  - 适用于 Pipeline / MsgHub

通常由 ChatModel 类型自动选择，需要时显式指定。

## 超时与重试

```java
ExecutionConfig exec = ExecutionConfig.builder()
    .timeout(Duration.ofMinutes(2))
    .maxAttempts(3)
    .initialBackoff(Duration.ofSeconds(1))
    .maxBackoff(Duration.ofSeconds(10))
    .backoffMultiplier(2.0)
    .build();

GenerateOptions opts = GenerateOptions.builder()
    .executionConfig(exec)
    .build();
```

也可以在 `ReActAgent.builder().modelExecutionConfig(exec)` 上配置（见 03）。

## 选型建议

| 场景 | 推荐 |
|------|------|
| 中国大陆 + 多模态 + 国产化 | DashScope / qwen-max / qwen-vl |
| 海外 + 顶级编码能力 | Anthropic claude-sonnet-4-x |
| 性价比 / 推理模型 | DeepSeek（走 OpenAI 兼容） |
| 私有部署 / 离线 | Ollama + qwen / llama |
| Google Cloud 生态 | Gemini via Vertex |
