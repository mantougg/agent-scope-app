package space.wlshow.scope.agent;

import io.agentscope.core.memory.Memory;
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
import space.wlshow.scope.tool.SubmitTool;
import space.wlshow.scope.tool.TodoDeleteTools;
import space.wlshow.scope.tool.TodoQueryTools;
import space.wlshow.scope.tool.TodoUpdateTools;
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
     * 构造"需求解析"专用 Agent。
     * RC2 起改走 Structured Output（{@code agent.call(msgs, AnalysisResult.class)}），
     * 框架内部用 {@code generate_response} 工具 + schema 校验 + 不合规 reminder 自动 reasoning。
     * {@code maxIters=5} 给框架几次重试余量；happy path 一次即返（StructuredOutputHook 成功后立刻 stopAgent）。
     */
    public static ReActAgent buildParser() {
        if (!ModelRegistry.canResolve(DEFAULT_MODEL_ID)) initModels();
        return ReActAgent.builder()
                .name("RequirementAnalyst")
                .sysPrompt(Prompts.analyst())
                .model(ModelRegistry.resolve(DEFAULT_MODEL_ID))
                .maxIters(5)
                .build();
    }

    /**
     * 构造"工具调度版"分析 Agent，替代 Day 3 的 buildParser()。
     * - 强制 system prompt 引导 LLM 使用 create_* 工具
     * - parallel(true) 让多个 module/model 工具并发
     */
    public static ReActAgent buildAnalystWithTools(TodoManager todos, Memory memory) {
        if (!ModelRegistry.canResolve(DEFAULT_MODEL_ID)) initModels();
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
                .parallel(true)
                .build());
        toolkit.registerTool(new FrontendCreateTools(todos));
        toolkit.registerTool(new TodoQueryTools(todos));
        toolkit.registerTool(new TodoUpdateTools(todos));
        toolkit.registerTool(new TodoDeleteTools(todos));
        toolkit.registerTool(new SubmitTool(todos));

        return ReActAgent.builder()
                .name("RequirementAnalyst")
                .sysPrompt(Prompts.analystMultiRound())
                .model(ModelRegistry.resolve(DEFAULT_MODEL_ID))
                .toolkit(toolkit)
                .memory(memory)
                .maxIters(15)        // 一个稍大点的需求可能调 1+5+5 ≈ 11 次工具
                .enablePendingToolRecovery(false)  // HITL: 由我们自己管 pending tool 生命周期，框架不要补合成错误结果
                .hooks(List.of(new PromptLengthHook()))   // Day 7 §6.4 LLM_CALL stage 靠它打点
                .build();
    }

    private AgentFactory() {}
}