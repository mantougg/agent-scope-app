# AgentScope-Java 学习笔记

本目录用于沉淀 [AgentScope-Java](https://github.com/agentscope-ai/agentscope-java) 框架的学习与实践笔记，便于后续补充与查阅。

## 索引

| # | 文件 | 内容 | 状态 |
|---|------|------|------|
| 01 | [01-overview.md](./01-overview.md) | 项目定位、版本、模块、依赖坐标、快速开始 | ✅ |
| 02 | [02-core-concepts.md](./02-core-concepts.md) | Msg / Agent / Memory / Toolkit / Model 五大抽象 | ✅ |
| 03 | [03-react-agent.md](./03-react-agent.md) | `ReActAgent` 构造参数、ReAct 循环、执行控制 | ✅ |
| 04 | [04-tool-system.md](./04-tool-system.md) | `@Tool` 注解、`Toolkit`、挂起恢复、工具组、内置工具 | ✅ |
| 05 | [05-model-providers.md](./05-model-providers.md) | DashScope/OpenAI/Anthropic/Gemini/Ollama、`GenerateOptions`、`ModelRegistry` | ✅ |
| 06 | [06-memory-state-session.md](./06-memory-state-session.md) | 短期/长期记忆、`StateModule`、`Session` | 🚧 待补 |
| 07 | [07-harness.md](./07-harness.md) | Harness 子系统（1.1 新增）：Workspace / Compaction / Sandbox / Subagent | ✅ |
| 08 | [08-multi-agent.md](./08-multi-agent.md) | Pipeline / Routing / Supervisor / Handoffs / Debate / MsgHub | 🚧 待补 |
| 09 | [09-integration-mcp-a2a.md](./09-integration-mcp-a2a.md) | MCP 客户端、A2A（Nacos）、AG-UI | 🚧 待补 |
| 10 | [10-observability-hitl.md](./10-observability-hitl.md) | OpenTelemetry + Studio、Hook、HITL、安全中断/取消 | 🚧 待补 |
| 11 | [11-vs-python.md](./11-vs-python.md) | 与 Python 版 AgentScope 的差异 | ✅（推断） |
| 99 | [_links.md](./_links.md) | 上游一手资料链接 | ✅ |

## 实战课程

> 一周从 0 到成品的实战路线见 **[../learning.md](../learning.md)**。该课程基于本目录的笔记展开，每天对应一组章节。

## 阅读建议

1. 先看 [01-overview](./01-overview.md) 建立总体印象
2. 再看 [02-core-concepts](./02-core-concepts.md) 掌握五大抽象
3. 跑通 [03-react-agent](./03-react-agent.md) 的 Hello-World
4. 按需深入 04/05/07
5. 想做生产级 / coding agent 直接看 [07-harness](./07-harness.md)

## 后续补充优先级

- **P0**: 06 Memory/Session 细节、10 Hook 注入点清单（影响 ReAct 扩展）
- **P1**: 08 多智能体编排（与现有项目契合度待评估）
- **P2**: 09 MCP/A2A/AG-UI 集成
- **P3**: RAG、Structured Output、Streaming、Multimodal 等独立小专题

> 笔记基于 1.0.12 稳定版 + 1.1.0-RC1 预览，2026-05 整理。框架仍在迭代，请以 [官方文档](https://java.agentscope.io/) 为准。
