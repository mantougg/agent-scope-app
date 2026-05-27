package space.wlshow.scope.todo;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.state.State;

/**
 * TodoManager 的可持久化快照：复用 {@link TodoManager#getState()} / {@link TodoManager#loadState(JsonNode)}
 * 的 JSON 形态，外面再套一层 {@link State} marker，让 {@link io.agentscope.core.session.Session#save} 能吃。
 */
public record TodoState(JsonNode snapshot) implements State {}
