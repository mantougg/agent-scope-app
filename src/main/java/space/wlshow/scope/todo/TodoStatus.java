package space.wlshow.scope.todo;

public enum TodoStatus {
    PENDING, RUNNING, SUCCESS, FAILED;

    /** 是否终态，不能再迁移。 */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED;
    }
}