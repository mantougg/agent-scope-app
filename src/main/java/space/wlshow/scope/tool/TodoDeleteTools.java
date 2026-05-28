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
