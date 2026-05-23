# 11 · 与 Python 版 AgentScope 的差异

> 注：以下差异多为**推断**（README 未直接列出对照），需进一步核实。

## 语言与生态

| 维度 | Python 版 | Java 版 |
|------|----------|---------|
| 语言 | Python 3.10+ | JDK 17+ |
| 包管理 | pip / `agentscope` | Maven / `io.agentscope:agentscope` |
| 异步模型 | `asyncio` / `await` | **Project Reactor** `Mono<T>` / `Flux<T>` |
| 类型系统 | Pydantic / type hints | Java 静态类型 |
| 部署形态 | 解释执行 | 字节码 / **GraalVM 原生镜像 (~200ms 冷启动)** |

## 类型安全

- Java 的 **Structured Output** 直接映射到 POJO，编译期类型检查
- 与 Spring Boot / 微服务架构无缝衔接

## 企业集成

| 能力 | Python 版 | Java 版 |
|------|----------|---------|
| 服务发现 | （社区方案） | **A2A 内置 Nacos** |
| 可观测 | 自接 | **原生 OpenTelemetry** |
| 沙箱 | AgentScope Runtime | AgentScope Runtime + `SandboxFilesystemSpec` |

## 设计哲学

两版共享 [arXiv:2402.14034](https://arxiv.org/abs/2402.14034) 与 [arXiv:2508.16279](https://arxiv.org/abs/2508.16279) 的论文设计：
- Msg 为一等公民
- ReAct 为主推循环
- Multi-Agent 是核心命题（非锦上添花）
- Pipeline / MsgHub 等编排原语

## 落地差异（猜测，待验证）

- Python 版迭代更快，新功能首发
- Java 版工程化更重——Harness 1.1 是 Java 版主导
- Java 版的 `HarnessAgent` 在 Python 版可能没有完全对应
- 多模态生态 Python 更成熟（OpenCV / PIL 等）

## 选型建议

| 场景 | 推荐 |
|------|------|
| 探索 / 原型 / 数据分析 | Python 版 |
| 现有 Java 微服务体系 | Java 版 |
| 需要 GraalVM 原生镜像 | Java 版 |
| 需要 Spring / Nacos 等 Java 生态 | Java 版 |
| 多模态密集 / 强 ML 集成 | Python 版 |

## 待核实清单

- [ ] 两版 API 命名一致性程度
- [ ] 功能覆盖差异（哪些是 Python 独有 / Java 独有）
- [ ] 配置文件 / DSL 是否互通
- [ ] 多 Agent 通信协议是否互操作（A2A 是否跨语言）
