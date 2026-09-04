package cn.ethan.infrastructure.agent.coordination.springai;

import cn.ethan.core.agent.execution.AgentExecutionContext;
import cn.ethan.core.agent.execution.AgentExecutionStopReasonEnum;
import cn.ethan.core.agent.execution.AgentRuntimeMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型职责：验证同批工具按顺序执行，并在终止或重复失败后停止后续调用。
 *
 * @author ethan
 * @date 2026-09-04
 */
class ControlledToolCallingManagerTest {

    private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");

    @Test
    void stopsRemainingBatchAfterFinish() {
        SpringAiAgentTurnCoordinator.WorkflowInvocation invocation = invocation(3);
        AtomicInteger lookupCalls = new AtomicInteger();
        ToolCallback finish = callback("complete_agent_cycle", arguments ->
                new SpringAiAgentTurnCoordinator.ControlTools(invocation)
                        .completeAgentCycle("FINISH", "已完成"));
        ToolCallback lookup = callback("lookup_order", arguments -> {
            lookupCalls.incrementAndGet();
            return "不应执行";
        });

        ToolExecutionResultView result = execute(invocation, List.of(finish, lookup), List.of(
                new AssistantMessage.ToolCall("finish", "function", "complete_agent_cycle", "{}"),
                new AssistantMessage.ToolCall("lookup", "function", "lookup_order", "{\"orderId\":\"A\"}")));

        assertTrue(result.returnDirect());
        assertEquals(0, lookupCalls.get());
        assertTrue(invocation.terminal());
    }

    @Test
    void tripsRepeatedFailureAfterThreeEquivalentCalls() {
        SpringAiAgentTurnCoordinator.WorkflowInvocation invocation = invocation(3);
        AtomicInteger calls = new AtomicInteger();
        ToolCallback failing = callback("lookup_order", arguments -> {
            calls.incrementAndGet();
            throw new IllegalStateException("unstable backend");
        });
        List<AssistantMessage.ToolCall> requested = List.of(
                new AssistantMessage.ToolCall("lookup", "function", "lookup_order", "{ \"orderId\": \"A\" }"));

        execute(invocation, List.of(failing), requested);
        execute(invocation, List.of(failing), requested);
        ToolExecutionResultView third = execute(invocation, List.of(failing), requested);

        assertEquals(3, calls.get());
        assertTrue(third.returnDirect());
        assertEquals(AgentExecutionStopReasonEnum.TOOL_REPEATED_FAILURE,
                invocation.executionContext().stopReason());
        assertTrue(invocation.terminal());
    }

    @Test
    void boundsNormalToolResultBeforeItReturnsToTheModel() {
        SpringAiAgentTurnCoordinator.WorkflowInvocation invocation = invocation(3);
        ToolCallback oversized = callback("lookup_order", arguments -> "x".repeat(9_000));

        ToolExecutionResultView result = execute(invocation, List.of(oversized), List.of(
                new AssistantMessage.ToolCall("lookup", "function", "lookup_order", "{}")));

        assertTrue(result.responseData().startsWith("{\"truncated\":true,\"value\":\""));
        assertTrue(result.responseData().length() < 8_100);
    }

    @Test
    void letsTheCurrentToolFinishWhenTheResponseConsumedTheOutputBudget() {
        AgentExecutionContext context = new AgentExecutionContext(
                Clock.fixed(NOW, ZoneOffset.UTC), NOW.plusSeconds(30), 1, 3);
        String reservation = context.reserveOutput(1);
        context.settleOutput(reservation, null);
        SpringAiAgentTurnCoordinator.WorkflowInvocation invocation = new SpringAiAgentTurnCoordinator.WorkflowInvocation(
                context, Clock.fixed(NOW, ZoneOffset.UTC), AgentRuntimeMetrics.noop());
        ToolCallback lookup = callback("lookup_order", arguments -> "事实已读取");

        ToolExecutionResultView result = execute(invocation, List.of(lookup), List.of(
                new AssistantMessage.ToolCall("lookup", "function", "lookup_order", "{}")));

        assertTrue(result.returnDirect());
        assertTrue(invocation.terminal());
        assertEquals(AgentExecutionStopReasonEnum.OUTPUT_BUDGET_EXCEEDED, context.stopReason());
    }

    private ToolExecutionResultView execute(
            SpringAiAgentTurnCoordinator.WorkflowInvocation invocation,
            List<ToolCallback> callbacks,
            List<AssistantMessage.ToolCall> calls
    ) {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .toolContext(Map.of(ControlledToolCallingAdvisor.TOOL_STATE_KEY, invocation))
                .build();
        Prompt prompt = new Prompt(List.of(new UserMessage("test")), options);
        AssistantMessage assistant = AssistantMessage.builder().toolCalls(calls).build();
        var result = new ControlledToolCallingManager().executeToolCalls(
                prompt, new ChatResponse(List.of(new Generation(assistant))));
        ToolResponseMessage response = (ToolResponseMessage) result.conversationHistory()
                .get(result.conversationHistory().size() - 1);
        return new ToolExecutionResultView(result.returnDirect(), response.getResponses().get(0).responseData());
    }

    private SpringAiAgentTurnCoordinator.WorkflowInvocation invocation(int threshold) {
        return new SpringAiAgentTurnCoordinator.WorkflowInvocation(
                new AgentExecutionContext(Clock.fixed(NOW, ZoneOffset.UTC), NOW.plusSeconds(30), 100, threshold),
                Clock.fixed(NOW, ZoneOffset.UTC), AgentRuntimeMetrics.noop());
    }

    private ToolCallback callback(String name, ToolCall call) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name).description(name).inputSchema("{}").build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String input) { return call.run(input); }
        };
    }

    @FunctionalInterface
    private interface ToolCall { String run(String input); }

    private record ToolExecutionResultView(boolean returnDirect, String responseData) { }
}
