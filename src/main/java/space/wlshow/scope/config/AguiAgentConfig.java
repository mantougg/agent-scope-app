package space.wlshow.scope.config;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.spring.boot.agui.common.AguiProperties;
import io.agentscope.spring.boot.agui.common.ThreadSessionManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import space.wlshow.scope.agent.AgentFactory;
import space.wlshow.scope.session.FileSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * AG-UI starter 的 {@code registerFactory(String, Supplier&lt;Agent&gt;)} 是无参 Supplier，
 * 拿不到 threadId。要按 threadId 加载 {@link FileSession} 必须走更上层的
 * {@code AgentResolver.resolveAgent(agentId, threadId)} —— 但 starter 的
 * {@code AguiWebFluxHandler.Builder} 没有暴露 {@code agentResolver(...)} 注入点。
 *
 * <p>所以这里走"覆盖 ThreadSessionManager"路线：
 * <ol>
 *   <li>{@code agentscope.agui.server-side-memory=true} 让 {@code DefaultAgentResolver}
 *       走 sessionManager 分支；</li>
 *   <li>这里的 Bean 覆盖 starter autoconfig 提供的默认 ThreadSessionManager
 *       （它是 {@code @ConditionalOnMissingBean}）；</li>
 *   <li>子类的 {@code getOrCreateAgent} 把 starter 默认传进来的 Supplier
 *       （那个会去 {@code registry.getAgent} 查表的）丢掉，换成闭包了 threadId 的 Supplier。</li>
 * </ol>
 *
 * <p>同一 threadId 在 ThreadSessionManager 里缓存为同一个 Agent 实例——
 * 进程存活期间 todos+memory 一直在内存里累积，{@link #saveAllOnShutdown()}
 * 退出时统一落盘（Day 6 没接 STATE_DELTA，粗粒度兜底）。
 */
@Configuration
public class AguiAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AguiAgentConfig.class);

    private final Map<String, FileSession> activeSessions = new ConcurrentHashMap<>();

    /**
     * 模型注册只跑一次：放在 factory 里会让每次新建 Agent 都触发
     * ModelRegistry "[primary] 被覆盖" 告警，刷屏。
     */
    @PostConstruct
    public void initModelsOnce() {
        AgentFactory.initModels();
        log.info("[AguiConfig] models initialized");
    }

    @Bean
    public ThreadSessionManager threadSessionManager(AguiProperties props) {
        return new ThreadSessionManager(
                props.getMaxThreadSessions(), props.getSessionTimeoutMinutes()) {
            @Override
            public Agent getOrCreateAgent(
                    String threadId, String agentId, Supplier<Agent> ignored) {
                return super.getOrCreateAgent(threadId, agentId, () -> buildForThread(threadId));
            }
        };
    }

    private Agent buildForThread(String threadId) {
        FileSession session = activeSessions.computeIfAbsent(threadId, FileSession::loadOrNew);
        log.info("[AguiConfig] build agent for thread={}, todos={}",
                threadId, session.todos.size());
        return AgentFactory.buildAnalystWithTools(session.todos, session.memory);
    }

    @PreDestroy
    public void saveAllOnShutdown() {
        activeSessions.forEach((id, session) -> {
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
        // Spring 6 / Servlet 6 起，allowCredentials=true 与 addAllowedMethod("*") 同时存在时
        // 部分 Chrome 版本会拒，必须显式列方法
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
