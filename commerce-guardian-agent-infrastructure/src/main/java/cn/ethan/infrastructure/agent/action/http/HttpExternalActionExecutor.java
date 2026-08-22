package cn.ethan.infrastructure.agent.action.http;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionExecutor;
import cn.ethan.core.agent.action.ExternalActionResultModel;
import cn.ethan.core.agent.action.ExternalActionResultStatusEnum;
import cn.ethan.core.agent.action.ExternalActionResultStore;
import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import cn.ethan.core.commerce.order.OrderActionGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * 类型职责：在订单服务由 HTTP 管理时执行远程退款，并在本地记录幂等回执。
 *
 * @author ethan
 * @date 2026-08-22
 */
@Component
@ConditionalOnProperty(name = "ai-agent.order.gateway", havingValue = "http")
public final class HttpExternalActionExecutor implements ExternalActionExecutor {

    private final ExternalActionResultStore results;
    private final OrderActionGateway orderActions;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public HttpExternalActionExecutor(
            ExternalActionResultStore results,
            OrderActionGateway orderActions,
            Clock clock,
            ObjectMapper objectMapper
    ) {
        this.results = results;
        this.orderActions = orderActions;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExternalActionResult execute(ExternalActionCommandModel command) {
        if (results.findByIdempotencyKey(command.idempotencyKey()).isPresent()) {
            return new ExternalActionResult(true, false, "IDEMPOTENT_REPLAY", "已复用外部动作结果");
        }
        if (command.type() != ExternalActionTypeEnum.REFUND) {
            return new ExternalActionResult(false, false, "ACTION_NOT_SUPPORTED", "当前 HTTP 执行器不支持该动作");
        }
        try {
            JsonNode root = objectMapper.readTree(command.payloadJson());
            String orderId = root.path("orderId").asString("").trim();
            String reason = root.path("reason").asString("").trim();
            if (orderId.isBlank() || reason.isBlank()) {
                return new ExternalActionResult(false, false, "ACTION_PAYLOAD_INVALID", "外部动作参数无效");
            }
            OrderActionGateway.OrderActionResult mutation = orderActions.refund(
                    command.userId(), orderId, reason, clock.instant());
            if (!mutation.success()) {
                return new ExternalActionResult(false, mutation.retryable(), mutation.code(), mutation.message());
            }
            results.createIfAbsent(new ExternalActionResultModel(
                    "result-" + UUID.randomUUID(), command.commandId(), command.idempotencyKey(), command.type(),
                    ExternalActionResultStatusEnum.SUCCEEDED,
                    "{\"status\":\"" + escape(mutation.code()) + "\"}", Instant.now(clock)));
            return new ExternalActionResult(true, false, mutation.code(), mutation.message());
        } catch (RuntimeException failure) {
            return new ExternalActionResult(false, true, "REMOTE_ACTION_EXCEPTION", "订单服务暂时不可用");
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
