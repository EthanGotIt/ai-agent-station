package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.execution.AgentTurnExecutionStateModel;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型职责：验证 Turn 恢复游标往返、工具批次约束及乐观并发版本。
 *
 * @author ethan
 * @date 2026-09-29
 */
class MybatisAgentTurnExecutionStateStoreTest {

    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");

    @Test
    void codecRoundTripsOnlyControlledToolExecutionState() {
        AgentTurnExecutionStateModel.ToolCall call = new AgentTurnExecutionStateModel.ToolCall(
                "provider-call-1", "invocation-1", "lookup_order", "{\"orderId\":\"ORDER-1\"}",
                AgentTurnExecutionStateModel.ToolCallStatusEnum.PENDING, null);
        AgentTurnExecutionStateModel state = new AgentTurnExecutionStateModel(
                "turn-1", 17_000, "batch-1", 0, List.of(call), 2, NOW);
        JacksonAgentTurnExecutionStateCodec codec = new JacksonAgentTurnExecutionStateCodec(new ObjectMapper());

        List<AgentTurnExecutionStateModel.ToolCall> restored = codec.decode(codec.encode(state.toolCalls()));

        assertEquals(List.of(call), restored);
        assertFalse(codec.encode(state.toolCalls()).contains("thinking"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateUsesTurnAndExpectedVersionAsCasBoundary() {
        AtomicReference<UpdateWrapper<AgentTurnExecutionStateEntity>> captured = new AtomicReference<>();
        AtomicReference<Integer> updateResult = new AtomicReference<>(1);
        AgentTurnExecutionStateMapper mapper = (AgentTurnExecutionStateMapper) Proxy.newProxyInstance(
                AgentTurnExecutionStateMapper.class.getClassLoader(),
                new Class<?>[]{AgentTurnExecutionStateMapper.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("update")) {
                        captured.set((UpdateWrapper<AgentTurnExecutionStateEntity>) arguments[1]);
                        return updateResult.get();
                    }
                    if (method.getName().equals("toString")) return "execution-state-mapper-test";
                    return null;
                });
        MybatisAgentTurnExecutionStateStore store = new MybatisAgentTurnExecutionStateStore(
                mapper, new JacksonAgentTurnExecutionStateCodec(new ObjectMapper()));
        AgentTurnExecutionStateModel expected = new AgentTurnExecutionStateModel(
                "turn-1", 100, null, 0, List.of(), 4, NOW);
        AgentTurnExecutionStateModel next = expected.next(250, null, 0, List.of(), NOW.plusSeconds(1));

        assertTrue(store.update(expected, next));
        assertTrue(captured.get().getExpression().getSqlSegment().contains("VERSION_NO"));
        assertTrue(captured.get().getParamNameValuePairs().containsValue(4L));
        updateResult.set(0);
        assertFalse(store.update(expected, next));
    }

    @Test
    void rejectsUnboundedOrInconsistentToolBatchSnapshots() {
        assertThrows(IllegalArgumentException.class, () -> new AgentTurnExecutionStateModel.ToolCall(
                "call-1", "invocation-1", "lookup_order", "{}",
                AgentTurnExecutionStateModel.ToolCallStatusEnum.COMPLETED, null));
        assertThrows(IllegalArgumentException.class, () -> new AgentTurnExecutionStateModel(
                "turn-1", 0, null, 1, List.of(), 0, NOW));
    }
}
