package space.wlshow.scope.agui;

import io.agentscope.core.agui.event.AguiEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import space.wlshow.scope.config.AguiAgentConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@CrossOrigin(origins = "http://localhost:5173")
public class StateStreamController {

    private static final Logger log = LoggerFactory.getLogger(StateStreamController.class);

    private final AguiAgentConfig aguiConfig;

    /** per-threadId 的 sink；TodoChangeListener 写、SSE 订阅者读。 */
    private final Map<String, Sinks.Many<AguiEvent>> sinks = new ConcurrentHashMap<>();

    public StateStreamController(AguiAgentConfig aguiConfig) {
        this.aguiConfig = aguiConfig;
    }

    /** 由 AguiAgentConfig 在构造 Agent 时调，拿到对应 threadId 的 sink。 */
    public Sinks.Many<AguiEvent> sinkFor(String threadId) {
        return sinks.computeIfAbsent(threadId,
                id -> Sinks.many().multicast().onBackpressureBuffer(128, false));
    }

    @GetMapping(path = "/agui/state-stream/{threadId}",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AguiEvent>> stream(@PathVariable("threadId") String threadId) {
        log.info("[StateStream] subscribe thread={}", threadId);
        // 确保 session 存在 + bridge 挂好（但不在这里发 snapshot——订阅者还没连上）
        aguiConfig.touchThread(threadId);
        AguiStateBridge bridge = aguiConfig.getBridge(threadId);

        Sinks.Many<AguiEvent> sink = sinkFor(threadId);
        return sink.asFlux()
                .doOnSubscribe(s -> {
                    // 订阅者已连上 SSE，此时发 snapshot 才不会丢失
                    if (bridge != null) {
                        bridge.snapshotNow();
                    }
                })
                .map(ev -> ServerSentEvent.<AguiEvent>builder()
                        .event(ev.getType().name())  // STATE_SNAPSHOT / STATE_DELTA
                        .data(ev)
                        .build())
                .doOnCancel(() -> log.info("[StateStream] unsubscribe thread={}", threadId));
    }
}