package space.wlshow.scope.agent;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.OpenAIChatModel;
import org.junit.jupiter.api.*;
import space.wlshow.scope.schema.SchemaValidator;
import space.wlshow.scope.spec.AnalysisResult;
import space.wlshow.scope.util.Prompts;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

/**
 * RC2 Structured Output 通道下的离线测试。
 * <p>RC2 把 LLM 输出固定走 OpenAI {@code tool_calls} 通道，固件必须模拟 {@code tool_calls}-shaped 响应。
 * 1.0.12 时代的纯 content/fence fixture 全部失效。
 */
class RequirementParserMockTest {

    static WireMockServer server;
    static SchemaValidator validator;

    @BeforeAll
    static void setup() {
        server = new WireMockServer(wireMockConfig()
                .dynamicPort()
                .usingFilesUnderClasspath("wiremock"));
        server.start();
        validator = new SchemaValidator("/schemas/analysis-result.schema.json");
    }

    @AfterAll
    static void tearDown() { server.stop(); }

    @AfterEach
    void reset() { server.resetRequests(); server.resetMappings(); }

    @Test
    void okOnFirstAttempt() {
        stubChat("analyst-tool-ok.json");

        AnalysisResult r = newParser().parse("做一个员工档案管理");

        assertEquals("employeeMgr", r.app().name());
        // RC2 StructuredOutputHook 在工具成功时 stopAgent()，happy path 只有 1 次 LLM 请求
        assertEquals(1, server.getAllServeEvents().size(), "happy path 只应调用 1 次 LLM");
    }

    @Test
    void retryWhenLLMSkipsTool() {
        // 第一次：模型没调 generate_response，框架 reminder 触发 gotoReasoning
        // 第二次：模型规规矩矩调了工具
        server.stubFor(post(urlPathMatching(".*/chat/completions"))
                .inScenario("retry")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("analyst-tool-skip.json"))
                .willSetStateTo("skipped-once"));
        server.stubFor(post(urlPathMatching(".*/chat/completions"))
                .inScenario("retry")
                .whenScenarioStateIs("skipped-once")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("analyst-tool-ok.json")));

        AnalysisResult r = newParser().parse("做一个员工档案管理");

        assertNotNull(r.app());
        assertTrue(server.getAllServeEvents().size() >= 2,
                "模型 skip 工具后框架应至少再调 1 次 LLM，实际=" + server.getAllServeEvents().size());
    }

    @Test
    void throwsWhenLLMNeverCallsTool() {
        // 永远只返回 content、不调 generate_response：框架 maxIters 耗尽后无 structured data
        stubChat("analyst-tool-skip.json");

        ParseException ex = assertThrows(ParseException.class,
                () -> newParser().parse("做一个员工档案管理"));
        assertFalse(ex.lastErrors().isEmpty(), "ParseException 必须携带 lastErrors");
    }

    private void stubChat(String fileName) {
        server.stubFor(post(urlPathMatching(".*/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile(fileName)));
    }

    private RequirementParser newParser() {
        // OpenAIChatModel 默认 stream=true 推 SSE chunk；fixture 是非流式 chat.completion，必须显式关
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey("test-key")
                .modelName("doubao-pro")
                .baseUrl(server.baseUrl())
                .stream(false)
                .build();
        ReActAgent agent = ReActAgent.builder()
                .name("AnalystTest")
                .sysPrompt(Prompts.analyst())
                .model(model)
                .maxIters(3)
                .build();
        return new RequirementParser(agent, validator);
    }
}
