package space.wlshow.scope.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.schema.SchemaValidator;
import space.wlshow.scope.schema.ValidationError;
import space.wlshow.scope.spec.AnalysisResult;
import space.wlshow.scope.util.Json;

import java.util.List;

/**
 * 把用户中文需求 -&gt; 合法的 AnalysisResult。
 *
 * <p>RC2 改造：走 {@link ReActAgent#call(List, Class)} 内置 Structured Output 通道，
 * 由框架注册临时 {@code generate_response} 工具、按 schema 校验、不合规自动 {@code gotoReasoning()} 重试。
 * 我们这层只做：
 * <ul>
 *   <li>触发调用并拿 {@link Msg#getStructuredData(Class)}；</li>
 *   <li>用项目原 schema（含 pattern/enum/$ref 递归）做<b>二次断言</b>——
 *       反射生成的 schema 可能漏掉某些约束，留我们这道兜底。</li>
 * </ul>
 *
 * <p>不再手写"3 次自纠错"循环：框架内部已经按 schema 校验 + reminder gotoReasoning。
 */
public class RequirementParser {

    private static final Logger log = LoggerFactory.getLogger(RequirementParser.class);

    private final ReActAgent agent;
    private final SchemaValidator validator;

    public RequirementParser(ReActAgent agent, SchemaValidator validator) {
        this.agent = agent;
        this.validator = validator;
    }

    public AnalysisResult parse(String userRequirement) {
        log.info("[Parse] start promptHeadChars={}",
                userRequirement.substring(0, Math.min(80, userRequirement.length())));

        Msg out = agent.call(
                List.of(Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(userRequirement).build())
                        .build()),
                AnalysisResult.class
        ).block();

        if (out == null) {
            throw new ParseException("LLM 返回 null", List.of("agent.call() returned null"));
        }
        if (!out.hasStructuredData()) {
            throw new ParseException(
                    "LLM 未产出符合 schema 的结构化输出",
                    List.of("framework retry exhausted or model refused to call generate_response"));
        }

        AnalysisResult result;
        try {
            result = out.getStructuredData(AnalysisResult.class);
        } catch (Exception e) {
            throw new ParseException("结构化输出反序列化失败",
                    List.of(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        // 二次断言：用项目原 schema（含 pattern/enum）兜底反射 schema 漏掉的约束
        JsonNode tree = Json.mapper().valueToTree(result);
        List<String> errors = validator.validate(tree).stream()
                .map(ValidationError::message)
                .toList();
        if (!errors.isEmpty()) {
            log.warn("[Parse] 二次校验失败 errors={}", errors);
            throw new ParseException("结构化输出二次校验未过", errors);
        }

        log.info("[Parse] success app={} modules={} models={}",
                result.app() == null ? null : result.app().name(),
                result.modules() == null ? 0 : result.modules().size(),
                result.models() == null ? 0 : result.models().size());
        return result;
    }
}
