# 10 · 可观测性 / Hook / HITL / 安全中断 🚧

> **本章 TODO**——需抓取：
> - https://java.agentscope.io/_sources/en/task/hook.md
> - https://java.agentscope.io/_sources/en/task/hitl.md
> - https://java.agentscope.io/_sources/en/task/observability.md
> - https://java.agentscope.io/_sources/en/task/streaming.md

## Hook 系统

### 已知事件

- `PreReasoningEvent` / `PostReasoningEvent`
- `PreActingEvent` / `PostActingEvent`
- `RuntimeContextAwareHook`（Harness 引入，注入 sessionId/userId）

### 已知 Harness Hook 名单

- `WorkspaceContextHook` — system prompt 注入
- `CompactionHook` — 上下文压缩
- `ToolResultEvictionHook` — 大结果落盘
- `MemoryFlushHook` — 当日记忆蒸馏
- `SessionPersistenceHook` — 跨进程持久化
- `SubagentsHook` — 注入 task/task_output 工具

> 完整 Hook API、注册方式、优先级语义待补。

## HITL（Human-in-the-Loop）

- 通过 Hook 注入纠正与额外上下文
- 通过 `agent.interrupt(Msg)` 在执行中插入指令
- 通过 [Tool Suspend](./04-tool-system.md#工具挂起--恢复tool-suspend) 等人审

## 安全中断 / 优雅取消

```java
// 软中断（保留 context / tool state）
agent.interrupt();

// 中断 + 注入新指令
agent.interrupt(Msg.builder()
    .role(MsgRole.SYSTEM)
    .textContent("请用中文回答")
    .build());
```

- 中断后 `GenerateReason == INTERRUPTED`
- 下一次 `call(...)` 自动恢复

## 可观测性

- 原生集成 **OpenTelemetry**
- 配套 **AgentScope Studio** 可视化调试（独立产品）

### 已知 Studio 能力（推断）

- Trace 追踪每轮 reasoning/acting
- Token 用量统计
- 工具调用时序
- Memory / Session 状态查看

## Streaming

- `agent.stream(msg)` → `Flux<Msg>`
- 增量发出 `TextBlock` / `ThinkingBlock` / `ToolUseBlock`
- 工具中 `ToolEmitter.emit(...)` 的中间进度**只有 Hook 看得到**，不会进上下文

## 待补充清单

- [ ] Hook 完整事件清单与字段
- [ ] Hook 注册 API（`hooks(...)` 入参类型）
- [ ] Hook 优先级与执行顺序
- [ ] HITL 标准流程示例（待人审 → 决策 → 续跑）
- [ ] OpenTelemetry trace 字段约定
- [ ] AgentScope Studio 部署方式
- [ ] Streaming 与 SSE / WebSocket 的桥接
