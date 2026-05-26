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
