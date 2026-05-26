package space.wlshow.scope.todo;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

/**
 * 一项待办，不可变。状态变更通过 withXxx 返回新实例，由 TodoManager 替换。
 * payload 是即将下发前端的 JSON（Day 6 才真用）。
 */
public record TodoItem(
        String id,
        TodoType type,
        String targetName,
        JsonNode payload,
        TodoStatus status,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
    public TodoItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static TodoItem newPending(String id, TodoType type, String targetName, JsonNode payload) {
        Instant now = Instant.now();
        return new TodoItem(id, type, targetName, payload, TodoStatus.PENDING, null, now, now);
    }

    public TodoItem withStatus(TodoStatus next, String err) {
        return new TodoItem(id, type, targetName, payload, next, err, createdAt, Instant.now());
    }

    public TodoItem withPayload(JsonNode newPayload) {
        return new TodoItem(id, type, targetName, newPayload, status, errorMessage,
                createdAt, Instant.now());
    }
}