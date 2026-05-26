package space.wlshow.scope.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.todo.TodoManager;
import space.wlshow.scope.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 单文件会话：只持久化 TodoManager，Memory 每次进程启动从空开始。
 * 重启后 LLM 第一句话需要调 list_todos 看现状（已在 prompt 里约束）。
 */
public class FileSession {

    private static final Logger log = LoggerFactory.getLogger(FileSession.class);
    private static final Path BASE = Path.of("data", "sessions");

    public final String id;
    public final TodoManager todos;
    public final Memory memory;          // 每次新建，重启后从空起

    public FileSession(String id, TodoManager todos, Memory memory) {
        this.id = id;
        this.todos = todos;
        this.memory = memory;
    }

    public static FileSession loadOrNew(String id) {
        Path f = BASE.resolve(id + ".json");
        TodoManager todos = new TodoManager();
        Memory memory = new InMemoryMemory();
        if (Files.exists(f)) {
            try {
                JsonNode root = Json.tree(Files.newInputStream(f));
                todos.loadState(root.path("todos"));
                log.info("[Session] LOAD {} todos={}", id, todos.size());
            } catch (IOException e) {
                throw new IllegalStateException("Session 加载失败: " + f, e);
            }
        } else {
            log.info("[Session] NEW {}", id);
        }
        return new FileSession(id, todos, memory);
    }

    public void save() {
        try {
            Files.createDirectories(BASE);
            ObjectNode root = Json.mapper().createObjectNode();
            root.set("todos", todos.getState());
            Path f = BASE.resolve(id + ".json");
            Files.writeString(f, Json.writePretty(root));
            log.info("[Session] SAVED {} -> {}", id, f);
        } catch (IOException e) {
            throw new IllegalStateException("Session 保存失败", e);
        }
    }
}