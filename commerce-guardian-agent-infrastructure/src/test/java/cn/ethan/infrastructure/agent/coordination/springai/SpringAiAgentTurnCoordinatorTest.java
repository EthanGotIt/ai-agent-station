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
import cn.ethan.core.agent.thread.AgentQuestionAnswerInput;
import cn.ethan.core.agent.workflow.AgentWorkflowEngine;
import cn.ethan.core.agent.workflow.AgentQuestionCardAnswerActionEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardResumeTargetEnum;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 类型职责：验证协调器使用 DeepSeek 兼容的流式 ChatClient 路径，并将完整回复交给持久化 Item 层。
 *
 * @author ethan
 * @date 2026-08-21
 */
class SpringAiAgentTurnCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void consumesEveryContentDeltaButDoesNotPublishTransientEvents() {
        List<String> deltas = List.of("你", "好", "！");
        CapturingEvents events = new CapturingEvents();
        SpringAiAgentTurnCoordinator coordinator = coordinator(new StreamingModel(
                Flux.just(deltas.stream().map(this::response).toArray(ChatResponse[]::new)), false), events);

        AgentTurnCoordinator.AgentCoordinatorResult result = coordinator.run(
                thread(), turn(), List.of(), null);

        assertEquals("你好！", result.assistantMessage());
        assertEquals(List.of(), events.published);
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

    @Test
    void cancellationAnswerResumesWorkflowEvenWhenAnswersAreEmpty() {
        CapturingEvents events = new CapturingEvents();
        AtomicReference<Map<String, String>> received = new AtomicReference<>();
        AgentWorkflowEngine workflow = new AgentWorkflowEngine() {
            @Override
            public StartResult start(AgentThreadModel thread, AgentTurnModel turn,
                                     String operation, Map<String, String> arguments) {
                throw new AssertionError("workflow start must not be called");
            }

            @Override
            public ResumeResult resume(AgentThreadModel thread, AgentTurnModel turn,
                                       Map<String, String> answers) {
                received.set(answers);
                return new ResumeResult("本次操作已结束", "REJECTED", null);
            }
        };
        SpringAiAgentTurnCoordinator coordinator = coordinator(
                new StreamingModel(Flux.error(new AssertionError("model must not be called")), false),
                events, workflow);

        AgentTurnCoordinator.AgentCoordinatorResult result = coordinator.run(
                thread(), cancellationTurn(), List.of(), Map.of());

        assertEquals("本次操作已结束", result.assistantMessage());
        assertEquals(Map.of(), received.get());
    }

    private SpringAiAgentTurnCoordinator coordinator(ChatModel model, CapturingEvents events) {
        return coordinator(model, events, new AgentWorkflowEngine() {
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
        });
    }

    private SpringAiAgentTurnCoordinator coordinator(
            ChatModel model, CapturingEvents events, AgentWorkflowEngine workflowEngine) {
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
                workflowEngine,
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

    private AgentTurnModel cancellationTurn() {
        AgentQuestionAnswerInput answer = new AgentQuestionAnswerInput(
                "question-1", "run-1", AgentQuestionCardResumeTargetEnum.WORKFLOW, 2L, Map.of(),
                AgentQuestionCardAnswerActionEnum.CANCEL);
        return new AgentTurnModel(
                "turn-1", "thread-1", "user-1", "request-1", "结束本次操作",
                AgentTurnStatusEnum.ACTIVE, 0, "run-1", null, NOW, NOW, null, answer);
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
