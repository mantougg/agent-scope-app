# 03 · ReActAgent

`ReActAgent` 是 AgentScope-Java 的主力 Agent 实现，采用经典 **Reasoning + Acting** 循环。

## 构造参数全表

```java
ReActAgent agent = ReActAgent.builder()
    .name("...")                    // 必填
    .model(...)                     // 必填
    // 以下全部可选：
    .sysPrompt("...")               // System prompt（推荐）
    .description("...")             // Agent 用途描述（作为子 Agent 时给上级看）
    .toolkit(toolkit)               // 工具注册
    .memory(memory)                 // 短期记忆，默认 InMemoryMemory
    .longTermMemory(ltm)            // 长期记忆
    .longTermMemoryMode(...)        // AGENT_CONTROL / STATIC_CONTROL / BOTH
    .generateOptions(...)           // temperature / topP / maxTokens / toolChoice ...
    .toolExecutionContext(ctx)      // 工具注入业务对象用
    .planNotebook(...)              // 计划管理（或 .enablePlan()）
    .maxIters(10)                   // ReAct 循环上限，默认 10
    .checkRunning(true)             // 防并发，默认 true
    .modelExecutionConfig(...)      // 模型调用超时/重试
    .toolExecutionConfig(...)       // 工具调用超时/重试
    .hooks(...)                     // Hook 列表
    .build();
```

## ReAct 循环

每轮：
1. **Reasoning**：读 Memory → Formatter → 调 Model
2. **判断**：响应是否包含 `ToolUseBlock`？
3. **Acting**（若需要）：Toolkit 执行 → 结果写入 Memory → 回到步骤 1
4. **退出**：无工具调用、达 `maxIters`、收到 `interrupt()`、`ToolSuspendException`

## 流式 vs 同步

```java
// 同步——拿到最终回复
Msg final = agent.call(userMsg).block();

// 流式——增量观察（含 thinking / 中间 tool 调用）
agent.stream(userMsg).subscribe(msg -> {
    if (msg.hasContentBlock(TextBlock.class)) {
        System.out.print(msg.getTextContent());
    }
});
```

## 超时与重试

```java
ExecutionConfig modelCfg = ExecutionConfig.builder()
    .timeout(Duration.ofMinutes(2))
    .maxAttempts(3)
    .initialBackoff(Duration.ofSeconds(1))
    .maxBackoff(Duration.ofSeconds(10))
    .backoffMultiplier(2.0)
    .build();

ExecutionConfig toolCfg = ExecutionConfig.builder()
    .timeout(Duration.ofSeconds(30))
    .maxAttempts(1)              // 工具通常不重试
    .build();

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .modelExecutionConfig(modelCfg)
    .toolExecutionConfig(toolCfg)
    .build();
```

## 工具执行上下文（依赖注入）

把不该暴露给 LLM 的业务对象（用户身份、DB Session、租户上下文…）注入工具：

```java
public class UserContext {
    private final String userId;
    public UserContext(String userId) { this.userId = userId; }
    public String getUserId() { return userId; }
}

ToolExecutionContext ctx = ToolExecutionContext.builder()
    .register(new UserContext("user-123"))
    .build();

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .toolkit(toolkit)
    .toolExecutionContext(ctx)
    .build();

// 工具中直接声明参数，自动注入（不需要 @ToolParam）
@Tool(name = "query", description = "Query data")
public String query(
        @ToolParam(name = "sql") String sql,
        UserContext userCtx       // ← 自动注入
) {
    return "result for " + userCtx.getUserId();
}
```

## 计划管理（PlanNotebook）

```java
// 一键开启
ReActAgent agent = ReActAgent.builder()
    .name("Assistant").model(model)
    .enablePlan()
    .build();

// 自定义
PlanNotebook nb = PlanNotebook.builder().maxSubtasks(15).build();
ReActAgent agent = ReActAgent.builder()
    .name("Assistant").model(model)
    .planNotebook(nb)
    .build();
```

`PlanNotebook` 自带工具让 LLM 创建/暂停/恢复/修改多个并发计划，适合长任务。

## 完整 Hello World

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;

public class QuickStart {

    public static void main(String[] args) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SimpleTools());

        ReActAgent jarvis = ReActAgent.builder()
            .name("Jarvis")
            .sysPrompt("You are an assistant named Jarvis.")
            .model(DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen3-max")
                .build())
            .toolkit(toolkit)
            .build();

        Msg response = jarvis.call(
            Msg.builder().textContent("Hello Jarvis, what time is it?").build()
        ).block();

        System.out.println(response.getTextContent());
    }
}

class SimpleTools {
    @Tool(name = "get_time", description = "Get current time")
    public String getTime(
        @ToolParam(name = "zone", description = "Timezone") String zone
    ) {
        return java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
```

## UserAgent（人机交互）

```java
UserAgent user = UserAgent.builder().name("User").build();
Msg input = user.call(null).block();   // 阻塞读取用户输入（终端）
```

常用于 MsgHub / 多智能体场景中代表"人"。

## 安全中断（Safe Interruption）

```java
agent.interrupt();                        // 软中断：保留 context / tool state
agent.interrupt(Msg.builder()
    .role(MsgRole.SYSTEM)
    .textContent("纠正：请用中文回答")
    .build());                            // 中断同时注入新指令
```

中断后 `GenerateReason == INTERRUPTED`；下一次 `call()` 可以恢复。

## 关于 Hook 注入点

文档列出 Hook 围绕 reasoning / acting 两阶段。**完整事件清单 TODO**——见 [10-observability-hitl](./10-observability-hitl.md)。
