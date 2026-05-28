package space.wlshow.scope.tool;

import org.junit.jupiter.api.Test;
import space.wlshow.scope.todo.TodoItem;
import space.wlshow.scope.todo.TodoManager;

import static org.junit.jupiter.api.Assertions.*;

class TodoDeleteToolsTest {

    @Test
    void deleteModule_happy() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoDeleteTools del = new TodoDeleteTools(mgr);
        create.createModule("员工管理", "staffMgr", "管理员工档案");

        String r = del.deleteModule("staffMgr");

        assertTrue(r.contains("已删除"), r);
        assertEquals(0, mgr.size());
    }

    @Test
    void deleteModule_unknownId_returnsError() {
        TodoManager mgr = new TodoManager();
        TodoDeleteTools del = new TodoDeleteTools(mgr);

        String r = del.deleteModule("nope");

        assertTrue(r.startsWith("ERROR:"), r);
    }

    @Test
    void deleteModel_happy() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoDeleteTools del = new TodoDeleteTools(mgr);
        create.createModel("employee", "ENTITY", "yuangong", "t_employee", "",
                """
                [{"comment":"主键","name":"id","dataType":"long","usage":"primary",
                  "relateModelType":"","subs":null}]
                """);

        String r = del.deleteModel("employee");

        assertTrue(r.contains("已删除"), r);
        assertEquals(0, mgr.size());
    }

    @Test
    void deleteRunning_rejected() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoDeleteTools del = new TodoDeleteTools(mgr);
        create.createModule("员工管理", "staffMgr", "x");
        TodoItem it = mgr.snapshot().get(0);
        mgr.markRunning(it.id());

        String r = del.deleteModule("staffMgr");

        assertTrue(r.startsWith("ERROR:"), r);
        assertEquals(1, mgr.size(), "running 项不应被删");
    }

    @Test
    void cancelSubmission_clearsAll() {
        TodoManager mgr = new TodoManager();
        FrontendCreateTools create = new FrontendCreateTools(mgr);
        TodoDeleteTools del = new TodoDeleteTools(mgr);
        create.createApp("leaveMgr", "请假管理", "23");
        create.createModule("请假申请", "leaveApply", "x");
        assertEquals(2, mgr.size());

        String r = del.cancelSubmission();

        assertTrue(r.contains("2"), r);
        assertEquals(0, mgr.size());
    }

    @Test
    void cancelSubmission_emptyOk() {
        TodoManager mgr = new TodoManager();
        TodoDeleteTools del = new TodoDeleteTools(mgr);

        String r = del.cancelSubmission();

        assertTrue(r.contains("0") || r.contains("无待办") || r.contains("空"), r);
        assertEquals(0, mgr.size());
    }
}
