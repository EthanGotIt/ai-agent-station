package cn.ethan.dto;

import cn.ethan.core.agent.enums.ToolInterventionDecisionEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * ReAct 工具确认请求 DTO：由 SSE 外的旁路接口提交。
 *
 * @author ethan
 * @date 2026-08-09
 */
public record AgentToolInterventionRequestDto(
        @NotBlank(message = "sessionId 不能为空")
        @Size(max = 128, message = "sessionId 长度不能超过 128")
        String sessionId,

        @NotEmpty(message = "toolCallIds 不能为空")
        List<@NotBlank(message = "toolCallId 不能为空") @Size(max = 128) String> toolCallIds,

        @NotNull(message = "decision 不能为空")
        ToolInterventionDecisionEnum decision
) {
}
