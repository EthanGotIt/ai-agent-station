package cn.ethan.ai.test.evaluation;

import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesToolRequest;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;
import cn.ethan.ai.domain.agent.port.driven.IOrderGateway;
import cn.ethan.ai.infrastructure.adapter.ai.SpringAiAfterSalesToolAdapter;
import cn.ethan.ai.test.support.DotenvExtension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

@SpringBootTest(properties = {
        "spring.ai.model.chat=openai"
})
@ActiveProfiles("dev")
@ExtendWith(DotenvExtension.class)
@EnabledIf(value = "cn.ethan.ai.test.support.DotenvConditions#isLiveEvaluationEnabled",
        disabledReason = "实时模型评估需通过 .env 开启")
public class ModelToolCallSmokeIT {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private ToolCallingManager toolCallingManager;

    @Value("${after-sales.execution-model:deepseek-v4-flash}")
    private String executionModel;

    @Test
    void shouldProposeAndExecuteOrderQuery() {
        SpringAiAfterSalesToolAdapter adapter = new SpringAiAfterSalesToolAdapter(
                new StubOrderGateway(),
                toolCallingManager,
                chatModel,
                executionModel);

        ChatResponse rawResponse = chatModel.call(new org.springframework.ai.chat.prompt.Prompt(
                java.util.List.of(
                        new org.springframework.ai.chat.messages.SystemMessage(
                                "你是售后退款意图解析器。你只能调用 query_order，只提取用户明确提供的 orderId。"),
                        new org.springframework.ai.chat.messages.UserMessage(
                                "我要退款，订单号 ORDER-SMOKE-001")
                ),
                org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(executionModel)
                        .toolCallbacks(adapterQueryOrderTool())
                        .temperature(0.0)
                        .build()));
        System.out.println("Raw response: " + rawResponse);
        System.out.println("Has tool calls: " + (rawResponse != null && rawResponse.hasToolCalls()));

        AfterSalesToolRequest request = adapter.proposeOrderQuery(
                "我要退款，订单号 ORDER-SMOKE-001", "user-1", "session-1", null, "DAMAGED", null);

        System.out.println("Tool request: " + request.toolName() + " / " + request.argumentsJson());

        Assertions.assertEquals("query_order", request.toolName());
        AfterSalesToolResult result = adapter.executeOrderQuery(request, "user-1", "退款订单");
        Assertions.assertTrue(result.success());
        Assertions.assertEquals("ORDER-SMOKE-001", result.order().orderId());
    }

    private org.springframework.ai.tool.ToolCallback adapterQueryOrderTool() {
        return new org.springframework.ai.tool.ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name("query_order")
                        .description("按用户明确提供的订单号查询订单，只读工具")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}},\"required\":[\"orderId\"]}")
                        .build();
            }

            @Override
            public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() {
                return org.springframework.ai.tool.metadata.ToolMetadata.builder().returnDirect(false).build();
            }

            @Override
            public String call(String toolInput) {
                return "called";
            }

            @Override
            public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
                return "called";
            }
        };
    }

    private static final class StubOrderGateway implements IOrderGateway {
        @Override
        public Optional<AfterSalesOrderSnapshot> findOrder(String orderId, String requesterId) {
            return Optional.of(new AfterSalesOrderSnapshot(orderId, "user-1", "PAID", 5));
        }
    }
}
