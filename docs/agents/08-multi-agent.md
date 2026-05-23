# 08 · 多智能体编排 🚧

> **本章 TODO**——需抓取以下原始文档补全：
> - https://java.agentscope.io/_sources/en/multi-agent/overview.md
> - https://java.agentscope.io/_sources/en/multi-agent/pipeline.md
> - https://java.agentscope.io/_sources/en/multi-agent/workflow.md
> - https://java.agentscope.io/_sources/en/multi-agent/routing.md
> - https://java.agentscope.io/_sources/en/multi-agent/skills.md
> - https://java.agentscope.io/_sources/en/multi-agent/subagent.md
> - https://java.agentscope.io/_sources/en/multi-agent/supervisor.md
> - https://java.agentscope.io/_sources/en/multi-agent/handoffs.md
> - https://java.agentscope.io/_sources/en/multi-agent/multiagent-debate.md
> - https://java.agentscope.io/_sources/en/task/msghub.md

## 主题地图（待充实）

| 模式 | 解决问题 | 关键词 |
|------|---------|--------|
| **Pipeline** | 固定顺序流水线 | 顺序、确定性 |
| **Custom Workflow** | 自由 DAG | 可视化、可编程 |
| **Routing** | 按内容/意图分发到不同 Agent | 分类器、上游 router |
| **Skills (Progressive Disclosure)** | 渐进式技能加载 | 减少 prompt 体积 |
| **Subagents** | 主-从委派 | `task` / `task_output` |
| **Supervisor** | 主管 Agent 监督执行 | 自纠错、重试 |
| **Handoffs** | 角色交接 | 客服 → 专家 |
| **Multi-Agent Debate** | 多 Agent 辩论 → 共识 | 抗幻觉 |
| **MsgHub** | 多 Agent 共享消息总线 | 公开/广播频道 |
| **Agent as Tool** | 把一个 Agent 当工具调用 | 嵌套抽象 |

## 已知线索

- 每 provider 有 `*MultiAgentFormatter`，用 `<history></history>` 包装多 Agent 历史
- Harness 的 `SubagentsHook` 注入 `task` / `task_output` 工具，支持同步/后台
- `UserAgent` 可在 MsgHub 中代表"人"
- A2A 协议（Agent2Agent）走 Nacos 做分布式多 Agent（见 [09](./09-integration-mcp-a2a.md)）

## 设计原则推断

从 ReActAgent 的"不可并发"约束推断：
- 多 Agent 编排必须分别持有各自实例
- 并发执行子 Agent 时框架应自动 fork / pool（待验证）

## 待补充清单

- [ ] Pipeline 的 API 与代码示例
- [ ] Routing 的分类器实现方式
- [ ] Subagent 与"Agent as Tool"的边界
- [ ] Debate 的共识算法
- [ ] MsgHub 的消息分发模型（订阅 / 广播 / 私信）
- [ ] 多 Agent 共享 Memory 的策略
