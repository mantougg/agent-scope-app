package space.wlshow.scope.agent;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import space.wlshow.scope.config.AppConfig;
import space.wlshow.scope.hook.PromptLengthHook;
import space.wlshow.scope.model.ModelRegistry;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.todo.TodoManager;
import space.wlshow.scope.tool.FrontendCreateTools;
import space.wlshow.scope.util.Prompts;

import java.util.List;

public final class AgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

    /** 默认的模型注册 ID */
    public static final String DEFAULT_MODEL_ID = "primary";

    /**
     * 根据配置文件初始化模型注册表。
     * 调用后，可通过 {@code ModelRegistry.resolve("primary")} 获取模型实例。
     * <p>
     * 支持多模型：后续可在 application.conf 里加 model.ids 数组，循环注册。
     */
    public static void initModels() {
        Model model = OpenAIChatModel.builder()
                .apiKey(AppConfig.modelApiKey())
                .modelName(AppConfig.modelName())
                .baseUrl(AppConfig.modelBaseUrl())
                .build();

        ModelRegistry.register(DEFAULT_MODEL_ID, model);
        log.info("已注册模型 [{}] -> {}/{}",
                DEFAULT_MODEL_ID, AppConfig.modelProvider(), AppConfig.modelName());
    }

    /**
     * 构建需求分析 Agent，模型从 ModelRegistry 中按 ID 取用。
     */
    public static ReActAgent buildAnalyst() {
        // 确保模型已注册
        if (!ModelRegistry.canResolve(DEFAULT_MODEL_ID)) {
            initModels();
        }

        Model model = ModelRegistry.resolve(DEFAULT_MODEL_ID);

        return ReActAgent.builder()
                .name(AppConfig.agentName())
                .sysPrompt(AppConfig.sysPrompt())
                .model(model)
                .maxIters(AppConfig.maxIters())
                .hooks(List.of(new PromptLengthHook()))
                .build();
    }

    /**
     * 构造"需求解析"专用 Agent：
     * - 强制 system prompt
     * - 关闭 tool（Day 3 还不接 Toolkit）
     * - 关闭 stream（Day 3 要拿完整 JSON 一次解析）
     */
    public static ReActAgent buildParser() {
        initModels();
        return ReActAgent.builder()
                .name("RequirementAnalyst")
                .sysPrompt(Prompts.analyst())
                .model(ModelRegistry.resolve(DEFAULT_MODEL_ID))
                .maxIters(2)             // 解析任务一次性回答，不需要多步推理
                .build();
    }

    /**
     * 构造"工具调度版"分析 Agent，替代 Day 3 的 buildParser()。
     * - 强制 system prompt 引导 LLM 使用 create_* 工具
     * - parallel(true) 让多个 module/model 工具并发
     */
    public static ReActAgent buildAnalystWithTools(TodoManager todos) {
        initModels();
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
                .parallel(true)
                .build());
        toolkit.registerTool(new FrontendCreateTools(todos));

        return ReActAgent.builder()
                .name("RequirementAnalyst")
                .sysPrompt(Prompts.analystWithTools())
                .model(ModelRegistry.resolve(DEFAULT_MODEL_ID))
                .toolkit(toolkit)
                .maxIters(15)        // 一个稍大点的需求可能调 1+5+5 ≈ 11 次工具
                .build();
    }

    private AgentFactory() {}
}