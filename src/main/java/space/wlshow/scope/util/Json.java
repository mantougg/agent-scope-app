package space.wlshow.scope.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.InputStream;

/**
 * 全局 ObjectMapper 门面：
 * - 宽进严出：反序列化忽略未知字段（LLM 经常多吐），序列化保持紧凑
 * - 注册 JavaTimeModule，禁掉 timestamp 序列化（用 ISO-8601）
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.INDENT_OUTPUT, false);

    public static ObjectMapper mapper() { return MAPPER; }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 反序列化失败: " + e.getOriginalMessage(), e);
        }
    }

    public static JsonNode tree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getOriginalMessage(), e);
        }
    }

    public static JsonNode tree(InputStream in) {
        try {
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("读取 JSON 流失败", e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    public static String writePretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /**
     * 剥离 Markdown 代码 fence 包裹的 JSON。容错：
     * - ```json\n{...}\n```
     * - ```\n{...}\n```
     * - 前后有寒暄文字
     * - 没有 fence 直接返回
     */
    public static String stripFence(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // 优先：找到第一段 fence
        int fenceStart = s.indexOf("```");
        if (fenceStart >= 0) {
            int contentStart = s.indexOf('\n', fenceStart);
            int fenceEnd = s.lastIndexOf("```");
            if (contentStart > 0 && fenceEnd > contentStart) {
                return s.substring(contentStart + 1, fenceEnd).trim();
            }
        }
        // 兜底：截取第一个 { 到最后一个 }
        int objStart = s.indexOf('{');
        int objEnd = s.lastIndexOf('}');
        if (objStart >= 0 && objEnd > objStart) {
            return s.substring(objStart, objEnd + 1);
        }
        return s;
    }

    public static <T> java.util.List<T> readList(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(java.util.List.class, type));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 列表反序列化失败: " + e.getOriginalMessage(), e);
        }
    }

    private Json() {}
}