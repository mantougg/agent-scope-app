package space.wlshow.scope;

import space.wlshow.scope.agent.AgentFactory;
import space.wlshow.scope.config.AppConfig;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScopeApp {

    private static final Logger log = LoggerFactory.getLogger(ScopeApp.class);

    public static void main(String[] args) {
        log.info("Booting Scope App ...");

        ReActAgent agent = AgentFactory.buildAnalyst();

        String userInput = "用一句话告诉我现在是 Day 1。";
        log.info("[USER] {}", userInput);

        Msg request = Msg.builder().textContent(userInput).build();

        Msg response = agent.call(request)
                .timeout(AppConfig.timeout())
                .block();

        String reply = response.getTextContent();
        System.out.println("\n>>> " + reply + "\n");
        log.info("[BOT] reason={}, tokens={}, content={}",
                response.getGenerateReason(),
                response.getChatUsage(),
                reply);
    }
}