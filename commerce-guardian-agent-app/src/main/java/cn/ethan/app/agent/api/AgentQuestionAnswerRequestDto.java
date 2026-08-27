package cn.ethan.app.agent.api;

import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.workflow.AgentQuestionCardAnswerActionEnum;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 类型职责：接收 QuestionCard 的回答版本、幂等键和受控字段。
 *
 * @author ethan
 * @date 2026-08-27
 */
public record AgentQuestionAnswerRequestDto(
        @NotBlank @Size(max = AgentTurnModel.MAX_CLIENT_REQUEST_ID_LENGTH) String clientRequestId,
        @NotNull @Min(0) Long expectedVersion,
        Map<String, String> answers,
        AgentQuestionCardAnswerActionEnum action
) {
}
