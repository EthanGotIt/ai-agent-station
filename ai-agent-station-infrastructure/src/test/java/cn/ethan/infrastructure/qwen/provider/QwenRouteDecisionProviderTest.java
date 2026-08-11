package cn.ethan.infrastructure.qwen.provider;

import cn.ethan.core.agent.enums.RouteTypeEnum;
import cn.ethan.core.agent.model.AgentRequestModel;
import cn.ethan.core.agent.model.RouteDecisionModel;
import cn.ethan.core.agent.support.CancellationToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Qwen 路由决策提供器测试：通过 Mock HTTP 验证 Chat Completions 结构化输出契约。
 *
 * @author ethan
 * @date 2026-08-05
 */
class QwenRouteDecisionProviderTest {

    private static final String ROUTER_POLICY = "POLICY_MARKER: trusted-router-boundary";

    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void callsChatCompletionsWithBoundedThinkingAndValidatedSchema() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey("test-key")
                .model("qwen3.7-plus")
                .temperature(0.0)
                .timeout(Duration.ofSeconds(2))
                .maxRetries(0)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(options)
                .build();
        QwenRouteDecisionProvider provider = new QwenRouteDecisionProvider(
                ChatClient.builder(chatModel).build(),
                true,
                512,
                ROUTER_POLICY
        );

        RouteDecisionModel decision = provider.decide(
                new AgentRequestModel(
                        "request-1",
                        "session-1",
                        "请研究今天的行业动态"
                ),
                "user-1",
                new CancellationToken()
        );

        assertEquals(RouteTypeEnum.REACT, decision.routeType());
        assertEquals("react", decision.executorId());
        assertTrue(requestPath.get().endsWith("/chat/completions"));
        assertTrue(requestBody.get().contains("\"enable_thinking\":true"));
        assertTrue(requestBody.get().contains("\"thinking_budget\":512"));
        assertTrue(requestBody.get().contains("routeType"));
        assertTrue(requestBody.get().contains("POLICY_MARKER: trusted-router-boundary"));
    }

    @Test
    void rejectsBlankRouterPolicy() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new QwenRouteDecisionProvider(null, true, 512, "  ")
        );
    }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            byte[] body = """
                    {
                      "id": "chatcmpl-test",
                      "object": "chat.completion",
                      "created": 1785916800,
                      "model": "qwen3.7-plus",
                      "choices": [{
                        "index": 0,
                        "message": {
                          "role": "assistant",
                          "content": "{\\"routeType\\":\\"REACT\\",\\"executorId\\":\\"react\\",\\"normalizedIntent\\":\\"research\\",\\"requiredFields\\":[],\\"reasonCode\\":\\"MODEL_REACT\\"}",
                          "refusal": null
                        },
                        "finish_reason": "stop",
                        "logprobs": null
                      }],
                      "usage": {
                        "prompt_tokens": 10,
                        "completion_tokens": 5,
                        "total_tokens": 15
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
