# 04 · Tool 系统

## 三种定义工具的方式

### 方式 1：注解（最常用）

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

- `@ToolParam.name` **必填**（Java 不保留参数名）
- `@Tool.strict = true` 让支持严格 schema 的模型严格遵守签名
- 自动生成 JSON Schema 供 LLM 理解参数

### 方式 2：`AgentTool` 接口（细粒度控制）

需要完全控制 schema 时用：

```java
public class CustomTool implements AgentTool {
    @Override public String getName() { return "custom_tool"; }
    @Override public String getDescription() { return "..."; }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of("query", Map.of("type", "string")),
            "required", List.of("query")
        );
    }

    @Override public Boolean getStrict() { return true; }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String q = (String) param.getInput().get("query");
        return Mono.just(ToolResultBlock.text("Result: " + q));
    }
}
```

### 方式 3：Schema-Only（外部执行）

只注册 schema，没有本地实现——LLM 调用时自动挂起，由外部系统填回结果。

```java
ToolSchema schema = ToolSchema.builder()
    .name("query_database")
    .description("Query external database")
    .parameters(Map.of(
        "type", "object",
        "properties", Map.of("sql", Map.of("type", "string")),
        "required", List.of("sql")
    ))
    .strict(true)
    .build();

toolkit.registerSchema(schema);
toolkit.registerSchemas(List.of(s1, s2));   // 批量

toolkit.isExternalTool("query_database");   // true
```

## 返回类型

| 返回 | 行为 |
|------|------|
| `String` / `int` / 任意对象 | 同步，自动包成 `ToolResultBlock` |
| `Mono<T>` | 异步 |
| `Flux<T>` | 流式 |
| `ToolResultBlock` | 完全控制（文本/图像/错误码） |

### 异步示例

```java
@Tool(description = "Async search")
public Mono<String> search(@ToolParam(name = "query") String query) {
    return webClient.get().uri("/search?q=" + query)
        .retrieve().bodyToMono(String.class);
}
```

### 流式中间进度（仅 Hook 可见，不发给 LLM）

```java
@Tool(description = "Generate data")
public ToolResultBlock generate(
    @ToolParam(name = "count") int count,
    ToolEmitter emitter            // ← 自动注入
) {
    for (int i = 0; i < count; i++) {
        emitter.emit(ToolResultBlock.text("Progress " + i));
    }
    return ToolResultBlock.text("Completed");
}
```

## Toolkit 配置

```java
Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
    .parallel(true)                    // 同轮多工具并行
    .allowToolDeletion(false)          // 禁止运行时删除
    .executionConfig(ExecutionConfig.builder()
        .timeout(Duration.ofSeconds(30))
        .build())
    .build());
```

| 选项 | 默认 |
|------|------|
| `parallel` | false |
| `allowToolDeletion` | true |
| `executionConfig.timeout` | 5 min |

## 工具挂起 / 恢复（Tool Suspend）

把工具变成"外部异步" — 适合人审、外部 API 回调、长任务。

```java
@Tool(name = "external_api", description = "Call external API")
public ToolResultBlock callExt(@ToolParam(name = "url") String url) {
    throw new ToolSuspendException("Awaiting external API: " + url);
}
```

调用端：

```java
Msg response = agent.call(userMsg).block();

if (response.getGenerateReason() == GenerateReason.TOOL_SUSPENDED) {
    for (ToolUseBlock t : response.getContentBlocks(ToolUseBlock.class)) {
        // ... 外部执行，拿到 result ...
        Msg toolResult = Msg.builder()
            .role(MsgRole.TOOL)
            .content(ToolResultBlock.of(t.getId(), t.getName(),
                TextBlock.builder().text("external result").build()))
            .build();
        response = agent.call(toolResult).block();   // 恢复执行
    }
}
```

## Tool Groups（动态启用/禁用）

适合多角色 Agent / 权限分级。

```java
toolkit.createToolGroup("basic", "Basic Tools", true);   // 默认激活
toolkit.createToolGroup("admin", "Admin Tools", false);

toolkit.registration()
    .tool(new BasicTools())
    .group("basic")
    .apply();

toolkit.updateToolGroups(List.of("admin"), true);    // 启用
toolkit.updateToolGroups(List.of("basic"), false);   // 停用
```

### Meta Tools

```java
toolkit.registerMetaTool();
// 给 LLM 提供 reset_equipped_tools 工具——它可以自己切换激活的工具组
```

## 预设参数（隐藏敏感参数）

```java
public class EmailService {
    @Tool(description = "Send email")
    public String send(
        @ToolParam(name = "to") String to,
        @ToolParam(name = "subject") String subject,
        @ToolParam(name = "apiKey") String apiKey   // LLM 看不到
    ) { ... }
}

toolkit.registration()
    .tool(new EmailService())
    .presetParameters(Map.of(
        "send", Map.of("apiKey", System.getenv("EMAIL_API_KEY"))
    ))
    .apply();
```

LLM 视角只看到 `to` + `subject`，`apiKey` 自动注入。

## 内置工具

### 文件

```java
import io.agentscope.core.tool.file.ReadFileTool;
import io.agentscope.core.tool.file.WriteFileTool;

// 不安全（全盘）
toolkit.registerTool(new ReadFileTool());
toolkit.registerTool(new WriteFileTool());

// 安全模式（推荐）：限制作用范围
toolkit.registerTool(new ReadFileTool("/safe/workspace"));
toolkit.registerTool(new WriteFileTool("/safe/workspace"));
```

| 工具 | 方法 |
|------|------|
| ReadFileTool | `view_text_file`（按行范围）/ `list_directory` |
| WriteFileTool | `write_text_file`（创建/覆盖/替换）/ `insert_text_file`（行级插入） |

### Shell

```java
import io.agentscope.core.tool.coding.ShellCommandTool;

Function<String, Boolean> approver = cmd -> askUserForApproval(cmd);
toolkit.registerTool(new ShellCommandTool(allowedCommands, approver));
```

### 多模态

```java
import io.agentscope.core.tool.multimodal.DashScopeMultiModalTool;
import io.agentscope.core.tool.multimodal.OpenAIMultiModalTool;

toolkit.registerTool(new DashScopeMultiModalTool(System.getenv("DASHSCOPE_API_KEY")));
toolkit.registerTool(new OpenAIMultiModalTool(System.getenv("OPENAI_API_KEY")));
```

## 与 MCP 集成

🚧 **TODO**：参见 [09-integration-mcp-a2a](./09-integration-mcp-a2a.md)。文档主页面已提及但本笔记尚未抓取。

## 关于"工具结果驱逐"

当工具返回非常大（整文件、长日志），Harness 的 `ToolResultEvictionHook` 会把结果落盘，对话上下文只保留首尾预览 + 占位符。需要时通过 `filesystem` 工具读取完整内容。详见 [07-harness](./07-harness.md)。

## 官方示例

仓库 `agentscope-examples/documentation/quickstart/`：
- `ToolCallingExample.java`
- `ToolGroupExample.java`
- `MultiModalToolExample.java`
