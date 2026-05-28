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
