package space.wlshow.scope.model;

import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册表 —— 将模型实例以字符串 ID 注册，
 * AgentFactory 等消费者只需知道 ID 即可取用，从而把具体模型构建逻辑从业务代码中解耦。
 * <p>
 * 用法：
 * <pre>{@code
 * ModelRegistry.register("primary", OpenAIChatModel.builder()...build());
 * Model model = ModelRegistry.resolve("primary");
 * }</pre>
 */
public final class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    /** 已注册的模型实例，key = 业务自定义 ID */
    private static final Map<String, Model> REGISTRY = new ConcurrentHashMap<>();

    // ---- 注册 ----

    /**
     * 以指定 ID 注册一个模型实例。
     * 如果该 ID 已存在，旧实例会被覆盖并打印警告。
     */
    public static void register(String id, Model model) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("模型 ID 不能为空");
        }
        Model prev = REGISTRY.put(id, model);
        if (prev != null) {
            log.warn("模型 [{}] 被覆盖", id);
        } else {
            log.info("模型 [{}] 注册成功", id);
        }
    }

    // ---- 查询 ----

    /**
     * 根据 ID 获取模型实例。
     *
     * @throws IllegalArgumentException 若 ID 未注册
     */
    public static Model resolve(String id) {
        Model model = REGISTRY.get(id);
        if (model == null) {
            throw new IllegalArgumentException(
                    "模型 [" + id + "] 未注册。已注册: " + REGISTRY.keySet());
        }
        return model;
    }

    /** 判断某 ID 是否已注册 */
    public static boolean canResolve(String id) {
        return REGISTRY.containsKey(id);
    }

    // ---- 管理 ----

    /** 移除指定 ID 的注册 */
    public static void unregister(String id) {
        REGISTRY.remove(id);
    }

    /** 清空所有注册（主要用于测试） */
    public static void reset() {
        REGISTRY.clear();
    }

    /** 当前已注册的所有 ID（只读快照） */
    public static Set<String> registeredIds() {
        return Set.copyOf(REGISTRY.keySet());
    }

    private ModelRegistry() {}
}
