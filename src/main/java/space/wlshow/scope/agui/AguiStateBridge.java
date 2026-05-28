package space.wlshow.scope.agui;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEvent.JsonPatchOperation;
import io.agentscope.core.agui.event.AguiEvent.StateDelta;
import io.agentscope.core.agui.event.AguiEvent.StateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;
import space.wlshow.scope.todo.TodoChangeListener;
import space.wlshow.scope.todo.TodoItem;
import space.wlshow.scope.todo.TodoManager;
import space.wlshow.scope.todo.TodoStatus;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 TodoManager 变更映射到 AG-UI STATE_* 事件，写入 per-threadId 的
 * {@link Sinks.Many}。SSE 端点（{@link space.wlshow.scope.agui.StateStreamController}）
 * 订阅同一个 Sink，把事件推到前端。
 *
 * <p>一个 thread 一个实例（per-threadId），保证发到对应订阅者。
 * runId 在 starter 内部由 AguiAgentAdapter 持有，旁路流拿不到，统一用 "state-stream"。
 */
public class AguiStateBridge implements TodoChangeListener {

    private static final Logger log = LoggerFactory.getLogger(AguiStateBridge.class);
    private static final String SIDE_RUN_ID = "state-stream";

    private final String threadId;
    private final TodoManager todos;
    private final Sinks.Many<AguiEvent> sink;

    public AguiStateBridge(String threadId, TodoManager todos, Sinks.Many<AguiEvent> sink) {
        this.threadId = threadId;
        this.todos = todos;
        this.sink = sink;
    }

    /** 订阅者连接建立时调，发一份完整状态。 */
    public void snapshotNow() {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("todos", todos.snapshot().stream().map(this::serialize).toList());
        emit(new StateSnapshot(threadId, SIDE_RUN_ID, snap));
        log.debug("[StateBridge] STATE_SNAPSHOT thread={} size={}", threadId, todos.size());
    }

    @Override
    public void onCreate(TodoItem item) {
        emit(new StateDelta(threadId, SIDE_RUN_ID,
                List.of(JsonPatchOperation.add("/todos/-", serialize(item)))));
        log.debug("[StateBridge] STATE_DELTA add id={}", item.id());
    }

    @Override
    public void onStatusChange(String id, TodoStatus from, TodoStatus to, String err) {
        var ops = new java.util.ArrayList<JsonPatchOperation>();
        ops.add(JsonPatchOperation.replace("/todos/id=" + id + "/status", to.name()));
        if (err != null) {
            ops.add(JsonPatchOperation.add("/todos/id=" + id + "/errorMessage", err));
        }
        emit(new StateDelta(threadId, SIDE_RUN_ID, ops));
        log.debug("[StateBridge] STATE_DELTA {} {}->{}", id, from, to);
    }

    @Override
    public void onClear() {
        emit(new StateDelta(threadId, SIDE_RUN_ID,
                List.of(JsonPatchOperation.replace("/todos", List.of()))));
        log.debug("[StateBridge] STATE_DELTA clear");
    }

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

    /**
     * Toolkit 配 {@code parallel=true} 时多个 {@code create_*} 在不同线程并发调
     * {@link TodoManager#add}，进而并发触发本方法。{@code Sinks.many().multicast()}
     * 不是线程安全的——并发 {@code tryEmitNext} 会返回 {@code FAIL_NON_SERIALIZED}。
     *
     * <p>用 {@code synchronized} 把同一 bridge 实例的发射串行化即可消除该失败；
     * 仅 per-thread bridge 自旋这把锁，不会跨线程争用。
     *
     * <p>仍剩余的失败语义（buffer 满 / sink 已关 / 订阅者已取消）一律 log.warn 丢弃，
     * 绝不把异常往上抛——sink 故障不能把工具调用炸掉，
     * 下一次 STATE_SNAPSHOT（重连或刷新）会兜底重对齐。
     */
    private synchronized void emit(AguiEvent ev) {
        Sinks.EmitResult r = sink.tryEmitNext(ev);
        if (r.isFailure()) {
            log.warn("[StateBridge] emit dropped {} for {}", r, ev.getType());
        }
    }

    private Map<String, Object> serialize(TodoItem it) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", it.id());
        n.put("type", it.type().name());
        n.put("targetName", it.targetName());
        n.put("status", it.status().name());
        n.put("payload", it.payload());   // Jackson 会把 JsonNode 直接序列化
        if (it.errorMessage() != null) n.put("errorMessage", it.errorMessage());
        return n;
    }
}