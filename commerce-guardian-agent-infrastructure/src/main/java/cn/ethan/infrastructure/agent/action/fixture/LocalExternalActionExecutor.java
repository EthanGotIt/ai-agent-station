package cn.ethan.infrastructure.agent.action.fixture;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionExecutor;
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

    @Override
    public ExternalActionResult execute(ExternalActionCommandModel command) {
        LOGGER.info("演示外部动作已执行，actionType={}, idempotencyKey={}",
                command.type(), command.idempotencyKey());
        return new ExternalActionResult(true, false, "OK", "演示外部动作执行成功");
    }
}
