package space.wlshow.scope.spec;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import space.wlshow.scope.util.Json;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonRoundTripTest {

    @Test
    void appSpec_roundTrip() {
        var origin = new AppSpec("leaveSystem", "请假管理", "23");
        var json   = Json.write(origin);
        var back   = Json.read(json, AppSpec.class);
        assertEquals(origin, back);
        assertTrue(json.contains("\"name\":\"leaveSystem\""));
    }

    @Test
    void analysisResult_fullTree_roundTrip() {
        var leaf = new FieldSpec("主键", "id", "long", "primary", "", null);
        var detail = new FieldSpec("明细", "details", "array", "", "collection",
                List.of(new FieldSpec("天数", "days", "double", "", "", null)));
        var model = new DataModelSpec("请假单", "TASK_MASTER_SLAVE",
                "qingjiadan", "t_leave_request", "",
                List.of(leaf, detail));

        var origin = new AnalysisResult(
                new AppSpec("leaveSystem", "请假管理", "23"),
                List.of(new ModuleSpec("请假申请", "leaveRequest", "员工提交请假")),
                List.of(model),
                List.of(),
                List.of("请假明细是否需要附件字段？")
        );

        var json = Json.write(origin);
        var back = Json.read(json, AnalysisResult.class);
        assertEquals(origin, back);
    }

    @Test
    void unknownFields_areIgnored() {
        // LLM 哪天多吐一个 "confidence" 字段，不要直接砸掉
        String dirty = """
                { "name":"x", "label":"X", "type":"23",
                  "confidence": 0.87, "_debug": {"foo":1} }
                """;
        AppSpec back = Json.read(dirty, AppSpec.class);
        assertEquals("x", back.name());
    }

    @Test
    void nullSubs_keptAsNull() {
        var leaf = new FieldSpec("c", "id", "long", "primary", "", null);
        String json = Json.write(leaf);
        // 题面叶子节点不带 subs：默认序列化会输出 "subs":null
        // 我们暂时不做 @JsonInclude(NON_NULL)，因为 Day 3 还要观察 LLM 学习样式
        JsonNode node = Json.tree(json);
        assertTrue(node.has("subs"));
        assertTrue(node.get("subs").isNull());
    }
}