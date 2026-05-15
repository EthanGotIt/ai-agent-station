package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 导入分块元数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagIngestionChunkVO {

    private String ragId;

    private String docId;

    private String chunkId;

    private String parentChunkId;

    private Integer chunkLevel;

    private String chunkType;

    private String chunkText;

    private String metadataJson;

    private Integer status;

}
