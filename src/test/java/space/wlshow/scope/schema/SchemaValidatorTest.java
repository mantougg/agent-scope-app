package space.wlshow.scope.schema;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchemaValidatorTest {

    private final SchemaValidator validator =
            new SchemaValidator(SchemaValidator.ANALYSIS_RESULT);

    /** 从 classpath 读样本 JSON 文本，避免硬编码 src/test/... 物理路径 */
    private static String loadSample(String name) {
        String path = "/schema-samples/" + name;
        try (InputStream in = SchemaValidatorTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("找不到样本: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取样本失败: " + path, e);
        }
    }

    @Nested
    class Pass {
        @Test
        void leaveSystem_isValid() {
            List<ValidationError> errors =
                    validator.validate(loadSample("pass-leave-system.json"));
            assertTrue(errors.isEmpty(),
                    "应通过但失败了：" + errors);
        }
    }

    @Nested
    class Fail {

        @Test
        void missingApp() {
            var errors = validator.validate(loadSample("fail-missing-app.json"));
            assertFalse(errors.isEmpty());
            assertTrue(
                    errors.stream().anyMatch(e ->
                            "required".equals(e.keyword()) && e.message().contains("app")),
                    "应检出缺 app，实际错误：" + errors);
        }

        @Test
        void badModuleId() {
            var errors = validator.validate(loadSample("fail-bad-module-id.json"));
            assertTrue(errors.stream().anyMatch(e ->
                            "pattern".equals(e.keyword())
                                    && e.path().contains("moduleId")),
                    "应检出 moduleId pattern 违反：" + errors);
        }

        @Test
        void badDataType_enumViolation() {
            var errors = validator.validate(loadSample("fail-bad-data-type.json"));
            assertTrue(errors.stream().anyMatch(e ->
                            "enum".equals(e.keyword())
                                    && e.path().contains("dataType")),
                    "应检出 dataType 不在枚举：" + errors);
        }

        @Test
        void recursive_subsDataType() {
            var errors = validator.validate(loadSample("fail-recursive-bad.json"));
            // 关键：错误路径必须包含 subs，证明 $ref 递归生效
            assertTrue(errors.stream().anyMatch(e ->
                            e.path().contains("subs")
                                    && e.path().contains("dataType")),
                    "应在 subs 内部检出错误，证明 $ref 递归生效。实际：" + errors);
        }
    }

    @Test
    void resourceMissing_failsFast() {
        var ex = assertThrows(IllegalStateException.class,
                () -> new SchemaValidator("/schemas/not-exist.schema.json"));
        assertTrue(ex.getMessage().contains("找不到 Schema 资源"));
    }
}