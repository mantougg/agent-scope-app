package space.wlshow.scope.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.time.Duration;

public final class AppConfig {

    private static final Config CFG = ConfigFactory.load();

    public static String modelProvider() {
        return CFG.getString("model.provider");
    }

    public static String modelName() {
        return CFG.getString("model.name");
    }

    public static String modelBaseUrl() {
        return CFG.getString("model.baseUrl");
    }

    public static String modelApiKey() {
        if (!CFG.hasPath("model.apiKey") || CFG.getString("model.apiKey").isBlank()) {
            throw new IllegalStateException(
                    "API key 未配置。请设置环境变量 ARK_API_KEY 后重启进程。");
        }
        return CFG.getString("model.apiKey");
    }

    public static String agentName()  { return CFG.getString("agent.name"); }
    public static int    maxIters()   { return CFG.getInt("agent.maxIters"); }
    public static Duration timeout()  { return CFG.getDuration("agent.timeout"); }
    public static String sysPrompt()  { return CFG.getString("agent.sysPrompt").trim(); }

    private AppConfig() {}
}