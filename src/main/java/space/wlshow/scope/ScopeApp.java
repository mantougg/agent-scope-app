package space.wlshow.scope;

import space.wlshow.scope.agent.AgentFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.agent.ParseException;
import space.wlshow.scope.agent.RequirementParser;
import space.wlshow.scope.config.AppConfig;
import space.wlshow.scope.schema.SchemaValidator;
import space.wlshow.scope.session.FileSession;
import space.wlshow.scope.spec.AnalysisResult;
import space.wlshow.scope.util.Json;

import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import static space.wlshow.scope.util.Prompts.analyst;

public class ScopeApp {

    private static final Logger log = LoggerFactory.getLogger(ScopeApp.class);

    static ReActAgent parserAgent = AgentFactory.buildParser();
    static SchemaValidator validator = new SchemaValidator("/schemas/analysis-result.schema.json");
    static RequirementParser parser = new RequirementParser(parserAgent, validator);

    /** Day 5：会话持久化。SCOPE_SESSION 环境变量切换隔离的待办池。 */
    static String sessionId = System.getenv().getOrDefault("SCOPE_SESSION", "default");
    static FileSession session = FileSession.loadOrNew(sessionId);
    static ReActAgent analystWithTools = AgentFactory.buildAnalystWithTools(session.todos, session.memory);

    public static void main(String[] args) {
        log.info("Booting Scope App (REPL model, session={}) ...", sessionId);

        // Shutdown hook 兜底：正常 exit / Ctrl+C 触发；kill -9 不会触发，所以主路径还是 inline save
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { session.save(); } catch (Exception e) { log.warn("shutdown save failed: {}", e.toString()); }
        }));

        ReActAgent agent = AgentFactory.buildAnalyst();
        Scanner sc = new Scanner(System.in, StandardCharsets.UTF_8);

        System.out.println("Scope REPL (session=" + sessionId + ")");
        System.out.println("  exit          退出");
        System.out.println("  /stream       切换流式");
        System.out.println("  /parse <需求>  Day 3 解析");
        System.out.println("  /run <需求>    Day 4/5 工具调度（多轮）");
        System.out.println("  /submit       Day 5 HITL：把所有 PENDING 待办下发前端（需 y/N 确认）");
        System.out.println("  /todos        查看当前 session 的待办列表（不调 LLM）");
        System.out.println();

        boolean stream = false;
        while (true) {
            System.out.println(stream ? "you (stream) > " : "you > ");
            String line = sc.nextLine();
            if (line.isEmpty()) continue;
            if ("exit".equalsIgnoreCase(line)) break;
            if ("/stream".equals(line)) { stream = !stream; continue; }
            if ("/todos".equals(line)) {
                printTodos();
                continue;
            }
            if ("/submit".equals(line)) {
                runSubmit(analystWithTools, session, sc);
                continue;
            }
            if (line.startsWith("/parse ")) {
                String req = line.substring("/parse ".length()).trim();
                if (req.isEmpty()) {
                    System.out.println("用法：/parse <中文需求>");
                    continue;
                }
                try {
                    AnalysisResult result = parser.parse(req);
                    System.out.println("[PARSED]\n" + Json.writePretty(result));
                } catch (ParseException e) {
                    System.out.println("[PARSE-FAIL] " + e.getMessage());
                    e.lastErrors().forEach(err -> System.out.println("  - " + err));
                }
                continue;
            }
            if (line.startsWith("/run ")) {
                String req = line.substring("/run ".length()).trim();
                if (req.isEmpty()) {
                    System.out.println("用法：/run <中文需求>");
                    continue;
                }
                log.info("[USER /run] {}", req);
                try {
                    Msg out = analystWithTools.call(Msg.builder().textContent(req).build())
                            .timeout(AppConfig.timeout())
                            .block();
                    // LLM 可能在 /run 这一轮就自作主张调 submit_to_frontend(confirmed=false)，
                    // 触发挂起。不处理就会把没回填的 tool_use 留在 Memory，下次 agent.call
                    // 会抛 "Pending tool calls exist without results"。这里走和 /submit 同一套
                    // handleSuspend，把 y/N 弹出来正经走完一轮。
                    if (out != null && out.getGenerateReason() == GenerateReason.TOOL_SUSPENDED) {
                        handleSuspend(analystWithTools, session, sc, out);
                    } else {
                        System.out.println("[ASSISTANT] " + (out == null ? "(空)" : out.getTextContent()));
                    }
                    System.out.println();
                    printTodos();
                } catch (Exception e) {
                    log.error("/run failed: {}", e.toString(), e);
                    System.out.println("(error: " + e.getMessage() + ")");
                } finally {
                    // inline save 是主路径：即便 LLM 调用抛异常，已经落进 TodoManager 的待办也要持久化
                    session.save();
                }
                continue;
            }

            log.info("[USER] {}", line);
            Msg req = Msg.builder().textContent(line).build();

            try {
                if (stream) {
                    runStream(agent, req);
                } else {
                    runSync(agent, req);
                }
            } catch (Exception e) {
                log.error("call failed: {}", e.toString(), e);
                System.out.println("(error: " + e.getMessage() + ")");
            }
            System.out.println();
        }
        System.out.println("bye.");
    }

    private static void printTodos() {
        System.out.println("=== Todos (" + session.todos.size() + ") ===");
        session.todos.snapshot().forEach(it -> System.out.printf("  %s  %-15s  %-25s  %s%n",
                it.id(), it.type(), it.targetName(), it.status()));
    }

    /**
     * Day 5 Phase 5 · /submit 链路：让 LLM 调 submit_to_frontend(false) → 框架把
     * ToolSuspendException 转成 GenerateReason.TOOL_SUSPENDED 的 Msg → 我们弹 y/N
     * 确认 → 把 USER_CONFIRMED / USER_REJECTED 作为 ToolResult 回填，LLM 续跑。
     * <p>
     * 注意：sc 必须是 main 里那个共享的 Scanner（不要在这里 new Scanner(System.in)），
     * 否则 Windows 下两个 Scanner 抢 System.in 缓冲区，y/N 经常吞行。
     */
    private static void runSubmit(ReActAgent agent, FileSession session, Scanner sc) {
        log.info("[USER /submit]");
        Msg out;
        try {
            // 文案用"请把所有待办下发前端"而不是"我确认了"，避免 LLM 跳过挂起直接走 confirmed=true
            out = agent.call(Msg.builder().textContent("请把所有待办下发前端").build())
                    .timeout(AppConfig.timeout())
                    .block();
        } catch (Exception e) {
            // AS-Java 1.0.12 框架会把 ToolSuspendException 转成 TOOL_SUSPENDED Msg，正常路径走不到这里；
            // 保留 catch 是为了兜底未来版本如果改成"直接抛"也能给出可读错误而不是栈撕裂。
            log.error("/submit failed: {}", e.toString(), e);
            System.out.println("(error: " + e.getMessage() + ")");
            session.save();
            return;
        }

        try {
            if (out != null && out.getGenerateReason() == GenerateReason.TOOL_SUSPENDED) {
                handleSuspend(agent, session, sc, out);
            } else {
                System.out.println("[ASSISTANT] " + (out == null ? "(空)" : out.getTextContent()));
            }
            printTodos();
        } finally {
            session.save();
        }
    }

    private static void handleSuspend(ReActAgent agent, FileSession session, Scanner sc, Msg suspended) {
        // 1.0.12 把 ToolSuspendException 转成一个 isSuspended()==true 的 ToolResultBlock，
        // 上面已经带好了 id / name，所以不需要再去翻 ToolUseBlock 拿 id。
        ToolResultBlock pending = suspended.getContentBlocks(ToolResultBlock.class).stream()
                .filter(ToolResultBlock::isSuspended)
                .findFirst()
                .orElse(null);
        if (pending == null) {
            log.warn("[Submit] TOOL_SUSPENDED 但没找到 isSuspended() 的 ToolResultBlock");
            System.out.println("[ASSISTANT] " + suspended.getTextContent());
            return;
        }

        String reason = pending.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(b -> ((TextBlock) b).getText())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("(no reason)");

        System.out.println("[CONFIRM?]");
        System.out.println(reason);
        System.out.print("确认下发？(y/N): ");
        String ans = sc.nextLine().trim().toLowerCase();
        String reply = ("y".equals(ans) || "yes".equals(ans)) ? "USER_CONFIRMED" : "USER_REJECTED";
        log.info("[Submit] toolUseId={} reply={}", pending.getId(), reply);

        // 回填 ToolResult：role=TOOL + 同样的 id/name + 文本 USER_CONFIRMED/USER_REJECTED
        Msg toolResultMsg = Msg.builder()
                .role(MsgRole.TOOL)
                .content(ToolResultBlock.of(
                        pending.getId(),
                        pending.getName(),
                        TextBlock.builder().text(reply).build()))
                .build();

        try {
            Msg next = agent.call(toolResultMsg).timeout(AppConfig.timeout()).block();
            System.out.println("[ASSISTANT] " + (next == null ? "(空)" : next.getTextContent()));
        } catch (Exception e) {
            log.error("/submit resume failed: {}", e.toString(), e);
            System.out.println("(error: " + e.getMessage() + ")");
        }
    }

    private static void runSync(ReActAgent agent, Msg req) {
        Msg resp = agent.call(req).timeout((AppConfig.timeout())).block();
        String text = resp.getTextContent();
        System.out.println("bot > " + text);
        log.info("[BOT] reason={} text={}", resp.getGenerateReason(), text);
    }

    private static void runStream(ReActAgent agent, Msg req) {
        System.out.println("bot > ");
        StringBuilder buf = new StringBuilder();

        agent.stream(req)
                .timeout(AppConfig.timeout())
                .toStream()
                .forEach(msg -> {
                    String text = msg.getMessage().getTextContent();
                    if (text != null && !text.isEmpty()) {
                        buf.append(text);
                        System.out.print(text);
                        System.out.flush();
                    }
                });
        System.out.println();
        log.info("[Bot-STREAM] text={}", buf);
    }
}
