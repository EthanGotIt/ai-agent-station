package cn.ethan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 类型职责：接收一次 Agent Turn 的幂等请求标识和用户输入。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentTurnSubmitRequestDto(
        @NotBlank @Size(max = 255) String clientRequestId,
        @NotBlank @Size(max = 20_000) String message
) {
}
