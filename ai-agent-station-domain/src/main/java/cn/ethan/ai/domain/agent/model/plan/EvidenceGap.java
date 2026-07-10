package cn.ethan.ai.domain.agent.model.plan;

/**
 * 规划器声明的证据缺口。reasonCode 是审计说明，不保存模型长推理。
 */
public record EvidenceGap(String field, EvidenceSource source, String reasonCode) {

    public enum EvidenceSource {
        USER,
        TOOL
    }
}
