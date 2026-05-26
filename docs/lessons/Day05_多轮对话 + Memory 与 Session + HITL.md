# Day 5 · 多轮对话 + Memory 与 Session 持久化 + HITL

> 上级文档：[../learning.md](../learning.md)
> 配套笔记：[../agents/06-memory-state-session.md](../agents/06-memory-state-session.md) · [../agents/04-tool-system.md § 工具挂起](../agents/04-tool-system.md) · [../agents/10-observability-hitl.md](../agents/10-observability-hitl.md)
> 前置：[Day 4 · TodoManager + 业务工具集](<Day04_TodoManager + 业务工具集.md>) 已完成

> 📌 关于命名：本课用**自写的 `FileSession`** 完成会话持久化（只持久化 `TodoManager`；Memory 重启后从空起，理由见附录 A）。AS-Java 自带的 `Session / JsonSession` 抽象不直接使用，原因同附录 A。

## 0. 一句话目标

**今天结束时**，你的 REPL 能跑这个剧本：

```
> /run 做一个库存管理系统
[ASSISTANT] ... 已登记 1 APP + 2 Module（监控/入库）+ 2 Model
            假设：... 问：要不要加出库？
=== Todos (5) ===

> /run 要，加上出库审批
[ASSISTANT] 已增量补 1 Module + 1 Model
=== Todos (7) ===  ← 原有 5 个不动，新加 2 个

> /submit
[CONFIRM?] 7 项待办：
  todo-1 CREATE_APP 库存管理
  ...
  确认下发？(y/N): y
[DISPATCHED] 7 项已发送（dry-run）
```

然后**杀掉进程重启**，凭 `sessionId` 还原同一份 TodoManager。

> ⚠️ Day 5 **CLI 版 HITL**：通过 `ToolSuspend` 异常 + 控制台 `readLine` 实现。Day 7 会把这一套搬到 AG-UI 事件流，Day 5 的 CLI 代码会被替换。所以本日的 HITL 是"过渡形态"。

## 1. 学习目标

- ✅ 接入 `InMemoryMemory`，看到多轮对话上下文是怎么进 prompt 的
- ✅ 实现 `list_todos` / `update_module` / `update_model` 三个**增量**工具
- ✅ 用 `JsonSession`（或自写文件持久化）让进程 kill 重启后能续跑
- ✅ 用 **ToolSuspend** 实现 HITL：`submit_to_frontend(confirmed=false)` 抛挂起异常，外层接住，等用户确认后回填 ToolResult
- ✅ 跑通"增量追加 → 确认下发"完整剧本

## 2. 时间盒（建议 8 学时）

| 阶段 | 时长 | 主题 | 验收 |
|------|------|------|------|
| Phase 0 | 30 min | 资料预读 + 设计决策 | 能说出 Memory / Session / ToolSuspend 各自职责 |
| Phase 1 | 45 min | Memory 接入 + 多轮 prompt | 第二次 `/run` 能引用第一次结果 |
| Phase 2 | 90 min | 3 个增量工具 | LLM 追加需求时调 `update_*` 而非 `create_*` |
| Phase 3 | 60 min | Session 持久化 | 进程 kill 重启后 `todo-N` 序号延续、状态保留 |
| Phase 4 | 90 min | `submit_to_frontend` + ToolSuspend | 工具能抛挂起异常并恢复 |
| Phase 5 | 60 min | CLI 确认回填 ToolResult | y/n 回填都能正确续跑 |
| Phase 6 | 45 min | 剧本回归 + commit | 完整剧本测试通过，`day5: ...` commit |

---

## 3. Phase 0 · 资料预读（30 min）

### 3.1 三个抽象的职责切分

| 抽象 | 存什么 | 何时清 | 谁用 |
|------|-------|--------|------|
| **Memory** | 一次会话的消息列表（user / assistant / tool） | 进程死或显式 `clear` | LLM 上下文拼装 |
| **Session** | TodoManager 状态 + Memory 序列化结果 | 显式删 session 文件 | 进程重启时 `load(sessionId)` 续跑 |
| **ToolSuspend** | "我先停一下，等外部回答" 的信号 | 用户回填 ToolResult 后续跑 | 工具方法体内 `throw` |

**关键点**：Memory 解决"多轮"，Session 解决"重启"，ToolSuspend 解决"等人"。三者正交。

### 3.2 增量更新的 prompt 设计

我们已经在 Day 4 给了 LLM 一组 `create_*` 工具。Day 5 加两条原则到 prompt：

1. 收到新需求先调 `list_todos()` 看现状
2. 判断是 ADD（前所未有的 module/model）还是 MODIFY（既有的字段变更）
3. ADD 调 `create_*`；MODIFY 调 `update_*`
4. **严禁删除既有待办**，除非用户明确说"删掉 xxx"

### 3.3 ToolSuspend 的形状

伪代码：

```java
@Tool(name = "submit_to_frontend",
      description = "把所有待办下发前端。必须用户确认后才会真发。")
public String submit(@ToolParam(name = "confirmed") boolean confirmed) {
    if (!confirmed) {
        throw new ToolSuspendException("AWAITING_USER_CONFIRMATION:" + todos.size());
    }
    // confirmed=true 时才真发
    return doDispatch();
}
```

外层调用：

```java
Msg out = agent.call(msg).block();
if (out.getGenerateReason() == GenerateReason.TOOL_SUSPENDED) {
    // 找到挂起的 ToolUseBlock
    // 让用户决定 → 把 USER_CONFIRMED / USER_REJECTED 作为 ToolResult 回填
    agent.call(toolResultMsg).block();
}
```

> ⚠️ **版本敏感**：`ToolSuspendException` / `GenerateReason.TOOL_SUSPENDED` 这些 API 名称在 AS-Java 1.0.x 和 1.1.x 之间可能微调。**Phase 0 必做**：先把这两个名字在 1.0.12 sources jar 里 grep 一遍确认：
>
> ```bash
> mvn -q dependency:sources
> # Linux/macOS
> jar -tf ~/.m2/repository/io/agentscope/agentscope/1.0.12/agentscope-1.0.12-sources.jar \
>   | grep -E "ToolSuspend|GenerateReason"
> # 或者用 IDE 在外部库里搜 "class ToolSuspendException"，记下它的真实包路径
> ```
>
> 本课程示例代码里写的 `io.agentscope.core.exception.ToolSuspendException` 是占位，落地时按 jar 里的实际全限定名替换 import。

### 3.4 `/submit` 命令 vs 自然语言"提交"

Day 5 有两条触发提交的路径，**本质是一条**：

- **CLI 路径**：用户敲 `/submit`，REPL 构造一条"请把所有待办下发前端"的用户消息给 LLM
- **自然语言路径**：用户直接说"确认 / 提交 / 发布"，LLM 按 prompt 规则自行调 `submit_to_frontend(false)`

两条路径在 Agent 视角下都是"LLM 决定调 submit_to_frontend"。`/submit` 只是把这条自然语言固定化、避免歧义。本课程的实现以 CLI 路径为主，自然语言路径靠 prompt 兼容。

### 3.5 预读链接

- [AS-Java Memory / State / Session](../agents/06-memory-state-session.md)
- [AS-Java Tool 工具挂起](../agents/04-tool-system.md)
- [AS-Java HITL 实战](../agents/10-observability-hitl.md)

### ✅ Phase 0 验收

- [ ] 能在白板画一条带 ToolSuspend 的时序图
- [ ] 能说出 Memory 和 Session 各自存什么

---

## 4. Phase 1 · Memory 接入 + 多轮 prompt（45 min）

### 4.1 给 `AgentFactory` 加 Memory

> 📌 **按 Phase 顺序写**：Phase 1 时只有 `FrontendCreateTools`（Day 4 的），`TodoQueryTools / TodoUpdateTools` 是 Phase 2 新建、`SubmitTool` 是 Phase 4 新建。先把 `buildAnalystWithTools` 改成接受 Memory，再到对应 Phase 回来逐行加 `toolkit.registerTool(...)`。

Phase 1 的最小改动（只加 Memory，工具注册保持 Day 4 原样）：

```java
public static ReActAgent buildAnalystWithTools(TodoManager todos, Memory memory) {
    initModels();
    Toolkit toolkit = new Toolkit(ToolkitConfig.builder().parallel(true).build());
    toolkit.registerTool(new FrontendCreateTools(todos));
    // Phase 2 完成后回来加：
    // toolkit.registerTool(new TodoQueryTools(todos));
    // toolkit.registerTool(new TodoUpdateTools(todos));
    // Phase 4 完成后回来加：
    // toolkit.registerTool(new SubmitTool(todos));

    return ReActAgent.builder()
            .name("RequirementAnalyst")
            .sysPrompt(Prompts.analystMultiRound())
            .model(ModelRegistry.resolve(DEFAULT_MODEL_ID))
            .toolkit(toolkit)
            .memory(memory)           // ← 关键
            .maxIters(20)
            .build();
}
```

Phase 4 结束时这个方法的最终形态：

```java
toolkit.registerTool(new FrontendCreateTools(todos));
toolkit.registerTool(new TodoQueryTools(todos));
toolkit.registerTool(new TodoUpdateTools(todos));
toolkit.registerTool(new SubmitTool(todos));
```

在 REPL 启动时构造 Memory，并复用同一个实例多次 `/run`：

```java
Memory memory = new InMemoryMemory();
ReActAgent analyst = AgentFactory.buildAnalystWithTools(todos, memory);
```

### 4.2 新 prompt `prompts/analyst-multi-round.md`

> ⚠️ **必须自包含**：AS-Java 的 `sysPrompt` 是单一字符串，切到这份 prompt 后，Day 4 那份 `analyst-with-tools.md` **不会**自动叠加。早期文档曾写「按 Day 4 的工作流」这种跨文件指代，模型其实看不到 Day 4 prompt，会丢掉"主键必须 `name=id/long/primary`、主从单据用 `array.subs`、camelCase、`usage` 取值、假设 vs 反问二分"等 prompt-only 约束（Schema 只兜得住命名 / dataType / type 三条枚举类约束，业务规则兜不住）。所以这里把 Day 4 的工作流 + 字段规范 + 不确定信息处理**全部内联**进来，再追加 Day 5 的多轮 + 提交规则。

```markdown
你是「需求分析助手」。你必须**通过工具调用**把分析结果交付，不要直接输出 JSON 文本，也不要解释。

# 第一轮工作流（用户给完整或半完整需求时）

1. 先用一句话默念你将拆出几个 Module / 几个 Model（不要打印出来）
2. 调用 `create_app` 登记应用，**恰好一次**
3. 对每个业务模块依次调用 `create_module`
4. 对每个数据模型依次调用 `create_model`，注意：
    - 每个 model 必须包含 `name=id, dataType=long, usage=primary` 的主键
    - 含明细的单据用 `type=TASK_MASTER_SLAVE`，明细放在 `dataType=array` 字段的 `subs` 数组里
5. 全部调完后用 1 句中文短消息总结你做了什么，并把假设和反问一起列出，**不要再附 JSON**

# 第二轮起（用户在补充、修正、删除）

1. **必须先调 `list_todos()`**，看清当前待办再做判断
2. 三种动作：
    - **ADD**：用户提出全新模块或模型 → 用 `create_module` / `create_model`
    - **MODIFY**：用户改既有模块的字段或 desc → 用 `update_module` / `update_model`
    - **明确删除**：用户说"删掉 xxx" → Day 5 暂不实现 `delete_*`，先在总结里记一条 question 等下一轮再处理

# 字段规范（每一轮都务必严格遵守）

- `moduleId` / `app.name` / `model.name` / `field.name`：camelCase 英文
- `dataType` ∈ `{long, int, double, string, boolean, date, array}`
- `model.type` ∈ `{ENTITY, TASK, TASK_MASTER_SLAVE}`
- `usage`：主键写 `"primary"`，外键写 `"foreign"`，其他写 `""`

# 不确定信息怎么办

- 用户没说但你做了假设的，**先按你的判断调工具**，最后总结里告诉用户你做了哪些假设
- 用户必须回答才能继续的（如"是否需要附件"），**直接在总结里问**，等用户下一轮回复，**这一轮不要瞎编**

# 严禁

- 重生整组待办（这会清掉用户已经看过/将要确认的）
- 把已存在的 module / model 用同名 `create_*` 再登记一遍（会被 Schema 拒，且会污染序号）

# 提交（仅当用户明确表达提交意图）

- 用户说"确认 / 提交 / 发布 / 下发 / 入库 / 保存生效"等时，调 `submit_to_frontend(confirmed=false)` 先列出待办给用户预览
- 用户再次确认时（外层会自动回填 `USER_CONFIRMED`），系统会让你恢复，再调一次 `submit_to_frontend(confirmed=true)` 完成下发
- **严禁**在需求分析阶段（用户没明确说提交）自作主张调 `submit_to_frontend` —— 它**不是**工作流的收尾步骤；登记 `create_*` 调完直接用 1 句话总结即可
```

> 📌 **prompt 工程的实战准则**：prompt 应当像一封信，不是一段代码。当读者（模型）跨段引用某个上下文（"按 Day 4 的工作流"）时，那个上下文必须就在同一封信里。DRY 在 prompt 写作里优先级很低——多写几行重复内容换"打开一个文件就看到全部"完全值。

### 4.3 `Prompts.analystMultiRound()`

```java
private static volatile String analystMultiRound;

public static String analystMultiRound() {
    if (analystMultiRound == null) {
        synchronized (Prompts.class) {
            if (analystMultiRound == null) {
                analystMultiRound = read("/prompts/analyst-multi-round.md");
            }
        }
    }
    return analystMultiRound;
}
```

### 4.4 验证多轮

```bash
mvn -q compile exec:java
> /run 做一个简单员工档案管理，含姓名工号
[ASSISTANT] 已登记 ...
=== Todos (3) ===

> /run 再加个部门字段
[ASSISTANT] （应该看到调用 list_todos 后调 update_model）...
=== Todos (3) ===  ← 数量不变，部门字段进了 employee 的 fields
```

### ✅ Phase 1 验收

- [ ] 第二轮 LLM 必先调 `list_todos`（看 `logs/scope.log`）
- [ ] 部门字段是 update 到既有 employee 模型而不是新建一个 employee

---

## 5. Phase 2 · 增量工具（90 min）

### 5.1 `TodoQueryTools.java`

> 📌 **必须打 `[Tool]` 日志**：沿用 Day 4 `FrontendCreateTools` 每个工具末尾 `log.info("[Tool] ...")` 的惯例，否则当 LLM 真的调了 `list_todos` 时你在 `logs/scope.log` 里看不到任何痕迹，没法判断 prompt 规则有没有生效。

```java
package space.wlshow.scope.tool;

import io.agentscope.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.todo.TodoItem;
import space.wlshow.scope.todo.TodoManager;

import java.util.stream.Collectors;

public class TodoQueryTools {

    private static final Logger log = LoggerFactory.getLogger(TodoQueryTools.class);

    private final TodoManager todos;

    public TodoQueryTools(TodoManager todos) { this.todos = todos; }

    @Tool(name = "list_todos",
          description = "列出当前所有待办（含 id / type / 名称 / 状态 / payload）。" +
                        "多轮对话第二轮起，新用户输入到来时必须先调一次。")
    public String listTodos() {
        int size = todos.size();
        log.info("[Tool] list_todos size={}", size);
        if (size == 0) return "当前无待办";
        return todos.snapshot().stream()
                .map(this::format)
                .collect(Collectors.joining("\n"));
    }

    private String format(TodoItem it) {
        return String.format("- %s [%s] %s status=%s payload=%s",
                it.id(), it.type(), it.targetName(), it.status(), it.payload().toString());
    }
}
```

### 5.2 `TodoUpdateTools.java`

> 📌 **沿用 Day 4 的 Schema 兜底模式 + `[Tool]` 日志**：
> - 修改后的 payload 必须再过一次 `module-spec.schema.json` / `data-model-spec.schema.json`，校验失败返回 `ERROR: ...` 给 LLM，**不**写入 TodoManager
> - 每个工具方法在成功路径末尾 `log.info("[Tool] update_module/update_model ... payload=...")`，失败路径 `log.warn("[Tool] ... rejected/not-found ...")`
> 否则 `[Todo] PAYLOAD-REPLACE` 是从 `TodoManager` 内部打的，工具层"没说话"——排查多轮对话时无从下手

```java
package space.wlshow.scope.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.schema.SchemaValidator;
import space.wlshow.scope.schema.ValidationError;
import space.wlshow.scope.spec.FieldSpec;
import space.wlshow.scope.todo.TodoItem;
import space.wlshow.scope.todo.TodoManager;
import space.wlshow.scope.todo.TodoStatus;
import space.wlshow.scope.todo.TodoType;
import space.wlshow.scope.util.Json;

import java.util.List;
import java.util.Optional;

public class TodoUpdateTools {

    private static final Logger log = LoggerFactory.getLogger(TodoUpdateTools.class);

    private static final SchemaValidator MODULE_VAL =
            new SchemaValidator("/schemas/module-spec.schema.json");
    private static final SchemaValidator MODEL_VAL =
            new SchemaValidator("/schemas/data-model-spec.schema.json");

    private final TodoManager todos;

    public TodoUpdateTools(TodoManager todos) { this.todos = todos; }

    @Tool(name = "update_module",
          description = "修改一个已存在的 Module 的中文名或描述。" +
                        "通过 moduleId 定位，不能改 moduleId 本身。")
    public String updateModule(
            @ToolParam(name = "moduleId") String moduleId,
            @ToolParam(name = "newModuleName", description = "可选，为空表示不改") String newModuleName,
            @ToolParam(name = "newModuleDesc", description = "可选，为空表示不改") String newModuleDesc
    ) {
        Optional<TodoItem> found = findByModuleId(moduleId);
        if (found.isEmpty()) {
            log.warn("[Tool] update_module not-found moduleId={}", moduleId);
            return "ERROR: 未找到 moduleId=" + moduleId;
        }

        TodoItem it = found.get();
        if (it.status() != TodoStatus.PENDING) {
            log.warn("[Tool] update_module rejected id={} status={}", it.id(), it.status());
            return "ERROR: " + it.id() + " 状态为 " + it.status() + "，不可修改";
        }

        ObjectNode p = ((ObjectNode) it.payload()).deepCopy();
        if (newModuleName != null && !newModuleName.isBlank()) p.put("moduleName", newModuleName);
        if (newModuleDesc != null && !newModuleDesc.isBlank()) p.put("moduleDesc", newModuleDesc);

        String err = validate(MODULE_VAL, p, "update_module");
        if (err != null) return err;

        todos.replacePayload(it.id(), p);
        log.info("[Tool] update_module id={} newName={} newDesc={} payload={}",
                it.id(), newModuleName, newModuleDesc, p);
        return "MODULE 已更新：" + it.id();
    }

    @Tool(name = "update_model",
          description = "向已存在的数据模型追加字段。fieldsJson 是要追加的 FieldSpec 数组。" +
                        "如需删除字段或修改既有字段，先记 warning，本日不实现。")
    public String updateModel(
            @ToolParam(name = "modelName") String modelName,
            @ToolParam(name = "appendFieldsJson") String appendFieldsJson
    ) {
        Optional<TodoItem> found = findByModelName(modelName);
        if (found.isEmpty()) {
            log.warn("[Tool] update_model not-found modelName={}", modelName);
            return "ERROR: 未找到 model name=" + modelName;
        }

        TodoItem it = found.get();
        if (it.status() != TodoStatus.PENDING) {
            log.warn("[Tool] update_model rejected id={} status={}", it.id(), it.status());
            return "ERROR: " + it.id() + " 状态为 " + it.status() + "，不可修改";
        }

        List<FieldSpec> appended = Json.readList(appendFieldsJson, FieldSpec.class);
        ObjectNode p = ((ObjectNode) it.payload()).deepCopy();
        var arr = (com.fasterxml.jackson.databind.node.ArrayNode) p.get("fields");
        appended.forEach(f -> arr.add(Json.mapper().valueToTree(f)));

        String err = validate(MODEL_VAL, p, "update_model");
        if (err != null) return err;

        todos.replacePayload(it.id(), p);
        log.info("[Tool] update_model id={} appended={} totalFields={} payload={}",
                it.id(), appended.size(), arr.size(), p);
        return "MODEL 已追加 " + appended.size() + " 个字段到 " + it.id();
    }

    /** 与 FrontendCreateTools.validate 同形：合规返回 null，否则返回 "ERROR: ..." 字符串。 */
    private static String validate(SchemaValidator v, JsonNode payload, String tool) {
        List<String> errors = v.validate(payload).stream()
                .map(ValidationError::message)
                .toList();
        if (errors.isEmpty()) return null;
        log.warn("[Tool] {} rejected: {}", tool, errors);
        return "ERROR: 参数不合规：" + String.join("; ", errors);
    }

    private Optional<TodoItem> findByModuleId(String moduleId) {
        return todos.snapshot().stream()
                .filter(it -> it.type() == TodoType.CREATE_MODULE)
                .filter(it -> moduleId.equals(it.payload().path("moduleId").asText()))
                .findFirst();
    }

    private Optional<TodoItem> findByModelName(String name) {
        return todos.snapshot().stream()
                .filter(it -> it.type() == TodoType.CREATE_MODEL)
                .filter(it -> name.equals(it.payload().path("name").asText()))
                .findFirst();
    }
}
```

### 5.3 `TodoItem.withPayload` + `TodoManager.replacePayload`

为了对称 Day 4 已经定下的 `withStatus(...)` record 工厂模式，先在 `TodoItem` 上加一个 `withPayload`：

```java
public TodoItem withPayload(JsonNode newPayload) {
    return new TodoItem(id, type, targetName, newPayload, status, errorMessage,
            createdAt, Instant.now());
}
```

`TodoManager` 暴露 `replacePayload`，复用 `withPayload`：

```java
public void replacePayload(String id, JsonNode newPayload) {
    TodoItem cur = get(id);
    if (cur.status() != TodoStatus.PENDING) {
        throw new IllegalStateException("非 PENDING 不可改 payload: " + id);
    }
    TodoItem next = cur.withPayload(newPayload);
    items.put(id, next);
    log.info("[Todo] PAYLOAD-REPLACE id={}", id);
}
```

> 📌 双重检查并不冗余：`TodoUpdateTools` 里的 `status != PENDING` 检查是为了给 LLM 返回友好的 `ERROR: ...` 触发自纠错；`TodoManager.replacePayload` 的检查是底线防御（外部直接调也安全）。两层各司其职。

### 5.4 验证

```bash
> /run 做一个简单员工档案，含姓名工号
=== Todos (3) ===

> /run 再加个部门和入职日期
（看 logs：先 list_todos，再 update_model employee）
=== Todos (3) ===  ← employee 的 fields 多了 2 个
```

### ✅ Phase 2 验收

- [ ] 追加字段不会新建 TodoItem
- [ ] 改既有 PENDING 工具能跑通
- [ ] 改一个不存在的 moduleId 返回 ERROR

---

## 6. Phase 3 · Session 持久化（60 min）

### 6.1 设计选型

Day 5 走**最简实现**：手写 `FileSession`，只把 `TodoManager.getState()` 序列化到 `data/sessions/<id>.json`。理由：

1. **AS-Java 自带的 `JsonSession` API 跨 patch 版本签名不稳**，自写 30 行更可控
2. **Memory 不持久化** ——`Msg` 是含 `ContentBlock` 多态字段的密封类型，用 Jackson 直接 `readList` 容易踩反序列化坑（不同版本字段差异大）。本课程的策略是：**进程重启后 Memory 从空起，但 TodoManager 保留**——用户重启再进来时，LLM 第一句话先调 `list_todos` 看现状即可补上"上下文"。这套策略已经在 `analyst-multi-round.md` 的"第二轮起必须 list_todos"规则里覆盖了。
3. Day 7 升级到 Harness Compaction 时反正要重写，没必要今天叠两层抽象

> 📌 如果你想用 AS-Java 自带的 `Session`，看 [../agents/06-memory-state-session.md](../agents/06-memory-state-session.md)，原理一致，本课不强制。如果想连 Memory 也持久化，参考附录 A 的扩展说明。

### 6.2 `session/FileSession.java`

```java
package space.wlshow.scope.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.todo.TodoManager;
import space.wlshow.scope.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 单文件会话：只持久化 TodoManager，Memory 每次进程启动从空开始。
 * 重启后 LLM 第一句话需要调 list_todos 看现状（已在 prompt 里约束）。
 */
public class FileSession {

    private static final Logger log = LoggerFactory.getLogger(FileSession.class);
    private static final Path BASE = Path.of("data", "sessions");

    public final String id;
    public final TodoManager todos;
    public final Memory memory;          // 每次新建，重启后从空起

    public FileSession(String id, TodoManager todos, Memory memory) {
        this.id = id;
        this.todos = todos;
        this.memory = memory;
    }

    public static FileSession loadOrNew(String id) {
        Path f = BASE.resolve(id + ".json");
        TodoManager todos = new TodoManager();
        Memory memory = new InMemoryMemory();
        if (Files.exists(f)) {
            try {
                JsonNode root = Json.tree(Files.newInputStream(f));
                todos.loadState(root.path("todos"));
                log.info("[Session] LOAD {} todos={}", id, todos.size());
            } catch (IOException e) {
                throw new IllegalStateException("Session 加载失败: " + f, e);
            }
        } else {
            log.info("[Session] NEW {}", id);
        }
        return new FileSession(id, todos, memory);
    }

    public void save() {
        try {
            Files.createDirectories(BASE);
            ObjectNode root = Json.mapper().createObjectNode();
            root.set("todos", todos.getState());
            Path f = BASE.resolve(id + ".json");
            Files.writeString(f, Json.writePretty(root));
            log.info("[Session] SAVED {} -> {}", id, f);
        } catch (IOException e) {
            throw new IllegalStateException("Session 保存失败", e);
        }
    }
}
```

### 6.3 REPL 改造

```java
String sessionId = System.getenv().getOrDefault("SCOPE_SESSION", "default");
FileSession session = FileSession.loadOrNew(sessionId);
ReActAgent analyst = AgentFactory.buildAnalystWithTools(session.todos, session.memory);

// 1. 每个改动状态的命令完成后 inline save，避免崩溃丢数据
// 2. shutdown hook 兜底（正常 exit 走到这里；kill -9 不会走，所以 inline 是主路径）
Runtime.getRuntime().addShutdownHook(new Thread(session::save));
```

在 `/run` 处理段末尾、`/submit` 处理段末尾分别加：

```java
session.save();
```

> 📌 `inline save` 是主路径，`shutdown hook` 只是兜底（`kill -9` 不会触发）。教学上要让学习者明白两者各自的作用。

### 6.4 加 `.gitignore`

```
data/sessions/
```

### 6.5 验证

```bash
# 1. 默认 session 跑一次后重启
mvn -q compile exec:java
> /run 做一个员工档案
> exit            # 退出走正常路径，shutdown hook 触发 save；但 /run 末尾的 inline save 已经写过文件了

# 另一个终端
ls data/sessions/        # 看到 default.json
cat data/sessions/default.json | head -30

# 2. 重启同 session，TodoManager 应保留
mvn -q compile exec:java
> /todos
=== Todos (3) ===            ← 之前的还在

# 3. 切到另一个 sessionId，应该是空的
SCOPE_SESSION=alice mvn -q compile exec:java
> /todos
=== Todos (0) ===            ← 与 default 完全隔离
> /run 做一个请假管理
> exit

ls data/sessions/        # 现在看到 default.json + alice.json
```

> 📌 `/todos` 是个不调 LLM 的快捷命令，方便排查 session 加载情况：

```java
} else if (line.equals("/todos")) {
    System.out.println("=== Todos (" + session.todos.size() + ") ===");
    session.todos.snapshot().forEach(it ->
        System.out.printf("  %s  %-15s  %-25s  %s%n",
            it.id(), it.type(), it.targetName(), it.status()));
    continue;
}
```

### ✅ Phase 3 验收

- [ ] 退出后 `data/sessions/default.json` 存在
- [ ] 重启后 `/todos` 能看到原列表
- [ ] `SCOPE_SESSION=alice` 跑能跟 `default` 隔离

---

## 7. Phase 4 · `submit_to_frontend` + ToolSuspend（90 min）

### 7.1 设计

LLM 决定提交时，调用 `submit_to_frontend(confirmed=false)`。工具做两件事：

1. 把所有 PENDING 待办整理成"将下发"清单（**不真发**）
2. 抛 `ToolSuspendException`，把清单字符串作为 suspend reason 携带

外层（REPL）捕获挂起：
- 弹出确认提示
- 用户输入 `y` → 回填 `USER_CONFIRMED`
- 用户输入 `n` → 回填 `USER_REJECTED`

LLM 续跑后：
- 收到 `USER_CONFIRMED` → 调一次 `submit_to_frontend(confirmed=true)` 真发
- 收到 `USER_REJECTED` → 1 句话告诉用户已取消，待办保持 PENDING

### 7.2 `SubmitTool.java`

> 📌 **description 必须显式拦住"LLM 工作流末尾自作主张调 submit"的常见误触**。早期版本 description 只写了"怎么调"，结果实测 `/run 做一个员工档案管理` 时模型把 submit 当作工作流收尾步骤一并调了——`SubmitTool` 抛 `ToolSuspendException` → 框架转 `TOOL_SUSPENDED` Msg → REPL 没把这条挂起接住 → 一个没回填的 `ToolUseBlock` 卡在 Memory 里 → 下一次 `/submit` 直接报 `Pending tool calls exist without results`。`description` 是模型实际看到的唯一行为约束（prompt 离它远），必须在这里把"什么时候不该调"写死。

```java
package space.wlshow.scope.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.ToolSuspendException;     // ← 1.0.12 实际在 .tool 包，不是 .exception
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.todo.TodoItem;
import space.wlshow.scope.todo.TodoManager;
import space.wlshow.scope.todo.TodoStatus;

import java.util.List;

public class SubmitTool {

    private static final Logger log = LoggerFactory.getLogger(SubmitTool.class);
    private final TodoManager todos;

    public SubmitTool(TodoManager todos) { this.todos = todos; }

    @Tool(name = "submit_to_frontend",
          description = "把所有 PENDING 待办下发前端。" +
                        "【调用时机】仅当用户明确表达「提交 / 确认 / 下发 / 发布 / 入库 / 保存生效」等意图时才调用；" +
                        "需求分析、登记 create_app / create_module / create_model 的阶段一律不要调，" +
                        "也不要把它当成工作流的收尾步骤——结束本轮工具调用直接用一句话向用户总结即可。" +
                        "【调用方式】必须先以 confirmed=false 调一次，让系统等用户确认；" +
                        "用户确认后系统会自动让你恢复，再以 confirmed=true 调一次完成真正下发。")
    public String submit(@ToolParam(name = "confirmed",
                                    description = "用户是否已确认。第一次必填 false。") boolean confirmed) {
        List<TodoItem> pending = todos.snapshot().stream()
                .filter(it -> it.status() == TodoStatus.PENDING).toList();

        if (pending.isEmpty()) return "没有 PENDING 待办，无需下发";

        if (!confirmed) {
            String summary = pending.stream()
                    .map(it -> String.format("- %s [%s] %s", it.id(), it.type(), it.targetName()))
                    .reduce((a, b) -> a + "\n" + b).orElse("");
            log.info("[Submit] suspend with {} items", pending.size());
            throw new ToolSuspendException("AWAITING_USER_CONFIRMATION\n" + summary);
        }

        // confirmed=true：真发（Day 5 dry-run，只是把状态机走一遍）
        // Day 4 设计意图：RUNNING 表示"工具下发中"。Day 6 接前端后，markRunning 和
        // markSuccess 之间会插入实际 SSE 下发 + 等待前端 ACK 的逻辑；
        // 本日 dry-run 直接顺一遍只为把状态机跑通。
        int n = 0;
        for (TodoItem it : pending) {
            todos.markRunning(it.id());
            // TODO Day 6：换成 bridge.dispatch(it).block() 后再 markSuccess
            todos.markSuccess(it.id());
            n++;
        }
        log.info("[Submit] dispatched {} items (dry-run)", n);
        return "已下发 " + n + " 项（dry-run，Day 6 接前端）";
    }
}
```

### 7.3 prompt 补丁

`analyst-multi-round.md` 末尾的"# 提交"段已经描述了。再次核对 LLM 真的会按"先 false 后 true"的顺序调。

### ✅ Phase 4 验收

- [ ] 用户没确认时 `submit_to_frontend` 抛出 `ToolSuspendException`
- [ ] 看日志看到 `[Submit] suspend with N items`
- [ ] TodoManager 状态在 suspend 阶段仍是 PENDING（不动）

---

## 8. Phase 5 · CLI 端确认回填（60 min）

### 8.1 REPL `/submit` 命令

```java
} else if (line.equals("/submit")) {
    runSubmit(analystWithTools, session, sc);   // sc 是 main 里已存在的 Scanner 实例
    continue;
}
```

> ⚠️ **必须共用 main 里那一个 `Scanner`**，不要在 `runSubmit` 内 `new Scanner(System.in)`——两个 Scanner 抢同一个 `System.in` 缓冲区，Windows 下经常表现为"按 y 没反应 / 多吞一行"。

> 📌 **1.0.12 suspend 的实际形态**（先看 jar 再写代码）：当 `SubmitTool.submit(false)` 抛 `ToolSuspendException` 时，AS-Java 1.0.12 框架内部 catch 它，调 `ToolResultBlock.suspended(toolUse, exception)` 生成一个 **`metadata[agentscope_suspended]=true`** 的 `ToolResultBlock`（自带 `id` / `name`），把这个 block 塞进返回 `Msg` 的 content，并把 `Msg.getGenerateReason()` 置为 `GenerateReason.TOOL_SUSPENDED`。所以 REPL 侧的检测只有**一条路**：看返回 Msg 的 `getGenerateReason()`。不需要再去单独维护"上一个 `ToolUseBlock.id`"——挂起 `ToolResultBlock` 本身就带着。文档早期写的 `lastToolUseId(agent)` / `isSuspend(e)` / `suspendReason(out)` 是版本敏感占位 facade，1.0.12 下不需要。

`runSubmit` + `handleSuspend`：

```java
private static void runSubmit(ReActAgent agent, FileSession session, Scanner sc) {
    log.info("[USER /submit]");
    Msg out;
    try {
        // 文案用"请把所有待办下发前端"而不是"我确认了"，避免 LLM 跳过挂起直接走 confirmed=true
        out = agent.call(Msg.builder().textContent("请把所有待办下发前端").build())
                .timeout(AppConfig.timeout())
                .block();
    } catch (Exception e) {
        // 1.0.12 框架会把 ToolSuspendException 转成 TOOL_SUSPENDED Msg，正常路径走不到这里；
        // 保留 catch 是为了兜底未来版本如果改成"直接抛"也能给出可读错误而不是栈撕裂。
        log.error("/submit failed: {}", e.toString(), e);
        System.out.println("(error: " + e.getMessage() + ")");
        session.save();
        return;
    }

    try {
        if (out != null && out.getGenerateReason() == GenerateReason.TOOL_SUSPENDED) {
            handleSuspend(agent, session, sc, out);
        } else {
            System.out.println("[ASSISTANT] " + (out == null ? "(空)" : out.getTextContent()));
        }
        printTodos();
    } finally {
        session.save();
    }
}

private static void handleSuspend(ReActAgent agent, FileSession session, Scanner sc, Msg suspended) {
    // 从挂起 Msg 的 content 里捞出 isSuspended()==true 的 ToolResultBlock：id / name 全在它身上
    ToolResultBlock pending = suspended.getContentBlocks(ToolResultBlock.class).stream()
            .filter(ToolResultBlock::isSuspended)
            .findFirst()
            .orElse(null);
    if (pending == null) {
        log.warn("[Submit] TOOL_SUSPENDED 但没找到 isSuspended() 的 ToolResultBlock");
        System.out.println("[ASSISTANT] " + suspended.getTextContent());
        return;
    }

    // 挂起 reason = SubmitTool 抛 ToolSuspendException 时携带的文本（含 PENDING 清单）
    String reason = pending.getOutput().stream()
            .filter(TextBlock.class::isInstance)
            .map(b -> ((TextBlock) b).getText())
            .reduce((a, b) -> a + "\n" + b)
            .orElse("(no reason)");

    System.out.println("[CONFIRM?]");
    System.out.println(reason);
    System.out.print("确认下发？(y/N): ");
    String ans = sc.nextLine().trim().toLowerCase();   // 复用外层 Scanner
    String reply = ("y".equals(ans) || "yes".equals(ans)) ? "USER_CONFIRMED" : "USER_REJECTED";
    log.info("[Submit] toolUseId={} reply={}", pending.getId(), reply);

    // 回填 ToolResult：role=TOOL + 同样的 id/name + 文本 USER_CONFIRMED/USER_REJECTED
    // 这里 builder 走长形式（role / content），不是 textContent 短形式——因为 content 是 ToolResultBlock 不是 TextBlock
    Msg toolResultMsg = Msg.builder()
            .role(MsgRole.TOOL)
            .content(ToolResultBlock.of(
                    pending.getId(),
                    pending.getName(),
                    TextBlock.builder().text(reply).build()))
            .build();

    try {
        Msg next = agent.call(toolResultMsg).timeout(AppConfig.timeout()).block();
        System.out.println("[ASSISTANT] " + (next == null ? "(空)" : next.getTextContent()));
    } catch (Exception e) {
        log.error("/submit resume failed: {}", e.toString(), e);
        System.out.println("(error: " + e.getMessage() + ")");
    }
}
```

> 📌 几点关键约定：
> - **`Msg` 短/长形式**：仓库主路径（`ScopeApp.java`、`WireMockAgentTest`）用 `Msg.builder().textContent(...).build()`；这里回填 `ToolResultBlock` 时必须走长形式 `role(...).content(ToolResultBlock.of(id, name, TextBlock)).build()`，因为 content 已经不是纯文本。
> - **不用 `lastToolUseId(agent)`**：1.0.12 把 id 直接写在挂起的 `ToolResultBlock` 上，从返回 Msg 取即可。如果你用的是更早的 patch 版本发现 Msg 上拿不到 id，再退而求其次去 Memory 翻最后一个 `ToolUseBlock`。
> - **`finally { session.save(); }`**：suspend 路径会 sc.nextLine() 阻塞，期间崩溃也要保住已落 TodoManager 的待办——所以 save 必须放在 finally。

### 8.1.1 `/run` 也要接 suspend（坑！）

文档早期版本只在 `/submit` 加挂起处理，实测**翻车**：

```
> /run 做一个员工档案管理
... LLM 调了 create_app / create_module / create_model 之后
... 模型把 submit_to_frontend 当成工作流收尾步骤一并调了
[Submit] suspend with 3 items
[ASSISTANT]                    ← /run 没认 TOOL_SUSPENDED，打了个空字符串就返回
=== Todos (3) ===   PENDING / PENDING / PENDING
> /submit
java.lang.IllegalStateException: Pending tool calls exist without results.
  Pending IDs: [call_xxxxxxxxxx]
```

根因：`/run` 拿到 `TOOL_SUSPENDED` Msg 后没把里面的挂起 `ToolUseBlock` 续完，那条 `tool_use` 永远留在 Memory 里。下一次 `agent.call(...)`（不管是 `/run` 还是 `/submit`）一进 `ReActAgent.doCall` 头部检查就直接抛 `Pending tool calls exist without results`。

**Phase 4 的 description guard 是治本，Phase 5 的 `/run` suspend 兜底是治标**，两道并行：

```java
if (line.startsWith("/run ")) {
    ...
    try {
        Msg out = analystWithTools.call(Msg.builder().textContent(req).build())
                .timeout(AppConfig.timeout())
                .block();
        // LLM 可能在 /run 这一轮就自作主张调 submit_to_frontend(confirmed=false)，触发挂起。
        // 不处理就会把没回填的 tool_use 留在 Memory，下次 agent.call 会抛
        // "Pending tool calls exist without results"。这里走和 /submit 同一套 handleSuspend。
        if (out != null && out.getGenerateReason() == GenerateReason.TOOL_SUSPENDED) {
            handleSuspend(analystWithTools, session, sc, out);
        } else {
            System.out.println("[ASSISTANT] " + (out == null ? "(空)" : out.getTextContent()));
        }
        printTodos();
    } catch (Exception e) {
        log.error("/run failed: {}", e.toString(), e);
        System.out.println("(error: " + e.getMessage() + ")");
    } finally {
        session.save();
    }
    continue;
}
```

> 📌 **不要只靠 prompt / description 拦**。LLM 行为是概率的，prompt 写得再死，遇到大需求 / 长上下文还是有概率绕开。REPL 侧的 suspend 兜底是 last line of defense——不管 LLM 在哪一轮、出于什么理由调了 `submit_to_frontend(false)`，REPL 都应当能弹 y/N 把 ToolResult 续上。

### 8.2 跑一遍

```
> /run 做一个员工档案管理
=== Todos (3) ===

> /submit
[ASSISTANT] （LLM 调了 submit_to_frontend(confirmed=false)，进入挂起）
[CONFIRM?]
AWAITING_USER_CONFIRMATION
- todo-1 [CREATE_APP] 员工档案管理
- todo-2 [CREATE_MODULE] 员工管理
- todo-3 [CREATE_MODEL] employee
确认下发？(y/N): y
[ASSISTANT] 已下发 3 项
=== Todos (3) ===
  todo-1  员工档案管理              SUCCESS
  todo-2  员工管理                 SUCCESS
  todo-3  employee                SUCCESS
```

### 8.3 拒绝路径

```
> /submit
[CONFIRM?] ...
确认下发？(y/N): n
[ASSISTANT] 已取消下发，待办保持原状
=== Todos (3) ===
  todo-1  ...  PENDING
  ...
```

### ✅ Phase 5 验收

- [ ] y 路径：所有 PENDING → SUCCESS
- [ ] n 路径：所有 PENDING 保持 PENDING
- [ ] 日志里能看清 ToolUseBlock id 和 ToolResult 内容

---

## 9. Phase 6 · 完整剧本 + commit（45 min）

### 9.1 剧本回归测试

> ⚠️ **本节是设计指引，不是可直接 copy-paste 的代码**。Day 5 涉及 ToolSuspend、ToolUseBlock id 回填等版本敏感 API，端到端测试很难脱离实际 jar 给出稳定代码。下面只列**测试场景设计**，具体实现请按你跑通的 8.1 代码补 facade。

**目标**：用 WireMock 录一组 LLM 响应（4 段），驱动以下场景：

| 场景 | LLM 输出 | 验收 |
|------|----------|------|
| 第一轮"做库存管理" | 多个 tool_calls：`create_app` + 2× `create_module` + 2× `create_model` | `todos.size() == 5`，全 PENDING |
| 第二轮"加出库审批" | `list_todos` + 1× `create_module` + 1× `create_model` | `todos.size() == 7`，原 5 项不动 |
| /submit 第一次 | `submit_to_frontend(confirmed=false)` | 触发 suspend，状态全 PENDING |
| 回填 USER_CONFIRMED | `submit_to_frontend(confirmed=true)` | 全部 → SUCCESS |
| 回填 USER_REJECTED 路径 | LLM 1 句话告知取消 | 状态保持 PENDING |

**测试骨架**（伪 facade，仅示意结构）：

```java
@Test
void fullScenario_addThenAppendThenConfirm() throws Exception {
    // 1. 起 WireMock，加载 src/test/resources/wiremock/__files/day5-*.json 四份 stub
    // 2. 构造 FileSession (tmp 目录)，buildAnalystWithTools(session.todos, session.memory)
    // 3. 调 agent.call(Msg.builder().textContent("做一个库存管理系统").build()).block()
    //    断言：session.todos.size() == 5
    // 4. 同一个 agent 再 call("再加个出库审批")
    //    断言：session.todos.size() == 7、原 5 项 status 不变
    // 5. 触发 /submit 链路（参考 8.1 的 runSubmit），用 USER_CONFIRMED 回填
    //    断言：所有项 → SUCCESS
}

@Test
void rejectConfirm_keepsPending() {
    // 同上前 3 步；回填 USER_REJECTED
    // 断言：所有项保持 PENDING
}
```

> 📌 **录 mock 的关键**：录的是 LLM 的 tool_calls 输出，**TodoManager 状态由我们自己的工具代码控制**，断言验的是状态机和 TodoManager 数量。WireMock 端只需把 `/chat/completions` 的响应按调用次序串起来即可，参考 Day 3 `RequirementParserMockTest` 的 stub 顺序写法。

### 9.2 跨进程持久化测试

```java
@Test
void afterRestart_todosRecovered() {
    // 在 tmp dir 写一份 sessions/<id>.json
    FileSession s1 = FileSession.loadOrNew("test-restart");
    s1.todos.add(TodoType.CREATE_APP, "x", JsonNodeFactory.instance.objectNode());
    s1.save();

    FileSession s2 = FileSession.loadOrNew("test-restart");
    assertEquals(1, s2.todos.size());
}
```

### 9.3 commit

> 📌 列具体文件、不要整目录 `git add`：`src/main/java/space/wlshow/scope/tool/` 在 Day 4 已经 commit，整目录 add 会让 commit 意图不清晰。

```bash
git add src/main/java/space/wlshow/scope/session/FileSession.java \
        src/main/java/space/wlshow/scope/tool/TodoQueryTools.java \
        src/main/java/space/wlshow/scope/tool/TodoUpdateTools.java \
        src/main/java/space/wlshow/scope/tool/SubmitTool.java \
        src/main/java/space/wlshow/scope/todo/TodoItem.java \
        src/main/java/space/wlshow/scope/todo/TodoManager.java \
        src/main/java/space/wlshow/scope/agent/AgentFactory.java \
        src/main/java/space/wlshow/scope/util/Prompts.java \
        src/main/java/space/wlshow/scope/ScopeApp.java \
        src/main/resources/prompts/analyst-multi-round.md \
        src/test/java/space/wlshow/scope/agent/Day5ScenarioTest.java \
        .gitignore

git commit -m "day5: 多轮对话 + Session 持久化 + CLI HITL

- InMemoryMemory 接到 buildAnalystWithTools，多轮上下文连贯
- list_todos / update_module / update_model 三个增量工具（update_* 沿用工具内 Schema 兜底）
- TodoItem.withPayload + TodoManager.replacePayload 对称 Day 4 的 withStatus
- FileSession 把 TodoManager 落 data/sessions/<id>.json（Memory 不持久化，由 prompt 约束 list_todos 兜底）
- SubmitTool 用 ToolSuspendException 实现 CLI HITL，y/n 回填 ToolResult
- SubmitTool.description 加【调用时机】guard，拦住 LLM 把 submit 当工作流收尾乱调
- ScopeApp 的 /run 也接 TOOL_SUSPENDED 兜底，防止 LLM 中途挂起后 Memory 卡死
- 端到端剧本测试骨架：增量追加 → y 确认 / n 拒绝
- data/sessions/ 加入 .gitignore"
```

### 9.4 更新文档导航

`README.md` 加 Day 5 链接。`CLAUDE.md` 第 9 节表格加 Day 5 行。

### ✅ Phase 6 验收

- [ ] 完整剧本测试全过
- [ ] 进程 kill 重启 todos 恢复
- [ ] 文档导航更新

---

## 10. 故障排查表

| 现象 | 原因 / 排查 |
|------|-----------|
| 第二轮 LLM 不调 `list_todos` 直接重生 | system prompt 不够强；查 `analyst-multi-round.md` 是否真用上；模型可能不擅长长 prompt，换 qwen-max / doubao-pro |
| `update_module` 找不到 moduleId | LLM 把 moduleId 拼错（小驼峰漂移）；让 `list_todos` 输出更明显，prompt 里强调"复制原 id"  |
| `submit_to_frontend` 没挂起，直接返回 | ToolSuspendException 全限定名错；1.0.12 实际是 `io.agentscope.core.tool.ToolSuspendException`（**不是** `.exception` 子包）|
| `/submit` 报 `Pending tool calls exist without results. Pending IDs: [call_xxx]` | LLM 在上一轮 `/run` 里就调了 `submit_to_frontend(false)`，REPL 没接挂起 → 未回填的 `ToolUseBlock` 卡在 Memory 里。**`/run` 也必须走 `handleSuspend`**（见 § 8.1.1），同时强化 `SubmitTool.description` 的"调用时机"段。临时排障：`exit` 重启 REPL（Memory 不持久化，会清空），保留 `data/sessions/<id>.json` 即可 |
| `/run` 末尾 `[ASSISTANT]` 是空字符串，日志却看到 `[Submit] suspend with N items` | 同上：LLM 在 `/run` 中误触发 submit；REPL 拿到的 Msg `getGenerateReason()==TOOL_SUSPENDED`，文本字段为空，不接就丢——见 § 8.1.1 |
| 回填 ToolResult 报"unknown toolUseId" | 1.0.12 下挂起的 `ToolResultBlock.getId()` 直接取即可，不用 `lastToolUseId(agent)`；老版本回退到"按工具名筛最后一个 `ToolUseBlock`" |
| 重启后 todo-N 序号又从 1 开始 | `TodoManager.loadState` 没读 `seq`；本课的实现已读，检查 `getState/loadState` 是否对称 |
| `data/sessions/` 进了 git | `.gitignore` 没生效；用 `git rm --cached -r data/sessions/` 取消跟踪 |
| Memory 越积越长导致 token 超 | Day 7 升级到 Harness 的 Compaction；Day 5 暂时用 `if (memory.size() > 30) memory.clearOldest()` |

---

## 11. 附录 A · 为什么不直接用 AS-Java 的 `JsonSession`、并且 Memory 不持久化？

AS-Java 1.0.x 系列里 `JsonSession` 的字段命名和 `StateModule` 接口在 patch 版本之间有微调，1.0.12 与 1.1.0-RC1 又有差异。Day 5 我们走自写的 `FileSession`，原因：

1. 我们只需要"加载 + 保存"两个动作，自写 30 行
2. AS-Java 自己的 Session 把 Memory 和 StateModule 都包了，但我们的 `TodoManager` 还没实现 `StateModule`，硬接需要先适配
3. Day 7 升级 Harness 时反正要重写一遍，没必要今天叠两层

**Memory 不持久化的额外考量**：`Msg` 是含 `ContentBlock` 多态字段的密封类型（TextBlock / ToolUseBlock / ToolResultBlock / ThinkingBlock ...），用 Jackson 直接 `readList(json, Msg.class)` 在不同 AS-Java 版本会因为多态注解微调而崩。本课程的策略：

- **持久化**只保留 TodoManager（纯 record + 简单 JsonNode payload，反序列化稳定）
- **Memory 重启从空起**，靠 prompt §"第二轮起必须先调 list_todos" 让 LLM 自行重建上下文

如果你确实想连 Memory 也持久化，三条路（详见 **§14 附录 D · Memory 持久化扩展（选修）**）：
- **方案 A · 纯文本快照**（30 行）：只存 user/assistant 文本，丢工具调用历史，适合纯聊天场景
- **方案 B · ContentBlock 完整序列化**（150 行）：自写 Jackson mixin + 专用 mapper，重启如未中断
- **方案 C · Harness 内置 Session**：升 1.1.x，框架托管，详见 Day 7 附录 B

如果你想体验官方 Session，看 [../agents/06-memory-state-session.md](../agents/06-memory-state-session.md)，按 jar 里实际签名替换即可。

---

## 12. 附录 B · `ToolSuspend` 替代方案：Hook 拦截

如果你的 AS-Java 版本 ToolSuspend 不好使，可以用 Hook 拦截 `submit_to_frontend` 工具调用：

```java
// 注意：1.0.12 实际是单一 Hook 接口 + onEvent(HookEvent) 分派
// （见仓库 PromptLengthHook 的写法）。下面是示意伪码，
// 真做时请按 jar 里 Hook / HookEvent / ToolCall 相关事件实际签名替换。
public class SubmitConfirmHook implements Hook {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        // if (event instanceof PreActingEvent e && "submit_to_frontend".equals(e.getToolName())) {
        //     弹 CLI 询问，拒绝时通过 e.cancel(reason) 阻止真正调用
        // }
        return Mono.just(event);
    }
}
```

Hook 路径的好处是**工具实现保持纯净**，缺点是从 LLM 视角看不到"挂起"事件，难以引导它"等用户确认完再说"。Day 5 优先走 ToolSuspend，Hook 留作 Day 7 备选。

---

## 13. 附录 C · 实现与文档差异回顾

落地 Phase 4 / Phase 5 时，**实际跑通的代码与本文 §7.2 / §8.1 早期示例有不小偏离**。这一节把每条偏离原原本本记下来，给后续学员一份 errata，也给文档作者一面镜子。

### 13.1 偏离清单

| 文档原样 | 实际落地 | 偏离原因 |
|---|---|---|
| `import io.agentscope.core.exception.ToolSuspendException;`（§7.2）| `import io.agentscope.core.tool.ToolSuspendException;` | 1.0.12 sources jar 里实际包名是 `core.tool`，文档作者写示例时凭命名习惯猜了 `.exception`，没翻 jar |
| `@Tool description` 只写"怎么调"（§7.2） | description 多了【调用时机】段，明确禁止在需求分析阶段调 | 实测发现 LLM 把 `submit_to_frontend` 当成工作流收尾，需求分析末尾自作主张调它，prompt 拦不住 |
| `runSubmit` 用 `isSuspendOutput(out)` / `suspendReason(out)` 占位 facade（§8.1） | 用 `out.getGenerateReason() == GenerateReason.TOOL_SUSPENDED` 一条路 | 文档为了兼容"未来 patch 版本可能改成抛异常"留了 facade 抽象；1.0.12 实际只有一条路，不需要 facade |
| `handleSuspend(..., String reason)` + 单独 `lastToolUseId(agent)` 取 id（§8.1） | `handleSuspend(..., Msg suspended)`，从挂起 `ToolResultBlock` 上**直接** `getId()` / `getName()` / `getOutput()` | 1.0.12 框架内部 catch `ToolSuspendException` 后调 `ToolResultBlock.suspended(toolUse, exception)` 生成一个 `isSuspended()==true` 的块，**自带 id / name / reason text**，根本不用单独再翻 Memory 拿 id |
| 只在 `/submit` 接挂起（§8.1） | `/run` **也**走 `handleSuspend`（§8.1.1 新增） | 实测翻车：LLM 在 `/run` 一轮里就调了 `submit_to_frontend(false)`，`/run` 没接挂起 → 未回填 `tool_use` 卡死 Memory → 下次 `agent.call` 直接抛 `Pending tool calls exist without results` |
| `AgentFactory.buildAnalystWithTools` 用 `Prompts.analystMultiRound()`（§4.1） | 仓库一度仍用 `Prompts.analystWithTools()`（Day 4 prompt） | Phase 1 prompt 切换"忘了切"。`analyst-multi-round.md` 写好了、`Prompts.analystMultiRound()` 也加了，但 `AgentFactory` 那一行 `.sysPrompt(...)` 没改 →  **已修复**：切到 `analystMultiRound()` + 把 `analyst-multi-round.md` 重写为自包含（内联 Day 4 工作流 + 字段规范 + 不确定信息处理，避免跨文件指代失效） |
| Phase 6 commit 清单（§9.3）| 实际 commit 还需要包含 `SubmitTool` description 改动、`ScopeApp` 的 `/run` suspend 兜底改动 | Phase 4 / Phase 5 / Phase 6 之间的微改没回写到 9.3 的清单里 |

### 13.2 偏离背后的四类根因

把上面 7 条对照归纳，差异主要来自这四类：

**(a) 文档写作时对 jar 不确信，留了过多版本敏感占位（占 4 条）**

`isSuspend(e)` / `suspendMessage(e)` / `isSuspendOutput(out)` / `suspendReason(out)` / `lastToolUseId(agent)` —— 这些方法名都是"等学员按 jar 实际签名替换"的 facade。占位的本意是"不锁死签名"，结果是学员（包括 LLM-coder）真要落地时多了一道**翻译成本**：要先把这一堆假名翻译到 `getGenerateReason()` / `getContentBlocks(ToolResultBlock.class).filter(isSuspended)`。

更糟糕的是，占位 facade 容易把"实现该有几个文件、几条路"的拓扑也搞错。文档的 `handleSuspend(String reason)` 默认 reason 是个字符串，但 1.0.12 实际你要的是整个 Msg（要从里面捞 id + name + reason），signature 一开始就错了。

**(b) 文档写作时没跑过 LLM 真实路径，没意识到 LLM 会"工作流末尾顺手调 submit"（占 2 条）**

`description` 的 guard 段、`/run` 的 suspend 兜底，都是实测翻车后才发现的需求。文档的 §0 剧本默认 LLM 守规矩、只在 `/submit` 时才调 submit ——这是**作者视角**，不是**模型视角**。从模型视角看，`submit_to_frontend` 一注册进 Toolkit 就是工具集的一员，description 没拦就当成可以随便调；prompt 离 tool description 远，约束弱。

`/run` 没接 suspend 是设计冗余的缺失：HITL 兜底必须在 `agent.call` **每一个**出口都接，不只是用户敲 `/submit` 那一次。这条偏离本质是"防御性编程做到一半"。

**(c) Phase 之间的依赖没显式串起来（占 1 条）**

`buildAnalystWithTools` 该用哪份 prompt 是 Phase 1 的事，但它对 Phase 4 `SubmitTool` 的行为约束有跨 Phase 的影响（prompt 的"# 提交"段是否生效）。文档把每个 Phase 当独立模块写，没在 Phase 1 末尾打一个"如果你跳过这步，Phase 4 的 LLM 行为约束会少一道"的醒目提示，结果实际落地时 Phase 1 切了 Memory 没切 prompt，没人发现。

还有一层更隐蔽的：**prompt 文件之间不能跨文件指代**。`analyst-multi-round.md` 早期版本写"按 Day 4 的工作流"，但模型看到的 `sysPrompt` 是**单一字符串**，Day 4 prompt 根本不在 context 里——跨文件指代等于断链。本课修复后已把 Day 4 工作流 + 字段规范 + 不确定信息处理全部**内联**进 multi-round prompt，§4.2 的 ⚠️ 警示框就在讲这件事。

**(d) 文档 commit 清单（§9.3）没回写微改（占 1 条）**

Phase 4 / Phase 5 / Phase 6 之间互相 patch 时新增的小改动（description guard、`/run` suspend 分支），没回写到 9.3 的 `git add` 清单里。这是工程上的小漏，但学员照清单 commit 会落下文件。

### 13.3 给后续课程文档的写作建议

1. **先查 jar，再写示例代码**——`@Tool` / `ToolSuspendException` / `ToolResultBlock` / `GenerateReason` 这些 API 都能用 `jar -tf agentscope-1.0.12-sources.jar | grep XX` 验，写示例前花 30 秒查一遍包路径，能挡掉 §13.1 第 1 / 4 行整类偏离。
2. **占位 facade 要伴随"占位翻译表"**——如果某段示例用了 `isSuspend(e)` 这种占位名，文档必须紧跟一个翻译表：「在 1.0.12 这是 X，在 1.1.0 这是 Y」。不要把翻译甩给学员。
3. **HITL 设计要枚举所有 agent.call 出口**——不止 `/submit`，凡是会 `agent.call(...)` 的命令（`/run` / `/parse` / 未来的 `/replay`）都得接 suspend。文档写 HITL 时应当先列"agent.call 出口表"，每个出口都画进时序图。
4. **Phase 之间的依赖要"前向引用"**——Phase 1 末尾应当说"如果你跳过 prompt 切换，Phase 4 / Phase 5 会出现什么具体症状"，不要等学员自己撞墙。
5. **prompt 文件之间不能跨文件指代**——`sysPrompt` 是单一字符串，模型只能看到当前那一份；写"按 X 文件的工作流"等同于断链。需要复用就**内联**，DRY 在 prompt 写作里优先级远低于"打开一个文件就看到全部"。
6. **Commit 清单（§9.x）应当在每个 Phase 结束后回写**——Phase 4 / Phase 5 改了哪些文件，加进 Phase 6 的 `git add` 清单。否则清单天生滞后。

---

## 14. 附录 D · Memory 持久化扩展（选修）

> 📌 §11 附录 A 给了"为什么 Day 5 主路径不持久化 Memory"的论点。这一节给"如果你真要做，该怎么做"的可跑通方案。**不是 Day 5 必学**，跳过不影响主流程；但当你的场景命中 D.1 列出的几种情况，回来照着做。

### D.1 什么场景必须持久化 Memory

主路径用"只持久化 TodoManager + prompt 强制 `list_todos`" 兜底，是因为**我们做的是需求分析，每条用户消息都被工具调用转化为 TodoItem 的变更**——事实可重建，过程丢了不影响。

但下面这些场景**事实没法从工具调用重建**，Memory 必须持久化：

| 场景 | 为什么 TodoManager 兜不住 |
|------|---------------------------|
| 长对话角色扮演 / 心理咨询 / 文学创作 | 根本没有结构化产物，过程就是事实本身 |
| 客服 / 协作场景 | 用户 A 说的话用户 B 接手时必须看见，TodoManager 不存原话 |
| 合规审计 | 法规要求保留完整对话原文，不能"靠重建" |
| 多模态对话（图片 / 音频） | `ImageBlock` / `AudioBlock` 的 base64 数据没法从工具历史推回来 |
| Few-shot 长上下文 LLM 套娃 | 前几轮的 LLM 推理过程（`ThinkingBlock`）也想留下来做参考 |

### D.2 三档方案对照

| 方案 | 实现成本 | 能力 | 版本风险 | 推荐场景 |
|------|---------|------|---------|---------|
| **A · 纯文本快照** | ~30 行 | 只存 user/assistant 文本，丢工具调用历史 | 几乎无 | 客服、角色扮演 |
| **B · ContentBlock 完整序列化** | ~150 行 + Jackson mixin | 完整保留全部 block 类型，重启如未中断 | 中（AS-Java 升级时新增子类要补） |合规、多模态 |
| **C · Harness 内置 Session** | 升级到 1.1.x RC | 框架托管，含 Compaction | 升级到 RC 的稳定性风险 | 新项目、不介意 RC |

下面三种都给出可跑通代码 + round-trip 测试。

### D.3 方案 A · 纯文本快照

**核心思路**：只把 `MsgRole.USER` 和 `MsgRole.ASSISTANT` 的**纯文本**摘出来存数组；`MsgRole.TOOL` / `ToolUseBlock` / `ToolResultBlock` **全丢**。重启后按 `[(role, text), ...]` 重建 Memory。

`session/TextMemorySnapshot.java`：

```java
package space.wlshow.scope.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import space.wlshow.scope.util.Json;

import java.util.List;

/** 把 Memory 拍成纯文本快照（丢工具调用）。 */
public final class TextMemorySnapshot {

    public static JsonNode dump(Memory memory) {
        ArrayNode arr = Json.mapper().createArrayNode();
        for (Msg m : memory.getMessages()) {
            if (m.getRole() != MsgRole.USER && m.getRole() != MsgRole.ASSISTANT) continue;
            String text = m.getTextContent();
            if (text == null || text.isBlank()) continue;
            ObjectNode n = arr.addObject();
            n.put("role", m.getRole().name());
            n.put("text", text);
        }
        return arr;
    }

    public static void restore(Memory memory, JsonNode arr) {
        if (arr == null || !arr.isArray()) return;
        for (JsonNode n : arr) {
            MsgRole role = MsgRole.valueOf(n.path("role").asText());
            String text = n.path("text").asText();
            memory.add(Msg.builder()
                    .role(role)
                    .content(TextBlock.builder().text(text).build())
                    .build());
        }
    }

    private TextMemorySnapshot() {}
}
```

`FileSession.loadOrNew` 与 `save` 里接一下：

```java
// save()
root.set("memorySnapshot", TextMemorySnapshot.dump(memory));

// loadOrNew()
TextMemorySnapshot.restore(memory, root.path("memorySnapshot"));
```

**限制**：
- 工具调用历史丢失。重启后 LLM 看不到上一轮调用了 `create_app`，所以你**仍然**需要 prompt 强制 `list_todos`
- 不适合 Day 5 这种重度工具调度的场景；适合"只聊天" 的 Agent

### D.4 方案 B · ContentBlock 完整序列化

**核心思路**：用 Jackson 的 mixin 注解给 `ContentBlock` 加上 `@JsonTypeInfo` + `@JsonSubTypes`，注册到 `ObjectMapper`。这样 `Msg` 整条都能 round-trip。

#### D.4.1 写 Mixin（不动框架源码）

`util/ContentBlockMixin.java`：

```java
package space.wlshow.scope.util;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;

/**
 * 给框架的 ContentBlock 接口附加多态注解（mixin 不动源码）。
 *
 * ⚠️ 版本敏感：AS-Java 升版本可能新增 ContentBlock 子类（如未来的 ImageBlock /
 * AudioBlock）。新增时这里必须补一行 @Type，否则 Jackson 反序列化会抛
 * UnrecognizedSubtypeException。补法：mvn dependency:sources →
 * jar -tf ... | grep "extends ContentBlock" 拿到当前所有子类列表 → 一一对齐。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "_type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextBlock.class,       name = "text"),
        @JsonSubTypes.Type(value = ToolUseBlock.class,    name = "tool_use"),
        @JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool_result"),
        @JsonSubTypes.Type(value = ThinkingBlock.class,   name = "thinking"),
        // 升版本时在这里补：@JsonSubTypes.Type(value = ImageBlock.class, name = "image"),
})
public interface ContentBlockMixin {}
```

#### D.4.2 给 `Json.mapper()` 注册 mixin

改 `util/Json.java`，在静态初始化里加：

```java
static {
    mapper.addMixIn(io.agentscope.core.message.ContentBlock.class, ContentBlockMixin.class);
    // 防止 ToolResultBlock 内层 output 字段（List<TextBlock>）也丢类型
    mapper.activateDefaultTyping(
            mapper.getPolymorphicTypeValidator(),
            ObjectMapper.DefaultTyping.NON_FINAL_AND_ENUMS,
            JsonTypeInfo.As.PROPERTY);
}
```

> ⚠️ `activateDefaultTyping` 是**全局**开关，会影响项目里其他 JSON 序列化形态。如果你 schema 校验对字段名敏感（多了 `@class` 之类），把这条限制成只对 `Memory` 用的专用 `ObjectMapper`，别动主 mapper。

#### D.4.3 写序列化/反序列化工具

`session/MemorySerde.java`：

```java
package space.wlshow.scope.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.util.Json;

import java.util.List;

public final class MemorySerde {

    private static final Logger log = LoggerFactory.getLogger(MemorySerde.class);
    private static final ObjectMapper M = Json.mapper();

    public static JsonNode dump(Memory memory) {
        return M.valueToTree(memory.getMessages());
    }

    /** 反序列化失败时只记 WARN 不抛——Memory 是过程数据，损坏一份只是降级到"重启没记忆"，不该让进程起不来。 */
    public static void restore(Memory memory, JsonNode arr) {
        if (arr == null || arr.isMissingNode() || !arr.isArray()) return;
        try {
            List<Msg> msgs = M.convertValue(arr, new TypeReference<List<Msg>>() {});
            msgs.forEach(memory::add);
            log.info("[Memory] restored {} messages", msgs.size());
        } catch (Exception e) {
            log.warn("[Memory] restore failed, fall back to empty memory: {}", e.toString());
        }
    }

    private MemorySerde() {}
}
```

#### D.4.4 接到 `FileSession`

```java
// save()
root.set("memory", MemorySerde.dump(memory));

// loadOrNew()
MemorySerde.restore(memory, root.path("memory"));
```

#### D.4.5 round-trip 单测

`src/test/java/space/wlshow/scope/session/MemorySerdeTest.java`：

```java
package space.wlshow.scope.session;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemorySerdeTest {

    @Test
    void roundTrip_textAndToolBlocks_preserved() {
        Memory src = new InMemoryMemory();
        src.add(Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text("做一个员工档案管理").build())
                .build());
        src.add(Msg.builder().role(MsgRole.ASSISTANT)
                .content(List.of(
                        TextBlock.builder().text("我先登记 APP").build(),
                        ToolUseBlock.builder()
                                .id("call-1").name("create_app")
                                .input("{\"name\":\"employeeMgr\"}").build()))
                .build());
        src.add(Msg.builder().role(MsgRole.TOOL)
                .content(ToolResultBlock.of("call-1", "create_app",
                        TextBlock.builder().text("APP 待办已登记：id=todo-1").build()))
                .build());

        // dump
        JsonNode snapshot = MemorySerde.dump(src);
        assertTrue(snapshot.isArray());
        assertEquals(3, snapshot.size());

        // restore
        Memory dst = new InMemoryMemory();
        MemorySerde.restore(dst, snapshot);

        assertEquals(3, dst.getMessages().size());
        Msg restored = dst.getMessages().get(1);
        assertEquals(MsgRole.ASSISTANT, restored.getRole());
        // 第二条消息含 2 个 block：TextBlock + ToolUseBlock，类型必须保留
        assertEquals(1, restored.getContentBlocks(TextBlock.class).size());
        assertEquals(1, restored.getContentBlocks(ToolUseBlock.class).size());
        assertEquals("call-1",
                restored.getContentBlocks(ToolUseBlock.class).get(0).getId());
    }

    @Test
    void restore_corruptedJson_fallsBackToEmpty() {
        // 假装从老版本读到的 JSON，含未知 _type
        String bad = "[{\"role\":\"USER\",\"content\":[{\"_type\":\"image\",\"url\":\"...\"}]}]";
        Memory dst = new InMemoryMemory();
        MemorySerde.restore(dst, com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                .arrayNode());   // 跳过 ImageBlock 未注册的 case，本测试只验"不抛"
        assertEquals(0, dst.getMessages().size());
    }
}
```

跑一遍：

```bash
mvn -q test -Dtest=MemorySerdeTest
```

应该全绿。

#### D.4.6 升级雷区与解法

**雷区 1：跨版本读老 JSON 抛 `UnrecognizedSubtypeException`**

场景：你用 1.0.12 写下了 Memory，升到 1.1.x 后 `ThinkingBlock` 改名为 `ReasoningBlock`，老 JSON 里的 `"_type":"thinking"` 找不到。

解法：
- 用 `@JsonTypeInfo(... defaultImpl = UnknownBlock.class)` 给一个兜底类型，把识别不出的 block 转成 `UnknownBlock` 保留原始 JSON，**不丢消息只丢内容**
- 升级前跑一次"导出旧 Memory → 标记 schema_version=1 → 用迁移脚本转换"

**雷区 2：`ToolResultBlock.output` 内层是 `List<ContentBlock>`，嵌套多态**

D.4.2 启用了 `activateDefaultTyping`，但全局开会有副作用。**专用 mapper** 写法：

```java
public final class MemoryMapper {
    private static final ObjectMapper INSTANCE = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .addMixIn(ContentBlock.class, ContentBlockMixin.class);
    static { INSTANCE.activateDefaultTyping(...); }
    public static ObjectMapper get() { return INSTANCE; }
    private MemoryMapper() {}
}
```

`MemorySerde` 改用 `MemoryMapper.get()`，主项目的 `Json.mapper()` 不变。

**雷区 3：序列化文件越来越大**

100 轮对话 + 工具结果可能 1MB+。两个手段：
- `save()` 之前限制只保留最近 N 条（比如 50 条）
- 或接 Day 7 附录 B 的 Harness Compaction，对历史做摘要

```java
// 简单版：save 前截断
List<Msg> recent = memory.getMessages();
if (recent.size() > 50) {
    recent = recent.subList(recent.size() - 50, recent.size());
    memory.clear();
    recent.forEach(memory::add);
}
```

### D.5 方案 C · Harness 内置 Session

详见 Day 7 附录 B。一句话：升 `agentscope-harness:1.1.0-RC1`，`HarnessAgent` 的 `HarnessConfig.sessionDir(...)` 自动落盘，框架处理所有 ContentBlock 子类的演进。

**代价**：1.1.x 还是 RC，公司 maven 镜像不一定同步；某些 1.0.x 行为可能微调（HITL / Hook 接口）。

### D.6 决策建议

```
你的 Agent 涉及工具调用吗？
├── 否（纯聊天 / 角色扮演）
│   └── → 方案 A（纯文本快照），30 行搞定
│
└── 是
    ├── 重启后从 0 开始用户能接受吗？
    │   ├── 能（每个 session 独立任务，TodoManager 已兜底）
    │   │   └── → 不持久化（Day 5 主路径）
    │   │
    │   └── 不能（合规 / 协作 / 多模态）
    │       ├── 项目能升 1.1.x RC 吗？
    │       │   ├── 能 → 方案 C（Harness）
    │       │   └── 不能 → 方案 B（ContentBlock 多态序列化）
```

### D.7 测试矩阵

确认你的 Memory 持久化真做对了，至少跑下面 5 个场景：

| # | 场景 | 期望 |
|---|------|------|
| T1 | dump → restore → 消息数量、role、文本内容完全相等 | round-trip 无损 |
| T2 | 含 `ToolUseBlock` + `ToolResultBlock` 的 Msg dump → restore → `getId()` / `getName()` 不丢 | 工具调用历史保留 |
| T3 | restore 时 JSON 里出现未注册的 `_type` | 不抛异常，跳过该 Msg，记 WARN |
| T4 | save 后人工把文件改一个 `_type` 损坏，restore | 降级到空 Memory，进程能起 |
| T5 | 进程 A 写、进程 B 读，跨进程一致 | 落盘格式不依赖 JVM 内存布局 |

T1-T3 在 §D.4.5 的单测里已经覆盖；T4 / T5 建议手工跑一次。

---

## 15. 写在 Day 6 之前

明天 Day 6 我们会：

- 把 CLI 入口替换成 **Spring Boot WebFlux** + `agentscope-agui-spring-boot-starter`
- 启动后自动暴露 `POST /agui/run`（SSE 端点）
- 摸熟 **17 个 AG-UI 事件**：Lifecycle / TextMessage / ToolCall / State / Special
- 起一个 **Vue3** 前端（Vite + `@ag-ui/client`），输入框敲入需求看到流式打字机回复

> 📌 **关于 CLI**：Day 6 不会"删掉"今天写的 CLI REPL，而是把它整体复制到一个备份类 `ScopeReplApp.java`（同目录、不带 Spring 注解），让 Spring Boot 引导成为新默认。需要回到 CLI 调试时走 `mvn exec:java -Dexec.mainClass=...ScopeReplApp`。所以 `/run` / `/submit` / `/todos` / `handleSuspend` 这套代码不会丢——只是默认入口换了。

Day 5 的 `ToolSuspend HITL` 在 Day 7 会被**重写**成 AG-UI 的 `TOOL_CALL_*` 事件 + 前端确认弹窗。今天的 CLI 实现是这套机制的"低保真原型"。
