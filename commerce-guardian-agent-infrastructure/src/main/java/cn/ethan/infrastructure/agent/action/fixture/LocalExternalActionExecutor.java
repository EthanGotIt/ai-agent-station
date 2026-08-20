package cn.ethan.infrastructure.agent.action.fixture;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionExecutor;
import cn.ethan.core.agent.action.ExternalActionResultModel;
import cn.ethan.core.agent.action.ExternalActionResultStatusEnum;
import cn.ethan.core.agent.action.ExternalActionResultStore;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 类型职责：为退款和催发货提供可重复执行的本地外部系统夹具。
 *
 * @author ethan
 * @date 2026-08-19
 */
@Component
public final class LocalExternalActionExecutor implements ExternalActionExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalExternalActionExecutor.class);

    private final ExternalActionResultStore results;
    private final Clock clock;

    @Autowired
    public LocalExternalActionExecutor(ExternalActionResultStore results, Clock clock) {
        this.results = results;
        this.clock = clock;
    }

    @Override
    public ExternalActionResult execute(ExternalActionCommandModel command) {
        if (results.findByIdempotencyKey(command.idempotencyKey()).isPresent()) {
            return new ExternalActionResult(true, false, "IDEMPOTENT_REPLAY", "已复用外部动作结果");
        }
        LOGGER.info("演示外部动作已执行，actionType={}, idempotencyKey={}",
                command.type(), command.idempotencyKey());
        results.createIfAbsent(new ExternalActionResultModel(
                "result-" + UUID.randomUUID(), command.commandId(), command.idempotencyKey(), command.type(),
                ExternalActionResultStatusEnum.SUCCEEDED, "{\"status\":\"SUCCEEDED\"}", Instant.now(clock)));
        return new ExternalActionResult(true, false, "OK", "演示外部动作执行成功");
    }
}
