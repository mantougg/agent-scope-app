package space.wlshow.scope.tool;

import org.junit.jupiter.api.Test;
import space.wlshow.scope.todo.TodoManager;
import space.wlshow.scope.todo.TodoType;

import static org.junit.jupiter.api.Assertions.*;

class FrontendCreateToolsTest {

    @Test
    void createApp_addsPendingTodo() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools tools = new FrontendCreateTools(mgr);

        String msg = tools.createApp("leaveMgr", "请假管理", "23");

        assertTrue(msg.contains("todo-1"));
        assertEquals(1, mgr.size());
        assertEquals(TodoType.CREATE_APP, mgr.get("todo-1").type());
    }

    @Test
    void createModel_acceptsFieldsJson() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools tools = new FrontendCreateTools(mgr);

        String fields = """
            [{"comment":"主键","name":"id","dataType":"long","usage":"primary",
              "relateModelType":"","subs":null}]
            """;
        String msg = tools.createModel("employee", "ENTITY", "yuangong",
                "t_employee", "", fields);

        assertTrue(msg.contains("fields=1"));
    }

    @Test
    void createModel_malformedFieldsJson_throws() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools tools = new FrontendCreateTools(mgr);

        assertThrows(IllegalArgumentException.class, () ->
                tools.createModel("employee", "ENTITY", "yuangong", "t_employee", "",
                        "this is not json"));
    }

    @Test
    void createApp_rejectsBadName() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools tools = new FrontendCreateTools(mgr);

        // name 是中文，违反 pattern ^[a-zA-Z][a-zA-Z0-9]*$
        String msg = tools.createApp("请假管理", "请假管理", "23");
        assertTrue(msg.startsWith("ERROR:"), "期望 ERROR 开头，实际：" + msg);
        assertEquals(0, mgr.size(), "脏数据不应落 TodoManager");
    }
}