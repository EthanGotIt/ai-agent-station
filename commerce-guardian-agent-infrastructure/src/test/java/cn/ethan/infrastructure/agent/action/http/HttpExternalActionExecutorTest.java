package cn.ethan.infrastructure.agent.action.http;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionExecutor;
import cn.ethan.core.agent.action.ExternalActionResultModel;
import cn.ethan.core.agent.action.ExternalActionResultStore;
import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import cn.ethan.core.commerce.order.OrderActionGateway;
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
 * HTTP 外部动作执行器单元测试：使用内存订单动作端口，不依赖网络监听。
 *
 * @author ethan
 * @date 2026-08-27
 */
class HttpExternalActionExecutorTest {

    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    void forwardsIdempotencyKeyAndPersistsReceipt() {
        RecordingActions actions = new RecordingActions();
        InMemoryResults results = new InMemoryResults();
        ExternalActionExecutor executor = new HttpExternalActionExecutor(
                results, actions, Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());

        var first = executor.execute(command(ExternalActionTypeEnum.REFUND, "key-1",
                "{\"orderId\":\"ORDER-001\",\"reason\":\"商品不符\"}"));
        var replay = executor.execute(command(ExternalActionTypeEnum.REFUND, "key-1",
                "{\"orderId\":\"ORDER-001\",\"reason\":\"商品不符\"}"));

        assertTrue(first.success());
        assertEquals("key-1", actions.keys.get(ExternalActionTypeEnum.REFUND));
        assertEquals("IDEMPOTENT_REPLAY", replay.code());
        assertEquals(1, results.values.size());
    }

    @Test
    void rejectsInvalidPayloadBeforeRemoteMutation() {
        RecordingActions actions = new RecordingActions();
        ExternalActionExecutor executor = new HttpExternalActionExecutor(
                new InMemoryResults(), actions, Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());

        var result = executor.execute(command(ExternalActionTypeEnum.REFUND, "key-2",
                "{\"orderId\":\"ORDER-001\"}"));

        assertEquals("ACTION_PAYLOAD_INVALID", result.code());
        assertTrue(actions.keys.isEmpty());
    }

    private static ExternalActionCommandModel command(
            ExternalActionTypeEnum type, String key, String payload) {
        return new ExternalActionCommandModel(
                "command-" + key, "run-1", "thread-1", "turn-1", "user-1", type,
                key, payload, cn.ethan.core.agent.action.ExternalActionStatusEnum.PENDING,
                0, 3, NOW.plusSeconds(1), null, null, null, null, NOW, NOW, null);
    }

    private static final class RecordingActions implements OrderActionGateway {
        private final Map<ExternalActionTypeEnum, String> keys = new HashMap<>();

        @Override
        public OrderActionResult refund(String userId, String orderId, String reason,
                                        String idempotencyKey, Instant now) {
            keys.put(ExternalActionTypeEnum.REFUND, idempotencyKey);
            return OrderActionResult.succeeded("OK", "done");
        }

        @Override
        public OrderActionResult expedite(String userId, String orderId, String idempotencyKey, Instant now) {
            keys.put(ExternalActionTypeEnum.EXPEDITE, idempotencyKey);
            return OrderActionResult.succeeded("OK", "done");
        }

        @Override
        public OrderActionResult deleteOrder(String userId, String orderId, String idempotencyKey, Instant now) {
            keys.put(ExternalActionTypeEnum.DELETE_ORDER, idempotencyKey);
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
