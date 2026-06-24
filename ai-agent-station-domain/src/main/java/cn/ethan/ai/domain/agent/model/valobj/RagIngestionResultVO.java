package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 文档导入结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagIngestionResultVO {

    private String ragId;

    private String docId;

    private String title;

    private Integer chunkCount;
}
