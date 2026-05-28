package space.wlshow.scope.todo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.StateModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.observability.Stage;
import space.wlshow.scope.util.Json;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 待办列表 + 状态机 + 监听器 + {@link StateModule}（RC2 起即时落盘）。
 * <p>线程安全：{@code Toolkit(parallel=true)} 下多个 {@code create_*} 工具会并发调进来，
 * 读写 {@link #items} 的方法统一用 {@code synchronized}（锁实例本身）串行化。
 * {@code add()} 末尾触发的 {@code AutoSaveListener.persist() → getState()} 重入同一把锁，
 * Java synchronized 可重入，安全；{@code AguiStateBridge.emit()} 锁的是自己，不会与本锁死锁。
 */
public class TodoManager implements StateModule {

    private static final Logger log = LoggerFactory.getLogger(TodoManager.class);

    private final LinkedHashMap<String, TodoItem> items = new LinkedHashMap<>();
    private final AtomicLong seq = new AtomicLong(0);
    private final List<TodoChangeListener> listeners = new ArrayList<>();

    public synchronized void addListener(TodoChangeListener l) { listeners.add(l); }

    /** 新增 PENDING 待办，返回生成的 id（"todo-1"、"todo-2"...）。 */
    public synchronized TodoItem add(TodoType type, String targetName, JsonNode payload) {
        String id = "todo-" + seq.incrementAndGet();
        TodoItem it = TodoItem.newPending(id, type, targetName, payload);
        items.put(id, it);
        Stage.run(Stage.TODO_UPDATE, () ->
                log.info("[Todo] CREATE id={} type={} target={}", id, type, targetName));
        listeners.forEach(l -> l.onCreate(it));
        return it;
    }

    public synchronized Optional<TodoItem> next() {
        return items.values().stream().filter(it -> it.status() == TodoStatus.PENDING).findFirst();
    }

    public synchronized TodoItem get(String id) {
        TodoItem it = items.get(id);
        if (it == null) throw new NoSuchElementException("没有此待办: " + id);
        return it;
    }

    public void markRunning(String id) { transit(id, TodoStatus.RUNNING, null); }
    public void markSuccess(String id) { transit(id, TodoStatus.SUCCESS, null); }
    public void markFailed(String id, String err) { transit(id, TodoStatus.FAILED, err); }

    private synchronized void transit(String id, TodoStatus to, String err) {
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
        TodoStatus from = cur.status();
        Stage.run(Stage.TODO_UPDATE, () ->
                log.info("[Todo] {} {} -> {} {}",
                        id, from, to, err == null ? "" : "err=" + err));
        listeners.forEach(l -> l.onStatusChange(id, from, to, err));
    }

    public synchronized List<TodoItem> snapshot() { return List.copyOf(items.values()); }

    public synchronized int size() { return items.size(); }

    public synchronized void clear() {
        items.clear();
        seq.set(0);
        Stage.run(Stage.TODO_UPDATE, () -> log.info("[Todo] CLEAR"));
        listeners.forEach(TodoChangeListener::onClear);
    }

    // --- StateModule（Day 5 接通） ---

    public synchronized JsonNode getState() {
        ObjectNode root = Json.mapper().createObjectNode();
        ArrayNode arr = root.putArray("items");
        items.values().forEach(it -> arr.add(Json.mapper().valueToTree(it)));
        root.put("seq", seq.get());
        return root;
    }

    public synchronized void loadState(JsonNode state) {
        items.clear();
        seq.set(state.path("seq").asLong(0));
        for (JsonNode n : state.path("items")) {
            TodoItem it = Json.mapper().convertValue(n, TodoItem.class);
            items.put(it.id(), it);
        }
        log.info("[Todo] LOADED size={} seq={}", items.size(), seq.get());
    }

    public synchronized void replacePayload(String id, JsonNode newPayload) {
        TodoItem cur = get(id);
        if (cur.status() != TodoStatus.PENDING) {
            throw new IllegalStateException("非 PENDING 不可改 payload: " + id);
        }
        TodoItem next = cur.withPayload(newPayload);
        items.put(id, next);
        log.info("[Todo] PAYLOAD-REPLACE id={}", id);
    }

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

    public synchronized boolean addListenerIfAbsent(TodoChangeListener l) {
        if (listeners.stream().anyMatch(x -> x.getClass() == l.getClass())) return false;
        listeners.add(l);
        return true;
    }

    // --- StateModule（RC2 接通 JsonSession） ---

    /** {@inheritDoc}
     * 把 {@link #getState()} 包成 {@link TodoState} 写到 {@code <sessionDir>/todos.json}。
     */
    @Override
    public void saveTo(Session session, SessionKey sessionKey) {
        session.save(sessionKey, "todos", new TodoState(getState()));
    }

    /** {@inheritDoc}
     * 从 {@code <sessionDir>/todos.json} 读出 {@link TodoState}，没有就什么也不做。
     */
    @Override
    public void loadFrom(Session session, SessionKey sessionKey) {
        session.get(sessionKey, "todos", TodoState.class)
                .ifPresent(st -> loadState(st.snapshot()));
    }
}