package cn.ethan.ai.domain.agent.model.valobj.enums;

import java.util.Arrays;

/**
 * 执行计划步骤类型枚举
 */
public enum PlanStepTypeEnumVO {

    LLM,
    TOOL,
    SUPERVISION,
    SUMMARY;

    public static boolean contains(String value) {
        if (value == null) {
            return false;
        }
        return Arrays.stream(values()).anyMatch(item -> item.name().equalsIgnoreCase(value.trim()));
    }

    public static boolean requiresTool(String value) {
        return TOOL.name().equalsIgnoreCase(value == null ? "" : value.trim());
    }
}
