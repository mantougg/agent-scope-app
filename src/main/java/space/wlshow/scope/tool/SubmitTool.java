package space.wlshow.scope.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.ToolSuspendException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.observability.Stage;
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
                    "用户确认后系统会自动让你恢复，再以 confirmed=true 调一次完成真正下发。" +
                    "【取消处理】若续跑时收到 USER_REJECTED，必须随后立即调用 cancel_submission 工具" +
                    "清空所有待办；不要再追问用户是否要清，也不要尝试继续提交。")
    public String submit(@ToolParam(name = "confirmed",
            description = "用户是否已确认。第一次必填 false。") boolean confirmed) {
        return Stage.call(Stage.TOOL_CALL, () -> {
            log.info("[Tool] 调用工具 name=submit_to_frontend argsHash={}",
                    Stage.argsHash(confirmed));
            List<TodoItem> pending = todos.snapshot().stream()
                    .filter(it -> it.status() == TodoStatus.PENDING).toList();

            if (pending.isEmpty()) return "没有 PENDING 待办，无需下发";

            if (!confirmed) {
                String summary = pending.stream()
                        .map(it -> String.format("- %s [%s] %s",
                                it.id(), it.type(), it.targetName()))
                        .reduce((a, b) -> a + "\n" + b).orElse("");
                log.info("[Submit] suspend with {} items", pending.size());
                throw new ToolSuspendException("AWAITING_USER_CONFIRMATION\n" + summary);
            }

            // confirmed=true：真发（Day 5 dry-run，只是把状态机走一遍）
            // Day 7 §9 接 HttpDispatcher 后，markRunning 和 markSuccess 之间会插入
            // 实际 HTTP 下发 + 等待 ACK 的逻辑；本日 dry-run 直接顺一遍。
            int n = 0;
            for (TodoItem it : pending) {
                todos.markRunning(it.id());
                long start = System.currentTimeMillis();
                int payloadSize = it.payload().toString().length();
                Stage.run(Stage.FRONTEND_DISPATCH, () ->
                        log.info("[Dispatch] id={} endpoint={} size={}",
                                it.id(), endpointOf(it), payloadSize));
                try {
                    // TODO Day 7 §9：换成 dispatcher.dispatch(it).block()
                    todos.markSuccess(it.id());
                    long ms = System.currentTimeMillis() - start;
                    Stage.run(Stage.FRONTEND_CALLBACK, () ->
                            log.info("[Dispatch] 回执 id={} status=SUCCESS latency={}ms",
                                    it.id(), ms));
                } catch (Exception e) {
                    todos.markFailed(it.id(), e.getMessage());
                    long ms = System.currentTimeMillis() - start;
                    Stage.run(Stage.FRONTEND_CALLBACK, () ->
                            log.warn("[Dispatch] 回执 id={} status=FAILED latency={}ms err={}",
                                    it.id(), ms, e.getMessage()));
                }
                n++;
            }
            log.info("[Submit] dispatched {} items (dry-run)", n);
            return "已下发 " + n + " 项（dry-run，Day 7 §9 接 HttpDispatcher）";
        });
    }

    /** dry-run 期间假装的下游端点名；接上 HttpDispatcher 后换成真实 URL。 */
    private static String endpointOf(TodoItem it) {
        return switch (it.type()) {
            case CREATE_APP -> "POST /api/app";
            case CREATE_MODULE -> "POST /api/module";
            case CREATE_MODEL -> "POST /api/model";
        };
    }
}