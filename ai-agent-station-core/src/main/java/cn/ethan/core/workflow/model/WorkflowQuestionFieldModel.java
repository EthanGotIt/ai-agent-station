package cn.ethan.core.workflow.model;

import cn.ethan.core.workflow.enums.WorkflowQuestionFieldTypeEnum;

import java.util.List;

/**
 * QuestionCard 字段模型：描述用户可回答的一个受控字段。
 *
 * @author ethan
 * @date 2026-08-09
 */
public record WorkflowQuestionFieldModel(
        String name,
        String label,
        WorkflowQuestionFieldTypeEnum type,
        boolean required,
        List<String> options,
        WorkflowQuestionSuggestionModel suggestion
) {

    public WorkflowQuestionFieldModel(
            String name,
            String label,
            WorkflowQuestionFieldTypeEnum type,
            boolean required,
            List<String> options
    ) {
        this(name, label, type, required, options, null);
    }

    public WorkflowQuestionFieldModel {
        if (isBlank(name) || isBlank(label) || type == null) {
            throw new IllegalArgumentException("workflow question field is incomplete");
        }
        options = options == null ? List.of() : List.copyOf(options);
        if ((type == WorkflowQuestionFieldTypeEnum.SINGLE_SELECT
                || type == WorkflowQuestionFieldTypeEnum.CONFIRM) && options.isEmpty()) {
            throw new IllegalArgumentException("workflow question options are required");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
