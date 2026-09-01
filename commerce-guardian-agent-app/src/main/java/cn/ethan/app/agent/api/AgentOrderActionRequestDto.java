package cn.ethan.app.agent.api;

import cn.ethan.core.agent.coordination.AgentOrderActionTypeEnum;
import cn.ethan.core.agent.thread.AgentTurnModel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 类型职责：接收订单卡片发起的确定性动作，不携带可执行的自然语言脚本。
 *
 * @author ethan
 * @date 2026-08-24
 */
public record AgentOrderActionRequestDto(
        @NotBlank @Size(max = AgentTurnModel.MAX_CLIENT_REQUEST_ID_LENGTH) String clientRequestId,
        @NotBlank String sourceTurnId,
        @NotBlank String orderId,
        @NotNull AgentOrderActionTypeEnum actionType
) {
}
