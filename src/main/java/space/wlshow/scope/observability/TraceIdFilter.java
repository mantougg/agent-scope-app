package space.wlshow.scope.observability;

import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.UUID;

@Component
@Order(-100)
public class TraceIdFilter implements WebFilter {

    public static final String KEY = "traceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Phase 3a 阶段没接 OTel，先用 UUID 兜底生成全 32 位 traceId（去掉横线）。
        // Phase 3b 接 OTel 后，本类整段替换为读 Span.current()（见 §7.4）。
        String tid = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        if (tid == null || tid.isBlank()) {
            tid = UUID.randomUUID().toString().replace("-", "");
        }
        exchange.getResponse().getHeaders().add("X-Trace-Id", tid);

        final String finalTid = tid;
        return chain.filter(exchange)
                .contextWrite(Context.of(KEY, finalTid))
                .doFirst(() -> {
                    MDC.put(KEY, finalTid);
                    MDC.put("traceIdShort", finalTid.substring(0, Math.min(8, finalTid.length())));
                })
                .doFinally(s -> {
                    MDC.remove(KEY);
                    MDC.remove("traceIdShort");
                });
    }
}