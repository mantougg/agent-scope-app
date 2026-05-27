package space.wlshow.scope.session;

import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.todo.TodoChangeListener;
import space.wlshow.scope.todo.TodoItem;
import space.wlshow.scope.todo.TodoManager;
import space.wlshow.scope.todo.TodoStatus;

import java.nio.file.Path;

/**
 * RC2 改造：内部用 {@link JsonSession} + {@link SimpleSessionKey} 持久化 {@link TodoManager} 的状态。
 *
 * <p>对外 API 与 1.0.12 兼容：仍然暴露 {@code id} / {@code todos} / {@code memory} / {@code save()}。
 * <p>关键差异：构造时挂一个 {@link AutoSaveListener}，{@link TodoManager} 任意变更
 * （add/status/clear）都<b>即时落盘</b>。`@PreDestroy` / shutdown hook 现在只是兜底，
 * 解决了 1.0.12 时代 Ctrl+C 退出 sessions 目录可能为空的回归问题。
 *
 * <p>边界（参 docs/agentscope-1.1.0-RC2-migration-plan.md §3）：
 * 本 Session 只持久化 TodoManager，<b>不</b>持久化 Memory。AG-UI 路径必须保持
 * {@code ThreadAgentResolver.hasMemory()=false} 让 memory 由前端 messages 重建，否则会重新
 * 撞回 {@code AguiRequestProcessor.extractLatestUserMessage} 吃掉 {@code role:"tool"} 的死路。
 */
public class FileSession {

    private static final Logger log = LoggerFactory.getLogger(FileSession.class);
    private static final Path BASE = Path.of("data", "sessions");
    /** 单例 JsonSession：所有 thread 共享同一个目录。 */
    private static final JsonSession JSON_SESSION = new JsonSession(BASE);

    public final String id;
    public final SessionKey key;
    public final TodoManager todos;
    public final Memory memory;          // 每次新建，重启后从空起

    private FileSession(String id, TodoManager todos, Memory memory) {
        this.id = id;
        this.key = SimpleSessionKey.of(id);
        this.todos = todos;
        this.memory = memory;
    }

    public static FileSession loadOrNew(String id) {
        TodoManager todos = new TodoManager();
        Memory memory = new InMemoryMemory();
        SessionKey key = SimpleSessionKey.of(id);
        if (JSON_SESSION.exists(key)) {
            todos.loadFrom(JSON_SESSION, key);
            log.info("[Session] LOAD {} todos={}", id, todos.size());
        } else {
            log.info("[Session] NEW {}", id);
        }
        FileSession s = new FileSession(id, todos, memory);
        // 即时落盘 listener：任意 todo 变更立即写盘
        todos.addListenerIfAbsent(new AutoSaveListener(s));
        return s;
    }

    /** 兜底全量落盘（shutdown hook / @PreDestroy 调）。日常变更由 AutoSaveListener 即时落盘。 */
    public void save() {
        todos.saveTo(JSON_SESSION, key);
        log.info("[Session] SAVED {}", id);
    }

    /**
     * 任意 TodoManager 变更都触发一次 saveTo。
     * <p>{@link TodoManager#addListenerIfAbsent} 按 class 去重，保证一个 FileSession 只挂一份。
     */
    private static final class AutoSaveListener implements TodoChangeListener {
        private final FileSession session;

        AutoSaveListener(FileSession session) {
            this.session = session;
        }

        @Override
        public void onCreate(TodoItem item) {
            persist();
        }

        @Override
        public void onStatusChange(String id, TodoStatus from, TodoStatus to, String err) {
            persist();
        }

        @Override
        public void onClear() {
            persist();
        }

        private void persist() {
            try {
                session.todos.saveTo(JSON_SESSION, session.key);
            } catch (Exception e) {
                log.warn("[Session] auto save failed: {}", e.toString());
            }
        }
    }
}
