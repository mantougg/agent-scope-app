package space.wlshow.scope;

import space.wlshow.scope.agent.AgentFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.agent.ParseException;
import space.wlshow.scope.agent.RequirementParser;
import space.wlshow.scope.config.AppConfig;
import space.wlshow.scope.schema.SchemaValidator;
import space.wlshow.scope.spec.AnalysisResult;
import space.wlshow.scope.todo.TodoManager;
import space.wlshow.scope.util.Json;

import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class ScopeApp {

    private static final Logger log = LoggerFactory.getLogger(ScopeApp.class);

    static ReActAgent parserAgent = AgentFactory.buildParser();
    static SchemaValidator validator = new SchemaValidator("/schemas/analysis-result.schema.json");
    static RequirementParser parser = new RequirementParser(parserAgent, validator);

    static TodoManager todos = new TodoManager();
    static ReActAgent analystWithTools = AgentFactory.buildAnalystWithTools(todos);

    public static void main(String[] args) {
        log.info("Booting Scope App (REPL model) ...");

        ReActAgent agent = AgentFactory.buildAnalyst();
        Scanner sc = new Scanner(System.in, StandardCharsets.UTF_8);

        System.out.println("Scope REPL - 输入 'exit' 退出， '/stream' 切换流式， '/parse <需求>' 解析， '/run <需求>' 工具调度。\n");

        boolean stream = false;
        while (true) {
            System.out.println(stream ? "you (stream) > " : "you > ");
            String line = sc.nextLine();
            if (line.isEmpty()) continue;
            if ("exit".equalsIgnoreCase(line)) break;
            if ("/stream".equals(line)) { stream = !stream; continue; }
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
                    System.out.println("[ASSISTANT] " + (out == null ? "(空)" : out.getTextContent()));
                    System.out.println();
                    System.out.println("=== Todos (" + todos.size() + ") ===");
                    todos.snapshot().forEach(it -> System.out.printf("  %s  %-15s  %-25s  %s%n",
                            it.id(), it.type(), it.targetName(), it.status()));
                } catch (Exception e) {
                    log.error("/run failed: {}", e.toString(), e);
                    System.out.println("(error: " + e.getMessage() + ")");
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