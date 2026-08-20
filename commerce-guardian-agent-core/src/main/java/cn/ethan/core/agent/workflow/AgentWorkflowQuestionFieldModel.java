package cn.ethan.core.agent.workflow;

import java.util.List;

/**
 * 类型职责：描述 QuestionCard 单个回答字段的必填、长度和可选值约束。
 *
 * @author ethan
 * @date 2026-08-21
 */
public record AgentWorkflowQuestionFieldModel(
        String name,
        boolean required,
        int maxLength,
        List<String> options
) {

    public AgentWorkflowQuestionFieldModel {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("QuestionCard 字段名不合法");
        }
        if (maxLength < 1 || maxLength > 4_000) {
            throw new IllegalArgumentException("QuestionCard 字段长度限制不合法");
        }
        options = options == null ? List.of() : List.copyOf(options);
        if (options.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > maxLength)
                || options.stream().distinct().count() != options.size()) {
            throw new IllegalArgumentException("QuestionCard 字段选项不合法");
        }
    }
}
