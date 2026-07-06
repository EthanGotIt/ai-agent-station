package cn.ethan.ai.test.infrastructure;

import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesToolRequest;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;
import cn.ethan.ai.domain.agent.port.driven.IOrderGateway;
import cn.ethan.ai.infrastructure.adapter.ai.SpringAiAfterSalesToolAdapter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SpringAiAfterSalesToolAdapterTest {

    @Test
    void shouldUseChatModelThenToolCallingManagerWithPrivateUserContext() {
        ChatModel chatModel = chatModelReturningToolCall("query_order", "{\"orderId\":\"ORDER-1\"}");
        SpringAiAfterSalesToolAdapter adapter = new SpringAiAfterSalesToolAdapter(
                new OrderOnlyRepository(),
                ToolCallingManager.builder().build(),
                chatModel,
                "stub"
        );

        AfterSalesToolRequest request = adapter.proposeOrderQuery(
                "退款订单 ORDER-1", "user-1", "session-1", null, "DAMAGED", null);
        AfterSalesToolResult result = adapter.executeOrderQuery(request, "user-1", "退款订单 ORDER-1");

        Assertions.assertEquals("query_order", request.toolName());
        Assertions.assertTrue(result.success());
        Assertions.assertEquals("ORDER-1", result.order().orderId());
        Assertions.assertEquals("user-1", result.order().ownerId());
        Assertions.assertFalse(result.outputJson().contains("user-2"));
    }

    private ChatModel chatModelReturningToolCall(String toolName, String arguments) {
        ChatModel chatModel = mock(ChatModel.class);
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", toolName, arguments)))
                .build();
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(message)));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        return chatModel;
    }

    private static final class OrderOnlyRepository implements IOrderGateway {
        @Override
        public Optional<AfterSalesOrderSnapshot> findOrder(String orderId, String requesterId) {
            return Optional.of(new AfterSalesOrderSnapshot(orderId, "user-1", "PAID", null));
        }
    }
}
