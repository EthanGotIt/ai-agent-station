package cn.ethan.dto;

import cn.ethan.core.workflow.model.WorkflowQuestionModel;

import java.util.List;

/**
 * Workflow 问题卡 DTO：前端据此渲染并显式提交答案。
 *
 * @author ethan
 * @date 2026-08-09
 */
public record AgentWorkflowQuestionDto(
        String questionId,
        String checkpointId,
        String cardType,
        String title,
        String prompt,
        List<AgentWorkflowQuestionFieldDto> fields
) {

    public static AgentWorkflowQuestionDto from(WorkflowQuestionModel question) {
        return question == null ? null : new AgentWorkflowQuestionDto(
                question.questionId(), question.checkpointId(), question.cardType(), question.title(),
                question.prompt(), question.fields().stream().map(AgentWorkflowQuestionFieldDto::from).toList()
        );
    }
}
