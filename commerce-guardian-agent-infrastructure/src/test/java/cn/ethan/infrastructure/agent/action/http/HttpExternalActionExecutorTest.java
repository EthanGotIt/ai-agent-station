package cn.ethan.infrastructure.agent.action.http;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionResultModel;
import cn.ethan.core.agent.action.ExternalActionResultStore;
import cn.ethan.core.agent.action.ExternalActionStatusEnum;
import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import cn.ethan.core.commerce.order.OrderActionGateway;
import cn.ethan.core.commerce.order.OrderVisibilityEnum;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型职责：验证 HTTP 外部动作执行器把命令幂等键传递到订单动作端口。
 *
 * @author ethan
 * @date 2026-08-23
 */
class HttpExternalActionExecutorTest {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Test
    void forwardsIdempotencyKeyForEveryOrderAction() {
        RecordingActions actions = new RecordingActions();
        HttpExternalActionExecutor executor = new HttpExternalActionExecutor(
                new InMemoryResults(), actions, Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());

        for (ExternalActionTypeEnum type : ExternalActionTypeEnum.values()) {
            String key = "order-action-key-" + type.name();
            String payload = switch (type) {
                case REFUND -> "{\"orderId\":\"ORDER-001\",\"reason\":\"商品不符\"}";
                case HIDE_ORDER -> "{\"orderId\":\"ORDER-001\",\"visibility\":\"HIDDEN\"}";
                case RESTORE_ORDER -> "{\"orderId\":\"ORDER-001\",\"visibility\":\"ACTIVE\"}";
                case EXPEDITE -> "{\"orderId\":\"ORDER-001\"}";
            };
            assertTrue(executor.execute(command(type, key, payload)).success());
            assertEquals(key, actions.keys.get(type));
        }
    }

    private static ExternalActionCommandModel command(
            ExternalActionTypeEnum type,
            String key,
            String payload
    ) {
        return new ExternalActionCommandModel(
                "command-" + type.name(), "run-" + type.name(), "thread-1", "turn-1", "user-1", type,
                key, payload, ExternalActionStatusEnum.PENDING, 0, 3, NOW.plusSeconds(1), null, null,
                null, null, NOW, NOW, null);
    }

    private static final class RecordingActions implements OrderActionGateway {
        private final Map<ExternalActionTypeEnum, String> keys = new HashMap<>();

        @Override
        public OrderActionResult refund(
                String userId, String orderId, String reason, String idempotencyKey, Instant now) {
            keys.put(ExternalActionTypeEnum.REFUND, idempotencyKey);
            return OrderActionResult.succeeded("OK", "done");
        }

        @Override
        public OrderActionResult expedite(String userId, String orderId, String idempotencyKey, Instant now) {
            keys.put(ExternalActionTypeEnum.EXPEDITE, idempotencyKey);
            return OrderActionResult.succeeded("OK", "done");
        }

        @Override
        public OrderActionResult setVisibility(
                String userId,
                String orderId,
                OrderVisibilityEnum visibility,
                String idempotencyKey,
                Instant now
        ) {
            keys.put(visibility == OrderVisibilityEnum.HIDDEN
                    ? ExternalActionTypeEnum.HIDE_ORDER : ExternalActionTypeEnum.RESTORE_ORDER, idempotencyKey);
            return OrderActionResult.succeeded("OK", "done");
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
            values.putIfAbsent(result.idempotencyKey(), result);
            return values.get(result.idempotencyKey());
        }
    }
}
