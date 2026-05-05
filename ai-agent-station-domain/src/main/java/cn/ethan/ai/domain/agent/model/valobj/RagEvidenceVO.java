package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 证据展示对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagEvidenceVO {

    private String evidenceId;

    private String documentId;

    private String chunkId;

    private String sourceName;

    private String sectionTitle;

    private String retrievalQuery;

    private Integer rank;

    private Double score;

    private String contentPreview;
}

