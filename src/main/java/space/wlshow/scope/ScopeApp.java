package space.wlshow.scope;

import io.agentscope.core.message.TextBlock;
import space.wlshow.scope.agent.AgentFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.config.AppConfig;

import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class ScopeApp {

    private static final Logger log = LoggerFactory.getLogger(ScopeApp.class);

    public static void main(String[] args) {
        log.info("Booting Scope App (REPL model) ...");

        ReActAgent agent = AgentFactory.buildAnalyst();
        Scanner sc = new Scanner(System.in, StandardCharsets.UTF_8);

        System.out.println("Scope REPL - 输入 'exit' 退出， '/stream' 切换流式。\n");

        boolean stream = false;
        while (true) {
            System.out.println(stream ? "you (stream) > " : "you > ");
            String line = sc.nextLine();
            if (line.isEmpty()) continue;
            if ("exit".equalsIgnoreCase(line)) break;
            if ("/stream".equals(line)) { stream = !stream; continue; }

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