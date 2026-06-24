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

    private String hitChunkId;

    private String parentChunkId;

    private String parentKey;

    private Boolean parentExpanded;

    private String sourceName;

    private String sourceType;

    private String sectionTitle;

    private String retrievalQuery;

    private Integer rank;

    private Integer fusionRank;

    private Double score;

    private String contentPreview;

    private String uri;

    private String toolName;

    private String retrievedAt;

    private Boolean attributable;
}

