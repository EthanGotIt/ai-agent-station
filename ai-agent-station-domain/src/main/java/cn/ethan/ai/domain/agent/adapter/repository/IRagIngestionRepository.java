package cn.ethan.ai.domain.agent.adapter.repository;

import cn.ethan.ai.domain.agent.model.valobj.RagIngestionChunkVO;
import cn.ethan.ai.domain.agent.model.valobj.RagIngestionDocumentVO;

import java.util.List;

/**
 * RAG 文档与分块元数据写库仓储。
 */
public interface IRagIngestionRepository {

    void replaceDocument(RagIngestionDocumentVO document, List<RagIngestionChunkVO> chunks);
}
