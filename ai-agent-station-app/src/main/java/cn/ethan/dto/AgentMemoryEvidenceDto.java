package cn.ethan.dto;

import cn.ethan.core.agent.model.AgentMemoryEvidenceModel;

import java.time.Instant;

/**
 * 记忆证据 DTO：仅公开来源引用，不返回原始对话内容。
 *
 * @author ethan
 * @date 2026-08-10
 */
public record AgentMemoryEvidenceDto(
        String evidenceId,
        String evidenceType,
        String evidenceRef,
        Instant createdAt
) {

    public static AgentMemoryEvidenceDto from(AgentMemoryEvidenceModel evidence) {
        return new AgentMemoryEvidenceDto(
                evidence.evidenceId(), evidence.evidenceType(), evidence.evidenceRef(), evidence.createdAt()
        );
    }
}
