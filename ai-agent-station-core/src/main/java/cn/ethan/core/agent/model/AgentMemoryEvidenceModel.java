package cn.ethan.core.agent.model;

import java.time.Instant;

/**
 * 记忆证据：仅保存稳定来源引用，不复制原始提示词或敏感内容。
 *
 * @author ethan
 * @date 2026-08-10
 */
public record AgentMemoryEvidenceModel(
        String evidenceId,
        String entryId,
        String evidenceType,
        String evidenceRef,
        Instant createdAt
) {

    public AgentMemoryEvidenceModel {
        if (isBlank(evidenceId) || isBlank(entryId) || isBlank(evidenceType)
                || isBlank(evidenceRef) || createdAt == null) {
            throw new IllegalArgumentException("memory evidence is incomplete");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
