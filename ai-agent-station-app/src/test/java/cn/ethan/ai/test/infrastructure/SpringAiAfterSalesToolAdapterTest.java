package cn.ethan.ai.test.infrastructure;

import cn.ethan.ai.domain.agent.model.AfterSalesCaseView;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesRefundResult;
import cn.ethan.ai.domain.agent.model.AfterSalesToolRequest;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesRepository;
import cn.ethan.ai.domain.agent.port.driven.IOrderGateway;
import cn.ethan.ai.infrastructure.adapter.ai.SpringAiAfterSalesToolAdapter;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.support.GenericApplicationContext;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class SpringAiAfterSalesToolAdapterTest {

    @Test
    void shouldUseRawChatModelThenToolCallingManagerWithPrivateUserContext() {
        AtomicInteger modelCalls = new AtomicInteger();
        GenericApplicationContext context = new GenericApplicationContext();
        try (context) {
            context.registerBean("afterSalesModel", ChatModel.class, () -> new ScriptedChatModel(modelCalls));
            context.refresh();
            SpringAiAfterSalesToolAdapter adapter = new SpringAiAfterSalesToolAdapter(
                    context,
                    new OrderOnlyRepository(),
                    ToolCallingManager.builder().build(),
                    null,
                    "afterSalesModel",
                    "stub"
            );

            AfterSalesToolRequest request = adapter.proposeOrderQuery(
                    "退款订单 ORDER-1", "user-1", "session-1", "ORDER-1", "DAMAGED", null);
            AfterSalesToolResult result = adapter.executeOrderQuery(request, "user-1", "退款订单 ORDER-1");

            Assertions.assertEquals(1, modelCalls.get());
            Assertions.assertEquals("query_order", request.toolName());
            Assertions.assertTrue(result.success());
            Assertions.assertEquals("ORDER-1", result.order().orderId());
            Assertions.assertEquals("user-1", result.order().ownerId());
            Assertions.assertFalse(result.outputJson().contains("user-2"));
        }
    }

    private static final class ScriptedChatModel implements ChatModel {
        private final AtomicInteger calls;

        private ScriptedChatModel(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public @NonNull ChatResponse call(@NonNull Prompt prompt) {
            calls.incrementAndGet();
            AssistantMessage message = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call-1", "function", "query_order", "{\"orderId\":\"ORDER-1\"}"
                    )))
                    .build();
            return new ChatResponse(List.of(new Generation(message)));
        }

        @Override
        public @NonNull OpenAiChatOptions getOptions() {
            return OpenAiChatOptions.builder().model("stub").build();
        }
    }

    private static final class OrderOnlyRepository implements IOrderGateway {
        @Override
        public Optional<AfterSalesOrderSnapshot> findOrder(String orderId, String requesterId) {
            return Optional.of(new AfterSalesOrderSnapshot(orderId, "user-1", "PAID", null));
        }
    }
}
