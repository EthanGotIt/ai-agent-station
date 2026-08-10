package cn.ethan.dto;

import cn.ethan.core.workflow.model.WorkflowQuestionFieldModel;

import java.util.List;

/**
 * Workflow 问题字段 DTO：描述客户端应收集的单个答案。
 *
 * @author ethan
 * @date 2026-08-09
 */
public record AgentWorkflowQuestionFieldDto(
        String name,
        String label,
        String type,
        boolean required,
        List<String> options,
        AgentWorkflowQuestionSuggestionDto suggestion
) {

    public static AgentWorkflowQuestionFieldDto from(WorkflowQuestionFieldModel field) {
        return new AgentWorkflowQuestionFieldDto(
                field.name(), field.label(), field.type().name(), field.required(), field.options(),
                AgentWorkflowQuestionSuggestionDto.from(field.suggestion())
        );
    }
}
