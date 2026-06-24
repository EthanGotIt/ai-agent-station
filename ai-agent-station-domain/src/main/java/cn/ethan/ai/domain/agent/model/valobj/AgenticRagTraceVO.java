package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Agentic RAG 真实执行轨迹。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgenticRagTraceVO {

    private String originalQuestion;

    private String intent;

    @Builder.Default
    private List<String> plannedQueries = new ArrayList<>();

    @Builder.Default
    private List<RetrievalRoundVO> retrievalRounds = new ArrayList<>();

    @Builder.Default
    private List<RagEvidenceVO> finalEvidences = new ArrayList<>();

    private boolean evidenceSufficient;

    private boolean secondRetrievalTriggered;

    private String noEvidenceReason;

    private String policyResult;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetrievalRoundVO {

        private int round;

        private String query;

        private String channel;

        private String sourceType;

        private int hitCount;

        private String reason;

        private String policyResult;

        private long costMillis;
    }
}
