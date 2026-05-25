package space.wlshow.scope.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.util.Json;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JSON Schema 校验器。
 *
 * 设计：
 * - 构造时一次性加载并编译 Schema（线程安全，可作单例缓存）
 * - validate 永不抛业务异常；空列表 = 通过，非空 = 有错
 * - 资源加载失败 = 配置错误 = fail fast（直接抛 IllegalStateException）
 */
public final class SchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(SchemaValidator.class);

    /** 默认契约：AnalysisResult 全树校验 */
    public static final String ANALYSIS_RESULT = "/schemas/analysis-result.schema.json";

    private final String resourcePath;
    private final JsonSchema schema;

    public SchemaValidator(String classpathResource) {
        this.resourcePath = classpathResource;
        this.schema = load(classpathResource);
        log.info("[Schema] 已加载: {}", classpathResource);
    }

    private static JsonSchema load(String classpathResource) {
        try (InputStream in = SchemaValidator.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("找不到 Schema 资源: " + classpathResource);
            }
            JsonSchemaFactory factory = JsonSchemaFactory
                    .getInstance(SpecVersion.VersionFlag.V202012);
            return factory.getSchema(in);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "加载 Schema 失败: " + classpathResource, e);
        }
    }

    public List<ValidationError> validate(JsonNode node) {
        Set<ValidationMessage> raw = schema.validate(node);
        if (raw.isEmpty()) return List.of();
        List<ValidationError> errors = raw.stream()
                .map(ValidationError::from)
                .collect(Collectors.toList());
        log.debug("[Schema] {} 校验失败 {} 项：{}", resourcePath, errors.size(), errors);
        return errors;
    }

    public List<ValidationError> validate(String json) {
        return validate(Json.tree(json));
    }

    public boolean isValid(JsonNode node) {
        return validate(node).isEmpty();
    }
}