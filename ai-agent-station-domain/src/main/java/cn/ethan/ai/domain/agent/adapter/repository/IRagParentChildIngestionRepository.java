package cn.ethan.ai.domain.agent.adapter.repository;

import cn.ethan.ai.domain.agent.model.valobj.RagIngestionChunkVO;
import cn.ethan.ai.domain.agent.model.valobj.RagIngestionDocumentVO;

import java.util.List;

/**
 * RAG 父子分块写库仓储
 */
public interface IRagParentChildIngestionRepository {

    /**
     * 以文档维度全量替换父子分块元数据。
     *
     * @param document 文档元数据
     * @param chunks   父块与子块集合
     */
    void replaceDocument(RagIngestionDocumentVO document, List<RagIngestionChunkVO> chunks);

}
