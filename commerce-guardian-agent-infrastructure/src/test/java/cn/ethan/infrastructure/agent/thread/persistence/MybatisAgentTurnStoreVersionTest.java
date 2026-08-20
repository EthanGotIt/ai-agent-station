package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 类型职责：验证 Turn Store 将版本条件、单调推进和终态保护落实到 MyBatis 更新边界。
 *
 * @author ethan
 * @date 2026-08-21
 */
class MybatisAgentTurnStoreVersionTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void casUsesExpectedVersionAndReturnsFalseWhenNoRowWasUpdated() {
        AtomicInteger updateResult = new AtomicInteger(1);
        AtomicReference<UpdateWrapper<AgentTurnEntity>> captured = new AtomicReference<>();
        AgentTurnMapper mapper = mapper(AgentTurnMapper.class, (method, arguments) -> {
            if (method.equals("update")) {
                captured.set((UpdateWrapper<AgentTurnEntity>) arguments[1]);
                return updateResult.get();
            }
            return defaultValue(method);
        });
        MybatisAgentTurnStore store = store(mapper);
        AgentTurnModel expected = turn();
        AgentTurnModel next = expected.active(NOW.plusSeconds(1));

        assertTrue(store.updateTurn(expected, next));
        UpdateWrapper<AgentTurnEntity> wrapper = captured.get();
        assertNotNull(wrapper);
        assertTrue(wrapper.getSqlSet().contains("VERSION_NO"));
        assertTrue(wrapper.getExpression().getSqlSegment().contains("VERSION_NO"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(1L));

        updateResult.set(0);
        assertFalse(store.updateTurn(expected, next));
    }

    @Test
    void terminalTurnCannotBeRewrittenEvenWithTheCurrentVersion() {
        AtomicInteger updates = new AtomicInteger();
        AgentTurnMapper mapper = mapper(AgentTurnMapper.class, (method, arguments) -> {
            if (method.equals("update")) {
                updates.incrementAndGet();
                return 1;
            }
            return defaultValue(method);
        });
        MybatisAgentTurnStore store = store(mapper);
        AgentTurnModel terminal = turn().terminal(AgentTurnStatusEnum.COMPLETED, null, NOW.plusSeconds(1));
        AgentTurnModel illegalNext = terminal.terminal(AgentTurnStatusEnum.FAILED, "LATE", NOW.plusSeconds(2));

        assertFalse(store.updateTurn(terminal, illegalNext));
        assertEquals(0, updates.get());
    }

    private MybatisAgentTurnStore store(AgentTurnMapper mapper) {
        JacksonAgentWorkflowAnswerCodec codec = new JacksonAgentWorkflowAnswerCodec(new ObjectMapper());
        return new MybatisAgentTurnStore(
                mapper,
                mapper(AgentItemMapper.class, (method, arguments) -> defaultValue(method)),
                mapper(AgentThreadMapper.class, (method, arguments) -> defaultValue(method)),
                codec);
    }

    private AgentTurnModel turn() {
        return new AgentTurnModel(
                "turn-1", "thread-1", "user-1", "request-1", "message",
                AgentTurnStatusEnum.QUEUED, 0, null, null, NOW, null, null);
    }

    @SuppressWarnings("unchecked")
    private <T> T mapper(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> invocation.invoke(method.getName(), arguments));
    }

    private static Object defaultValue(String method) {
        if (method.equals("toString")) {
            return "MapperTestProxy";
        }
        if (method.equals("selectList")) {
            return java.util.List.of();
        }
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] arguments);
    }
}
