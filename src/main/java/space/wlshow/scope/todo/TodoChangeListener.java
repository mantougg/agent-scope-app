package space.wlshow.scope.todo;

import com.fasterxml.jackson.databind.JsonNode;

/** Day 7 给 AG-UI STATE_DELTA 桥用；今天单测里也用得上。 */
public interface TodoChangeListener {
    default void onCreate(TodoItem item) {}
    default void onStatusChange(String id, TodoStatus from, TodoStatus to, String err) {}
    default void onClear() {}
    /** 待办从池里被移除（仅 PENDING 允许）。Day 2026-05-28 新增。 */
    default void onRemove(String id) {}
    /** 待办 payload 被原地替换（仅 PENDING 允许）。Day 2026-05-28 新增。 */
    default void onPayloadReplace(String id, JsonNode newPayload) {}
}