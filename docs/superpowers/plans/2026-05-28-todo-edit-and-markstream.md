# Todo 编辑工具集 + Markdown 流式渲染 + HITL 内联卡片 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户能在 `submit_to_frontend` 真发之前用对话改完应用名 / 模块 / 字段，chat 用 markdown 流式渲染，HITL 确认按钮从弹窗搬进 chat 流。

**Architecture:** 后端通过新增 5 个 `@Tool`（`update_app` / `delete_module` / `delete_model` / `update_field` / `delete_field` / `cancel_submission`）扩展 LLM 可调用的 TodoManager 修改能力；TodoManager 新增 `remove` + `onRemove` / `onPayloadReplace` listener 钩子，AguiStateBridge 实现新钩子映射成 STATE_DELTA。前端把 assistant 消息体切到 `markstream-vue` 的 `<MarkdownRender>`；HitlConfirmModal 删掉，HITL 确认改成 `UiMsg.kind='hitl-card'` 的内联气泡，取消按钮触发 `role:'tool' content='USER_REJECTED'` 续跑，LLM 按 SubmitTool 描述指引调 `cancel_submission` 清空。

**Tech Stack:** Java 17 + AgentScope-Java 1.0.12 + Spring Boot WebFlux + Vue 3 + markstream-vue + `@ag-ui/client`

> **Spec reference:** [docs/superpowers/specs/2026-05-28-todo-edit-and-markstream-design.md](../specs/2026-05-28-todo-edit-and-markstream-design.md)
> **Requirements:** [docs/requirements/2026-05-28.md](../../requirements/2026-05-28.md)

---

## Task 1: TodoChangeListener 新增两个 default 钩子

**Files:**
- Modify: `src/main/java/space/wlshow/scope/todo/TodoChangeListener.java`

- [ ] **Step 1: 修改接口加两个 default 方法**

打开 `src/main/java/space/wlshow/scope/todo/TodoChangeListener.java`，替换全文为：

```java
package space.wlshow.scope.todo;

import com.fasterxml.jackson.databind.JsonNode;

/** Day 7 给 AG-UI STATE_DELTA 桥用；今天单测里也用得上。 */
public interface TodoChangeListener {
    default void onCreate(TodoItem item) {}
    default void onStatusChange(String id, TodoStatus from, TodoStatus to, String err) {}
    default void onClear() {}
    /** 待办从池里被移除（仅 PENDING 允许）。Day 2026-05-28 新增。 */
    default void onRemove(String id) {}
    /** 待办 payload 被原地替换（仅 PENDING 允许）。Day 2026-05-28 新增。 */
    default void onPayloadReplace(String id, JsonNode newPayload) {}
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn -q compile`
Expected: BUILD SUCCESS（接口只加 default 方法，不破坏现有实现）

- [ ] **Step 3: Commit**

```bash
git add src/main/java/space/wlshow/scope/todo/TodoChangeListener.java
git commit -m "feat(todo): TodoChangeListener 加 onRemove / onPayloadReplace 钩子"
```

---

## Task 2: TodoManager.remove() + 测试

**Files:**
- Modify: `src/main/java/space/wlshow/scope/todo/TodoManager.java`
- Test: `src/test/java/space/wlshow/scope/todo/TodoManagerTest.java`

- [ ] **Step 1: 写失败测试**

打开 `src/test/java/space/wlshow/scope/todo/TodoManagerTest.java`，在末尾 `}` 之前追加：

```java
    @Test
    void remove_pendingOk() {
        TodoManager mgr = new TodoManager();
        TodoItem it = mgr.add(TodoType.CREATE_APP, "x", JsonNodeFactory.instance.objectNode());
        assertTrue(mgr.remove(it.id()));
        assertEquals(0, mgr.size());
    }

    @Test
    void remove_unknownId_returnsFalse() {
        TodoManager mgr = new TodoManager();
        assertFalse(mgr.remove("todo-999"));
    }

    @Test
    void remove_runningRejected() {
        TodoManager mgr = new TodoManager();
        TodoItem it = mgr.add(TodoType.CREATE_APP, "x", JsonNodeFactory.instance.objectNode());
        mgr.markRunning(it.id());
        assertThrows(IllegalStateException.class, () -> mgr.remove(it.id()));
        assertEquals(1, mgr.size(), "running 项不应被移除");
    }

    @Test
    void remove_firesOnRemove() {
        TodoManager mgr = new TodoManager();
        List<String> events = new ArrayList<>();
        mgr.addListener(new TodoChangeListener() {
            @Override public void onRemove(String id) { events.add("remove:" + id); }
        });
        TodoItem it = mgr.add(TodoType.CREATE_APP, "x", JsonNodeFactory.instance.objectNode());
        mgr.remove(it.id());
        assertEquals(List.of("remove:todo-1"), events);
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q test -Dtest=TodoManagerTest`
Expected: COMPILATION FAILURE（`remove` 方法尚不存在）

- [ ] **Step 3: 在 TodoManager 加 remove 方法**

打开 `src/main/java/space/wlshow/scope/todo/TodoManager.java`。在 `public synchronized void replacePayload(...)` 方法**正下方**追加：

```java
    /** 从池里移除一个 PENDING 待办，触发 {@link TodoChangeListener#onRemove}。
     *  非 PENDING 抛 {@link IllegalStateException}；id 不存在返回 false。 */
    public synchronized boolean remove(String id) {
        TodoItem cur = items.get(id);
        if (cur == null) return false;
        if (cur.status() != TodoStatus.PENDING) {
            throw new IllegalStateException(
                    "非 PENDING 不可删: " + id + " status=" + cur.status());
        }
        items.remove(id);
        Stage.run(Stage.TODO_UPDATE, () -> log.info("[Todo] REMOVE id={}", id));
        listeners.forEach(l -> l.onRemove(id));
        return true;
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q test -Dtest=TodoManagerTest`
Expected: BUILD SUCCESS，11 tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/space/wlshow/scope/todo/TodoManager.java \
       src/test/java/space/wlshow/scope/todo/TodoManagerTest.java
git commit -m "feat(todo): TodoManager.remove() + onRemove 钩子"
```

---

## Task 3: TodoManager.replacePayload 末尾 fire onPayloadReplace + 测试

**Files:**
- Modify: `src/main/java/space/wlshow/scope/todo/TodoManager.java`
- Test: `src/test/java/space/wlshow/scope/todo/TodoManagerTest.java`

- [ ] **Step 1: 写失败测试**

在 `TodoManagerTest.java` 末尾 `}` 之前追加：

```java
    @Test
    void replacePayload_firesOnPayloadReplace() {
        TodoManager mgr = new TodoManager();
        List<String> events = new ArrayList<>();
        mgr.addListener(new TodoChangeListener() {
            @Override public void onPayloadReplace(String id, com.fasterxml.jackson.databind.JsonNode newPayload) {
                events.add("payload:" + id + ":" + newPayload.path("name").asText());
            }
        });
        TodoItem it = mgr.add(TodoType.CREATE_APP, "x", JsonNodeFactory.instance.objectNode());

        com.fasterxml.jackson.databind.node.ObjectNode newPayload =
                JsonNodeFactory.instance.objectNode();
        newPayload.put("name", "renamed");
        mgr.replacePayload(it.id(), newPayload);

        assertEquals(List.of("payload:todo-1:renamed"), events);
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q test -Dtest=TodoManagerTest#replacePayload_firesOnPayloadReplace`
Expected: FAIL（事件列表为空，钩子尚未 fire）

- [ ] **Step 3: 在 replacePayload 末尾 fire**

打开 `src/main/java/space/wlshow/scope/todo/TodoManager.java`，找到 `replacePayload` 方法（约 110-118 行），把：

```java
    public synchronized void replacePayload(String id, JsonNode newPayload) {
        TodoItem cur = get(id);
        if (cur.status() != TodoStatus.PENDING) {
            throw new IllegalStateException("非 PENDING 不可改 payload: " + id);
        }
        TodoItem next = cur.withPayload(newPayload);
        items.put(id, next);
        log.info("[Todo] PAYLOAD-REPLACE id={}", id);
    }
```

替换为：

```java
    public synchronized void replacePayload(String id, JsonNode newPayload) {
        TodoItem cur = get(id);
        if (cur.status() != TodoStatus.PENDING) {
            throw new IllegalStateException("非 PENDING 不可改 payload: " + id);
        }
        TodoItem next = cur.withPayload(newPayload);
        items.put(id, next);
        log.info("[Todo] PAYLOAD-REPLACE id={}", id);
        listeners.forEach(l -> l.onPayloadReplace(id, newPayload));
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q test -Dtest=TodoManagerTest`
Expected: BUILD SUCCESS，12 tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/space/wlshow/scope/todo/TodoManager.java \
       src/test/java/space/wlshow/scope/todo/TodoManagerTest.java
git commit -m "feat(todo): replacePayload 末尾 fire onPayloadReplace"
```

---

## Task 4: AguiStateBridge 实现 onRemove / onPayloadReplace + 测试

**Files:**
- Modify: `src/main/java/space/wlshow/scope/agui/AguiStateBridge.java`
- Test: `src/test/java/space/wlshow/scope/agui/AguiStateBridgeTest.java`（新建）

- [ ] **Step 1: 建测试目录 + 写失败测试**

执行：
```bash
mkdir -p src/test/java/space/wlshow/scope/agui
```

新建 `src/test/java/space/wlshow/scope/agui/AguiStateBridgeTest.java`：

```java
package space.wlshow.scope.agui;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEvent.JsonPatchOperation;
import io.agentscope.core.agui.event.AguiEvent.StateDelta;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;
import space.wlshow.scope.todo.TodoItem;
import space.wlshow.scope.todo.TodoManager;
import space.wlshow.scope.todo.TodoType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AguiStateBridgeTest {

    private static List<AguiEvent> collect(Sinks.Many<AguiEvent> sink) {
        List<AguiEvent> out = new ArrayList<>();
        sink.asFlux().subscribe(out::add);
        return out;
    }

    @Test
    void onRemove_emitsRemoveOp() {
        TodoManager todos = new TodoManager();
        Sinks.Many<AguiEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        List<AguiEvent> events = collect(sink);
        AguiStateBridge bridge = new AguiStateBridge("t1", todos, sink);
        todos.addListener(bridge);

        TodoItem it = todos.add(TodoType.CREATE_MODULE, "员工管理",
                JsonNodeFactory.instance.objectNode());
        todos.remove(it.id());

        StateDelta delta = (StateDelta) events.stream()
                .filter(StateDelta.class::isInstance)
                .reduce((a, b) -> b).orElseThrow();
        JsonPatchOperation op = delta.delta().get(0);
        assertEquals("remove", op.op());
        assertEquals("/todos/id=" + it.id(), op.path());
    }

    @Test
    void onPayloadReplace_emitsReplaceOp() {
        TodoManager todos = new TodoManager();
        Sinks.Many<AguiEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        List<AguiEvent> events = collect(sink);
        AguiStateBridge bridge = new AguiStateBridge("t2", todos, sink);
        todos.addListener(bridge);

        TodoItem it = todos.add(TodoType.CREATE_APP, "x",
                JsonNodeFactory.instance.objectNode());

        ObjectNode newPayload = JsonNodeFactory.instance.objectNode();
        newPayload.put("name", "renamed");
        todos.replacePayload(it.id(), newPayload);

        StateDelta delta = (StateDelta) events.stream()
                .filter(StateDelta.class::isInstance)
                .reduce((a, b) -> b).orElseThrow();
        JsonPatchOperation op = delta.delta().get(0);
        assertEquals("replace", op.op());
        assertEquals("/todos/id=" + it.id() + "/payload", op.path());
        assertNotNull(op.value());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q test -Dtest=AguiStateBridgeTest`
Expected: FAIL（`onRemove` / `onPayloadReplace` 在 `AguiStateBridge` 里尚未实现，事件列表里不会有对应 STATE_DELTA）

- [ ] **Step 3: 在 AguiStateBridge 实现两个钩子**

打开 `src/main/java/space/wlshow/scope/agui/AguiStateBridge.java`。在 `public void onClear()` 方法**正下方**追加：

```java
    @Override
    public void onRemove(String id) {
        emit(new StateDelta(threadId, SIDE_RUN_ID,
                List.of(JsonPatchOperation.remove("/todos/id=" + id))));
        log.debug("[StateBridge] STATE_DELTA remove id={}", id);
    }

    @Override
    public void onPayloadReplace(String id,
                                 com.fasterxml.jackson.databind.JsonNode newPayload) {
        emit(new StateDelta(threadId, SIDE_RUN_ID,
                List.of(JsonPatchOperation.replace("/todos/id=" + id + "/payload", newPayload))));
        log.debug("[StateBridge] STATE_DELTA replace payload id={}", id);
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q test -Dtest=AguiStateBridgeTest`
Expected: BUILD SUCCESS，2 tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/space/wlshow/scope/agui/AguiStateBridge.java \
       src/test/java/space/wlshow/scope/agui/AguiStateBridgeTest.java
git commit -m "feat(agui): AguiStateBridge 实现 onRemove / onPayloadReplace"
```

---

## Task 5: TodoUpdateTools 追加 update_app + 测试

**Files:**
- Modify: `src/main/java/space/wlshow/scope/tool/TodoUpdateTools.java`
- Test: `src/test/java/space/wlshow/scope/tool/TodoUpdateToolsTest.java`（新建）

- [ ] **Step 1: 写失败测试**

新建 `src/test/java/space/wlshow/scope/tool/TodoUpdateToolsTest.java`：

```java
package space.wlshow.scope.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import space.wlshow.scope.todo.TodoItem;
import space.wlshow.scope.todo.TodoManager;

import static org.junit.jupiter.api.Assertions.*;

class TodoUpdateToolsTest {

    private static TodoItem seedApp(TodoManager mgr, FrontendCreateTools create) {
        create.createApp("leaveMgr", "请假管理", "23");
        return mgr.snapshot().get(0);
    }

    @Test
    void updateApp_changeLabel_ok() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoUpdateTools update = new TodoUpdateTools(mgr);
        TodoItem app = seedApp(mgr, create);

        String r = update.updateApp("", "员工档案");

        assertTrue(r.contains("APP 已更新"), r);
        JsonNode p = mgr.get(app.id()).payload();
        assertEquals("员工档案", p.path("label").asText());
        assertEquals("leaveMgr", p.path("name").asText());
    }

    @Test
    void updateApp_changeName_ok() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoUpdateTools update = new TodoUpdateTools(mgr);
        TodoItem app = seedApp(mgr, create);

        String r = update.updateApp("staffMgr", "");

        assertTrue(r.contains("APP 已更新"), r);
        assertEquals("staffMgr", mgr.get(app.id()).payload().path("name").asText());
    }

    @Test
    void updateApp_badNamePattern_rejected() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoUpdateTools update = new TodoUpdateTools(mgr);
        TodoItem app = seedApp(mgr, create);

        // name 必须 ^[a-zA-Z][a-zA-Z0-9]*$，传中文应拒
        String r = update.updateApp("员工档案", "");

        assertTrue(r.startsWith("ERROR:"), r);
        // 原 payload 不变
        assertEquals("leaveMgr", mgr.get(app.id()).payload().path("name").asText());
    }

    @Test
    void updateApp_noAppTodo_rejected() {
        TodoManager mgr = new TodoManager();
        TodoUpdateTools update = new TodoUpdateTools(mgr);

        String r = update.updateApp("x", "x");

        assertTrue(r.startsWith("ERROR:"), r);
    }

    @Test
    void updateApp_multipleAppTodos_rejected() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoUpdateTools update = new TodoUpdateTools(mgr);
        create.createApp("a", "应用一", "23");
        create.createApp("b", "应用二", "23");

        String r = update.updateApp("c", "");

        assertTrue(r.startsWith("ERROR:"), r);
    }

    @Test
    void updateApp_runningTodo_rejected() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoUpdateTools update = new TodoUpdateTools(mgr);
        TodoItem app = seedApp(mgr, create);
        mgr.markRunning(app.id());

        String r = update.updateApp("staffMgr", "");

        assertTrue(r.startsWith("ERROR:"), r);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q test -Dtest=TodoUpdateToolsTest`
Expected: COMPILATION FAILURE（`updateApp` 方法尚不存在）

- [ ] **Step 3: 在 TodoUpdateTools 加 update_app**

打开 `src/main/java/space/wlshow/scope/tool/TodoUpdateTools.java`，在 `import java.util.Optional;` 下方追加：

```java
import space.wlshow.scope.todo.TodoType;
```

（若已 import 则跳过。）

在 `private static final SchemaValidator MODEL_VAL = ...` **正下方**追加：

```java
    private static final SchemaValidator APP_VAL =
            new SchemaValidator("/schemas/app-spec.schema.json");
```

在 `public TodoUpdateTools(TodoManager todos) { this.todos = todos; }` **下方**追加新工具方法：

```java
    @Tool(name = "update_app",
            description = "修改应用的英文标识 (name) 或中文显示名 (label)。type 分类码不允许改。" +
                    "一次会话里只允许有一条 CREATE_APP 待办，本工具不需要传 id：" +
                    "工具内部找唯一一条 CREATE_APP 待办；若不存在或存在多条，返回 ERROR。")
    public String updateApp(
            @ToolParam(name = "newName",
                    description = "可选，英文 camelCase；为空表示不改") String newName,
            @ToolParam(name = "newLabel",
                    description = "可选，中文显示名；为空表示不改") String newLabel
    ) {
        return Stage.call(Stage.TOOL_CALL, () -> {
            log.info("[Tool] 调用工具 name=update_app argsHash={}",
                    Stage.argsHash(newName, newLabel));
            List<TodoItem> appTodos = todos.snapshot().stream()
                    .filter(it -> it.type() == TodoType.CREATE_APP)
                    .toList();
            if (appTodos.isEmpty()) {
                log.warn("[Tool] update_app no CREATE_APP todo");
                return "ERROR: 当前没有应用待办，请先用 create_app 登记应用";
            }
            if (appTodos.size() > 1) {
                log.warn("[Tool] update_app multiple CREATE_APP todos size={}", appTodos.size());
                return "ERROR: 检测到 " + appTodos.size() + " 条应用待办，update_app 仅支持单应用场景";
            }
            TodoItem it = appTodos.get(0);
            if (it.status() != TodoStatus.PENDING) {
                log.warn("[Tool] update_app rejected id={} status={}", it.id(), it.status());
                return "ERROR: " + it.id() + " 状态为 " + it.status() + "，不可修改";
            }

            ObjectNode p = ((ObjectNode) it.payload()).deepCopy();
            if (newName != null && !newName.isBlank()) p.put("name", newName);
            if (newLabel != null && !newLabel.isBlank()) p.put("label", newLabel);

            String err = validate(APP_VAL, p, "update_app");
            if (err != null) return err;

            todos.replacePayload(it.id(), p);
            log.info("[Tool] update_app id={} newName={} newLabel={} payload={}",
                    it.id(), newName, newLabel, p);
            return "APP 已更新：" + it.id();
        });
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q test -Dtest=TodoUpdateToolsTest`
Expected: BUILD SUCCESS，6 tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/space/wlshow/scope/tool/TodoUpdateTools.java \
       src/test/java/space/wlshow/scope/tool/TodoUpdateToolsTest.java
git commit -m "feat(tool): 新增 update_app 工具修改应用 name/label"
```

---

## Task 6: TodoUpdateTools 追加 update_field + 测试

**Files:**
- Modify: `src/main/java/space/wlshow/scope/tool/TodoUpdateTools.java`
- Test: `src/test/java/space/wlshow/scope/tool/TodoUpdateToolsTest.java`

- [ ] **Step 1: 写失败测试**

在 `TodoUpdateToolsTest.java` 末尾 `}` 之前追加：

```java
    private static String fieldsJson(String... typeNamePairs) {
        // 每两个一组 (dataType, name)，生成 FieldSpec JSON 数组
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < typeNamePairs.length; i += 2) {
            if (i > 0) sb.append(",");
            sb.append(String.format("""
                    {"comment":"%s","name":"%s","dataType":"%s","usage":"","relateModelType":"","subs":null}""",
                    typeNamePairs[i + 1], typeNamePairs[i + 1], typeNamePairs[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    private static TodoItem seedModel(TodoManager mgr, FrontendCreateTools create) {
        // primary 主键 id 是 schema 隐含约束（题面要求 long+primary），但 schema 本身没强制
        create.createModel("employee", "ENTITY", "yuangong", "t_employee", "",
                fieldsJson("long", "id", "string", "phone"));
        return mgr.snapshot().get(0);
    }

    @Test
    void updateField_changeDataType_ok() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoUpdateTools update = new TodoUpdateTools(mgr);
        TodoItem model = seedModel(mgr, create);

        String r = update.updateField("employee", "phone", "long", "");

        assertTrue(r.contains("FIELD 已更新") || r.contains("已更新"), r);
        JsonNode fields = mgr.get(model.id()).payload().get("fields");
        assertEquals("long",
                fields.get(1).path("dataType").asText());
    }

    @Test
    void updateField_changeComment_ok() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoUpdateTools update = new TodoUpdateTools(mgr);
        TodoItem model = seedModel(mgr, create);

        String r = update.updateField("employee", "phone", "", "员工手机号");

        assertTrue(r.contains("已更新"), r);
        JsonNode fields = mgr.get(model.id()).payload().get("fields");
        assertEquals("员工手机号", fields.get(1).path("comment").asText());
    }

    @Test
    void updateField_unknownField_returnsError() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoUpdateTools update = new TodoUpdateTools(mgr);
        seedModel(mgr, create);

        String r = update.updateField("employee", "nope", "string", "");

        assertTrue(r.startsWith("ERROR:"), r);
        assertTrue(r.contains("nope"), r);
    }

    @Test
    void updateField_badDataType_rejected() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoUpdateTools update = new TodoUpdateTools(mgr);
        seedModel(mgr, create);

        // 不在 enum: long/int/double/string/boolean/date/array
        String r = update.updateField("employee", "phone", "STRING", "");

        assertTrue(r.startsWith("ERROR:"), r);
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q test -Dtest=TodoUpdateToolsTest`
Expected: COMPILATION FAILURE（`updateField` 方法尚不存在）

- [ ] **Step 3: 在 TodoUpdateTools 加 update_field**

打开 `src/main/java/space/wlshow/scope/tool/TodoUpdateTools.java`，在 `updateModel(...)` 方法**下方**追加：

```java
    @Tool(name = "update_field",
            description = "修改数据模型的某个顶层字段的 dataType 或 comment（中文描述）。" +
                    "通过 modelName + fieldName 定位；subs 嵌套字段本日不支持，" +
                    "遇到请告知用户'当前仅支持顶层字段编辑'。")
    public String updateField(
            @ToolParam(name = "modelName") String modelName,
            @ToolParam(name = "fieldName") String fieldName,
            @ToolParam(name = "newDataType",
                    description = "可选 long/int/double/string/boolean/date/array；为空不改") String newDataType,
            @ToolParam(name = "newComment",
                    description = "可选，字段中文描述；为空不改") String newComment
    ) {
        return Stage.call(Stage.TOOL_CALL, () -> {
            log.info("[Tool] 调用工具 name=update_field argsHash={}",
                    Stage.argsHash(modelName, fieldName, newDataType, newComment));
            Optional<TodoItem> found = findByModelName(modelName);
            if (found.isEmpty()) {
                log.warn("[Tool] update_field model not-found name={}", modelName);
                return "ERROR: 未找到 model name=" + modelName;
            }
            TodoItem it = found.get();
            if (it.status() != TodoStatus.PENDING) {
                log.warn("[Tool] update_field rejected id={} status={}", it.id(), it.status());
                return "ERROR: " + it.id() + " 状态为 " + it.status() + "，不可修改";
            }

            ObjectNode p = ((ObjectNode) it.payload()).deepCopy();
            com.fasterxml.jackson.databind.node.ArrayNode fields =
                    (com.fasterxml.jackson.databind.node.ArrayNode) p.get("fields");
            ObjectNode target = null;
            for (JsonNode f : fields) {
                if (fieldName.equals(f.path("name").asText())) { target = (ObjectNode) f; break; }
            }
            if (target == null) {
                log.warn("[Tool] update_field field not-found model={} field={}", modelName, fieldName);
                return "ERROR: 未找到 model=" + modelName + " 的 field=" + fieldName +
                        "（仅支持顶层字段；可能是嵌套字段 subs，本日不支持编辑）";
            }
            if (newDataType != null && !newDataType.isBlank()) target.put("dataType", newDataType);
            if (newComment != null && !newComment.isBlank()) target.put("comment", newComment);

            String err = validate(MODEL_VAL, p, "update_field");
            if (err != null) return err;

            todos.replacePayload(it.id(), p);
            log.info("[Tool] update_field id={} field={} newDataType={} newComment={}",
                    it.id(), fieldName, newDataType, newComment);
            return "FIELD 已更新：" + it.id() + " field=" + fieldName;
        });
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q test -Dtest=TodoUpdateToolsTest`
Expected: BUILD SUCCESS，10 tests pass（含 Task 5 的 6 个）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/space/wlshow/scope/tool/TodoUpdateTools.java \
       src/test/java/space/wlshow/scope/tool/TodoUpdateToolsTest.java
git commit -m "feat(tool): 新增 update_field 工具改字段 dataType/comment"
```

---

## Task 7: TodoUpdateTools 追加 delete_field + 测试

**Files:**
- Modify: `src/main/java/space/wlshow/scope/tool/TodoUpdateTools.java`
- Test: `src/test/java/space/wlshow/scope/tool/TodoUpdateToolsTest.java`

- [ ] **Step 1: 写失败测试**

在 `TodoUpdateToolsTest.java` 末尾 `}` 之前追加：

```java
    @Test
    void deleteField_ok() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoUpdateTools update = new TodoUpdateTools(mgr);
        TodoItem model = seedModel(mgr, create);

        String r = update.deleteField("employee", "phone");

        assertTrue(r.contains("已删除"), r);
        JsonNode fields = mgr.get(model.id()).payload().get("fields");
        assertEquals(1, fields.size());
        assertEquals("id", fields.get(0).path("name").asText());
    }

    @Test
    void deleteField_lastField_rejectedByMinItems() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoUpdateTools update = new TodoUpdateTools(mgr);
        // 只 seed 一个字段，删了之后 fields.minItems=1 会拒
        create.createModel("singleField", "ENTITY", "danziduan", "t_single", "",
                fieldsJson("long", "id"));

        String r = update.deleteField("singleField", "id");

        assertTrue(r.startsWith("ERROR:"), r);
        assertEquals(1, mgr.snapshot().get(0).payload().get("fields").size(),
                "schema 拒收后字段不应被实际删除");
    }

    @Test
    void deleteField_unknownField_returnsError() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoUpdateTools update = new TodoUpdateTools(mgr);
        seedModel(mgr, create);

        String r = update.deleteField("employee", "nope");

        assertTrue(r.startsWith("ERROR:"), r);
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q test -Dtest=TodoUpdateToolsTest`
Expected: COMPILATION FAILURE（`deleteField` 方法尚不存在）

- [ ] **Step 3: 在 TodoUpdateTools 加 delete_field**

打开 `src/main/java/space/wlshow/scope/tool/TodoUpdateTools.java`，在 `updateField(...)` 方法**下方**追加：

```java
    @Tool(name = "delete_field",
            description = "删除数据模型的某个顶层字段。subs 嵌套字段本日不支持。" +
                    "若删后字段数为 0 会被 schema 拒收，应改用 delete_model。")
    public String deleteField(
            @ToolParam(name = "modelName") String modelName,
            @ToolParam(name = "fieldName") String fieldName
    ) {
        return Stage.call(Stage.TOOL_CALL, () -> {
            log.info("[Tool] 调用工具 name=delete_field argsHash={}",
                    Stage.argsHash(modelName, fieldName));
            Optional<TodoItem> found = findByModelName(modelName);
            if (found.isEmpty()) {
                return "ERROR: 未找到 model name=" + modelName;
            }
            TodoItem it = found.get();
            if (it.status() != TodoStatus.PENDING) {
                return "ERROR: " + it.id() + " 状态为 " + it.status() + "，不可修改";
            }

            ObjectNode p = ((ObjectNode) it.payload()).deepCopy();
            com.fasterxml.jackson.databind.node.ArrayNode fields =
                    (com.fasterxml.jackson.databind.node.ArrayNode) p.get("fields");
            int hitIdx = -1;
            for (int i = 0; i < fields.size(); i++) {
                if (fieldName.equals(fields.get(i).path("name").asText())) { hitIdx = i; break; }
            }
            if (hitIdx < 0) {
                return "ERROR: 未找到 model=" + modelName + " 的 field=" + fieldName +
                        "（仅支持顶层字段；可能是嵌套字段 subs，本日不支持编辑）";
            }
            fields.remove(hitIdx);

            String err = validate(MODEL_VAL, p, "delete_field");
            if (err != null) return err;

            todos.replacePayload(it.id(), p);
            log.info("[Tool] delete_field id={} field={} remaining={}",
                    it.id(), fieldName, fields.size());
            return "FIELD 已删除：" + it.id() + " field=" + fieldName;
        });
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q test -Dtest=TodoUpdateToolsTest`
Expected: BUILD SUCCESS，13 tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/space/wlshow/scope/tool/TodoUpdateTools.java \
       src/test/java/space/wlshow/scope/tool/TodoUpdateToolsTest.java
git commit -m "feat(tool): 新增 delete_field 工具删顶层字段"
```

---

## Task 8: TodoDeleteTools 新文件（delete_module / delete_model / cancel_submission）+ 测试

**Files:**
- Create: `src/main/java/space/wlshow/scope/tool/TodoDeleteTools.java`
- Test: `src/test/java/space/wlshow/scope/tool/TodoDeleteToolsTest.java`

- [ ] **Step 1: 写失败测试**

新建 `src/test/java/space/wlshow/scope/tool/TodoDeleteToolsTest.java`：

```java
package space.wlshow.scope.tool;

import org.junit.jupiter.api.Test;
import space.wlshow.scope.todo.TodoItem;
import space.wlshow.scope.todo.TodoManager;

import static org.junit.jupiter.api.Assertions.*;

class TodoDeleteToolsTest {

    @Test
    void deleteModule_happy() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoDeleteTools del = new TodoDeleteTools(mgr);
        create.createModule("员工管理", "staffMgr", "管理员工档案");

        String r = del.deleteModule("staffMgr");

        assertTrue(r.contains("已删除"), r);
        assertEquals(0, mgr.size());
    }

    @Test
    void deleteModule_unknownId_returnsError() {
        TodoManager mgr = new TodoManager();
        TodoDeleteTools del = new TodoDeleteTools(mgr);

        String r = del.deleteModule("nope");

        assertTrue(r.startsWith("ERROR:"), r);
    }

    @Test
    void deleteModel_happy() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoDeleteTools del = new TodoDeleteTools(mgr);
        create.createModel("employee", "ENTITY", "yuangong", "t_employee", "",
                """
                [{"comment":"主键","name":"id","dataType":"long","usage":"primary",
                  "relateModelType":"","subs":null}]
                """);

        String r = del.deleteModel("employee");

        assertTrue(r.contains("已删除"), r);
        assertEquals(0, mgr.size());
    }

    @Test
    void deleteRunning_rejected() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoDeleteTools del = new TodoDeleteTools(mgr);
        create.createModule("员工管理", "staffMgr", "x");
        TodoItem it = mgr.snapshot().get(0);
        mgr.markRunning(it.id());

        String r = del.deleteModule("staffMgr");

        assertTrue(r.startsWith("ERROR:"), r);
        assertEquals(1, mgr.size(), "running 项不应被删");
    }

    @Test
    void cancelSubmission_clearsAll() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoDeleteTools del = new TodoDeleteTools(mgr);
        create.createApp("leaveMgr", "请假管理", "23");
        create.createModule("请假申请", "leaveApply", "x");
        assertEquals(2, mgr.size());

        String r = del.cancelSubmission();

        assertTrue(r.contains("2"), r);
        assertEquals(0, mgr.size());
    }

    @Test
    void cancelSubmission_emptyOk() {
        TodoManager mgr = new TodoManager();
        TodoDeleteTools del = new TodoDeleteTools(mgr);

        String r = del.cancelSubmission();

        assertTrue(r.contains("0") || r.contains("无待办") || r.contains("空"), r);
        assertEquals(0, mgr.size());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q test -Dtest=TodoDeleteToolsTest`
Expected: COMPILATION FAILURE（`TodoDeleteTools` 类尚不存在）

- [ ] **Step 3: 新建 TodoDeleteTools.java**

新建 `src/main/java/space/wlshow/scope/tool/TodoDeleteTools.java`：

```java
package space.wlshow.scope.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.observability.Stage;
import space.wlshow.scope.todo.TodoItem;
import space.wlshow.scope.todo.TodoManager;
import space.wlshow.scope.todo.TodoType;

import java.util.Optional;

/**
 * Day 2026-05-28 新增：把"删除某个待办"以及"用户取消提交后清空待办"
 * 暴露成 LLM 工具。删除仅在 status=PENDING 时允许；非 PENDING 返回 ERROR 让 LLM 自纠错。
 */
public class TodoDeleteTools {

    private static final Logger log = LoggerFactory.getLogger(TodoDeleteTools.class);

    private final TodoManager todos;

    public TodoDeleteTools(TodoManager todos) { this.todos = todos; }

    @Tool(name = "delete_module",
            description = "从待办池里删除一个 CREATE_MODULE 待办。仅当 status=PENDING 时允许。" +
                    "通过 moduleId 定位（英文 camelCase）。")
    public String deleteModule(@ToolParam(name = "moduleId") String moduleId) {
        return Stage.call(Stage.TOOL_CALL, () -> {
            log.info("[Tool] 调用工具 name=delete_module argsHash={}", Stage.argsHash(moduleId));
            Optional<TodoItem> found = todos.snapshot().stream()
                    .filter(it -> it.type() == TodoType.CREATE_MODULE)
                    .filter(it -> moduleId.equals(it.payload().path("moduleId").asText()))
                    .findFirst();
            if (found.isEmpty()) return "ERROR: 未找到 moduleId=" + moduleId;
            return tryRemove(found.get(), "delete_module");
        });
    }

    @Tool(name = "delete_model",
            description = "从待办池里删除一个 CREATE_MODEL 待办。仅当 status=PENDING 时允许。" +
                    "通过 modelName（英文 camelCase）定位。")
    public String deleteModel(@ToolParam(name = "modelName") String modelName) {
        return Stage.call(Stage.TOOL_CALL, () -> {
            log.info("[Tool] 调用工具 name=delete_model argsHash={}", Stage.argsHash(modelName));
            Optional<TodoItem> found = todos.snapshot().stream()
                    .filter(it -> it.type() == TodoType.CREATE_MODEL)
                    .filter(it -> modelName.equals(it.payload().path("name").asText()))
                    .findFirst();
            if (found.isEmpty()) return "ERROR: 未找到 model name=" + modelName;
            return tryRemove(found.get(), "delete_model");
        });
    }

    @Tool(name = "cancel_submission",
            description = "在用户拒绝提交（submit_to_frontend 续跑收到 USER_REJECTED）后立刻调用，" +
                    "清空所有待办。只在 submit_to_frontend 续跑得到 USER_REJECTED 后调用，" +
                    "不要在其他场景使用。")
    public String cancelSubmission() {
        return Stage.call(Stage.TOOL_CALL, () -> {
            int n = todos.size();
            log.info("[Tool] 调用工具 name=cancel_submission pendingSize={}", n);
            todos.clear();
            return "已清空 " + n + " 项待办（用户取消提交）";
        });
    }

    private String tryRemove(TodoItem it, String tool) {
        try {
            todos.remove(it.id());
            log.info("[Tool] {} id={}", tool, it.id());
            return "已删除：" + it.id();
        } catch (IllegalStateException e) {
            log.warn("[Tool] {} rejected id={} status={}", tool, it.id(), it.status());
            return "ERROR: " + it.id() + " 状态为 " + it.status() + "，不可删除";
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q test -Dtest=TodoDeleteToolsTest`
Expected: BUILD SUCCESS，6 tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/space/wlshow/scope/tool/TodoDeleteTools.java \
       src/test/java/space/wlshow/scope/tool/TodoDeleteToolsTest.java
git commit -m "feat(tool): 新增 TodoDeleteTools (delete_module/delete_model/cancel_submission)"
```

---

## Task 9: SubmitTool description 追加 USER_REJECTED 指引

**Files:**
- Modify: `src/main/java/space/wlshow/scope/tool/SubmitTool.java`

- [ ] **Step 1: 改 description**

打开 `src/main/java/space/wlshow/scope/tool/SubmitTool.java`，找到 `@Tool(name = "submit_to_frontend", description = ...)` 那一段（约 22-28 行），把 description 整段替换为：

```java
    @Tool(name = "submit_to_frontend",
            description = "把所有 PENDING 待办下发前端。" +
                    "【调用时机】仅当用户明确表达「提交 / 确认 / 下发 / 发布 / 入库 / 保存生效」等意图时才调用；" +
                    "需求分析、登记 create_app / create_module / create_model 的阶段一律不要调，" +
                    "也不要把它当成工作流的收尾步骤——结束本轮工具调用直接用一句话向用户总结即可。" +
                    "【调用方式】必须先以 confirmed=false 调一次，让系统等用户确认；" +
                    "用户确认后系统会自动让你恢复，再以 confirmed=true 调一次完成真正下发。" +
                    "【取消处理】若续跑时收到 USER_REJECTED，必须随后立即调用 cancel_submission 工具" +
                    "清空所有待办；不要再追问用户是否要清，也不要尝试继续提交。")
```

- [ ] **Step 2: 编译验证**

Run: `mvn -q compile`
Expected: BUILD SUCCESS（只改字符串字面值，无逻辑改动）

- [ ] **Step 3: 跑回归确认未破坏**

Run: `mvn -q test`
Expected: BUILD SUCCESS（所有现有测试通过）

- [ ] **Step 4: Commit**

```bash
git add src/main/java/space/wlshow/scope/tool/SubmitTool.java
git commit -m "feat(tool): submit_to_frontend description 补 USER_REJECTED→cancel_submission 指引"
```

---

## Task 10: AguiAgentConfig 注册 TodoDeleteTools

**Files:**
- Modify: `src/main/java/space/wlshow/scope/config/AguiAgentConfig.java`

- [ ] **Step 1: 找到 Toolkit 注册位置**

打开 `src/main/java/space/wlshow/scope/config/AguiAgentConfig.java`。用 Grep 找现有的 `TodoUpdateTools` 注册位置：

```
grep -n "TodoUpdateTools\|SubmitTool\|TodoQueryTools" src/main/java/space/wlshow/scope/config/AguiAgentConfig.java
```

Expected: 看到 `toolkit.registerTool(new TodoUpdateTools(...))` 之类的调用行。

- [ ] **Step 2: 加 TodoDeleteTools import + 注册**

在 import 区追加：

```java
import space.wlshow.scope.tool.TodoDeleteTools;
```

在 `TodoUpdateTools` 注册行旁追加（保持 Toolkit 同源，使用同一个 `todos` 实例）：

```java
toolkit.registerTool(new TodoDeleteTools(todos));
```

具体位置就贴在 `TodoUpdateTools` 注册行的下一行；缩进对齐。

- [ ] **Step 3: 启动冒烟**

Run: `mvn -q compile` 验证编译。
Expected: BUILD SUCCESS

Run: `mvn -q spring-boot:run`（后台跑或新终端跑都行；这一步只是冒烟看启动日志不报错）
Expected: 启动日志看到 `[AguiConfig] mounting custom AGUI run route at /agui/run`，无 ClassNotFoundException / NoSuchMethodError；Ctrl+C 停掉。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/space/wlshow/scope/config/AguiAgentConfig.java
git commit -m "feat(config): AguiAgentConfig 注册 TodoDeleteTools"
```

---

## Task 11: 前端依赖 markstream-vue + 新增 AssistantBubble.vue

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/src/main.ts`
- Create: `frontend/src/components/AssistantBubble.vue`

- [ ] **Step 1: 安装依赖**

Run:
```bash
cd frontend && npm install markstream-vue@latest
```
Expected: `markstream-vue` 出现在 `package.json` dependencies 中，无 peer-dep 报错。

- [ ] **Step 2: main.ts 加 CSS import**

打开 `frontend/src/main.ts`，在文件顶端、其他 import 之后追加：

```ts
import 'markstream-vue/index.css'
```

- [ ] **Step 3: 新建 AssistantBubble.vue**

新建 `frontend/src/components/AssistantBubble.vue`：

```vue
<script setup lang="ts">
import { MarkdownRender } from 'markstream-vue'
defineProps<{ text: string; streaming: boolean }>()
</script>

<template>
  <div class="bubble assistant-bubble">
    <MarkdownRender :content="text" />
    <span v-if="streaming" class="cursor">▍</span>
  </div>
</template>

<style scoped>
.assistant-bubble {
  padding: 12px 16px;
  border-radius: 16px;
  border-top-left-radius: 4px;
  font-size: 14.5px;
  line-height: 1.65;
  background: #fff;
  color: #111827;
  border: 1px solid rgba(0, 0, 0, 0.05);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.04);
  text-align: left;
  word-wrap: break-word;
  word-break: break-word;
}

.assistant-bubble :deep(h1) { font-size: 18px; margin: 8px 0 6px; }
.assistant-bubble :deep(h2) { font-size: 16px; margin: 8px 0 6px; }
.assistant-bubble :deep(h3) { font-size: 14.5px; margin: 6px 0 4px; }
.assistant-bubble :deep(p) { margin: 6px 0; }
.assistant-bubble :deep(ul),
.assistant-bubble :deep(ol) { margin: 6px 0; padding-left: 22px; }
.assistant-bubble :deep(li) { margin: 2px 0; }
.assistant-bubble :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  background: rgba(99, 102, 241, 0.10);
  color: #4f46e5;
  padding: 1px 6px;
  border-radius: 4px;
}
.assistant-bubble :deep(pre) {
  background: #0b1020;
  color: #e5e7eb;
  padding: 12px 14px;
  border-radius: 10px;
  overflow-x: auto;
  margin: 8px 0;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.18);
}
.assistant-bubble :deep(pre code) {
  background: transparent;
  color: inherit;
  padding: 0;
}

.cursor {
  display: inline-block;
  margin-left: 2px;
  animation: blink 1s steps(2) infinite;
  color: #8b5cf6;
}

@keyframes blink { 50% { opacity: 0; } }

@media (prefers-color-scheme: dark) {
  .assistant-bubble {
    background: #1f2937;
    color: #e5e7eb;
    border-color: rgba(255, 255, 255, 0.06);
  }
  .assistant-bubble :deep(code) {
    background: rgba(99, 102, 241, 0.22);
    color: #c7d2fe;
  }
}
</style>
```

- [ ] **Step 4: 启动 dev server 冒烟（手动）**

Run:
```bash
cd frontend && npm run dev
```
Expected: Vite 起在 5173，浏览器打开 `http://localhost:5173/?mock=1`，页面不应报 import 错误。先不要求样式生效（下个 Task 才会真用到 AssistantBubble）。

- [ ] **Step 5: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src/main.ts \
        frontend/src/components/AssistantBubble.vue
git commit -m "feat(frontend): 引入 markstream-vue + 新增 AssistantBubble"
```

---

## Task 12: types.ts 扩展 UiMsg + 删除 PendingConfirm

**Files:**
- Modify: `frontend/src/types.ts`

- [ ] **Step 1: 替换 types.ts**

打开 `frontend/src/types.ts`，整文件替换为：

```ts
export interface UiMsg {
  id: string
  role: 'user' | 'assistant'
  text: string
  kind?: 'text' | 'hitl-card'      // 默认 'text'
  toolCallId?: string              // kind='hitl-card' 时必填，用于续跑携带
  hitlTodos?: Todo[]               // kind='hitl-card' 时携带 Todo 快照
  hitlDecision?: HitlDecision      // 用户点完按钮后填上，用于卡片 disabled 状态
}

export interface Todo {
  id: string
  type: string
  targetName: string
  status: string
  payload?: unknown
  errorMessage?: string
}

export interface JsonPatchOp {
  op: 'add' | 'replace' | 'remove'
  path: string
  value?: unknown
}

export type HitlDecision = 'USER_CONFIRMED' | 'USER_REJECTED'
```

注意：`PendingConfirm` 类型已被删除。

- [ ] **Step 2: 编译验证**

Run: `cd frontend && npx vue-tsc --noEmit`
Expected: 出现红色错误 —— `App.vue` / `useScopeAgent.ts` / `HitlConfirmModal.vue` 等文件还在用 `PendingConfirm`。这是预期；后续 Task 会修。

不要 commit，把这一步当成"先打断点"，下个 Task 才能让编译重新通过。

---

## Task 13: HitlInlineCard.vue 新建

**Files:**
- Create: `frontend/src/components/HitlInlineCard.vue`

- [ ] **Step 1: 新建文件**

新建 `frontend/src/components/HitlInlineCard.vue`：

```vue
<script setup lang="ts">
import type { HitlDecision, UiMsg } from '../types'
defineProps<{ card: UiMsg }>()
defineEmits<{ decide: [decision: HitlDecision] }>()
</script>

<template>
  <div class="bubble hitl-card" :class="{ decided: !!card.hitlDecision }">
    <header class="hitl-head">
      <span class="hitl-icon">✓</span>
      <span class="hitl-title">确认下发到前端？</span>
      <span class="hitl-count">{{ card.hitlTodos?.length ?? 0 }} 项</span>
    </header>

    <ul class="hitl-list">
      <li v-for="t in card.hitlTodos" :key="t.id">
        <span class="hitl-type">{{ t.type.replace('CREATE_', '') }}</span>
        <span class="hitl-name">{{ t.targetName }}</span>
        <span class="hitl-id">{{ t.id }}</span>
      </li>
    </ul>

    <div v-if="!card.hitlDecision" class="hitl-actions">
      <button class="btn-secondary" type="button"
              @click="$emit('decide', 'USER_REJECTED')">取消</button>
      <button class="btn-primary" type="button"
              @click="$emit('decide', 'USER_CONFIRMED')">
        <span>确认下发</span>
      </button>
    </div>
    <div v-else class="hitl-decided">
      {{ card.hitlDecision === 'USER_CONFIRMED'
         ? '✓ 已确认下发'
         : '✕ 已取消（清空待办）' }}
    </div>
  </div>
</template>

<style scoped>
.hitl-card {
  background: linear-gradient(180deg, rgba(99, 102, 241, 0.04), rgba(255, 255, 255, 0.92));
  border: 1px solid rgba(99, 102, 241, 0.18);
  border-radius: 14px;
  padding: 14px 16px;
  box-shadow: 0 8px 24px rgba(99, 102, 241, 0.10);
  max-width: 480px;
  font-size: 14px;
  color: #111827;
}

.hitl-card.decided { opacity: 0.7; }

.hitl-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.hitl-icon {
  width: 24px;
  height: 24px;
  border-radius: 8px;
  background: linear-gradient(135deg, #6366f1, #8b5cf6 60%, #ec4899);
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 700;
  box-shadow: 0 4px 10px rgba(139, 92, 246, 0.35);
}

.hitl-title {
  font-weight: 700;
  font-size: 14px;
  color: #111827;
}

.hitl-count {
  margin-left: auto;
  font-size: 11px;
  color: #4f46e5;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  background: rgba(99, 102, 241, 0.10);
  border: 1px solid rgba(99, 102, 241, 0.20);
  padding: 2px 8px;
  border-radius: 999px;
}

.hitl-list {
  list-style: none;
  margin: 0 0 12px;
  padding: 4px;
  max-height: 220px;
  overflow-y: auto;
  background: #f9fafb;
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: 10px;
}

.hitl-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 10px;
  font-size: 13px;
  border-radius: 6px;
}

.hitl-list li:hover { background: rgba(99, 102, 241, 0.06); }

.hitl-type {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 10px;
  font-weight: 700;
  background: rgba(99, 102, 241, 0.10);
  color: #4f46e5;
  padding: 2px 8px;
  border-radius: 5px;
}

.hitl-name {
  flex: 1;
  color: #111827;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hitl-id {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  color: #9ca3af;
}

.hitl-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.hitl-actions button {
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  padding: 7px 14px;
  border-radius: 10px;
  border: none;
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.2s ease, background 0.18s ease;
}

.btn-primary {
  background: linear-gradient(135deg, #6366f1, #8b5cf6 60%, #ec4899);
  color: #fff;
  box-shadow: 0 4px 12px rgba(139, 92, 246, 0.35);
}

.btn-primary:hover { transform: translateY(-1px); box-shadow: 0 8px 18px rgba(139, 92, 246, 0.45); }

.btn-secondary {
  background: rgba(15, 23, 42, 0.05);
  color: #4b5563;
}

.btn-secondary:hover { background: rgba(15, 23, 42, 0.09); color: #111827; }

.hitl-decided {
  font-size: 13px;
  color: #4f46e5;
  font-weight: 600;
  padding: 6px 2px;
  text-align: right;
}

@media (prefers-color-scheme: dark) {
  .hitl-card {
    background: linear-gradient(180deg, rgba(99, 102, 241, 0.10), rgba(31, 41, 55, 0.92));
    color: #e5e7eb;
    border-color: rgba(99, 102, 241, 0.30);
  }
  .hitl-title { color: #f3f4f6; }
  .hitl-list {
    background: rgba(0, 0, 0, 0.25);
    border-color: rgba(255, 255, 255, 0.08);
  }
  .hitl-name { color: #e5e7eb; }
  .btn-secondary { background: rgba(255, 255, 255, 0.08); color: #cbd5e1; }
  .btn-secondary:hover { background: rgba(255, 255, 255, 0.14); color: #fff; }
}
</style>
```

- [ ] **Step 2: 不 commit，留到 Task 16 同 ChatPane / App / useScopeAgent 一并改完 + commit**

---

## Task 14: useScopeAgent.ts 改造 onRunFinishedEvent + resumeRun

**Files:**
- Modify: `frontend/src/composables/useScopeAgent.ts`

- [ ] **Step 1: 替换文件**

打开 `frontend/src/composables/useScopeAgent.ts`，整文件替换为：

```ts
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { HttpAgent } from '@ag-ui/client'
import type { HitlDecision, JsonPatchOp, Todo, UiMsg } from '../types'

export interface UseScopeAgentOptions {
  mock?: boolean
  baseUrl?: string
}

export function useScopeAgent(opts: UseScopeAgentOptions = {}) {
  const baseUrl = opts.baseUrl ?? 'http://localhost:8888'
  const threadId = 'thread-' + Date.now()
  const agent = new HttpAgent({
    url: `${baseUrl}/agui/run`,
    threadId,
  })

  const messages = ref<UiMsg[]>([])
  const todos = ref<Todo[]>([])
  const running = ref(false)
  const streamingId = ref<string | null>(null)
  const lastSubmitToolCallId = ref<string | null>(null)

  function mergeTodos(incoming: Todo[]) {
    const existing = new Map(todos.value.map(t => [t.id, t]))
    for (const t of incoming) {
      if (!existing.has(t.id)) existing.set(t.id, t)
    }
    todos.value = [...existing.values()]
  }

  function applyOps(ops: JsonPatchOp[]) {
    const arr = [...todos.value]
    for (const op of ops) {
      if (op.path === '/todos/-' && op.op === 'add') {
        const item = op.value as Todo
        if (!arr.find(t => t.id === item.id)) {
          arr.push(item)
        } else {
          console.debug('[STATE_DELTA] skip duplicate add id=', item.id)
        }
      } else if (op.path === '/todos' && op.op === 'replace') {
        arr.length = 0
        ;(op.value as Todo[]).forEach(t => arr.push(t))
      } else if (op.op === 'remove') {
        const m = op.path.match(/^\/todos\/id=([^/]+)$/)
        if (m) {
          const idx = arr.findIndex(t => t.id === m[1])
          if (idx >= 0) arr.splice(idx, 1)
          continue
        }
        console.warn('[STATE_DELTA] unhandled remove path', op.path)
      } else {
        const m = op.path.match(/^\/todos\/id=([^/]+)\/(\w+)$/)
        if (!m) {
          console.warn('[STATE_DELTA] unknown path', op.path)
          continue
        }
        const [, id, field] = m
        const t = arr.find(x => x.id === id)
        if (!t) {
          console.warn('[STATE_DELTA] todo not found id=', id)
          continue
        }
        ;(t as Record<string, unknown>)[field] = op.value
      }
    }
    todos.value = arr
  }

  if (!opts.mock) {
    agent.subscribe({
      onTextMessageStartEvent: ({ event }) => {
        streamingId.value = event.messageId
        messages.value.push({ id: event.messageId, role: 'assistant', text: '' })
      },
      onTextMessageContentEvent: ({ event }) => {
        const msg = messages.value.find(m => m.id === event.messageId)
        if (msg) msg.text += event.delta
      },
      onTextMessageEndEvent: () => {
        streamingId.value = null
      },
      onToolCallStartEvent: ({ event }) => {
        console.log('[ToolCall START]', event.toolCallName, event.toolCallId)
        if (event.toolCallName === 'submit_to_frontend') {
          lastSubmitToolCallId.value = event.toolCallId
        }
      },
      onToolCallEndEvent: (params) => {
        console.log('[ToolCall END]', params.event.toolCallId)
      },
      onToolCallResultEvent: ({ event }) => {
        console.log('[ToolCall RESULT] id=', event.toolCallId)
        if (event.toolCallId === lastSubmitToolCallId.value) {
          lastSubmitToolCallId.value = null
        }
      },
      onRunFinishedEvent: () => {
        running.value = false
        // 仍然挂着没拿到 RESULT 的 submit_to_frontend → ToolSuspend，
        // 此时往 chat 流推一条 hitl-card 助手消息
        if (lastSubmitToolCallId.value) {
          console.log('[HITL] pushing inline card for', lastSubmitToolCallId.value)
          messages.value.push({
            id: 'hitl-' + Date.now(),
            role: 'assistant',
            text: '',
            kind: 'hitl-card',
            toolCallId: lastSubmitToolCallId.value,
            hitlTodos: [...todos.value],
          })
          lastSubmitToolCallId.value = null
        }
      },
      onRunErrorEvent: ({ event }) => {
        running.value = false
        console.error('[RUN_ERROR]', event)
        messages.value.push({
          id: 'err-' + Date.now(),
          role: 'assistant',
          text: '[ERROR] ' + (event.message || '未知错误'),
        })
      },
    })
  }

  async function send(text: string) {
    const trimmed = text.trim()
    if (!trimmed || running.value) return
    const userMsg: UiMsg = { id: 'u-' + Date.now(), role: 'user', text: trimmed }
    messages.value.push(userMsg)
    if (opts.mock) {
      running.value = true
      setTimeout(() => {
        messages.value.push({
          id: 'a-' + Date.now(),
          role: 'assistant',
          text: '(mock) 收到 "' + trimmed + '"，当前是 mock 模式，未真正请求后端。',
        })
        running.value = false
      }, 400)
      return
    }
    agent.addMessage({ id: userMsg.id, role: 'user', content: trimmed })
    running.value = true
    try {
      await agent.runAgent({ runId: 'run-' + Date.now() })
    } catch (e) {
      running.value = false
      console.error(e)
    }
  }

  async function resumeRun(decision: HitlDecision, toolCallId: string) {
    const card = messages.value.find(
        m => m.kind === 'hitl-card' && m.toolCallId === toolCallId)
    if (!card || card.hitlDecision) return
    card.hitlDecision = decision

    if (opts.mock) { console.log('[mock][HITL] decision=', decision); return }
    running.value = true
    try {
      // role:'tool' 不能走 agent.addMessage（1.x 会拒收），直接 push 到内部 messages
      ;(agent as unknown as { messages: unknown[] }).messages.push({
        id: 'tr-' + Date.now(),
        role: 'tool',
        toolCallId,
        content: decision,
      })
      await agent.runAgent({ runId: 'run-' + Date.now() })
    } catch (e) {
      console.error('[HITL] resumeRun error', e)
    } finally {
      running.value = false
    }
  }

  let es: EventSource | null = null

  function connectSse() {
    if (opts.mock) return
    const url = `${baseUrl}/agui/state-stream/${encodeURIComponent(threadId)}`
    es = new EventSource(url)
    es.addEventListener('STATE_SNAPSHOT', (ev) => {
      const e = JSON.parse((ev as MessageEvent).data)
      const incoming = (e.snapshot?.todos ?? []) as Todo[]
      mergeTodos(incoming)
      console.log('[STATE_SNAPSHOT] size=', todos.value.length)
    })
    es.addEventListener('STATE_DELTA', (ev) => {
      const e = JSON.parse((ev as MessageEvent).data)
      applyOps(e.delta as JsonPatchOp[])
      console.log('[STATE_DELTA] ops=', e.delta.length)
    })
    es.onerror = (err) => console.warn('[StateStream] error', err)
  }

  function disconnectSse() {
    es?.close()
    es = null
  }

  function loadMockState(state: {
    messages?: UiMsg[]
    todos?: Todo[]
  }) {
    if (state.messages) messages.value = [...state.messages]
    if (state.todos) todos.value = [...state.todos]
  }

  onMounted(connectSse)
  onBeforeUnmount(disconnectSse)

  return {
    threadId,
    messages,
    todos,
    running,
    streamingId,
    send,
    resumeRun,
    loadMockState,
    isMock: !!opts.mock,
  }
}
```

变更点：
- 删 `pendingConfirm` ref，删 `PendingConfirm` import
- `onRunFinishedEvent` 不再设 pending，改为 push `kind='hitl-card'` 消息
- `resumeRun(decision, toolCallId)` 签名变化：第二参从 pending 派生改为显式传入
- `applyOps` 新增 `remove` 分支处理 `/todos/id=X`
- `loadMockState` 入参移除 `pendingConfirm` 字段

- [ ] **Step 2: 不 commit，留到 Task 16 一起**

---

## Task 15: ChatPane.vue v-for 改造 + 透传 decide emit

**Files:**
- Modify: `frontend/src/components/ChatPane.vue`

- [ ] **Step 1: 改 script 段加 import + emit**

打开 `frontend/src/components/ChatPane.vue`，找到 `<script setup lang="ts">` 段（第 1-52 行）。

在 `import type { UiMsg } from '../types'` 之后追加：

```ts
import type { HitlDecision } from '../types'
import AssistantBubble from './AssistantBubble.vue'
import HitlInlineCard from './HitlInlineCard.vue'
```

把原来的 `defineEmits<{ send: [text: string] }>()` 改为：

```ts
const emit = defineEmits<{
  send: [text: string]
  decide: [decision: HitlDecision, toolCallId: string]
}>()
```

注意：原代码用的是 `const emit = defineEmits<{ send: [text: string] }>()` 形式赋值（看 ChatPane.vue 第 13-15 行）；保持赋值形式即可。

- [ ] **Step 2: 改 template v-for 段**

找到 `<div v-for="m in messages"` 那一段（约第 87-95 行），替换为：

```vue
        <div v-for="m in messages" :key="m.id" :class="['row', m.role]">
          <div class="avatar">{{ m.role === 'user' ? '我' : 'AI' }}</div>
          <div class="bubble-wrap">
            <div class="role-label">{{ m.role === 'user' ? 'You' : 'Agent' }}</div>

            <div v-if="m.role === 'user'" class="bubble">
              <div class="text">{{ m.text }}</div>
            </div>

            <HitlInlineCard v-else-if="m.kind === 'hitl-card'"
                            :card="m"
                            @decide="(d: HitlDecision) => emit('decide', d, m.toolCallId!)" />

            <AssistantBubble v-else
                             :text="m.text"
                             :streaming="streamingId === m.id" />
          </div>
        </div>
```

注意：原来 `.bubble` + `.text` 里有 user/assistant 共用 `white-space: pre-wrap`。现在 user 仍走 `.bubble`（保留 pre-wrap），assistant 走 `.assistant-bubble`（不带 pre-wrap）。CSS 不需要改，因为 `.bubble` 选择器在 ChatPane 里的样式仍只作用于 v-if user 分支。

- [ ] **Step 3: 不 commit，留到 Task 16**

---

## Task 16: App.vue 删 HitlConfirmModal + 删源文件 + 改 mock + commit 前端整体

**Files:**
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/mocks/sampleState.json`
- Delete: `frontend/src/components/HitlConfirmModal.vue`

- [ ] **Step 1: 改 App.vue**

打开 `frontend/src/App.vue`。

a) `<script setup lang="ts">` 段（第 1-9 行）：
- 删除 `import HitlConfirmModal from './components/HitlConfirmModal.vue'`
- 把 useScopeAgent 解构改为（去掉 `pendingConfirm`）：

```ts
const {
  threadId,
  messages,
  todos,
  running,
  streamingId,
  send,
  resumeRun,
  loadMockState,
} = useScopeAgent({ mock: isMock })
```

b) `<template>` 段：
- 把 `<ChatPane>` 加 `@decide="resumeRun"`：

```vue
    <ChatPane
        :thread-id="threadId"
        :messages="messages"
        :running="running"
        :streaming-id="streamingId"
        :is-mock="isMock"
        @send="send"
        @decide="resumeRun"
    />
```

- 删除整个 `<HitlConfirmModal :pending="pendingConfirm" @decide="resumeRun" />` 标签

- [ ] **Step 2: 改 mocks/sampleState.json**

打开 `frontend/src/mocks/sampleState.json` 看现状：

```bash
cat frontend/src/mocks/sampleState.json
```

如有 `pendingConfirm` 字段：删掉；并在 `messages` 数组末尾追加一条 hitl-card mock 消息，便于 `?mock=1` 调样式。例如 messages 数组追加：

```json
{
  "id": "hitl-mock-1",
  "role": "assistant",
  "text": "",
  "kind": "hitl-card",
  "toolCallId": "tc-mock-1",
  "hitlTodos": [
    {"id": "todo-1", "type": "CREATE_APP", "targetName": "员工档案管理", "status": "PENDING"},
    {"id": "todo-2", "type": "CREATE_MODULE", "targetName": "员工管理", "status": "PENDING"},
    {"id": "todo-3", "type": "CREATE_MODEL", "targetName": "employee", "status": "PENDING"}
  ]
}
```

具体 JSON 修改要看现有 sampleState.json 的结构。若没有 `pendingConfirm` 字段、且 messages 已经有内容，只需要追加上述一条；并保证 JSON 合法（最后一项不带尾逗号）。

- [ ] **Step 3: 删 HitlConfirmModal.vue**

```bash
rm frontend/src/components/HitlConfirmModal.vue
```

- [ ] **Step 4: 类型检查 + dev 冒烟**

Run: `cd frontend && npx vue-tsc --noEmit`
Expected: 无错误。如果还有红色错误，回到对应文件按提示修正。

Run: `cd frontend && npm run dev`
然后在浏览器打开 `http://localhost:5173/?mock=1`，检查：
- chat 流里能看到 mock 的 hitl-card 卡片，按钮可以点击（mock 模式下点击只 console.log）
- 普通 assistant 消息走 markdown 渲染（mock 消息里有 `**加粗**` 或列表则能看到效果；若 sampleState 没有 markdown 内容，至少要看渲染没崩）

- [ ] **Step 5: Commit 前端整体改动**

```bash
git add frontend/src/types.ts \
        frontend/src/composables/useScopeAgent.ts \
        frontend/src/components/HitlInlineCard.vue \
        frontend/src/components/ChatPane.vue \
        frontend/src/App.vue \
        frontend/src/mocks/sampleState.json
git rm frontend/src/components/HitlConfirmModal.vue
git commit -m "feat(frontend): HITL 弹窗下沉为 chat 内联卡片 + markdown 流式渲染"
```

---

## Task 17: 端到端联调验收

**Files:** 无代码改动；纯人工验证后端 + 前端 + LLM 三方时序

- [ ] **Step 1: 起后端 + 前端**

终端 A：
```bash
mvn spring-boot:run
```
Expected: 看到 `[AguiConfig] mounting custom AGUI run route at /agui/run` + `[AguiConfig] models initialized`

终端 B：
```bash
cd frontend && npm run dev
```
Expected: Vite 起在 5173

- [ ] **Step 2: 端到端冒烟"修改+提交+取消"**

浏览器打开 `http://localhost:5173/`（不带 `?mock=1`），输入：

```
做一个简单的员工档案管理
```

Expected:
- chat 里 assistant 流式出 markdown 回复
- 看板出现 3-5 个 PENDING todo（app + module + model）

继续输入：

```
把员工模型的 phone 字段类型改成 long
```

Expected:
- LLM 调 `update_field(modelName=employee, fieldName=phone, newDataType=long, newComment=)`
- 看板对应 todo 的 payload.fields 里 phone 的 dataType 变成 long（看 `[STATE_DELTA] ops=1` 日志确认 `/todos/id=X/payload`）

继续输入：

```
提交
```

Expected:
- LLM 调 `submit_to_frontend(confirmed=false)`
- chat 流里出现 hitl-card 卡片

点"取消"按钮：

Expected:
- 卡片切到 disabled，显示"✕ 已取消（清空待办）"
- LLM 续跑后调 `cancel_submission()`
- 看板被清空（`[STATE_DELTA] ops=1` 日志显示 `replace /todos []`）
- chat 流继续接 LLM 的"已取消并清空，请重新描述需求"之类回复（markdown 渲染）

- [ ] **Step 3: 端到端冒烟"确认提交"**

刷新页面（新 threadId），重新跑：
```
做一个员工档案管理 → 提交 → 点"确认下发"
```

Expected:
- 卡片切到"✓ 已确认下发"
- 看板 todo 状态从 PENDING → RUNNING → SUCCESS 流转（看 `[STATE_DELTA]` 日志）

- [ ] **Step 4: 若验收通过，最终 commit**

无新增 diff 时跳过；若手工微调样式 / 文案，按需 commit。

---

## Self-Review

### Spec 覆盖核查

| Spec §  | 内容 | 落点 |
|---|---|---|
| §2.1 update_app | Task 5 |
| §2.1 update_field | Task 6 |
| §2.1 delete_field | Task 7 |
| §2.1 delete_module / delete_model / cancel_submission | Task 8 |
| §2.2 TodoManager.remove | Task 2 |
| §2.2 replacePayload fire onPayloadReplace | Task 3 |
| §2.3 TodoChangeListener 两钩子 | Task 1 |
| §2.4 AguiStateBridge 实现两钩子 | Task 4 |
| §2.5 SubmitTool description | Task 9 |
| §2.6 AguiAgentConfig Toolkit 注册 | Task 10 |
| §2.7 schema 校验策略 | Task 5/6/7 工具内 `validate(APP_VAL/MODEL_VAL, ...)` 调用 |
| §3 markstream-vue 接入 | Task 11 |
| §4.1 types.ts | Task 12 |
| §4.2 HitlInlineCard.vue | Task 13 |
| §4.3 useScopeAgent.ts | Task 14 |
| §4.4 App.vue | Task 16 |
| §4.5 ChatPane.vue emit | Task 15 |
| §4.6 删除清单 | Task 16 |
| §7 测试矩阵 | Task 2-8 单测；Task 17 手工 e2e |

### Placeholder Scan

- 无 "TBD" / "TODO" / "implement later"
- 所有代码块均完整
- 文件路径全用绝对相对路径（`src/main/java/...` / `frontend/src/...`）
- 没有"similar to Task N"
- 每个测试 / 实现都给完整代码

### 类型一致性

- `update_app(newName, newLabel)`：Task 5 工具签名、Task 5 测试均一致
- `update_field(modelName, fieldName, newDataType, newComment)`：Task 6 工具签名、Task 6 测试一致
- `delete_field(modelName, fieldName)`：Task 7 一致
- `cancel_submission()`：Task 8 一致
- `TodoManager.remove(String id) → boolean`：Task 2 实现与测试一致
- `TodoChangeListener.onRemove(String id)` / `onPayloadReplace(String id, JsonNode newPayload)`：Task 1 接口、Task 2/3 测试 listener、Task 4 AguiStateBridge 实现一致
- `UiMsg.kind: 'text' | 'hitl-card'`、`toolCallId`、`hitlTodos`、`hitlDecision`：Task 12 / Task 13 / Task 14 / Task 15 一致
- `resumeRun(decision, toolCallId)` 签名：Task 14 定义 + Task 15 调用 + Task 16 emit 透传一致
- 前端 `HitlDecision = 'USER_CONFIRMED' | 'USER_REJECTED'`：types.ts / HitlInlineCard / useScopeAgent / ChatPane 一致
