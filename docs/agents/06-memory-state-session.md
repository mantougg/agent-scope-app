# 06 · Memory / State / Session 🚧

> **本章 TODO**——需要抓取以下原始文档后补全：
> - https://java.agentscope.io/_sources/en/task/memory.md
> - https://java.agentscope.io/_sources/en/task/state.md
> - https://java.agentscope.io/_sources/en/task/session.md

## 已知要点（来自其他章节）

### Memory

- 默认实现：`InMemoryMemory`（短期）
- `ReActAgent` 自动追加 user msg / tool_use / tool_result / assistant msg
- 长期记忆通过 `ReActAgent.builder().longTermMemory(...).longTermMemoryMode(...)` 注入
- 三种 mode：
  - `AGENT_CONTROL` — Agent 自主决定写入/检索（通过工具）
  - `STATIC_CONTROL` — 框架按规则自动管理
  - `BOTH` — 混合
- Harness 把长期记忆物化成 `MEMORY.md` 文件 + 后台 `MemoryConsolidator`（详见 [07-harness](./07-harness.md)）

### State

- `StateModule` — 可序列化状态接口
- 任何实现了 `StateModule` 的组件都能参与会话持久化

### Session

- `JsonSession` — JSON 文件落地
- `SessionManager` — 按 `sessionId` 管理
- Harness 的 `SessionPersistenceHook` 自动按 `sessionId` 写入工作区，下次调用恢复

## 待补充清单

- [ ] Memory 接口完整方法签名
- [ ] 长期记忆的语义检索机制（embedding？关键词？）
- [ ] 多租户隔离的 API
- [ ] StateModule 的具体实现示例
- [ ] Session 的并发安全模型
- [ ] 自定义 Memory backend（Redis / SQLite / 向量库）的扩展点

## 关联

- [02-core-concepts § Memory](./02-core-concepts.md#3-memory)
- [03-react-agent](./03-react-agent.md) 中的 `longTermMemory` / `longTermMemoryMode` 参数
- [07-harness § Memory](./07-harness.md)
