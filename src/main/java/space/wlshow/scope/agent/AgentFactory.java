package space.wlshow.scope.agent;

import space.wlshow.scope.config.AppConfig;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.OpenAIChatModel;

public final class AgentFactory {

    public static ReActAgent buildAnalyst() {
        // 火山引擎 Ark 是 OpenAI 兼容协议，用 OpenAIChatModel + baseUrl 即可
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(AppConfig.modelApiKey())
                .modelName(AppConfig.modelName())
                .baseUrl(AppConfig.modelBaseUrl())
                .build();

        return ReActAgent.builder()
                .name(AppConfig.agentName())
                .sysPrompt(AppConfig.sysPrompt())
                .model(model)
                .maxIters(AppConfig.maxIters())
                .build();
    }

    private AgentFactory() {}
}