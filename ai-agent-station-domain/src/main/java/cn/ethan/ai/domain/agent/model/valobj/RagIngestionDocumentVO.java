package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 导入文档元数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagIngestionDocumentVO {

    private String ragId;

    private String docId;

    private String title;

    private String source;

    private String summary;

    private String metadataJson;

    private Integer status;

}
