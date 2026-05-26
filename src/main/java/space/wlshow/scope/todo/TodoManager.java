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