package cn.ethan.ai.domain.agent.adapter.port;

import cn.ethan.ai.domain.agent.model.valobj.RagIngestionDocumentVO;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * RAG 分块向量索引端口。
 */
public interface IRagChunkIndexPort {

    void replaceChunks(RagIngestionDocumentVO document, List<Document> chunks);
}
