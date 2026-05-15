package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 父子分块导入结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagParentChildIngestionResultVO {

    private String ragId;

    private String docId;

    private String title;

    private Integer parentChunkCount;

    private Integer childChunkCount;

}
