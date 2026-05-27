package space.wlshow.scope.observability;

import org.slf4j.MDC;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * 让一段代码在 MDC.stage=<name> 下执行，结束后自动恢复（不只是 remove，
 * 嵌套 stage 时不会把外层抹掉）。
 *
 * 用法：Stage.run(Stage.LLM_CALL, () -> agent.call(msg).block());
 */
public final class Stage {

    public static final String INPUT = "INPUT";
    public static final String LLM_CALL = "LLM_CALL";
    public static final String TOOL_CALL = "TOOL_CALL";
    public static final String SCHEMA_VALIDATE = "SCHEMA_VALIDATE";
    public static final String TODO_UPDATE = "TODO_UPDATE";
    public static final String FRONTEND_DISPATCH = "FRONTEND_DISPATCH";
    public static final String FRONTEND_CALLBACK = "FRONTEND_CALLBACK";

    public static void run(String name, Runnable r) {
        String prev = MDC.get("stage");
        MDC.put("stage", name);
        try { r.run(); } finally {
            if (prev == null) MDC.remove("stage"); else MDC.put("stage", prev);
        }
    }

    public static <T> T call(String name, Supplier<T> s) {
        String prev = MDC.get("stage");
        MDC.put("stage", name);
        try { return s.get(); } finally {
            if (prev == null) MDC.remove("stage"); else MDC.put("stage", prev);
        }
    }

    public static <T> T callChecked(String name, Callable<T> c) throws Exception {
        String prev = MDC.get("stage");
        MDC.put("stage", name);
        try { return c.call(); } finally {
            if (prev == null) MDC.remove("stage"); else MDC.put("stage", prev);
        }
    }

    /**
     * 把任意参数列表算成一个 8 位 hex 短哈希，用于在 TOOL_CALL 日志里识别同一组实参的重复调用，
     * 避免把整段 payload（可能是大段 JSON）直接 println 到日志里。
     */
    public static String argsHash(Object... args) {
        return String.format("%08x", Objects.hash(args) & 0xFFFFFFFFL);
    }

    private Stage() {}
}