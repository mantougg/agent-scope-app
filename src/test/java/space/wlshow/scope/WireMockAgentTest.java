package space.wlshow.scope;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.OpenAIChatModel;
import org.junit.jupiter.api.*;
import space.wlshow.scope.hook.PromptLengthHook;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用 WireMock 模拟火山引擎 Ark 的 /chat/completions 接口，
 * 离线验证 ReActAgent 的完整调用链路（无需真实 API key）。
 */
class WireMockAgentTest {

    private static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(
                WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void resetStubs() {
        // 清空上一个测试方法注册的所有 stub 和 scenario 状态，
        // 避免类似 multi-turn scenario 的 "turn2" 状态污染后续测试。
        wireMock.resetAll();

        // 默认 stub：非流式 /chat/completions，返回固定 mock 内容
        wireMock.stubFor(post(urlPathMatching("/api/v3/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "chatcmpl-test-001",
                                  "object": "chat.completion",
                                  "created": 1700000000,
                                  "model": "test-model",
                                  "choices": [
                                    {
                                      "index": 0,
                                      "message": {
                                        "role": "assistant",
                                        "content": "你好，我是 WireMock 模拟的需求分析助手。"
                                      },
                                      "finish_reason": "stop"
                                    }
                                  ],
                                  "usage": {
                                    "prompt_tokens": 20,
                                    "completion_tokens": 15,
                                    "total_tokens": 35
                                  }
                                }
                                """)));
    }

    @Test
    @DisplayName("同步调用：Agent 能通过 mock 接口拿到回复")
    void syncCall_shouldReturnMockedReply() {
        // 构建指向 WireMock 的 model（不需要真实 key）
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey("fake-key-for-test")
                .modelName("test-model")
                .baseUrl(wireMock.baseUrl() + "/api/v3")
                .stream(false)  // WireMock stub 是非流式响应，必须禁用 stream
                .build();

        ReActAgent agent = ReActAgent.builder()
                .name("TestAnalyst")
                .sysPrompt("你是需求分析助手。")
                .model(model)
                .maxIters(3)
                .hooks(List.of(new PromptLengthHook()))
                .build();

        Msg request = Msg.builder()
                .textContent("你好，请用一句话介绍你自己。")
                .build();

        Msg response = agent.call(request)
                .timeout(Duration.ofSeconds(10))
                .block();

        assertNotNull(response, "响应不应为空");
        String text = response.getTextContent();
        assertNotNull(text, "文本内容不应为空");
        assertTrue(text.contains("WireMock"), "回复应包含 mock 内容: " + text);

        // 验证 WireMock 确实被调用了一次
        wireMock.verify(1, postRequestedFor(
                urlPathMatching("/api/v3/chat/completions"))
                .withRequestBody(containing("你好")));
    }

    @Test
    @DisplayName("多轮调用：第二次请求会带上历史消息")
    void multiTurn_shouldIncludeHistoryInSecondRequest() {
        // 添加一个第二轮的 stub（匹配包含历史的请求）
        wireMock.stubFor(post(urlPathMatching("/api/v3/chat/completions"))
                .inScenario("multi-turn")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "chatcmpl-turn1",
                                  "object": "chat.completion",
                                  "created": 1700000001,
                                  "model": "test-model",
                                  "choices": [{"index": 0,
                                    "message": {"role": "assistant", "content": "第一轮回复"},
                                    "finish_reason": "stop"}],
                                  "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                                }
                                """))
                .willSetStateTo("turn2"));

        wireMock.stubFor(post(urlPathMatching("/api/v3/chat/completions"))
                .inScenario("multi-turn")
                .whenScenarioStateIs("turn2")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "chatcmpl-turn2",
                                  "object": "chat.completion",
                                  "created": 1700000002,
                                  "model": "test-model",
                                  "choices": [{"index": 0,
                                    "message": {"role": "assistant", "content": "第二轮回复"},
                                    "finish_reason": "stop"}],
                                  "usage": {"prompt_tokens": 30, "completion_tokens": 5, "total_tokens": 35}
                                }
                                """)));

        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey("fake-key")
                .modelName("test-model")
                .baseUrl(wireMock.baseUrl() + "/api/v3")
                .stream(false)  // WireMock stub 是非流式响应，必须禁用 stream
                .build();

        ReActAgent agent = ReActAgent.builder()
                .name("MultiTurnTest")
                .sysPrompt("你是助手。")
                .model(model)
                .maxIters(3)
                .build();

        // 第一轮
        Msg r1 = agent.call(Msg.builder().textContent("第一轮问题").build())
                .timeout(Duration.ofSeconds(10)).block();
        assertEquals("第一轮回复", r1.getTextContent());

        // 第二轮（Agent 会自动把第一轮历史带上）
        Msg r2 = agent.call(Msg.builder().textContent("第二轮问题").build())
                .timeout(Duration.ofSeconds(10)).block();
        assertEquals("第二轮回复", r2.getTextContent());

        // 至少调用了 2 次 API
        wireMock.verify(2, postRequestedFor(
                urlPathMatching("/api/v3/chat/completions")));
    }
}
