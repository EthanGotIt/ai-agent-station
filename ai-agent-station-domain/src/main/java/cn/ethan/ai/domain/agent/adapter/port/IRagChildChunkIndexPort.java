package cn.ethan.ai.domain.agent.adapter.port;

import cn.ethan.ai.domain.agent.model.valobj.RagIngestionDocumentVO;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * RAG 子块索引端口
 */
public interface IRagChildChunkIndexPort {

    /**
     * 替换指定文档在向量库和 ES 中的子块索引数据。
     *
     * @param document       文档元数据
     * @param childDocuments 子块文档，仅包含 chunk_level=2 的检索文档
     */
    void replaceChildChunks(RagIngestionDocumentVO document, List<Document> childDocuments);

}
