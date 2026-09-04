package cn.ethan.core.agent.execution;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型职责：验证 Turn 共享输出预算、上下文预算和重复工具失败计数的边界。
 *
 * @author ethan
 * @date 2026-09-04
 */
class AgentExecutionContextTest {

    private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");

    @Test
    void reservesOutputBeforeRequestAndChargesUnknownUsageConservatively() {
        AgentExecutionContext context = context(100, 3);

        String first = context.reserveOutput(60);
        assertTrue(first != null);
        context.settleOutput(first, 0);
        assertEquals(60, context.outputTokensUsed());

        String second = context.reserveOutput(60);
        assertTrue(second != null);
        context.settleOutput(second, 10);
        assertEquals(70, context.outputTokensUsed());

        String finalReservation = context.reserveOutput(40);
        assertTrue(finalReservation != null);
        context.settleOutput(finalReservation, null);
        assertEquals(100, context.outputTokensUsed());
        assertEquals(AgentExecutionStopReasonEnum.OUTPUT_BUDGET_EXCEEDED, context.stopReason());
        assertNull(context.reserveOutput(1));
    }

    @Test
    void repeatedFailureTupleTripsAndSuccessResetsCircuit() {
        AgentExecutionContext context = context(100, 3);

        assertFalse(context.recordToolFailure("lookup", "{\"orderId\":\"A\"}", "TOOL_INVALID_STATE"));
        assertFalse(context.recordToolFailure("lookup", "{ \"orderId\": \"A\" }", "TOOL_INVALID_STATE"));
        assertTrue(context.recordToolFailure("lookup", "{\"orderId\":\"A\"}", "TOOL_INVALID_STATE"));

        context.recordToolSuccess();
        assertFalse(context.recordToolFailure("lookup", "{\"orderId\":\"A\"}", "TOOL_INVALID_STATE"));
        assertFalse(context.recordToolFailure("lookup", "{\"orderId\":\"B\"}", "TOOL_INVALID_STATE"));
    }

    @Test
    void contextBudgetStopsWhenFullPromptEstimateExceedsLimit() {
        AgentExecutionContext context = context(100, 3);
        context.initializeContextBudget(50, 40);

        assertTrue(context.checkContextBudget(50));
        assertFalse(context.checkContextBudget(51));
        assertEquals(AgentExecutionStopReasonEnum.CONTEXT_BUDGET_EXCEEDED, context.stopReason());
    }

    private AgentExecutionContext context(int output, int threshold) {
        return new AgentExecutionContext(
                Clock.fixed(NOW, ZoneOffset.UTC), NOW.plusSeconds(30), output, threshold);
    }
}
