package space.wlshow.scope.config;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.processor.AgentResolver;
import io.agentscope.core.agui.processor.AguiRequestProcessor;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.spring.boot.agui.common.AguiProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import space.wlshow.scope.agent.AgentFactory;
import space.wlshow.scope.agui.AguiStateBridge;
import space.wlshow.scope.agui.StateStreamController;
import space.wlshow.scope.observability.Stage;
import space.wlshow.scope.session.FileSession;

import java.util.List;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自装 {@code /agui/run} 路由 + 自定义 {@link AgentResolver}，绕开 starter 默认行为的两个死路：
 *
 * <ol>
 *   <li>{@code server-side-memory=true} 时，{@code DefaultAgentResolver} 走 sessionManager，
 *       但 {@code AguiRequestProcessor.extractLatestUserMessage} 只挑 {@code role="user"} 的消息
 *       (源码：AguiRequestProcessor.java:170-183)。HITL 续跑前端推的
 *       {@code role:"tool"} 回填被静默吃掉，agent 拿到老 user message 触发
 *       {@code IllegalStateException("Pending tool calls exist without results")}，
 *       被 AguiAgentAdapter 的 {@code onErrorResume} 兜底成空响应。</li>
 *   <li>{@code server-side-memory=false} 时，{@code DefaultAgentResolver}
 *       只走 {@code registry.getAgent(agentId)}，<b>拿不到 threadId</b>，
 *       没法按线程分发对应的 {@link FileSession#todos}。</li>
 * </ol>
 *
 * <p>本类的做法：用 {@link AguiRequestProcessor} 直接构造一个 webflux 路由，
 * 注入自家 {@link ThreadAgentResolver}：
 *
 * <ul>
 *   <li>{@code resolveAgent(agentId, threadId)} 拿到 threadId 后从 {@link #activeSessions}
 *       加载/复用 {@link FileSession}，把它的 TodoManager 喂给新 Agent 的 Toolkit。
 *       每次请求都新建 Agent + 全新 {@link InMemoryMemory}，让 agent 的 memory 完全
 *       由前端送过来的 messages 数组重建——这样既不会跨请求累积，
 *       也保证 HITL 的 {@code role:"tool"} 回填能完整到达 {@link io.agentscope.core.ReActAgent#doCall} 的
 *       pending-tools 检查路径。</li>
 *   <li>{@code hasMemory(threadId)} 恒返回 false → 跳过 {@code extractLatestUserMessage},
 *       前端送的完整 messages 透传到 {@link io.agentscope.core.agui.adapter.AguiAgentAdapter}。</li>
 * </ul>
 *
 * <p>路由用 {@code @Order(HIGHEST_PRECEDENCE)} 优先于 starter 自动装配的
 * {@code aguiRoutes}（同一 path），{@link RouterFunction} 在 webflux 里按 ordered stream
 * 顺序合并，first-match 胜出。
 */
@Configuration
public class AguiAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AguiAgentConfig.class);

    private final Map<String, FileSession> activeSessions = new ConcurrentHashMap<>();
    /** per-threadId 的 bridge，StateStreamController 在 doOnSubscribe 时用。 */
    private final Map<String, AguiStateBridge> bridges = new ConcurrentHashMap<>();

    private final ObjectProvider<StateStreamController> stateStreamProvider;
    // ObjectProvider 解 AguiAgentConfig <-> StateStreamController 的循环依赖

    public AguiAgentConfig(ObjectProvider<StateStreamController> stateStreamProvider) {
        this.stateStreamProvider = stateStreamProvider;
    }

    /** StateStreamController 在订阅者连上后拿 bridge 发 snapshot。 */
    public AguiStateBridge getBridge(String threadId) {
        return bridges.get(threadId);
    }

    /**
     * 模型注册只跑一次；放进 resolver 里会让每次新建 Agent 都触发
     * ModelRegistry "[primary] 被覆盖" 告警，刷屏。
     */
    @PostConstruct
    public void initModelsOnce() {
        AgentFactory.initModels();
        log.info("[AguiConfig] models initialized");
    }

    /** 前端订阅 /agui/state-stream/{threadId} 时调，确保 session + bridge 就位。 */
    public void touchThread(String threadId) {
        FileSession session = activeSessions.computeIfAbsent(threadId, FileSession::loadOrNew);
        StateStreamController ctrl = stateStreamProvider.getIfAvailable();
        if (ctrl == null) return;
        bridges.computeIfAbsent(threadId, id -> {
            AguiStateBridge b = new AguiStateBridge(id, session.todos, ctrl.sinkFor(id));
            session.todos.addListenerIfAbsent(b);
            log.info("[AguiConfig] state bridge created thread={}", id);
            return b;
        });
    }

    /**
     * 自家 /agui/run 路由：用 {@link ThreadAgentResolver} 绕开 starter 的两个死路。
     * {@code @Order(HIGHEST_PRECEDENCE)} 让它在 starter 自动装配的 {@code aguiRoutes} 之前
     * 被 {@code RouterFunctionMapping} 串入合并链，相同 path first-match 胜出。
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public RouterFunction<ServerResponse> scopeAguiRunRoutes(AguiProperties props) {
        AguiAdapterConfig adapterConfig =
                AguiAdapterConfig.builder()
                        .defaultAgentId(props.getDefaultAgentId())
                        .toolMergeMode(props.getDefaultToolMergeMode())
                        .runTimeout(props.getRunTimeout())
                        .emitStateEvents(props.isEmitStateEvents())
                        .emitToolCallArgs(props.isEmitToolCallArgs())
                        .enableReasoning(props.isEnableReasoning())
                        .build();
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder()
                        .agentResolver(new ThreadAgentResolver())
                        .config(adapterConfig)
                        .build();
        AguiEventEncoder encoder = new AguiEventEncoder();
        String runPath = props.getPathPrefix() + "/run";
        log.info("[AguiConfig] mounting custom AGUI run route at {}", runPath);

        return RouterFunctions.route()
                .POST(
                        runPath,
                        req ->
                                req.bodyToMono(RunAgentInput.class)
                                        .flatMap(input -> handleRun(input, processor, encoder)))
                .build();
    }

    private Mono<ServerResponse> handleRun(
            RunAgentInput input, AguiRequestProcessor processor, AguiEventEncoder encoder) {
        try {
            // ── Day 7 §6.4 INPUT stage：在请求入口同步线程上记 threadId / runId / 最后 user 消息长度。
            // threadId/runId 也顺手 put 进 MDC，让同线程随后跑的工具/SchemaValidator 都带上。
            // 跨 Reactor scheduler 时 MDC 会丢——lesson §6.3.2 已声明本课不深入。
            MDC.put("threadId", input.getThreadId());
            MDC.put("runId", input.getRunId());
            String lastUserText = lastUserText(input.getMessages());
            Stage.run(Stage.INPUT, () ->
                    log.info("[Input] thread={} run={} userTextLen={}",
                            input.getThreadId(), input.getRunId(), lastUserText.length()));

            AguiRequestProcessor.ProcessResult result = processor.process(input, null, null);
            Flux<ServerSentEvent<String>> sse =
                    result.events()
                            .map(
                                    ev ->
                                            ServerSentEvent.<String>builder()
                                                    .data(encoder.encodeToJson(ev).trim())
                                                    .build())
                            .doOnCancel(
                                    () -> {
                                        log.info(
                                                "[AguiConfig] SSE stream cancelled run={},"
                                                        + " interrupting agent",
                                                input.getRunId());
                                        result.agent().interrupt();
                                    })
                            .doFinally(
                                    s -> {
                                        MDC.remove("threadId");
                                        MDC.remove("runId");
                                    });
            return ServerResponse.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(sse, ServerSentEvent.class);
        } catch (Exception e) {
            log.error("[AguiConfig] handleRun failed: {}", e.toString(), e);
            MDC.remove("threadId");
            MDC.remove("runId");
            return ServerResponse.status(500).bodyValue("Error: " + e.getMessage());
        }
    }

    /** 找前端 messages 数组里最后一条 role=user 的 content；HITL 续跑时是 role=tool，返回空串。 */
    private static String lastUserText(List<AguiMessage> messages) {
        if (messages == null) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            AguiMessage m = messages.get(i);
            if ("user".equalsIgnoreCase(m.getRole())) {
                String c = m.getContent();
                return c == null ? "" : c;
            }
        }
        return "";
    }

    /**
     * 每次请求都新建 Agent + 全新 Memory：让 agent.memory 完全由前端 messages 重建，
     * 既避免跨请求累积，也保证 HITL 的 {@code role:"tool"} 回填一路到 agent 的 doCall。
     * TodoManager 通过 {@link #activeSessions} 按 threadId 持久存活，工具能看见累计待办。
     */
    private class ThreadAgentResolver implements AgentResolver {

        @Override
        public Agent resolveAgent(String agentId, String threadId) {
            FileSession session = activeSessions.computeIfAbsent(threadId, FileSession::loadOrNew);

            // 兜底挂一次 bridge（前端订阅 state-stream 通常已经挂过；首次走 /agui/run 但还没订阅时这里补）
            StateStreamController ctrl = stateStreamProvider.getIfAvailable();
            if (ctrl != null) {
                bridges.computeIfAbsent(
                        threadId,
                        id -> {
                            AguiStateBridge b =
                                    new AguiStateBridge(id, session.todos, ctrl.sinkFor(id));
                            session.todos.addListenerIfAbsent(b);
                            log.info("[AguiConfig] state bridge created thread={}", id);
                            return b;
                        });
            }
            log.info(
                    "[AguiConfig] build agent for thread={}, todos={}",
                    threadId,
                    session.todos.size());
            Memory freshMemory = new InMemoryMemory();
            return AgentFactory.buildAnalystWithTools(session.todos, freshMemory);
        }

        @Override
        public boolean hasMemory(String threadId) {
            // 关键：报 false 让 AguiRequestProcessor 跳过 extractLatestUserMessage，
            // 前端送的完整 messages 数组（含 HITL 的 role=tool 回填）才会落到 agent.stream(msgs)。
            return false;
        }
    }

    @PreDestroy
    public void saveAllOnShutdown() {
        activeSessions.forEach(
                (id, session) -> {
                    try {
                        session.save();
                    } catch (Exception e) {
                        log.warn("[AguiConfig] save session {} failed: {}", id, e.toString());
                    }
                });
    }

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.addAllowedOrigin("http://localhost:5173");
        cfg.addAllowedMethod("GET");
        cfg.addAllowedMethod("POST");
        cfg.addAllowedMethod("OPTIONS");
        cfg.addAllowedHeader("*");
        cfg.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/agui/**", cfg);
        return new CorsWebFilter(source);
    }
}
