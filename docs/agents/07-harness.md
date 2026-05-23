# 07 · Harness 子系统（1.1+）

## 它在解决什么问题？

`ReActAgent` 只处理**单次**"请求-推理-工具-回复"循环。Harness 关注更长期的工程问题：

- 下一轮怎么办？
- 第二天上下文还在吗？
- 上下文溢出了怎么办？
- 状态丢了怎么办？
- 任务太重一个 Agent 扛不动怎么办？

**结论**：Harness = 基于 `ReActAgent` 构建的**生产级运行时基础设施**。统一入口是 `HarnessAgent`，**不替换推理循环**，而是通过 Hook + Toolkit + SkillBox 三条扩展通道注入能力。

## 组件全景

| 组件 | 解决的问题 | 关键实现 |
|------|----------|---------|
| **Workspace** | Agent 的"身份"从何而来 | `WorkspaceContextHook`：每轮 reasoning 前把 `AGENTS.md` / `MEMORY.md` / 当日记忆 / `KNOWLEDGE.md` 注入 system prompt |
| **Memory** | 对话事实如何跨会话留存 | 两层：`MemoryFlushHook` 在压缩前用 LLM 蒸馏当日事实日志；`MemoryConsolidator` 后台合并去重到 `MEMORY.md` |
| **Compaction** | 历史过长 | `CompactionHook`：消息/token 超阈值时摘要 + 保留尾部；真溢出时 `HarnessAgent` 捕获错误、强制压缩、自动重试 |
| **Tool Result Eviction** | 单次工具结果过大 | `ToolResultEvictionHook`：超大结果写文件系统，上下文仅留首尾预览 + 占位符 |
| **Session** | 跨进程保状态 | `SessionPersistenceHook`：按 `sessionId` 写工作区；下次调用自动恢复 |
| **Subagent** | 复杂任务分解 | `SubagentsHook`：注入 `task` / `task_output` 工具，支持同步/后台委派 |
| **Filesystem** | 隔离与控制运行环境 | 所有文件工具走 `AbstractFilesystem`：local+shell / composite+store / **sandbox** 三种模式 |
| **Sandbox** | 隔离执行（关键！） | 推荐 `filesystem(SandboxFilesystemSpec)`；自动注入 `shell_execute` 等危险工具 |
| **Tooling** | 默认工具基线 | 自动追加 `filesystem` / `memory_search` / `memory_get` / `session_search`；sandbox 后端再加 `shell_execute` |

## 三大支柱

Harness 把上面所有能力总结为 **持续运行（stable continuous operation）** 的三个支柱：

### 1. 身份延续（Identity Continuity）

- Workspace 注入 system prompt
- 两层记忆（每日蒸馏 + 长期合并）
- `skills/*/SKILL.md` 自动加载
- → persona、知识、技能在工作区中**持续积累**

### 2. 有界上下文（Bounded Context）

- Compaction → 控**深度**（历史长度）
- Tool Result Eviction → 控**宽度**（单条大小）
- 溢出恢复 → 兜底

### 3. 可恢复状态（Recoverable State）

- Session 持久化让进程重启续跑
- `RuntimeContext` 串联身份（`sessionId` / `userId`）
- 可插拔 `AbstractFilesystem` 决定**状态物理落点**

## 协作机制

三个共享对象贯穿所有 Hook：

- `WorkspaceManager` — 谁能读写工作区
- `AbstractFilesystem` — 工作区物理位置
- `RuntimeContext` — 当前调用的 `sessionId` / `userId`

`HarnessAgent.Builder.build()` 把：
- **Hook 通道** → 按 `priority` 排序传 `ReActAgent`
- **Toolkit 通道** → 基线工具追加到用户 Toolkit
- **SkillBox 通道** → 从 `workspace/skills/` 自动构造 SkillBox

每次 `call()` 开始时，`bindRuntimeContext` 把当前 `RuntimeContext` 分发给所有 `RuntimeContextAwareHook`，并按需从 Session 恢复状态。

## 工作区目录约定

```
workspace/
├── AGENTS.md            # 角色 / 编码规范 / persona
├── MEMORY.md            # 长期记忆（Consolidator 维护）
├── KNOWLEDGE.md         # 领域知识文档
├── skills/
│   ├── refactor/
│   │   └── SKILL.md     # 可复用技能
│   └── test-gen/
│       └── SKILL.md
├── subagents/
│   ├── reviewer.md      # 子 Agent 声明
│   └── tester.md
└── sessions/<sessionId>/ # Session 持久化
```

## 用 Harness 构建 Coding Agent

Harness 的能力对照 Coding Agent 的需求几乎一一映射：

| Coding Agent 需求 | Harness 能力 |
|-------------------|-------------|
| 项目档案（规范、架构、模块说明） | `AGENTS.md` / `KNOWLEDGE.md` |
| 隔离执行（`shell` / 改文件 / 跑测试） | `SandboxFilesystemSpec` + `shell_execute` |
| 任务分解（实现/测试/评审） | `SubagentsHook` + `task` / `task_output` |
| 沉淀工程知识 | 当日记忆 → Consolidator → `MEMORY.md` |
| 大文件 / 长日志 不爆 context | Compaction + Tool Result Eviction |
| 长会话续跑 | `SessionPersistenceHook` |
| 可复用技能（重构、单测生成） | `skills/*/SKILL.md` |

## 最小示例

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("coder")
    .model("dashscope:qwen-max")               // 字符串 id
    .workspace(Path.of("./workspace"))
    .filesystem(SandboxFilesystemSpec.of(...)) // 关键：隔离执行
    .toolResultEviction(ToolResultEvictionConfig.defaults())
    .build();

Msg out = agent.call(Msg.builder()
    .textContent("帮我把 OrderService 重构成 hexagonal 风格")
    .build()).block();
```

## 与 Claude Code / Codex 的对应

> Harness 的设计思路与 Anthropic 的 Claude Code / OpenAI 的 Codex 是同一类东西：把"Agent + 一组系统工具 + 工作区 + 安全沙箱 + 多代理"整体打包成产品级 runtime。

| Claude Code | AS-Java Harness |
|-------------|-----------------|
| CLAUDE.md / AGENTS.md | `AGENTS.md` |
| 自动加载的 skills | `skills/*/SKILL.md` |
| Memory（CLAUDE 的 memory 系统） | `MEMORY.md` + Consolidator |
| Sub-agents | `SubagentsHook` |
| Sandbox（bash 工具） | `SandboxFilesystemSpec` + `shell_execute` |
| Session persistence | `SessionPersistenceHook` |
| Tool result eviction（大输出落盘） | `ToolResultEvictionHook` |
| Compaction（上下文压缩） | `CompactionHook` |

可以把 Harness 看作"用 AS-Java 自己实现 Claude Code 的库"。

## 一手资料

- [Harness Overview](https://java.agentscope.io/_sources/en/harness/overview.md)
- [Architecture](https://java.agentscope.io/_sources/en/harness/architecture.md)
- 各子组件文档：`workspace.md` / `filesystem.md` / `session.md` / `memory.md` / `tool.md` / `subagent.md` / `streaming.md`
- 博客：[AgentScope Java 1.1 · Harness](https://java.agentscope.io/_sources/en/blogs/agentscope-v1-harness.md)
- Quickstart：`/harness/quickstart/index.md`
- Sandbox：`/harness/sandbox/index.md`
- Example：`/harness/example/index.md`
