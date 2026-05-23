# 09 · 集成：MCP / A2A / AG-UI 🚧

> **本章 TODO**——需抓取：
> - https://java.agentscope.io/_sources/en/task/mcp.md
> - https://java.agentscope.io/_sources/en/task/a2a.md
> - https://java.agentscope.io/_sources/en/task/agui.md
> - https://java.agentscope.io/_sources/en/integration/overview.md

## MCP（Model Context Protocol）

- AgentScope-Java 作为 **MCP 客户端**，可接入任意 MCP 兼容 Server
- 用法（待验证）：在 Toolkit 上注册 MCP 服务器，工具会作为外部工具暴露给 LLM
- 工具调用机制可能复用 [Schema-Only Tool](./04-tool-system.md#方式-3schema-only外部执行)

## A2A（Agent2Agent）

- 通过 **Nacos** 注册中心做分布式 Agent 服务发现
- 适合 Java 微服务生态——把 Agent 当成 SpringCloud 服务一样治理
- 跨进程/跨机器多 Agent 协作

## AG-UI Protocol

- 前端 ↔ Agent 通信协议（推测）
- 用于把 Agent 接入 Web 前端 / 桌面 GUI

## 待补充清单

- [ ] MCP 客户端注册 API
- [ ] MCP 工具命名空间冲突处理
- [ ] A2A 的服务注册元数据格式
- [ ] A2A 与 MsgHub 的关系
- [ ] AG-UI 的协议规格 / 与 WebSocket / SSE 的关系
- [ ] 各集成的端到端示例
