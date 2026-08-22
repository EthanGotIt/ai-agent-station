package cn.ethan.infrastructure.agent.action.fixture;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionResultModel;
import cn.ethan.core.agent.action.ExternalActionResultStore;
import cn.ethan.core.agent.action.ExternalActionStatusEnum;
import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import cn.ethan.core.commerce.order.OrderActionGateway;
import cn.ethan.core.commerce.order.OrderVisibilityEnum;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型职责：验证本地订单动作执行器对催发货、隐藏和恢复的分派与幂等回放。
 *
 * @author ethan
 * @date 2026-08-23
 */
class LocalExternalActionExecutorOrderActionTest {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Test
    void dispatchesOrderActionsAndDoesNotRepeatAfterIdempotentReplay() {
        InMemoryResults results = new InMemoryResults();
        RecordingOrderActions actions = new RecordingOrderActions();
        LocalExternalActionExecutor executor = new LocalExternalActionExecutor(
                results, actions, Clock.fixed(NOW, ZoneOffset.UTC));

        assertTrue(executor.execute(command(ExternalActionTypeEnum.EXPEDITE, "{}", "expedite")).success());
        assertTrue(executor.execute(command(ExternalActionTypeEnum.HIDE_ORDER,
                "{\"visibility\":\"HIDDEN\"}", "hide")).success());
        assertTrue(executor.execute(command(ExternalActionTypeEnum.RESTORE_ORDER,
                "{\"visibility\":\"ACTIVE\"}", "restore")).success());
        assertTrue(executor.execute(command(ExternalActionTypeEnum.EXPEDITE, "{}", "expedite")).success());

        assertEquals(1, actions.expediteCalls.get());
        assertEquals(1, actions.hideCalls.get());
        assertEquals(1, actions.restoreCalls.get());
        assertEquals(3, results.values.size());
    }

    private static ExternalActionCommandModel command(
            ExternalActionTypeEnum type,
            String payload,
            String key
    ) {
        String fullPayload = payload.equals("{}")
                ? "{\"orderId\":\"ORDER-001\"}"
                : "{\"orderId\":\"ORDER-001\"," + payload.substring(1);
        return new ExternalActionCommandModel(
                "command-" + key, "run-" + key, "thread-1", "turn-1", "user-1", type,
                "key-" + key, fullPayload,
                ExternalActionStatusEnum.PENDING, 0, 3, NOW.plusSeconds(1), null, null,
                null, null, NOW, NOW, null);
    }

    private static final class RecordingOrderActions implements OrderActionGateway {
        private final AtomicInteger expediteCalls = new AtomicInteger();
        private final AtomicInteger hideCalls = new AtomicInteger();
        private final AtomicInteger restoreCalls = new AtomicInteger();

        @Override
        public OrderActionResult refund(String userId, String orderId, String reason, Instant now) {
            return OrderActionResult.succeeded("REFUNDED", "ok");
        }

        @Override
        public OrderActionResult expedite(String userId, String orderId, Instant now) {
            expediteCalls.incrementAndGet();
            return OrderActionResult.succeeded("EXPEDITED", "ok");
        }

        @Override
        public OrderActionResult setVisibility(
                String userId,
                String orderId,
                OrderVisibilityEnum visibility,
                Instant now
        ) {
            if (visibility == OrderVisibilityEnum.HIDDEN) {
                hideCalls.incrementAndGet();
            } else {
                restoreCalls.incrementAndGet();
            }
            return OrderActionResult.succeeded("OK", "ok");
        }
    }

    private static final class InMemoryResults implements ExternalActionResultStore {
        private final Map<String, ExternalActionResultModel> values = new HashMap<>();

        @Override
        public Optional<ExternalActionResultModel> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(values.get(idempotencyKey));
        }

        @Override
        public ExternalActionResultModel createIfAbsent(ExternalActionResultModel result) {
            return values.computeIfAbsent(result.idempotencyKey(), ignored -> result);
        }
    }
}
