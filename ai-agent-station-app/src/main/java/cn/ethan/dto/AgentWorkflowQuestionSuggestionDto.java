package cn.ethan.dto;

import cn.ethan.core.workflow.model.WorkflowQuestionSuggestionModel;

/**
 * QuestionCard 建议值 DTO：客户端必须由用户确认后再提交 answers。
 *
 * @author ethan
 * @date 2026-08-10
 */
public record AgentWorkflowQuestionSuggestionDto(String value, String source, String memoryEntryId) {

    public static AgentWorkflowQuestionSuggestionDto from(WorkflowQuestionSuggestionModel suggestion) {
        return suggestion == null ? null : new AgentWorkflowQuestionSuggestionDto(
                suggestion.value(), suggestion.source(), suggestion.memoryEntryId()
        );
    }
}
