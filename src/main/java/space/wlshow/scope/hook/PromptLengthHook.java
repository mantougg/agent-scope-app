package space.wlshow.scope.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 在每轮 Reasoning 前，把发给 LLM 的 prompt 消息数和总字符长度打到日志；
 * Reasoning 结束后，记录回复长度和 GenerateReason。
 * <p>
 * 用途：快速观察每轮 prompt 是否过长、token 消耗趋势等。
 */
public class PromptLengthHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(PromptLengthHook.class);

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

            log.info("[Hook] PreReasoning: {} messages, ~{} chars",
                    msgCount, totalChars);

            if (totalChars > 8000) {
                log.warn("[Hook] Prompt 较长（~{} chars），可能接近上下文限制",
                        totalChars);
            }
            return Mono.just(event);
        }

        if (event instanceof PostReasoningEvent) {
            log.info("[Hook] PostReasoning: LLM 推理完成");
            return Mono.just(event);
        }

        return Mono.just(event);
    }
}
