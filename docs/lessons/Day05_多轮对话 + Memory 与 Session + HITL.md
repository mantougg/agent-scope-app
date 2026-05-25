# Day 5 · 多轮对话 + Memory 与 Session + HITL

> 上级文档：[../learning.md](../learning.md)
> 配套笔记：[../agents/06-memory-state-session.md](../agents/06-memory-state-session.md) · [../agents/04-tool-system.md § 工具挂起](../agents/04-tool-system.md) · [../agents/10-observability-hitl.md](../agents/10-observability-hitl.md)
> 前置：[Day 4 · TodoManager + 业务工具集](<Day04_TodoManager + 业务工具集.md>) 已完成

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

> ⚠️ **版本敏感**：`ToolSuspendException` / `GenerateReason.TOOL_SUSPENDED` 这些 API 名称在 AS-Java 1.0.x 和 1.1.x 之间可能微调，跑不通时先 `grep` 一遍 `agentscope-1.0.12-sources.jar`，本课程以 1.0.12 为基线。

### 3.4 预读链接

- [AS-Java Memory / State / Session](../agents/06-memory-state-session.md)
- [AS-Java Tool 工具挂起](../agents/04-tool-system.md)
- [AS-Java HITL 实战](../agents/10-observability-hitl.md)

### ✅ Phase 0 验收

- [ ] 能在白板画一条带 ToolSuspend 的时序图
- [ ] 能说出 Memory 和 Session 各自存什么

---

## 4. Phase 1 · Memory 接入 + 多轮 prompt（45 min）

### 4.1 给 `AgentFactory` 加 Memory

```java
public static ReActAgent buildAnalystWithTools(TodoManager todos, Memory memory) {
    initModels();
    Toolkit toolkit = new Toolkit(ToolkitConfig.builder().parallel(true).build());
    toolkit.registerTool(new FrontendCreateTools(todos));
    toolkit.registerTool(new TodoQueryTools(todos));         // Phase 2 新增
    toolkit.registerTool(new TodoUpdateTools(todos));        // Phase 2 新增
    toolkit.registerTool(new SubmitTool(todos));             // Phase 4 新增

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

在 REPL 启动时构造 Memory，并复用同一个实例多次 `/run`：

```java
Memory memory = new InMemoryMemory();
ReActAgent analyst = AgentFactory.buildAnalystWithTools(todos, memory);
```

### 4.2 新 prompt `prompts/analyst-multi-round.md`

```markdown
你是「需求分析助手」。多轮对话规则：

# 第一轮
- 用户给的是一份完整或半完整的需求
- 你按 Day 4 的工作流：create_app → create_module ×N → create_model ×N → 用 1 句话总结 + 列出假设和反问

# 第二轮起
- 用户可能在补充、修正、删除
- **必须先调 list_todos()**，看清当前待办再做判断
- 三种动作：
  1. **ADD**：用户提出全新模块或模型 → 用 create_module / create_model
  2. **MODIFY**：用户改既有模块的字段或 desc → 用 update_module / update_model
  3. **明确删除**：用户说"删掉 xxx" → 用 delete_module / delete_model（Day 5 不实现，先记 question）

# 严禁
- 重生整组待办（这会清掉用户已经看过/将要确认的）
- 把已存在的 module 用同名 `create_*` 再登记一遍（会被 Schema 拒）

# 提交
- 用户说"确认"/"提交"/"发布"等时，调 submit_to_frontend(confirmed=false) 先列出待办给用户预览
- 用户再次确认时（外层会自动回填 USER_CONFIRMED），系统会让你恢复，再调一次 submit_to_frontend(confirmed=true)
```

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

```java
package space.wlshow.scope.tool;

import io.agentscope.core.tool.Tool;
import space.wlshow.scope.todo.TodoItem;
import space.wlshow.scope.todo.TodoManager;

import java.util.stream.Collectors;

public class TodoQueryTools {

    private final TodoManager todos;

    public TodoQueryTools(TodoManager todos) { this.todos = todos; }

    @Tool(name = "list_todos",
          description = "列出当前所有待办（含 id / type / 名称 / 状态 / payload）。" +
                        "多轮对话第二轮起，新用户输入到来时必须先调一次。")
    public String listTodos() {
        if (todos.size() == 0) return "当前无待办";
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

```java
package space.wlshow.scope.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import space.wlshow.scope.model.FieldSpec;
import space.wlshow.scope.todo.TodoItem;
import space.wlshow.scope.todo.TodoManager;
import space.wlshow.scope.todo.TodoStatus;
import space.wlshow.scope.todo.TodoType;
import space.wlshow.scope.util.Json;

import java.util.List;
import java.util.Optional;

public class TodoUpdateTools {

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
        if (found.isEmpty()) return "ERROR: 未找到 moduleId=" + moduleId;

        TodoItem it = found.get();
        if (it.status() != TodoStatus.PENDING) {
            return "ERROR: " + it.id() + " 状态为 " + it.status() + "，不可修改";
        }

        ObjectNode p = ((ObjectNode) it.payload()).deepCopy();
        if (newModuleName != null && !newModuleName.isBlank()) p.put("moduleName", newModuleName);
        if (newModuleDesc != null && !newModuleDesc.isBlank()) p.put("moduleDesc", newModuleDesc);

        // TodoManager 没暴露"原地改 payload"接口，直接重建 record
        // 这里只是 demo，正式做法是给 TodoManager 加 updatePayload(id, newPayload)
        replacePayload(it, p);
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
        if (found.isEmpty()) return "ERROR: 未找到 model name=" + modelName;

        TodoItem it = found.get();
        if (it.status() != TodoStatus.PENDING) {
            return "ERROR: " + it.id() + " 状态为 " + it.status() + "，不可修改";
        }

        List<FieldSpec> appended = Json.readList(appendFieldsJson, FieldSpec.class);
        ObjectNode p = ((ObjectNode) it.payload()).deepCopy();
        var arr = (com.fasterxml.jackson.databind.node.ArrayNode) p.get("fields");
        appended.forEach(f -> arr.add(Json.mapper().valueToTree(f)));

        replacePayload(it, p);
        return "MODEL 已追加 " + appended.size() + " 个字段到 " + it.id();
    }

    private void replacePayload(TodoItem it, JsonNode newPayload) {
        // 简化实现：在 TodoManager 上加一个 setPayload，或者重建 item
        // 见 TodoManager 的修改（下面 5.3）
        todos.replacePayload(it.id(), newPayload);
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

### 5.3 `TodoManager` 加 `replacePayload`

```java
public void replacePayload(String id, JsonNode newPayload) {
    TodoItem cur = get(id);
    if (cur.status() != TodoStatus.PENDING) {
        throw new IllegalStateException("非 PENDING 不可改 payload: " + id);
    }
    TodoItem next = new TodoItem(
            cur.id(), cur.type(), cur.targetName(), newPayload,
            cur.status(), cur.errorMessage(), cur.createdAt(), java.time.Instant.now());
    items.put(id, next);
    log.info("[Todo] PAYLOAD-REPLACE id={}", id);
}
```

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

Day 5 我们走**最简实现**：手写 `FileSession`，把 `TodoManager.getState()` 和 Memory 的消息列表序列化到 `data/sessions/<id>.json`。AS-Java 自带的 `JsonSession` API 在 1.0.12 偶有签名变化，不如自写一份稳。

> 📌 如果你想用 AS-Java 自带的 `Session`，看 [../agents/06-memory-state-session.md](../agents/06-memory-state-session.md)，原理一致，本课不强制。

### 6.2 `session/FileSession.java`

```java
package space.wlshow.scope.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.todo.TodoManager;
import space.wlshow.scope.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class FileSession {

    private static final Logger log = LoggerFactory.getLogger(FileSession.class);
    private static final Path BASE = Path.of("data", "sessions");

    public final String id;
    public final TodoManager todos;
    public final Memory memory;

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
                List<Msg> msgs = Json.readList(root.path("memory").toString(), Msg.class);
                msgs.forEach(memory::add);
                log.info("[Session] LOAD {} todos={} memory={}", id, todos.size(), msgs.size());
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
            root.set("memory", Json.mapper().valueToTree(memory.getMessages()));
            Path f = BASE.resolve(id + ".json");
            Files.writeString(f, Json.writePretty(root));
            log.info("[Session] SAVED {} -> {}", id, f);
        } catch (IOException e) {
            throw new IllegalStateException("Session 保存失败", e);
        }
    }
}
```

> ⚠️ `memory.getMessages()` 的方法名在不同 AS-Java 版本可能是 `getHistory()` / `messages()`，按你 jar 里实际签名替换。

### 6.3 REPL 改造

```java
String sessionId = System.getenv().getOrDefault("SCOPE_SESSION", "default");
FileSession session = FileSession.loadOrNew(sessionId);
ReActAgent analyst = AgentFactory.buildAnalystWithTools(session.todos, session.memory);

// 每次 /run 或 /submit 之后自动 save
// 注册 shutdown hook 兜底
Runtime.getRuntime().addShutdownHook(new Thread(session::save));
```

### 6.4 加 `.gitignore`

```
data/sessions/
```

### 6.5 验证

```bash
mvn -q compile exec:java
> /run 做一个员工档案
> exit         （退出，shutdown hook 触发 save）

# 另一个终端
ls data/sessions/  # default.json 在

mvn -q compile exec:java
> /todos       （新加一个命令直接查）
=== Todos (3) ===   ← 之前的还在
```

> 📌 加一个 `/todos` 命令方便不调 LLM 直接看：

```java
} else if (input.equals("/todos")) {
    System.out.println("=== Todos (" + session.todos.size() + ") ===");
    session.todos.snapshot().forEach(it ->
        System.out.printf("  %s  %-15s  %-25s  %s%n",
            it.id(), it.type(), it.targetName(), it.status()));
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

```java
package space.wlshow.scope.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
// 1.0.12 的 ToolSuspendException 全限定名以你 jar 里实际为准
import io.agentscope.core.exception.ToolSuspendException;
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
                        "调用时必须先以 confirmed=false 调一次，让系统等用户确认；" +
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

        // confirmed=true：真发（Day 5 dry-run，只是把状态 RUNNING→SUCCESS 跑一遍）
        int n = 0;
        for (TodoItem it : pending) {
            todos.markRunning(it.id());
            // TODO Day 6：换成 bridge.dispatch(it)
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
} else if (input.equals("/submit")) {
    runSubmit(analyst, session);
}
```

`runSubmit` 的实现（简化版，把 ToolSuspend 处理封装一处）：

```java
private static void runSubmit(ReActAgent agent, FileSession session) {
    // 让 LLM 自己决定调 submit_to_frontend(false)
    Msg out;
    try {
        out = agent.call(Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text("我确认了，请提交所有待办").build())
                .build()).block();
    } catch (Exception e) {
        // 不同版本 AS-Java 暴露 suspend 的方式不同，可能是异常也可能是 GenerateReason
        if (!isSuspend(e)) throw e;
        handleSuspend(agent, session, suspendMessage(e));
        return;
    }

    if (out != null && isSuspendOutput(out)) {
        String reason = suspendReason(out);
        handleSuspend(agent, session, reason);
    } else {
        System.out.println("[ASSISTANT] " + (out == null ? "" : out.getTextContent()));
    }
}

private static void handleSuspend(ReActAgent agent, FileSession session, String reason) {
    System.out.println("[CONFIRM?]");
    System.out.println(reason);
    System.out.print("确认下发？(y/N): ");
    String ans = new java.util.Scanner(System.in).nextLine().trim().toLowerCase();
    String reply = "y".equals(ans) || "yes".equals(ans) ? "USER_CONFIRMED" : "USER_REJECTED";

    // 拿到挂起的 ToolUseBlock id（不同版本 API 略不同）
    String toolUseId = lastToolUseId(agent);   // 你的工具方法

    Msg toolResultMsg = Msg.builder()
            .role(MsgRole.TOOL)
            .content(ToolResultBlock.of(toolUseId, "submit_to_frontend",
                    TextBlock.builder().text(reply).build()))
            .build();

    Msg out = agent.call(toolResultMsg).block();
    System.out.println("[ASSISTANT] " + (out == null ? "" : out.getTextContent()));
    System.out.println("=== Todos (" + session.todos.size() + ") ===");
    session.todos.snapshot().forEach(it -> System.out.printf("  %s  %-25s  %s%n",
            it.id(), it.targetName(), it.status()));
}
```

> 📌 `lastToolUseId(agent)` / `isSuspend(e)` / `suspendReason(out)` 这些工具方法**根据你 AS-Java 1.0.12 的实际 API** 写。本课程不锁死签名，主要让你看清流程。如果你 jar 里能直接读到 `GenerateReason.TOOL_SUSPENDED` + `ToolUseBlock.id`，直接走那条路。

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

新建 `src/test/java/space/wlshow/scope/agent/Day5ScenarioTest.java`，用 WireMock 录一组响应（**3 段 LLM 输出**）：

1. 第一轮 "做库存管理"：返回多个 tool_calls（create_app + 2 create_module + 2 create_model）
2. 第二轮 "加出库审批"：返回 list_todos + 1 create_module + 1 create_model
3. 提交：返回 submit_to_frontend(false)
4. 收到 USER_CONFIRMED 续跑：返回 submit_to_frontend(true)

> 📌 录的 mock 是 LLM 输出，**TodoManager 状态由我们自己的工具代码控制**，断言验的是状态机和 TodoManager 数量。

```java
@Test
void fullScenario_addThenAppendThenConfirm() {
    // 加载 wiremock mappings/ 下的 4 个 stub
    server.loadMappingsUsing(...);

    // 第一轮
    parser.run("做一个库存管理系统");
    assertEquals(5, session.todos.size());

    // 第二轮
    parser.run("再加个出库审批");
    assertEquals(7, session.todos.size());
    assertTrue(session.todos.snapshot().stream()
            .allMatch(it -> it.status() == TodoStatus.PENDING));

    // 提交
    parser.submit("y");
    assertTrue(session.todos.snapshot().stream()
            .allMatch(it -> it.status() == TodoStatus.SUCCESS));
}

@Test
void rejectConfirm_keepsPending() {
    server.loadMappingsUsing(...);
    parser.run("做一个员工档案");
    parser.submit("n");
    assertTrue(session.todos.snapshot().stream()
            .allMatch(it -> it.status() == TodoStatus.PENDING));
}
```

> 📌 把 `parser.run` / `parser.submit` 封装成测试用的 facade，避免每个测试都重复 Msg 构造。

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

```bash
git add src/main/java/space/wlshow/scope/session/ \
        src/main/java/space/wlshow/scope/tool/ \
        src/main/java/space/wlshow/scope/todo/TodoManager.java \
        src/main/java/space/wlshow/scope/agent/AgentFactory.java \
        src/main/java/space/wlshow/scope/ScopeApp.java \
        src/main/resources/prompts/analyst-multi-round.md \
        src/test/java/space/wlshow/scope/agent/Day5ScenarioTest.java \
        .gitignore

git commit -m "day5: 多轮对话 + Session + CLI HITL

- InMemoryMemory 接到 buildAnalystWithTools，多轮上下文连贯
- list_todos / update_module / update_model 三个增量工具
- FileSession 把 TodoManager + Memory 落 data/sessions/<id>.json
- SubmitTool 用 ToolSuspendException 实现 CLI HITL，y/n 回填 ToolResult
- 端到端剧本测试：增量追加 → y 确认 / n 拒绝
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
| `submit_to_frontend` 没挂起，直接返回 | ToolSuspendException 全限定名错；查 jar 里实际类名（`grep -r "SuspendException" agentscope-1.0.12*`）|
| 回填 ToolResult 报"unknown toolUseId" | `lastToolUseId` 拿错；通常拿最后一个 `ToolUseBlock`，但 parallel(true) 时可能有多个，按工具名筛 |
| 重启后 todo-N 序号又从 1 开始 | `TodoManager.loadState` 没读 `seq`；本课的实现已读，检查 `getState/loadState` 是否对称 |
| `data/sessions/` 进了 git | `.gitignore` 没生效；用 `git rm --cached -r data/sessions/` 取消跟踪 |
| Memory 越积越长导致 token 超 | Day 7 升级到 Harness 的 Compaction；Day 5 暂时用 `if (memory.size() > 30) memory.clearOldest()` |

---

## 11. 附录 A · 为什么不直接用 AS-Java 的 `JsonSession`？

AS-Java 1.0.x 系列里 `JsonSession` 的字段命名和 `StateModule` 接口在 patch 版本之间有微调，1.0.12 与 1.1.0-RC1 又有差异。Day 5 我们走自写的 `FileSession`，原因：

1. 我们只需要"加载 + 保存"两个动作，自写 30 行
2. AS-Java 自己的 Session 把 Memory 和 StateModule 都包了，但我们的 `TodoManager` 还没实现 `StateModule`，硬接需要先适配
3. Day 7 升级 Harness 时反正要重写一遍，没必要今天叠两层

如果你想体验官方 Session，看 [../agents/06-memory-state-session.md](../agents/06-memory-state-session.md)，按 jar 里实际签名替换即可。

---

## 12. 附录 B · `ToolSuspend` 替代方案：Hook 拦截

如果你的 AS-Java 版本 ToolSuspend 不好使，可以用 `PreActingHook` 拦截 `submit_to_frontend` 工具调用：

```java
public class SubmitConfirmHook implements PreActingHook {
    @Override
    public Mono<Void> onBefore(ToolCallEvent event) {
        if ("submit_to_frontend".equals(event.toolName())) {
            // 弹 CLI 询问，拒绝时通过 event.cancel(reason) 阻止真正调用
        }
        return Mono.empty();
    }
}
```

Hook 路径的好处是**工具实现保持纯净**，缺点是从 LLM 视角看不到"挂起"事件，难以引导它"等用户确认完再说"。Day 5 优先走 ToolSuspend，Hook 留作 Day 7 备选。

---

## 13. 写在 Day 6 之前

明天 Day 6 我们会：

- 把 CLI 入口替换成 **Spring Boot WebFlux** + `agentscope-agui-spring-boot-starter`
- 启动后自动暴露 `POST /agui/run`（SSE 端点）
- 摸熟 **17 个 AG-UI 事件**：Lifecycle / TextMessage / ToolCall / State / Special
- 起一个 **Vue3** 前端（Vite + `@ag-ui/client`），输入框敲入需求看到流式打字机回复

Day 5 的 `ToolSuspend HITL` 在 Day 7 会被**重写**成 AG-UI 的 `TOOL_CALL_*` 事件 + 前端确认弹窗。今天的 CLI 实现是这套机制的"低保真原型"。
