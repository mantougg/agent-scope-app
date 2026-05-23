# 01 · 项目总览

## 定位

**AgentScope-Java** 是阿里通义实验室开源的 **Agent-Oriented Programming** 框架的 Java 实现，主打"用智能体范式构建 LLM 应用"。Apache-2.0，GitHub 3.2k★。

Slogan: *Agent-Oriented Programming for Building LLM Applications*

## 核心卖点

| 类别 | 能力 |
|------|------|
| 🎯 智能体控制 | ReAct 推理-行动循环、安全中断（Safe Interruption）、优雅取消（Graceful Cancellation）、HITL |
| 🛠️ 内置工具 | **PlanNotebook**（结构化任务规划）、**Structured Output**（自纠错 → POJO）、长期记忆、RAG（自托管 / 阿里百炼） |
| 🔌 协议集成 | **MCP** 客户端、**A2A**（Agent2Agent，基于 Nacos）、AG-UI |
| 🚀 生产级 | Project Reactor 响应式；**GraalVM 原生镜像 ~200ms 冷启动**；OpenTelemetry + AgentScope Studio 可视化；Runtime Sandbox |

## 版本

- **稳定**：`1.0.12`
- **预览**：`1.1.0-RC1`（Harness 子系统首版）
- 截至 2026-05 累计 18 个 release

## 模块（顶层目录）

```
agentscope-java/
├── agentscope-core/              # 核心：Msg / Agent / Toolkit / Model / Memory / Hook
├── agentscope-extensions/        # provider / RAG / A2A / AG-UI 扩展
├── agentscope-harness/           # 1.1 新增：生产级运行时（Workspace/Session/Sandbox/...）
├── agentscope-examples/          # 官方示例
├── agentscope-distribution/      # 发行打包
├── agentscope-dependencies-bom/  # 依赖 BOM
└── docs/
```

## 依赖坐标

**环境**：JDK 17+

**Maven**:
```xml
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope</artifactId>
  <version>1.0.12</version>
</dependency>
```

**Gradle**:
```groovy
implementation 'io.agentscope:agentscope:1.0.12'
```

## Hello World

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("You are a helpful AI assistant.")
    .model(DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-max")
        .build())
    .build();

Msg response = agent.call(Msg.builder()
        .textContent("Hello!")
        .build()).block();         // 注意：Mono#block() 同步等待

System.out.println(response.getTextContent());
```

## 关键约束

- **同一 Agent 实例不可并发调用**（`checkRunning(true)` 默认开启）。高并发场景需池化或每请求新建。
- **响应式 API**：所有调用返回 `Mono<Msg>` / `Flux<Msg>`，习惯 Reactor。
- **索引滞后**：使用 codegraph 等 watcher 时，写后查询有 ~500ms 抖动。

## 三种典型场景

1. **单 Agent + 工具**：纯 ReActAgent + Toolkit（参见 03、04）
2. **多 Agent 编排**：Pipeline / Routing / Supervisor（参见 08，待补）
3. **生产级 Coding Agent**：HarnessAgent + Sandbox + 长期记忆（参见 07）
