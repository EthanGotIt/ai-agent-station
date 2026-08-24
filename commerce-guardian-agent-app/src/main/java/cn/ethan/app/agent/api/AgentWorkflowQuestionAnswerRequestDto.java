package cn.ethan.app.agent.api;

import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.workflow.AgentWorkflowAnswerActionEnum;
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
        @NotBlank @Size(max = AgentTurnModel.MAX_CLIENT_REQUEST_ID_LENGTH) String clientRequestId,
        @NotBlank String checkpointId,
        @NotNull Long expectedVersion,
        @NotNull Map<String, String> answers,
        AgentWorkflowAnswerActionEnum action
) {
    /** 保留历史客户端构造方式，缺省按正常提交处理。 */
    public AgentWorkflowQuestionAnswerRequestDto(
            String clientRequestId,
            String checkpointId,
            Long expectedVersion,
            Map<String, String> answers
    ) {
        this(clientRequestId, checkpointId, expectedVersion, answers,
                AgentWorkflowAnswerActionEnum.SUBMIT);
    }
}
