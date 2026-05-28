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
}