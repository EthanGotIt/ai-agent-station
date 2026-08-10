package cn.ethan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Workflow 回答请求 DTO：提交 QuestionCard 的显式答案和乐观锁版本。
 *
 * @author ethan
 * @date 2026-08-09
 */
public record AgentWorkflowAnswerRequestDto(
        @NotBlank(message = "requestId 不能为空")
        @Size(max = 128, message = "requestId 长度不能超过 128")
        String requestId,

        @NotBlank(message = "sessionId 不能为空")
        @Size(max = 128, message = "sessionId 长度不能超过 128")
        String sessionId,

        @NotBlank(message = "questionId 不能为空")
        @Size(max = 128, message = "questionId 长度不能超过 128")
        String questionId,

        @NotBlank(message = "checkpointId 不能为空")
        @Size(max = 128, message = "checkpointId 长度不能超过 128")
        String checkpointId,

        long expectedVersion,

        @NotNull(message = "answers 不能为空")
        Map<@NotBlank(message = "答案字段不能为空") String,
                @NotBlank(message = "答案不能为空") String> answers,

        AgentMemoryOptionsDto memory
) {
}
