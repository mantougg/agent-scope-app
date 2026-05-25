package space.wlshow.scope.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.wlshow.scope.spec.AnalysisResult;
import space.wlshow.scope.schema.SchemaValidator;
import space.wlshow.scope.schema.ValidationError;
import space.wlshow.scope.util.Json;

import java.util.List;

/**
 * 把用户中文需求 -> 合法的 AnalysisResult。
 * 失败时最多重试 3 次，每次把上一轮 schema 错误回灌给 LLM。
 */
public class RequirementParser {

    private static final Logger log = LoggerFactory.getLogger(RequirementParser.class);
    private static final int MAX_ATTEMPTS = 3;

    private final ReActAgent agent;
    private final SchemaValidator validator;

    public RequirementParser(ReActAgent agent, SchemaValidator validator) {
        this.agent = agent;
        this.validator = validator;
    }

    public AnalysisResult parse(String userRequirement) {
        String prompt = userRequirement;
        List<String> lastErrors = List.of();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            log.info("[Parse] attempt={} promptHeadChars={}", attempt,
                    prompt.substring(0, Math.min(80, prompt.length())));

            Msg out = agent.call(Msg.builder()
                    .role(io.agentscope.core.message.MsgRole.USER)
                    .content(TextBlock.builder().text(prompt).build())
                    .build()).block();

            if (out == null) {
                throw new ParseException("LLM 返回 null", List.of("agent.call() returned null"));
            }

            String raw = out.getTextContent();
            String json = Json.stripFence(raw);
            log.debug("[Parse] raw chars={} stripped chars={}", raw.length(), json.length());

            try {
                JsonNode node = Json.tree(json);
                lastErrors = validator.validate(node).stream()
                        .map(ValidationError::message)
                        .toList();
                if (lastErrors.isEmpty()) {
                    AnalysisResult result = Json.mapper().treeToValue(node, AnalysisResult.class);
                    log.info("[Parse] success on attempt {}", attempt);
                    return result;
                }
                log.warn("[Parse] attempt {} schema errors: {}", attempt, lastErrors);
            } catch (Exception e) {
                lastErrors = List.of("JSON 解析失败: " + e.getMessage());
                log.warn("[Parse] attempt {} json broken: {}", attempt, e.getMessage());
            }

            prompt = buildRetryPrompt(userRequirement, json, lastErrors);
        }

        throw new ParseException(
                "LLM 输出连续 " + MAX_ATTEMPTS + " 次不符合 schema，已放弃。",
                lastErrors);
    }

    private static String buildRetryPrompt(String original, String lastJson, List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("上一次你的输出不符合 schema，错误如下：\n");
        for (int i = 0; i < errors.size(); i++) {
            sb.append((i + 1)).append(". ").append(errors.get(i)).append("\n");
        }
        sb.append("\n上一次的 JSON（节选前 500 字）：\n");
        sb.append(lastJson.substring(0, Math.min(500, lastJson.length())));
        sb.append("\n\n原始用户需求：\n");
        sb.append(original);
        sb.append("\n\n请只输出修正后的完整 JSON，不要解释。");
        return sb.toString();
    }
}