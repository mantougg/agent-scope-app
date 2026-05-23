package space.wlshow.scope.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public final class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    private static final String LOCAL_RESOURCE = "application-local.conf";
    private static final String BASE_RESOURCE  = "application.conf";

    private static final Config CFG = loadLayered();

    /**
     * 加载优先级：系统属性(-D) > application-local.conf（如存在，gitignored）> application.conf > reference.conf
     * <p>
     * 本地文件不存在时 {@code parseResources} 返回空 Config，整套机制自动降级为
     * 标准 {@code ConfigFactory.load()} 行为，不需要额外开关。
     */
    private static Config loadLayered() {
        Config local = ConfigFactory.parseResources(LOCAL_RESOURCE);
        Config base  = ConfigFactory.parseResources(BASE_RESOURCE);

        if (local.isEmpty()) {
            log.info("config source: {} (no local override)", BASE_RESOURCE);
        } else {
            log.info("config source: {} overlaid on {}", LOCAL_RESOURCE, BASE_RESOURCE);
        }

        return ConfigFactory.systemProperties()
                .withFallback(local)
                .withFallback(base)
                .resolve();
    }

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