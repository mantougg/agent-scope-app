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
        assertTrue(running.updatedAt().compareTo(origin.updatedAt()) >= 0,
                "updatedAt 不应回退；Windows 上 Instant.now() 分辨率较粗，允许相等");
    }

    @Test
    void terminalStates() {
        assertTrue(TodoStatus.SUCCESS.isTerminal());
        assertTrue(TodoStatus.FAILED.isTerminal());
        assertFalse(TodoStatus.PENDING.isTerminal());
        assertFalse(TodoStatus.RUNNING.isTerminal());
    }
}