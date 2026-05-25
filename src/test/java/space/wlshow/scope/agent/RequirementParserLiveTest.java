package space.wlshow.scope.agent;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import space.wlshow.scope.spec.AnalysisResult;
import space.wlshow.scope.schema.SchemaValidator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 LLM 测试，依赖 ARK_API_KEY。CI 默认不跑：用 @Tag("live") 标识。
 * 本地手动跑：mvn -Dgroups=live test
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ARK_API_KEY", matches = ".+")
class RequirementParserLiveTest {

    private final RequirementParser parser = new RequirementParser(
            AgentFactory.buildParser(),
            new SchemaValidator("/schemas/analysis-result.schema.json"));

    @Test
    void vagueInput_shouldFillQuestions() {
        AnalysisResult r = parser.parse("做个系统");
        assertFalse(r.questions().isEmpty(),
                "极模糊的需求应至少触发 1 个 question，实际 questions=" + r.questions());
    }

    @Test
    void mediumInput_shouldProduceValidSpec() {
        AnalysisResult r = parser.parse("做一个简单的员工档案管理，字段：姓名、工号、入职日期、部门");
        assertNotNull(r.app());
        assertFalse(r.modules().isEmpty());
        assertFalse(r.models().isEmpty());
        // 主键必须存在
        assertTrue(r.models().get(0).fields().stream()
                .anyMatch(f -> "id".equals(f.name()) && "primary".equals(f.usage())));
    }

    @Test
    void masterSlaveInput_shouldUseMasterSlaveType() {
        AnalysisResult r = parser.parse("做请假管理，员工提交多条请假明细，主管审批整张单");
        assertTrue(r.models().stream().anyMatch(m -> "TASK_MASTER_SLAVE".equals(m.type())),
                "含明细的单据应至少有一个 TASK_MASTER_SLAVE 模型，实际 types=" +
                        r.models().stream().map(m -> m.type()).toList());
    }
}