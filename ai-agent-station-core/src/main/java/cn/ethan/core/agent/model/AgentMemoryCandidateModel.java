package cn.ethan.core.agent.model;

import cn.ethan.core.agent.enums.AgentMemoryCategoryEnum;

/**
 * 记忆提取候选：只允许进入受控类别和键空间，避免自由文本成为业务输入。
 *
 * @author ethan
 * @date 2026-08-10
 */
public record AgentMemoryCandidateModel(
        AgentMemoryCategoryEnum category,
        String memoryKey,
        String value,
        double confidence
) {

    public AgentMemoryCandidateModel {
        if (category == null || isBlank(memoryKey) || isBlank(value)
                || memoryKey.length() > 64 || value.length() > 512
                || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("memory candidate is invalid");
        }
        memoryKey = memoryKey.strip();
        value = value.strip();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
