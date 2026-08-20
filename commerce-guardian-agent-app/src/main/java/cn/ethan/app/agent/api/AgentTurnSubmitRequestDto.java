package cn.ethan.app.agent.api;

import cn.ethan.core.agent.thread.AgentTurnModel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 类型职责：接收一次 Agent Turn 的幂等请求标识和用户输入。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentTurnSubmitRequestDto(
        @NotBlank @Size(max = AgentTurnModel.MAX_CLIENT_REQUEST_ID_LENGTH) String clientRequestId,
        @NotBlank @Size(max = AgentTurnModel.MAX_USER_MESSAGE_LENGTH) String message
) {
}
