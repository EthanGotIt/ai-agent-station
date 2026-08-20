package cn.ethan.infrastructure.agent.coordination.springai;

import cn.ethan.core.agent.coordination.AgentTurnCoordinator;
import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.execution.AgentRuntimeMetrics;
import cn.ethan.core.agent.execution.AgentExecutionCancelledException;
import cn.ethan.core.agent.execution.AgentExecutionContext;
import cn.ethan.core.agent.execution.AgentExecutionTimeoutException;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentThreadStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowEngine;
import cn.ethan.core.commerce.order.LogisticsGateway;
import cn.ethan.core.commerce.order.OrderGateway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 类型职责：验证协调器使用 DeepSeek 兼容的流式 ChatClient 路径并逐段发布事件。
 *
 * @author ethan
 * @date 2026-08-21
 */
class SpringAiAgentTurnCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void streamsEveryContentDeltaAndUsesFullMessageForRuntime() {
        List<String> deltas = List.of("你", "好", "！");
        CapturingEvents events = new CapturingEvents();
        SpringAiAgentTurnCoordinator coordinator = coordinator(new StreamingModel(
                Flux.just(deltas.stream().map(this::response).toArray(ChatResponse[]::new)), false), events);

        AgentTurnCoordinator.AgentCoordinatorResult result = coordinator.run(
                thread(), turn(), List.of(), null);

        assertEquals("你好！", result.assistantMessage());
        assertEquals(deltas, events.published.stream()
                .filter(event -> "assistant.delta".equals(event.type()))
                .map(AgentThreadEventGateway.AgentThreadEvent::payload)
                .toList());
        assertEquals(3, events.published.size());
    }

    @Test
    void doesNotFallBackToSynchronousCall() {
        CapturingEvents events = new CapturingEvents();
        SpringAiAgentTurnCoordinator coordinator = coordinator(new StreamingModel(
                Flux.just(response("流式")), true), events);

        assertEquals("流式", coordinator.run(thread(), turn(), List.of(), null).assistantMessage());
    }

    @Test
    void providerFailureIsNotConvertedIntoAnAssistantMessage() {
        CapturingEvents events = new CapturingEvents();
        SpringAiAgentTurnCoordinator coordinator = coordinator(new StreamingModel(
                Flux.error(new IllegalStateException("provider failure")), false), events);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> coordinator.run(thread(), turn(), List.of(), null)
        );

        assertEquals("Agent 模型调用失败", failure.getMessage());
        assertEquals(List.of(), events.published);
    }

    @Test
    void classifiesStreamingDeadlineAsTimeout() {
        CapturingEvents events = new CapturingEvents();
        SpringAiAgentTurnCoordinator coordinator = coordinator(new StreamingModel(
                Flux.never(), false), events);
        AgentExecutionContext context = new AgentExecutionContext(
                Clock.fixed(NOW, ZoneOffset.UTC), NOW.plusMillis(50));

        assertThrows(
                AgentExecutionTimeoutException.class,
                () -> coordinator.run(thread(), turn(), List.of(), null, context)
        );
        assertEquals(List.of(), events.published);
    }

    @Test
    void stopsBeforeProviderCallWhenExecutionWasCancelled() {
        CapturingEvents events = new CapturingEvents();
        SpringAiAgentTurnCoordinator coordinator = coordinator(new StreamingModel(
                Flux.just(response("不应发送")), false), events);
        AgentExecutionContext context = new AgentExecutionContext(
                Clock.fixed(NOW, ZoneOffset.UTC), NOW.plus(Duration.ofSeconds(1)));
        context.cancel();

        assertThrows(
                AgentExecutionCancelledException.class,
                () -> coordinator.run(thread(), turn(), List.of(), null, context)
        );
        assertEquals(List.of(), events.published);
    }

    private SpringAiAgentTurnCoordinator coordinator(ChatModel model, CapturingEvents events) {
        AgentItemStore items = new AgentItemStore() {
            @Override
            public long appendItem(AgentItemModel item) {
                return item.sequence();
            }

            @Override
            public List<AgentItemModel> listItems(String userId, String threadId, long afterSequence, int limit) {
                return List.of();
            }
        };
        return new SpringAiAgentTurnCoordinator(
                ChatClient.builder(model).build(),
                (orderId, userId) -> null,
                (orderId, userId) -> List.of(),
                new AgentWorkflowEngine() {
                    @Override
                    public StartResult start(AgentThreadModel thread, AgentTurnModel turn,
                                             String operation, java.util.Map<String, String> arguments) {
                        throw new AssertionError("workflow tool should not be called");
                    }

                    @Override
                    public ResumeResult resume(AgentThreadModel thread, AgentTurnModel turn,
                                               java.util.Map<String, String> answers) {
                        throw new AssertionError("workflow resume should not be called");
                    }
                },
                items,
                events,
                Clock.fixed(NOW, ZoneOffset.UTC),
                AgentRuntimeMetrics.noop()
        );
    }

    private AgentThreadModel thread() {
        return new AgentThreadModel(
                "thread-1", "user-1", "测试", AgentThreadStatusEnum.ACTIVE,
                null, null, 0, NOW, NOW);
    }

    private AgentTurnModel turn() {
        return new AgentTurnModel(
                "turn-1", "thread-1", "user-1", "request-1", "查询订单",
                AgentTurnStatusEnum.ACTIVE, 0, null, null, NOW, NOW, null);
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private static final class StreamingModel implements ChatModel {
        private final Flux<ChatResponse> responses;
        private final boolean failOnCall;

        private StreamingModel(Flux<ChatResponse> responses, boolean failOnCall) {
            this.responses = responses;
            this.failOnCall = failOnCall;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            if (failOnCall) {
                throw new AssertionError("synchronous ChatModel.call must not be used");
            }
            throw new AssertionError("test model only supports streaming");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return responses;
        }
    }

    private static final class CapturingEvents implements AgentThreadEventGateway {
        private final List<AgentThreadEvent> published = new ArrayList<>();

        @Override
        public void publish(AgentThreadEvent event) {
            published.add(event);
        }
    }
}
