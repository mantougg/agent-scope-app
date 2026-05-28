package space.wlshow.scope.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import space.wlshow.scope.todo.TodoItem;
import space.wlshow.scope.todo.TodoManager;

import static org.junit.jupiter.api.Assertions.*;

class TodoUpdateToolsTest {

    private static TodoItem seedApp(TodoManager mgr, FrontendCreateTools create) {
        create.createApp("leaveMgr", "请假管理", "23");
        return mgr.snapshot().get(0);
    }

    @Test
    void updateApp_changeLabel_ok() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoUpdateTools update = new TodoUpdateTools(mgr);
        TodoItem app = seedApp(mgr, create);

        String r = update.updateApp("", "员工档案");

        assertTrue(r.contains("APP 已更新"), r);
        JsonNode p = mgr.get(app.id()).payload();
        assertEquals("员工档案", p.path("label").asText());
        assertEquals("leaveMgr", p.path("name").asText());
    }

    @Test
    void updateApp_changeName_ok() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoUpdateTools update = new TodoUpdateTools(mgr);
        TodoItem app = seedApp(mgr, create);

        String r = update.updateApp("staffMgr", "");

        assertTrue(r.contains("APP 已更新"), r);
        assertEquals("staffMgr", mgr.get(app.id()).payload().path("name").asText());
    }

    @Test
    void updateApp_badNamePattern_rejected() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoUpdateTools update = new TodoUpdateTools(mgr);
        TodoItem app = seedApp(mgr, create);

        String r = update.updateApp("员工档案", "");

        assertTrue(r.startsWith("ERROR:"), r);
        assertEquals("leaveMgr", mgr.get(app.id()).payload().path("name").asText());
    }

    @Test
    void updateApp_noAppTodo_rejected() {
        TodoManager mgr = new TodoManager();
        TodoUpdateTools update = new TodoUpdateTools(mgr);

        String r = update.updateApp("x", "x");

        assertTrue(r.startsWith("ERROR:"), r);
    }

    @Test
    void updateApp_multipleAppTodos_rejected() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoUpdateTools update = new TodoUpdateTools(mgr);
        create.createApp("a", "应用一", "23");
        create.createApp("b", "应用二", "23");

        String r = update.updateApp("c", "");

        assertTrue(r.startsWith("ERROR:"), r);
    }

    @Test
    void updateApp_runningTodo_rejected() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoUpdateTools update = new TodoUpdateTools(mgr);
        TodoItem app = seedApp(mgr, create);
        mgr.markRunning(app.id());

        String r = update.updateApp("staffMgr", "");

        assertTrue(r.startsWith("ERROR:"), r);
    }
}
