package cn.ethan.infrastructure.agent.coordination.springai;

import cn.ethan.core.agent.context.AgentContextSummaryRequest;
import cn.ethan.core.agent.execution.AgentExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型职责：验证摘要流共享 Turn 预算，并在达到批准额度后取消继续消费。
 *
 * @author ethan
 * @date 2026-09-05
 */
class SpringAiAgentContextSummaryGatewayTest {

    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

    @Test
    void stopsAggregatingSummaryWhenApprovedOutputIsReached() {
        ChatModel model = new ChatModel() {
            @Override
            public ToolCallingChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            @Override
            public ChatResponse call(Prompt prompt) {
                throw new AssertionError("摘要只允许使用流式 ChatModel");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(response("x".repeat(400)), response("不应继续聚合"));
            }
        };
        SpringAiAgentContextSummaryGateway gateway = new SpringAiAgentContextSummaryGateway(model);
        AgentExecutionContext context = new AgentExecutionContext(
                Clock.fixed(NOW, ZoneOffset.UTC), NOW.plusSeconds(60), 128, 3);

        String summary = gateway.summarize(new AgentContextSummaryRequest(
                "thread-1", "", List.of(), 1, 1, "context-summary-v2", 128), context);

        assertTrue(summary.isEmpty(), "达到摘要输出上限时不得提交截断摘要");
        assertEquals(128, context.outputTokensUsed(), "缺失 usage 时保守结算批准额度");
    }

    @Test
    void treatsAnEmptySummaryStreamAsAConservativeNoUsageResponse() {
        ChatModel model = new ChatModel() {
            @Override
            public ToolCallingChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            @Override
            public ChatResponse call(Prompt prompt) {
                throw new AssertionError("摘要只允许使用流式 ChatModel");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.empty();
            }
        };
        AgentExecutionContext context = new AgentExecutionContext(
                Clock.fixed(NOW, ZoneOffset.UTC), NOW.plusSeconds(60), 128, 3);

        String summary = new SpringAiAgentContextSummaryGateway(model).summarize(
                new AgentContextSummaryRequest(
                        "thread-1", "旧摘要", List.of(), 1, 1, "context-summary-v2", 128), context);

        assertTrue(summary.isEmpty());
        assertEquals(128, context.outputTokensUsed());
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
