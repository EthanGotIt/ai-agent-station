package cn.ethan.infrastructure.agent.action.http;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionResultModel;
import cn.ethan.core.agent.action.ExternalActionResultStore;
import cn.ethan.core.agent.action.ExternalActionStatusEnum;
import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import cn.ethan.core.commerce.order.OrderActionGateway;
import cn.ethan.infrastructure.commerce.order.http.HttpOrderGateway;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型职责：验证 HTTP 外部动作执行器把命令幂等键传递到订单动作端口。
 *
 * @author ethan
 * @date 2026-08-23
 */
class HttpExternalActionExecutorIT {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Test
    void forwardsIdempotencyKeyForSupportedOrderActions() {
        RecordingActions actions = new RecordingActions();
        HttpExternalActionExecutor executor = new HttpExternalActionExecutor(
                new InMemoryResults(), actions, Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());

        for (ExternalActionTypeEnum type : ExternalActionTypeEnum.values()) {
            String key = "order-action-key-" + type.name();
            String payload = switch (type) {
                case REFUND -> "{\"orderId\":\"ORDER-001\",\"reason\":\"商品不符\"}";
                case DELETE_ORDER -> "{\"orderId\":\"ORDER-001\"}";
                case HIDE_ORDER, RESTORE_ORDER -> "{\"orderId\":\"ORDER-001\"}";
                case EXPEDITE -> "{\"orderId\":\"ORDER-001\"}";
            };
            var result = executor.execute(command(type, key, payload));
            if (type == ExternalActionTypeEnum.HIDE_ORDER || type == ExternalActionTypeEnum.RESTORE_ORDER) {
                assertFalse(result.success());
                assertEquals("ORDER_HISTORY_ACTION_REMOVED", result.code());
            } else {
                assertTrue(result.success());
                assertEquals(key, actions.keys.get(type));
            }
        }
    }

    @Test
    void retriesAfterLocalReceiptFailureKeepOneRemoteBusinessMutation() throws IOException {
        AtomicInteger requestCount = new AtomicInteger();
        AtomicInteger businessMutationCount = new AtomicInteger();
        Set<String> appliedKeys = ConcurrentHashMap.newKeySet();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/orders/ORDER-001/refund", exchange -> respondRefund(
                exchange, requestCount, businessMutationCount, appliedKeys));
        server.start();
        try {
            FlakyResults results = new FlakyResults();
            HttpOrderGateway actions = new HttpOrderGateway(
                    RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    java.time.Duration.ofSeconds(1));
            HttpExternalActionExecutor executor = new HttpExternalActionExecutor(
                    results, actions, Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
            ExternalActionCommandModel command = command(
                    ExternalActionTypeEnum.REFUND, "remote-retry-key",
                    "{\"orderId\":\"ORDER-001\",\"reason\":\"商品不符\"}");

            var first = executor.execute(command);
            var second = executor.execute(command);
            var replay = executor.execute(command);

            assertFalse(first.success());
            assertTrue(first.retryable());
            assertTrue(second.success());
            assertTrue(replay.success());
            assertEquals("IDEMPOTENT_REPLAY", replay.code());
            assertEquals(2, requestCount.get());
            assertEquals(1, businessMutationCount.get());
            assertEquals(1, results.values.size());
        } finally {
            server.stop(0);
        }
    }

    private static void respondRefund(
            HttpExchange exchange,
            AtomicInteger requestCount,
            AtomicInteger businessMutationCount,
            Set<String> appliedKeys
    ) throws IOException {
        try (exchange) {
            requestCount.incrementAndGet();
            String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            if (appliedKeys.add(key)) {
                businessMutationCount.incrementAndGet();
            }
            byte[] body = "{\"success\":true,\"retryable\":false,\"code\":\"ORDER_REFUNDED\",\"message\":\"ok\"}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
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
        public OrderActionResult deleteOrder(String userId, String orderId, String idempotencyKey, Instant now) {
            keys.put(ExternalActionTypeEnum.DELETE_ORDER, idempotencyKey);
            return OrderActionResult.succeeded("OK", "done");
        }

    }

    private static class InMemoryResults implements ExternalActionResultStore {
        protected final Map<String, ExternalActionResultModel> values = new HashMap<>();

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

    private static final class FlakyResults extends InMemoryResults {
        private boolean failNextCreate = true;

        @Override
        public ExternalActionResultModel createIfAbsent(ExternalActionResultModel result) {
            if (failNextCreate) {
                failNextCreate = false;
                throw new IllegalStateException("本地回执提交失败");
            }
            return super.createIfAbsent(result);
        }
    }
}
