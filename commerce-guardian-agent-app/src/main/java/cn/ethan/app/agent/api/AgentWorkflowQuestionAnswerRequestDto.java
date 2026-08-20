package cn.ethan.app.agent.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 类型职责：接收 QuestionCard 的检查点、版本和结构化回答。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentWorkflowQuestionAnswerRequestDto(
        @NotBlank @Size(max = 128) String clientRequestId,
        @NotBlank String checkpointId,
        @NotNull Long expectedVersion,
        @NotNull Map<String, String> answers
) {
}
