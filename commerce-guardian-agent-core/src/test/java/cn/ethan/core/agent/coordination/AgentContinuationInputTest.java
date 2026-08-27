package cn.ethan.core.agent.coordination;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型职责：验证 Continuation 幂等键覆盖全部触发事实并保持格式稳定。
 *
 * @author ethan
 * @date 2026-08-27
 */
class AgentContinuationInputTest {

    @Test
    void idempotencyKeyIncludesCommandStatusSequenceAndCycle() {
        AgentContinuationInput first = new AgentContinuationInput(
                "root", "parent", "run", "command", "succeeded", 10L, 1);
        AgentContinuationInput same = new AgentContinuationInput(
                "root", "parent", "run", "command", "SUCCEEDED", 10L, 1);
        AgentContinuationInput differentSequence = new AgentContinuationInput(
                "root", "parent", "run", "command", "SUCCEEDED", 11L, 1);

        assertEquals(first.idempotencyKey(), same.idempotencyKey());
        assertNotEquals(first.idempotencyKey(), differentSequence.idempotencyKey());
        assertTrue(first.idempotencyKey().startsWith("continuation:"));
        assertEquals(77, first.idempotencyKey().length());
    }
}
