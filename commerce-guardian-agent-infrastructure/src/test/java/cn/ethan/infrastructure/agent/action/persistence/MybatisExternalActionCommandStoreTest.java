package cn.ethan.infrastructure.agent.action.persistence;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionStatusEnum;
import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 外部动作持久化契约测试：验证状态收敛 CAS 携带完整旧 Lease 快照。
 *
 * @author ethan
 * @date 2026-08-20
 */
class MybatisExternalActionCommandStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void processingCasChecksOldOwnerVersionStatusAndAttemptCounters() {
        AtomicReference<UpdateWrapper<ExternalActionCommandEntity>> captured = new AtomicReference<>();
        AtomicInteger updateCalls = new AtomicInteger();
        ExternalActionCommandMapper mapper = mapper(captured, updateCalls, 0);
        MybatisExternalActionCommandStore store = new MybatisExternalActionCommandStore(mapper);
        ExternalActionCommandModel expected = processing();

        assertFalse(store.update(expected, expected.succeeded(NOW.plusSeconds(1))));

        UpdateWrapper<ExternalActionCommandEntity> wrapper = captured.get();
        assertNotNull(wrapper);
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("VERSION_NO"));
        assertTrue(sql.contains("STATUS"));
        assertTrue(sql.contains("LEASE_OWNER"));
        assertTrue(sql.contains("LEASE_UNTIL"));
        assertTrue(sql.contains("ATTEMPT_COUNT"));
        assertTrue(sql.contains("RETRY_CYCLE_ATTEMPT_COUNT"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("PROCESSING"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("worker-claimed"));
        assertEquals(1, updateCalls.get());
    }

    @Test
    void nonProcessingCompletionTransitionNeverReachesMapper() {
        AtomicReference<UpdateWrapper<ExternalActionCommandEntity>> captured = new AtomicReference<>();
        AtomicInteger updateCalls = new AtomicInteger();
        MybatisExternalActionCommandStore store = new MybatisExternalActionCommandStore(
                mapper(captured, updateCalls, 1));
        ExternalActionCommandModel expected = pending();
        ExternalActionCommandModel next = new ExternalActionCommandModel(
                expected.commandId(), expected.runId(), expected.threadId(), expected.turnId(), expected.userId(),
                expected.type(), expected.idempotencyKey(), expected.payloadJson(), ExternalActionStatusEnum.PENDING,
                expected.attemptCount(), expected.maxAttempts(), NOW.plusSeconds(1), null, null,
                expected.lastErrorCode(), expected.lastErrorMessage(), expected.createdAt(), NOW.plusSeconds(1), null,
                expected.version() + 1, expected.retryCycleAttemptCount());

        assertFalse(store.update(expected, next));
        assertEquals(0, updateCalls.get());
    }

    private ExternalActionCommandModel processing() {
        return new ExternalActionCommandModel(
                "command-1", "run-1", "thread-1", "turn-1", "user-1", ExternalActionTypeEnum.REFUND,
                "idem-1", "{}", ExternalActionStatusEnum.PROCESSING, 4, 3, null,
                "worker-claimed", NOW.plusSeconds(30), null, null, NOW.minusSeconds(60), NOW, null, 5, 2);
    }

    private ExternalActionCommandModel pending() {
        return new ExternalActionCommandModel(
                "command-1", "run-1", "thread-1", "turn-1", "user-1", ExternalActionTypeEnum.REFUND,
                "idem-1", "{}", ExternalActionStatusEnum.PENDING, 0, 3, NOW,
                null, null, null, null, NOW, NOW, null, 0, 0);
    }

    @SuppressWarnings("unchecked")
    private ExternalActionCommandMapper mapper(
            AtomicReference<UpdateWrapper<ExternalActionCommandEntity>> captured,
            AtomicInteger updateCalls,
            int updateResult
    ) {
        return (ExternalActionCommandMapper) Proxy.newProxyInstance(
                ExternalActionCommandMapper.class.getClassLoader(),
                new Class<?>[]{ExternalActionCommandMapper.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("update")) {
                        updateCalls.incrementAndGet();
                        captured.set((UpdateWrapper<ExternalActionCommandEntity>) arguments[1]);
                        return updateResult;
                    }
                    if (method.getName().equals("toString")) {
                        return "ExternalActionCommandMapperTestProxy";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
