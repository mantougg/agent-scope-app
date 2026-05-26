package space.wlshow.scope.todo;

/** Day 7 给 AG-UI STATE_DELTA 桥用；今天单测里也用得上。 */
public interface TodoChangeListener {
    default void onCreate(TodoItem item) {}
    default void onStatusChange(String id, TodoStatus from, TodoStatus to, String err) {}
    default void onClear() {}
}