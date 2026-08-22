package cn.ethan.infrastructure.agent.action.fixture;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionExecutor;
import cn.ethan.core.agent.action.ExternalActionResultModel;
import cn.ethan.core.agent.action.ExternalActionResultStatusEnum;
import cn.ethan.core.agent.action.ExternalActionResultStore;
import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import cn.ethan.core.commerce.order.OrderActionGateway;
import cn.ethan.core.commerce.order.OrderVisibilityEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * 类型职责：在本地演示订单系统中以同一本地事务更新退款事实并写入外部幂等回执。
 *
 * @author ethan
 * @date 2026-08-22
 */
@Component
@ConditionalOnProperty(
        name = "ai-agent.order.gateway",
        havingValue = "local",
        matchIfMissing = true
)
public final class LocalExternalActionExecutor implements ExternalActionExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalExternalActionExecutor.class);

    private final ExternalActionResultStore results;
    private final OrderActionGateway orderActions;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public LocalExternalActionExecutor(
            ExternalActionResultStore results,
            OrderActionGateway orderActions,
            Clock clock,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper
    ) {
        this(results, orderActions, clock, objectMapper, new TransactionTemplate(transactionManager));
    }

    /** 保留无 Spring 事务容器的测试构造边界。 */
    public LocalExternalActionExecutor(ExternalActionResultStore results, Clock clock) {
        this(results, null, clock, new ObjectMapper(), null);
    }

    /** 为动作分支测试提供无 Spring 事务容器的订单网关边界。 */
    LocalExternalActionExecutor(
            ExternalActionResultStore results,
            OrderActionGateway orderActions,
            Clock clock
    ) {
        this(results, orderActions, clock, new ObjectMapper(), null);
    }

    private LocalExternalActionExecutor(
            ExternalActionResultStore results,
            OrderActionGateway orderActions,
            Clock clock,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate
    ) {
        this.results = results;
        this.orderActions = orderActions;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public ExternalActionResult execute(ExternalActionCommandModel command) {
        if (transactionTemplate == null) {
            return executeInLocalStore(command);
        }
        ExternalActionResult result = transactionTemplate.execute(status -> executeInLocalStore(command));
        return result == null
                ? new ExternalActionResult(false, true, "LOCAL_ACTION_EMPTY_RESULT", "本地动作未返回结果")
                : result;
    }

    private ExternalActionResult executeInLocalStore(ExternalActionCommandModel command) {
        if (results.findByIdempotencyKey(command.idempotencyKey()).isPresent()) {
            return new ExternalActionResult(true, false, "IDEMPOTENT_REPLAY", "已复用外部动作结果");
        }
        if (orderActions == null) {
            return new ExternalActionResult(false, false, "ACTION_NOT_SUPPORTED", "当前本地执行器不支持该动作");
        }
        ActionPayload payload = parsePayload(command.payloadJson());
        if (payload == null) {
            return new ExternalActionResult(false, false, "ACTION_PAYLOAD_INVALID", "外部动作参数无效");
        }
        OrderActionGateway.OrderActionResult mutation = switch (command.type()) {
            case REFUND -> payload.reason().isBlank()
                    ? OrderActionGateway.OrderActionResult.failed(false,
                    "ACTION_PAYLOAD_INVALID", "退款原因不能为空")
                    : orderActions.refund(command.userId(), payload.orderId(), payload.reason(), clock.instant());
            case EXPEDITE -> orderActions.expedite(command.userId(), payload.orderId(), clock.instant());
            case HIDE_ORDER -> visibilityMutation(command, payload, OrderVisibilityEnum.HIDDEN);
            case RESTORE_ORDER -> visibilityMutation(command, payload, OrderVisibilityEnum.ACTIVE);
        };
        if (!mutation.success()) {
            return new ExternalActionResult(false, mutation.retryable(), mutation.code(), mutation.message());
        }
        LOGGER.info("演示订单动作事实已提交，actionType={}, idempotencyKey={}",
                command.type(), command.idempotencyKey());
        results.createIfAbsent(new ExternalActionResultModel(
                "result-" + UUID.randomUUID(), command.commandId(), command.idempotencyKey(), command.type(),
                ExternalActionResultStatusEnum.SUCCEEDED,
                "{\"status\":\"" + escape(mutation.code()) + "\"}", Instant.now(clock)));
        return new ExternalActionResult(true, false, mutation.code(), mutation.message());
    }

    private OrderActionGateway.OrderActionResult visibilityMutation(
            ExternalActionCommandModel command,
            ActionPayload payload,
            OrderVisibilityEnum expected
    ) {
        if (!payload.visibility().isBlank() && !expected.name().equalsIgnoreCase(payload.visibility())) {
            return OrderActionGateway.OrderActionResult.failed(false,
                    "ACTION_PAYLOAD_INVALID", "订单历史操作方向无效");
        }
        return orderActions.setVisibility(command.userId(), payload.orderId(), expected, clock.instant());
    }

    private ActionPayload parsePayload(String payloadJson) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson == null ? "{}" : payloadJson);
            String orderId = root.path("orderId").asString("").trim();
            String reason = root.path("reason").asString("").trim();
            String visibility = root.path("visibility").asString("").trim();
            return orderId.isBlank() ? null : new ActionPayload(orderId, reason, visibility);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record ActionPayload(String orderId, String reason, String visibility) {
    }
}
