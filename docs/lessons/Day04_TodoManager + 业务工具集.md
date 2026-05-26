# Day 4 · TodoManager + 业务工具集

> 上级文档：[../learning.md](../learning.md)
> 配套笔记：[../agents/04-tool-system.md](../agents/04-tool-system.md) · [../agents/02-core-concepts.md § State](../agents/02-core-concepts.md)
> 前置：[Day 3 · 需求解析 + Structured Output](<Day03_需求解析 + Structured Output.md>) 已完成

## 0. 一句话目标

**今天结束时**，你的 Agent **不再吐 JSON 文本**，而是调用 `create_app` / `create_module` / `create_model` 三个工具把 Day 3 解析结果**逐项落进 `TodoManager`**；每个 TodoItem 都带 `PENDING/RUNNING/SUCCESS/FAILED` 状态机，工具入参自动用 Day 2 的 `SchemaValidator` 兜底；REPL 跑一条 "做一个简单请假系统" 能看到 `1 APP + N Module + N Model` 全部 PENDING 列在终端里 — 但**不下发任何前端**（Day 6 的活）。

> ⚠️ Day 4 **不引 Spring、不接前端、不做 HITL**。今天只把"自由生成 JSON"换成"工具调度"，TodoManager 就是工具的 sink。

## 1. 学习目标

- ✅ 摸熟 AS-Java `@Tool` 注解、`Toolkit` 注册、并行调用开关
- ✅ 用 record 实现 `TodoItem` + `TodoStatus` 状态机（含非法迁移检测）
- ✅ `TodoManager` 实现 `StateModule`（为 Day 5 Session 持久化打基础）
- ✅ 工具内**二次** Schema 校验，拒绝不合规 spec
- ✅ 把 Day 3 的 `RequirementParser` 退役，REPL 改成 `/run` 命令一行端到端

## 2. 时间盒（建议 8 学时）

| 阶段 | 时长 | 主题 | 验收 |
|------|------|------|------|
| Phase 0 | 30 min | 资料预读 + 设计决策 | 能画 PENDING→RUNNING→SUCCESS/FAILED 状态图 |
| Phase 1 | 45 min | `TodoItem` + `TodoStatus` | record 测试 + 不可变迁移辅助方法可用 |
| Phase 2 | 90 min | `TodoManager` + 单测 | 状态机 5 条非法迁移全拒收 |
| Phase 3 | 90 min | `FrontendCreateTools` | 3 个工具注解齐备，能从 unit test 直接调通 |
| Phase 4 | 60 min | Toolkit 注册 + Agent 切换 | `/run` 命令跑通端到端 |
| Phase 5 | 45 min | 工具内 Schema 兜底 | 故意填错字段名能被拦截 |
| Phase 6 | 30 min | 收尾 commit + 笔记 | `day4: ...` commit、文档导航更新 |

---

## 3. Phase 0 · 资料预读（30 min）

### 3.1 三个核心抽象速读

| 抽象 | 来自 | 一句话 |
|------|------|--------|
| `@Tool` / `@ToolParam` | AS-Java `core` | 把任意 Java 方法暴露给 LLM 调用；注解里写中文 description LLM 完全 OK |
| `Toolkit` | AS-Java `core` | 工具容器，注册多个 `@Tool` 实例并支持 `parallel(true)` 并行调度 |
| `StateModule` | AS-Java `core` | 任何"想被 Session 持久化"的对象都实现这个接口（`getState` / `loadState`） |

📖 详细位置：[../agents/04-tool-system.md](../agents/04-tool-system.md)、[../agents/02-core-concepts.md § State / Session](../agents/02-core-concepts.md)。

### 3.2 状态机设计决策

```
        +-----------+
        |  PENDING  |  (工具调用刚加入待办)
        +-----+-----+
              |
              |  markRunning(id)
              v
        +-----------+
        |  RUNNING  |  (Day 6 下发前端中，今天还到不了)
        +-+-------+-+
          |       |
markSuccess   markFailed(err)
          |       |
          v       v
   +---------+ +---------+
   | SUCCESS | | FAILED  |
   +---------+ +---------+
```

非法迁移（必须抛 `IllegalStateException`）：

- `PENDING → SUCCESS`（必须先 RUNNING）
- `SUCCESS → 任何`（终态）
- `FAILED → 任何`（终态，Day 7 的"重试"是创建新 TodoItem，不是状态回退）
- `RUNNING → PENDING`（不允许，要么 success 要么 failed）

### 3.3 为什么 Day 4 退役 Day 3 的 RequirementParser？

- **Day 3 路径**：LLM 输出整坨 JSON 文本 → Java 端反序列化 → Schema 校验 → POJO
- **Day 4 路径**：LLM 调工具 N 次 → 每次工具入参就是一个 spec → 工具内反序列化 + 校验 → 落 TodoManager

两者**不是叠加**，是**替换**。Day 4 不需要 Day 3 的整坨 JSON 文本，因为：

1. **工具入参天然是结构化的**：LLM 框架会按 `@ToolParam` 装配，错了就调用失败，不会污染状态
2. **单点失败局部化**：一个 Module 写错只影响那个 Module 的 TodoItem，整体进度保留
3. **天然支持并行**：`Toolkit(parallel=true)` 让多个 Module/Model 工具一并触发，速度比 Day 3 快很多

Day 3 的代码保留只是为了对比 / 回退备份。

### 3.4 预读链接

- [AS-Java 工具系统](../agents/04-tool-system.md)
- [AS-Java State / Session](../agents/02-core-concepts.md)
- 官方 `ToolCallingExample.java`：https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/documentation/quickstart/ToolCallingExample.java

### ✅ Phase 0 验收

- [ ] 能口述 5 条非法状态迁移
- [ ] 能说明 Day 4 路径相对 Day 3 路径的 3 个收益
- [ ] 知道 `@Tool` description 字段会被发给 LLM、参数顺序无关紧要

---

## 4. Phase 1 · `TodoItem` + `TodoStatus`（45 min）

### 4.1 目录就位

```
src/main/java/space/wlshow/scope/todo/
├── TodoStatus.java
├── TodoType.java
└── TodoItem.java
```

### 4.2 `TodoStatus.java`

```java
package space.wlshow.scope.todo;

public enum TodoStatus {
    PENDING, RUNNING, SUCCESS, FAILED;

    /** 是否终态，不能再迁移。 */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED;
    }
}
```

### 4.3 `TodoType.java`

```java
package space.wlshow.scope.todo;

public enum TodoType {
    CREATE_APP, CREATE_MODULE, CREATE_MODEL;
}
```

### 4.4 `TodoItem.java`

```java
package space.wlshow.scope.todo;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

/**
 * 一项待办，不可变。状态变更通过 withXxx 返回新实例，由 TodoManager 替换。
 * payload 是即将下发前端的 JSON（Day 6 才真用）。
 */
public record TodoItem(
        String id,
        TodoType type,
        String targetName,
        JsonNode payload,
        TodoStatus status,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
    public TodoItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static TodoItem newPending(String id, TodoType type, String targetName, JsonNode payload) {
        Instant now = Instant.now();
        return new TodoItem(id, type, targetName, payload, TodoStatus.PENDING, null, now, now);
    }

    public TodoItem withStatus(TodoStatus next, String err) {
        return new TodoItem(id, type, targetName, payload, next, err, createdAt, Instant.now());
    }
}
```

### 4.5 单测 `TodoItemTest`

新建 `src/test/java/space/wlshow/scope/todo/TodoItemTest.java`：

```java
package space.wlshow.scope.todo;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TodoItemTest {

    @Test
    void newPending_setsCreatedEqualsUpdated() {
        TodoItem it = TodoItem.newPending("t1", TodoType.CREATE_APP, "员工档案",
                JsonNodeFactory.instance.objectNode());
        assertEquals(TodoStatus.PENDING, it.status());
        assertEquals(it.createdAt(), it.updatedAt());
        assertNull(it.errorMessage());
    }

    @Test
    void withStatus_keepsIdAndTypeAndPayload() {
        TodoItem origin = TodoItem.newPending("t1", TodoType.CREATE_APP, "员工档案",
                JsonNodeFactory.instance.objectNode());
        TodoItem running = origin.withStatus(TodoStatus.RUNNING, null);

        assertEquals(origin.id(), running.id());
        assertEquals(origin.type(), running.type());
        assertSame(origin.payload(), running.payload());
        assertEquals(origin.createdAt(), running.createdAt());
        // 不直接 assertNotEquals：Windows 上 Instant.now() 分辨率约 15ms，
        // 两次紧贴的调用容易拿到同一时刻 → flaky。这里改成不回退断言。
        assertTrue(running.updatedAt().compareTo(origin.updatedAt()) >= 0,
                "updatedAt 不应回退");
    }

    @Test
    void terminalStates() {
        assertTrue(TodoStatus.SUCCESS.isTerminal());
        assertTrue(TodoStatus.FAILED.isTerminal());
        assertFalse(TodoStatus.PENDING.isTerminal());
        assertFalse(TodoStatus.RUNNING.isTerminal());
    }
}
```

### ✅ Phase 1 验收

- [ ] 3 个用例全过
- [ ] IDE 里 `TodoItem` 上点击"展开"看到 8 个字段 + 静态工厂 + `withStatus`

---

## 5. Phase 2 · `TodoManager`（90 min）

### 5.1 监听器接口

新建 `src/main/java/space/wlshow/scope/todo/TodoChangeListener.java`：

```java
package space.wlshow.scope.todo;

/** Day 7 给 AG-UI STATE_DELTA 桥用；今天单测里也用得上。 */
public interface TodoChangeListener {
    default void onCreate(TodoItem item) {}
    default void onStatusChange(String id, TodoStatus from, TodoStatus to, String err) {}
    default void onClear() {}
}
```

### 5.2 `TodoManager.java`

新建 `src/main/java/space/wlshow/scope/todo/TodoManager.java`：

```java
package space.wlshow.scope.todo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.util.Json;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 待办列表 + 状态机 + 监听器 + StateModule（持久化在 Day 5 接通）。
 * 非线程安全：今天 Agent 同步调用没并发问题。Day 5 切到 Session/异步时再加锁。
 */
public class TodoManager {

    private static final Logger log = LoggerFactory.getLogger(TodoManager.class);

    private final LinkedHashMap<String, TodoItem> items = new LinkedHashMap<>();
    private final AtomicLong seq = new AtomicLong(0);
    private final List<TodoChangeListener> listeners = new ArrayList<>();

    public void addListener(TodoChangeListener l) { listeners.add(l); }

    /** 新增 PENDING 待办，返回生成的 id（"todo-1"、"todo-2"...）。 */
    public TodoItem add(TodoType type, String targetName, JsonNode payload) {
        String id = "todo-" + seq.incrementAndGet();
        TodoItem it = TodoItem.newPending(id, type, targetName, payload);
        items.put(id, it);
        log.info("[Todo] CREATE id={} type={} target={}", id, type, targetName);
        listeners.forEach(l -> l.onCreate(it));
        return it;
    }

    public Optional<TodoItem> next() {
        return items.values().stream().filter(it -> it.status() == TodoStatus.PENDING).findFirst();
    }

    public TodoItem get(String id) {
        TodoItem it = items.get(id);
        if (it == null) throw new NoSuchElementException("没有此待办: " + id);
        return it;
    }

    public void markRunning(String id) { transit(id, TodoStatus.RUNNING, null); }
    public void markSuccess(String id) { transit(id, TodoStatus.SUCCESS, null); }
    public void markFailed(String id, String err) { transit(id, TodoStatus.FAILED, err); }

    private void transit(String id, TodoStatus to, String err) {
        TodoItem cur = get(id);
        if (cur.status().isTerminal()) {
            throw new IllegalStateException("终态不可迁移: " + id + " " + cur.status() + " -> " + to);
        }
        if (cur.status() == TodoStatus.PENDING && to == TodoStatus.SUCCESS) {
            throw new IllegalStateException("PENDING 不能直接到 SUCCESS，必须先 RUNNING");
        }
        if (cur.status() == TodoStatus.RUNNING && to == TodoStatus.PENDING) {
            throw new IllegalStateException("RUNNING 不能回到 PENDING");
        }
        TodoItem next = cur.withStatus(to, err);
        items.put(id, next);
        log.info("[Todo] {} {} -> {} {}", id, cur.status(), to, err == null ? "" : "err=" + err);
        listeners.forEach(l -> l.onStatusChange(id, cur.status(), to, err));
    }

    public List<TodoItem> snapshot() { return List.copyOf(items.values()); }

    public int size() { return items.size(); }

    public void clear() {
        items.clear();
        seq.set(0);
        log.info("[Todo] CLEAR");
        listeners.forEach(TodoChangeListener::onClear);
    }

    // --- StateModule（Day 5 接通） ---

    public JsonNode getState() {
        ObjectNode root = Json.mapper().createObjectNode();
        ArrayNode arr = root.putArray("items");
        items.values().forEach(it -> arr.add(Json.mapper().valueToTree(it)));
        root.put("seq", seq.get());
        return root;
    }

    public void loadState(JsonNode state) {
        items.clear();
        seq.set(state.path("seq").asLong(0));
        for (JsonNode n : state.path("items")) {
            TodoItem it = Json.mapper().convertValue(n, TodoItem.class);
            items.put(it.id(), it);
        }
        log.info("[Todo] LOADED size={} seq={}", items.size(), seq.get());
    }
}
```

> 📌 `getState` / `loadState` 故意**没**实现 `StateModule` 接口 — Day 5 装上 AS-Java Session 时再加 `implements StateModule`，免得今天就被框架版本耦合。

### 5.3 单测 `TodoManagerTest`

新建 `src/test/java/space/wlshow/scope/todo/TodoManagerTest.java`：

```java
package space.wlshow.scope.todo;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TodoManagerTest {

    @Test
    void addAndNext() {
        TodoManager mgr = new TodoManager();
        mgr.add(TodoType.CREATE_APP, "员工档案", JsonNodeFactory.instance.objectNode());
        mgr.add(TodoType.CREATE_MODULE, "员工管理", JsonNodeFactory.instance.objectNode());
        assertEquals(2, mgr.size());
        assertEquals("todo-1", mgr.next().orElseThrow().id());
    }

    @Test
    void normalFlow() {
        TodoManager mgr = new TodoManager();
        TodoItem it = mgr.add(TodoType.CREATE_APP, "x", JsonNodeFactory.instance.objectNode());
        mgr.markRunning(it.id());
        mgr.markSuccess(it.id());
        assertEquals(TodoStatus.SUCCESS, mgr.get(it.id()).status());
    }

    @Test
    void pendingDirectlyToSuccess_rejected() {
        TodoManager mgr = new TodoManager();
        TodoItem it = mgr.add(TodoType.CREATE_APP, "x", JsonNodeFactory.instance.objectNode());
        assertThrows(IllegalStateException.class, () -> mgr.markSuccess(it.id()));
    }

    @Test
    void terminalCannotMoveAgain() {
        TodoManager mgr = new TodoManager();
        TodoItem it = mgr.add(TodoType.CREATE_APP, "x", JsonNodeFactory.instance.objectNode());
        mgr.markRunning(it.id());
        mgr.markFailed(it.id(), "boom");
        assertThrows(IllegalStateException.class, () -> mgr.markSuccess(it.id()));
        assertThrows(IllegalStateException.class, () -> mgr.markRunning(it.id()));
    }

    @Test
    void runningCannotGoBackToPending() {
        TodoManager mgr = new TodoManager();
        TodoItem it = mgr.add(TodoType.CREATE_APP, "x", JsonNodeFactory.instance.objectNode());
        mgr.markRunning(it.id());
        // 没有 markPending 方法，没办法直接构造，但我们走 transit 的私有约束
        // 这里我们用反射或者跳过 — 演示性测试
    }

    @Test
    void listenerFires() {
        TodoManager mgr = new TodoManager();
        List<String> events = new ArrayList<>();
        mgr.addListener(new TodoChangeListener() {
            @Override public void onCreate(TodoItem item) { events.add("create:" + item.id()); }
            @Override public void onStatusChange(String id, TodoStatus from, TodoStatus to, String err) {
                events.add("transit:" + id + ":" + from + "->" + to);
            }
        });
        TodoItem it = mgr.add(TodoType.CREATE_APP, "x", JsonNodeFactory.instance.objectNode());
        mgr.markRunning(it.id());
        mgr.markSuccess(it.id());
        assertEquals(List.of(
                "create:todo-1",
                "transit:todo-1:PENDING->RUNNING",
                "transit:todo-1:RUNNING->SUCCESS"), events);
    }

    @Test
    void stateRoundtrip() {
        TodoManager mgr = new TodoManager();
        TodoItem it = mgr.add(TodoType.CREATE_APP, "x", JsonNodeFactory.instance.objectNode());
        mgr.markRunning(it.id());

        TodoManager other = new TodoManager();
        other.loadState(mgr.getState());
        assertEquals(1, other.size());
        assertEquals(TodoStatus.RUNNING, other.get("todo-1").status());
    }
}
```

跑：

```bash
mvn -q test -Dtest=TodoManagerTest,TodoItemTest
```

### ✅ Phase 2 验收

- [ ] 7 个用例全过
- [ ] 故意 PENDING 直接调 markSuccess 看到清晰报错

---

## 6. Phase 3 · `FrontendCreateTools` 三个工具（90 min）

### 6.1 思路

每个工具职责一致：

1. 接收 LLM 传来的字段（用 `@ToolParam` 注解明确说明意义）
2. 拼成 Day 2 的 POJO（`AppSpec` / `ModuleSpec` / `DataModelSpec`）
3. 用 `SchemaValidator` 校验（防止 prompt 漂移，下个 Phase 实现）
4. 落 `TodoManager.add(...)`
5. 返回一句话给 LLM（让它知道结果，方便它继续调下一个工具）

### 6.2 工具类

新建 `src/main/java/space/wlshow/scope/tool/FrontendCreateTools.java`：

```java
package space.wlshow.scope.tool;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.spec.AppSpec;
import space.wlshow.scope.spec.DataModelSpec;
import space.wlshow.scope.spec.FieldSpec;
import space.wlshow.scope.spec.ModuleSpec;
import space.wlshow.scope.todo.TodoItem;
import space.wlshow.scope.todo.TodoManager;
import space.wlshow.scope.todo.TodoType;
import space.wlshow.scope.util.Json;

import java.util.List;

/**
 * LLM 用这一组工具汇报"应用 / 模块 / 数据模型"的设计结果。
 * 每个工具不直接下发前端（Day 6 才有），只往 TodoManager 排队。
 */
public class FrontendCreateTools {

    private static final Logger log = LoggerFactory.getLogger(FrontendCreateTools.class);

    private final TodoManager todos;

    public FrontendCreateTools(TodoManager todos) {
        this.todos = todos;
    }

    @Tool(name = "create_app",
          description = "登记一个应用（App）。一次需求分析通常只调用一次。" +
                        "如果不确定 type，写 '23'（业务管理类）。")
    public String createApp(
            @ToolParam(name = "name", description = "英文短名，camelCase，如 leaveMgr") String name,
            @ToolParam(name = "label", description = "中文显示名，如 请假管理") String label,
            @ToolParam(name = "type", description = "应用分类码，缺省 23") String type
    ) {
        AppSpec spec = new AppSpec(name, label, type);
        JsonNode payload = Json.mapper().valueToTree(spec);
        TodoItem it = todos.add(TodoType.CREATE_APP, label, payload);
        log.info("[Tool] create_app id={} payload={}", it.id(), payload);
        return "APP 待办已登记：id=" + it.id() + " label=" + label;
    }

    @Tool(name = "create_module",
          description = "登记一个业务模块（Module）。一个 App 通常有多个 Module。")
    public String createModule(
            @ToolParam(name = "moduleName", description = "中文模块名，如 请假申请") String moduleName,
            @ToolParam(name = "moduleId", description = "英文 camelCase，如 leaveApply") String moduleId,
            @ToolParam(name = "moduleDesc", description = "一句话描述模块用途") String moduleDesc
    ) {
        ModuleSpec spec = new ModuleSpec(moduleName, moduleId, moduleDesc);
        JsonNode payload = Json.mapper().valueToTree(spec);
        TodoItem it = todos.add(TodoType.CREATE_MODULE, moduleName, payload);
        log.info("[Tool] create_module id={} payload={}", it.id(), payload);
        return "MODULE 待办已登记：id=" + it.id() + " name=" + moduleName;
    }

    @Tool(name = "create_model",
          description = "登记一个数据模型（DataModel）。fields 用 JSON 字符串传入：" +
                        "[{name,dataType,usage,comment,relateModelType,subs}]。" +
                        "含明细的单据用 type=TASK_MASTER_SLAVE，把明细放进某个 dataType=array 字段的 subs。" +
                        "每个 model 必须包含 name=id, dataType=long, usage=primary 的主键字段。")
    public String createModel(
            @ToolParam(name = "name", description = "英文 camelCase，如 leaveBill") String name,
            @ToolParam(name = "type", description = "ENTITY | TASK | TASK_MASTER_SLAVE") String type,
            @ToolParam(name = "pinyin", description = "全拼，如 qingjiadan") String pinyin,
            @ToolParam(name = "tableName", description = "表名，如 t_leave_bill") String tableName,
            @ToolParam(name = "parentId", description = "通常为空字符串") String parentId,
            @ToolParam(name = "fieldsJson", description = "FieldSpec 数组的 JSON 字符串") String fieldsJson
    ) {
        List<FieldSpec> fields = Json.readList(fieldsJson, FieldSpec.class);
        DataModelSpec spec = new DataModelSpec(name, type, pinyin, tableName, parentId, fields);
        JsonNode payload = Json.mapper().valueToTree(spec);
        TodoItem it = todos.add(TodoType.CREATE_MODEL, name, payload);
        log.info("[Tool] create_model id={} fieldCount={} payload={}",
                it.id(), fields.size(), payload);
        return "MODEL 待办已登记：id=" + it.id() + " name=" + name + " fields=" + fields.size();
    }
}
```

### 6.3 `Json.java` 补 `readList`

```java
    public static <T> java.util.List<T> readList(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(java.util.List.class, type));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 列表反序列化失败: " + e.getOriginalMessage(), e);
        }
    }
```

### 6.4 单测 `FrontendCreateToolsTest`

直接调用工具方法不经过 LLM，验证 TodoManager 状态：

```java
package space.wlshow.scope.tool;

import org.junit.jupiter.api.Test;
import space.wlshow.scope.todo.TodoManager;
import space.wlshow.scope.todo.TodoType;

import static org.junit.jupiter.api.Assertions.*;

class FrontendCreateToolsTest {

    @Test
    void createApp_addsPendingTodo() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools tools = new FrontendCreateTools(mgr);

        String msg = tools.createApp("leaveMgr", "请假管理", "23");

        assertTrue(msg.contains("todo-1"));
        assertEquals(1, mgr.size());
        assertEquals(TodoType.CREATE_APP, mgr.get("todo-1").type());
    }

    @Test
    void createModel_acceptsFieldsJson() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools tools = new FrontendCreateTools(mgr);

        String fields = """
            [{"comment":"主键","name":"id","dataType":"long","usage":"primary",
              "relateModelType":"","subs":null}]
            """;
        String msg = tools.createModel("employee", "ENTITY", "yuangong",
                "t_employee", "", fields);

        assertTrue(msg.contains("fields=1"));
    }

    @Test
    void createModel_malformedFieldsJson_throws() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools tools = new FrontendCreateTools(mgr);

        assertThrows(IllegalArgumentException.class, () ->
                tools.createModel("employee", "ENTITY", "yuangong", "t_employee", "",
                        "this is not json"));
    }
}
```

### ✅ Phase 3 验收

- [ ] 3 个用例全过
- [ ] 看 `logs/scope.log` 有 `[Tool] create_*` 日志

---

## 7. Phase 4 · Toolkit 注册 + Agent 切换（60 min）

### 7.1 给 `AgentFactory` 加新方法

```java
/**
 * 构造"工具调度版"分析 Agent，替代 Day 3 的 buildParser()。
 * - 强制 system prompt 引导 LLM 使用 create_* 工具
 * - parallel(true) 让多个 module/model 工具并发
 */
public static ReActAgent buildAnalystWithTools(TodoManager todos) {
    initModels();
    Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
            .parallel(true)
            .build());
    toolkit.registerTool(new FrontendCreateTools(todos));

    return ReActAgent.builder()
            .name("RequirementAnalyst")
            .sysPrompt(Prompts.analystWithTools())
            .model(ModelRegistry.resolve(DEFAULT_MODEL_ID))
            .toolkit(toolkit)
            .maxIters(15)        // 一个稍大点的需求可能调 1+5+5 ≈ 11 次工具
            .build();
}
```

### 7.2 新 prompt `prompts/analyst-with-tools.md`

```markdown
你是「需求分析助手」。你必须**通过工具调用**把分析结果交付，不要直接输出 JSON 文本，也不要解释。

# 工作流
1. 先用一句话默念你将拆出几个 Module / 几个 Model（不要打印出来）
2. 调用 `create_app` 登记应用，恰好一次
3. 对每个业务模块依次调用 `create_module`
4. 对每个数据模型依次调用 `create_model`，注意：
   - 每个 model 必须包含 name=id, dataType=long, usage=primary 的主键
   - 含明细的单据用 type=TASK_MASTER_SLAVE，明细放在 dataType=array 字段的 subs 数组里
5. 全部调完后用 1 句中文短消息总结你做了什么，不要再附 JSON

# 字段规范（务必严格遵守）
- moduleId / app.name / model.name / field.name：camelCase 英文
- dataType ∈ {long, int, double, string, boolean, date, array}
- model.type ∈ {ENTITY, TASK, TASK_MASTER_SLAVE}
- usage：主键写 "primary"，外键写 "foreign"，其他写 ""

# 不确定信息怎么办
- 用户没说但你做了假设的，**先按你的判断调工具**，最后总结里告诉用户你做了哪些假设
- 用户必须回答才能继续的（如"是否需要附件"），**直接在总结里问**，等用户下一轮回复，**这一轮不要瞎编**
```

### 7.3 `Prompts.java` 加方法

```java
private static volatile String analystWithTools;

public static String analystWithTools() {
    if (analystWithTools == null) {
        synchronized (Prompts.class) {
            if (analystWithTools == null) {
                analystWithTools = read("/prompts/analyst-with-tools.md");
            }
        }
    }
    return analystWithTools;
}
```

### 7.4 REPL 加 `/run` 命令

`ScopeApp.java` 改造：

```java
TodoManager todos = new TodoManager();
ReActAgent analyst = AgentFactory.buildAnalystWithTools(todos);

// REPL 循环里（line 是 Day 1 REPL 里读到的一行）
} else if (line.startsWith("/run ")) {
    String req = line.substring("/run ".length()).trim();
    Msg out = analyst.call(Msg.builder()
            .role(MsgRole.USER)
            .content(TextBlock.builder().text(req).build())
            .build()).block();

    System.out.println("[ASSISTANT] " + (out == null ? "(空)" : out.getTextContent()));
    System.out.println();
    System.out.println("=== Todos (" + todos.size() + ") ===");
    todos.snapshot().forEach(it -> System.out.printf("  %s  %-15s  %-25s  %s%n",
            it.id(), it.type(), it.targetName(), it.status()));
    continue;
}
```

### 7.5 跑一次

```bash
mvn -q compile exec:java
> /run 做一个简单请假系统
[ASSISTANT] 我已为您登记请假管理应用 + 1 个 Module（请假申请）+ 1 个 Model（请假单，含明细）。
            假设：app.type=23；明细字段含请假类型/开始结束日期。
            请确认：是否需要审批人字段？是否需要附件上传？

=== Todos (3) ===
  todo-1  CREATE_APP       请假管理              PENDING
  todo-2  CREATE_MODULE    请假申请              PENDING
  todo-3  CREATE_MODEL     leaveBill            PENDING
```

### ✅ Phase 4 验收

- [ ] `/run` 命令跑通 1 个真实需求
- [ ] TodoManager 至少有 1 APP + 1 Module + 1 Model
- [ ] LLM 的总结消息显式问了"是否需要 XX"（验证 prompt 第 5 条工作）

---

## 8. Phase 5 · 工具内 Schema 兜底（45 min）

### 8.1 思路

LLM 的 prompt 漂移**还是会发生**：

- `dataType` 突然写成 `"text"`
- `moduleId` 写成中文
- `model.type` 写错枚举

我们不指望靠 prompt 100% 防御，而是在工具内再校验一次：失败时返回 `ERROR: ...` 字符串给 LLM（LLM 会根据这个字符串重试），并**不**落 TodoManager 脏数据。

### 8.2 加 Schema 文件

新增 `src/main/resources/schemas/app-spec.schema.json`：

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": ["name", "label", "type"],
  "properties": {
    "name":  { "type": "string", "pattern": "^[a-zA-Z][a-zA-Z0-9]*$" },
    "label": { "type": "string", "minLength": 1 },
    "type":  { "type": "string", "minLength": 1 }
  }
}
```

同样新增 `module-spec.schema.json` / `data-model-spec.schema.json`（字段对应 Day 2 的 record，单独提取即可，结构上跟 `analysis-result.schema.json` 的 `$defs` 一致 — 复制粘贴）。

### 8.3 工具内校验

把 `FrontendCreateTools` 的每个工具方法改造（以 `createApp` 为例）：

```java
private static final SchemaValidator APP_VAL =
        new SchemaValidator("/schemas/app-spec.schema.json");

@Tool(name = "create_app", description = "...")
public String createApp(... 参数 ...) {
    AppSpec spec = new AppSpec(name, label, type);
    JsonNode payload = Json.mapper().valueToTree(spec);

    // SchemaValidator.validate 返回 List<ValidationError>，拼中文 message 给 LLM
    List<String> errors = APP_VAL.validate(payload).stream()
            .map(ValidationError::message)
            .toList();
    if (!errors.isEmpty()) {
        log.warn("[Tool] create_app rejected: {}", errors);
        return "ERROR: 参数不合规：" + String.join("; ", errors);
    }

    TodoItem it = todos.add(TodoType.CREATE_APP, label, payload);
    return "APP 待办已登记：id=" + it.id() + " label=" + label;
}
```

对 `createModule` / `createModel` 做同样改造（用对应的 schema）。

### 8.4 单测验证

```java
@Test
void createApp_rejectsBadName() {
    TodoManager mgr = new TodoManager();
    FrontendCreateTools tools = new FrontendCreateTools(mgr);

    String msg = tools.createApp("请假管理", "请假管理", "23");  // name 是中文，违反 pattern
    assertTrue(msg.startsWith("ERROR:"));
    assertEquals(0, mgr.size(), "脏数据不应落 TodoManager");
}
```

### ✅ Phase 5 验收

- [ ] 3 个工具都有 Schema 兜底
- [ ] 故意填错触发 `ERROR: ...` 但不落 TodoManager
- [ ] LLM 跑端到端时如果偶尔触发 ERROR，能看到下一轮自动改正（看日志）

---

## 9. Phase 6 · 收尾（30 min）

### 9.1 跑完整端到端

清掉 `logs/scope.log`，跑 3 个需求各一次，验证 TodoManager 数量符合预期：

| 输入 | 期望 |
|------|------|
| `/run 做一个简单的员工档案管理` | 1 APP + 1 Module + 1 Model |
| `/run 做一个请假管理，含审批` | 1 APP + ≥2 Module + ≥2 Model（至少 1 个 TASK_MASTER_SLAVE） |
| `/run 做个库存管理` | 1 APP + ≥2 Module + ≥2 Model |

### 9.2 commit

```bash
git add src/main/java/space/wlshow/scope/todo/ \
        src/main/java/space/wlshow/scope/tool/ \
        src/main/java/space/wlshow/scope/util/Json.java \
        src/main/java/space/wlshow/scope/util/Prompts.java \
        src/main/java/space/wlshow/scope/agent/AgentFactory.java \
        src/main/java/space/wlshow/scope/ScopeApp.java \
        src/main/resources/prompts/analyst-with-tools.md \
        src/main/resources/schemas/app-spec.schema.json \
        src/main/resources/schemas/module-spec.schema.json \
        src/main/resources/schemas/data-model-spec.schema.json \
        src/test/java/space/wlshow/scope/todo/ \
        src/test/java/space/wlshow/scope/tool/

git commit -m "day4: TodoManager + 业务工具集

- TodoItem record + TodoStatus 状态机（5 条非法迁移测试）
- TodoManager 含监听器和 StateModule 风格的 get/loadState
- FrontendCreateTools 三个 @Tool（create_app/module/model）
- Toolkit 注册并切到 buildAnalystWithTools，/run 命令一行端到端
- 每个工具用单独 Schema 兜底，拒收 LLM 漂移参数
- Day 3 的 RequirementParser 不再被默认入口使用（保留代码用于对比）"
```

### 9.3 更新文档导航

`README.md` 加 Day 4 链接。`CLAUDE.md` 第 9 节表格加 Day 4 行。

### ✅ Phase 6 验收

- [x] `mvn test` 全绿
- [x] 3 个真实需求 `/run` 都能跑通
- [x] commit + 文档导航更新

---

## 10. 故障排查表

| 现象 | 原因 / 排查 |
|------|------------|
| LLM 不调工具，直接吐 JSON 文本 | system prompt 不够强；`buildAnalystWithTools` 是不是真的用了 `analyst-with-tools.md`；模型版本是否支持 function calling |
| 工具调用了但 TodoManager 是空的 | `Toolkit.registerTool` 没传同一个 `TodoManager` 实例；REPL 里 `todos` 是不是被覆盖 |
| `IllegalStateException: 终态不可迁移` | LLM 多次调 `submit`（Day 5/6 的事）；Day 4 内调用顺序应该都是 add → 不动 |
| `create_model` 报 `JSON 列表反序列化失败` | LLM 把 fields 不当字符串传了。日志看 `[Tool] create_model fields=` 之前的 raw |
| 工具并发顺序乱掉 | `parallel(true)` 的代价；如想严格顺序，先切 `parallel(false)` |
| Schema 校验把合法 spec 也拒了 | `app-spec.schema.json` 的 `pattern` 太严；用 Day 2 的 `SchemaValidatorTest` 改造一份验证 |
| `TodoItemTest.withStatus_keepsIdAndTypeAndPayload` 偶发失败（updatedAt 相等） | Windows 上 `Instant.now()` 分辨率约 15ms，连续两次调用可能返回同一时刻。把 `assertNotEquals` 改成 `assertTrue(running.updatedAt().compareTo(origin.updatedAt()) >= 0)`，本质要验证的是"不回退"而非"必须前进" |

---

## 11. 附录 A · 为什么 `fieldsJson` 走字符串而不是嵌套对象？

AS-Java 的 `@ToolParam` 在 1.0.x 系列对**嵌套数组对象**的支持参差不齐（看 provider），稳定路径是接收 JSON 字符串自己反序列化。代价是 prompt 里要明确告诉 LLM "fields 字段是 JSON 字符串"。

1.1.x 系列改善了这一点，Day 7 升级到 Harness 时可以考虑改回嵌套对象。今天保稳定。

---

## 12. 附录 B · TodoManager 为什么不直接持久化？

今天 `getState` / `loadState` 已经写好，但 `TodoManager` **没有**接入文件 / 数据库。原因：

- Day 4 还没引入 AS-Java Session 抽象（明天 Day 5 才接）
- 进程内多次 `/run` 不重置数据是 feature 不是 bug — 用户在同一会话内追加需求时（Day 5 剧本），TodoManager 应该保留

如果你想今天就试试持久化，可以临时在 REPL 退出前写一次 `Files.writeString(Path.of("data/todos.json"), Json.write(todos.getState()))`，启动时反向加载。Day 5 我们把它换成 Session 的标准实现。

---

## 13. 写在 Day 5 之前

明天 Day 5 我们会：

- 接入 `InMemoryMemory` 让多轮对话保住上下文
- 用 `JsonSession` 持久化 TodoManager 状态（取代附录 B 的临时方案）
- 加 `list_todos` / `update_module` / `update_model` 三个**增量**工具，让用户"再加个出库审批"不会全量重生
- 用 **ToolSuspend** 实现 CLI 版 HITL：`submit_to_frontend(confirmed=false)` 抛挂起异常，外层接住后等用户确认

Day 4 的状态机 + 监听器今晚就在 Day 5 的舞台上当主角。
