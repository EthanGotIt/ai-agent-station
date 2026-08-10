package cn.ethan.core.workflow.model;

import java.util.List;

/**
 * Workflow QuestionCard 模型：持久化用户输入检查点及其可渲染字段。
 *
 * @author ethan
 * @date 2026-08-09
 */
public record WorkflowQuestionModel(
        String questionId,
        String checkpointId,
        String cardType,
        String title,
        String prompt,
        List<WorkflowQuestionFieldModel> fields
) {

    public WorkflowQuestionModel {
        if (isBlank(questionId) || isBlank(checkpointId) || isBlank(cardType)
                || isBlank(title) || isBlank(prompt)) {
            throw new IllegalArgumentException("workflow question is incomplete");
        }
        fields = fields == null ? List.of() : List.copyOf(fields);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("workflow question fields are required");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
