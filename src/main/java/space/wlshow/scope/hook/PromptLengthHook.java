package space.wlshow.scope.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;
import space.wlshow.scope.observability.Stage;

import java.util.List;

/**
 * 在每轮 Reasoning 前/后埋 Day 7 §6.4 的 LLM_CALL stage：
 * - PreReasoning: 打 model + msgCount + promptChars；实例字段记下起始时间
 * - PostReasoning: 打 latencyMs；过 8000 chars 时再补一条 WARN
 *
 * <p>用实例字段（不是 ThreadLocal）记 startNanos：Reactor 会把 Pre/Post 派到不同的
 * boundedElastic worker 线程上，ThreadLocal 会丢；而 AS-Java 1.0.12 的 ReActAgent
 * 的 reasoning 循环对同一 hook 实例是<b>严格串行</b>（一个 Pre 一定先于对应 Post 完成），
 * 因此用实例字段安全。本仓库每个 Agent 都新建一个 hook 实例（{@code AgentFactory}），
 * 不会发生跨请求复用。
 */
public class PromptLengthHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(PromptLengthHook.class);
    private volatile long startNanos = -1L;

    @Override
    public int priority() {
        return 50;  // 比默认 100 高优先级
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {

        if (event instanceof PreReasoningEvent e) {
            List<Msg> messages = e.getInputMessages();
            int msgCount = messages.size();

            // 粗略估算 prompt 字符长度（把所有 TextBlock 的文本拼起来）
            int totalChars = messages.stream()
                    .mapToInt(m -> {
                        String text = m.getTextContent();
                        return text != null ? text.length() : 0;
                    })
                    .sum();

            this.startNanos = System.nanoTime();
            String prev = MDC.get("stage");
            MDC.put("stage", Stage.LLM_CALL);
            try {
                log.info("[LLM] PreReasoning model={} msgs={} promptChars={}",
                        e.getModelName(), msgCount, totalChars);
                if (totalChars > 8000) {
                    log.warn("[LLM] Prompt 较长（~{} chars），可能接近上下文限制", totalChars);
                }
            } finally {
                if (prev == null) MDC.remove("stage"); else MDC.put("stage", prev);
            }
            return Mono.just(event);
        }

        if (event instanceof PostReasoningEvent e) {
            long startNs = this.startNanos;
            long latencyMs = startNs <= 0 ? -1 : (System.nanoTime() - startNs) / 1_000_000L;
            this.startNanos = -1L;

            String prev = MDC.get("stage");
            MDC.put("stage", Stage.LLM_CALL);
            try {
                log.info("[LLM] PostReasoning model={} latency={}ms",
                        e.getModelName(), latencyMs);
            } finally {
                if (prev == null) MDC.remove("stage"); else MDC.put("stage", prev);
            }
            return Mono.just(event);
        }

        return Mono.just(event);
    }
}
