package cn.ethan.core.workflow.model;

/**
 * QuestionCard 建议值：仅帮助用户填写，不能绕过显式 answers 与业务校验。
 *
 * @author ethan
 * @date 2026-08-10
 */
public record WorkflowQuestionSuggestionModel(
        String value,
        String source,
        String memoryEntryId
) {

    public WorkflowQuestionSuggestionModel {
        if (isBlank(value) || isBlank(source) || isBlank(memoryEntryId)) {
            throw new IllegalArgumentException("workflow question suggestion is incomplete");
        }
        value = value.strip();
        source = source.strip();
        memoryEntryId = memoryEntryId.strip();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
