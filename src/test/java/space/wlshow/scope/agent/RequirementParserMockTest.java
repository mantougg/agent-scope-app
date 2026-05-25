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
        stubChat("analyst-ok.json");
        AnalysisResult r = newParser().parse("做一个员工档案管理");
        assertEquals("employeeMgr", r.app().name());
        assertEquals(1, server.getAllServeEvents().size(), "只应调用 1 次");
    }

    @Test
    void recoverFromFence() {
        stubChat("analyst-bad-fence.json");
        AnalysisResult r = newParser().parse("做一个员工档案管理");
        assertNotNull(r.app());        // fence 被 stripFence 剥掉，第一次就成功
    }

    @Test
    void retryAfterMissingField() {
        // 第一次返回缺 app，第二次返回正常
        server.stubFor(post(urlPathMatching(".*/chat/completions"))
                .inScenario("retry")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("analyst-missing-app.json"))
                .willSetStateTo("got-bad"));
        server.stubFor(post(urlPathMatching(".*/chat/completions"))
                .inScenario("retry")
                .whenScenarioStateIs("got-bad")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile("analyst-ok.json")));

        AnalysisResult r = newParser().parse("做一个员工档案管理");
        assertNotNull(r.app());
        assertEquals(2, server.getAllServeEvents().size(), "应该是 1 次失败 + 1 次成功");
    }

    @Test
    void giveUpAfterThree() {
        stubChat("analyst-missing-app.json");
        ParseException ex = assertThrows(ParseException.class,
                () -> newParser().parse("做一个员工档案管理"));
        assertEquals(3, server.getAllServeEvents().size(), "应该调用 3 次后放弃");
        assertFalse(ex.lastErrors().isEmpty());
    }

    private void stubChat(String fileName) {
        server.stubFor(post(urlPathMatching(".*/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBodyFile(fileName)));
    }

    private RequirementParser newParser() {
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey("test-key")
                .modelName("doubao-pro")
                .baseUrl(server.baseUrl())     // 指向 WireMock
                .stream(false)
                .build();
        ReActAgent agent = ReActAgent.builder()
                .name("AnalystTest")
                .sysPrompt(Prompts.analyst())
                .model(model)
                .maxIters(1)
                .build();
        return new RequirementParser(agent, validator);
    }
}