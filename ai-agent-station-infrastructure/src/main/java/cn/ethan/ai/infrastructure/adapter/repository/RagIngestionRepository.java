package cn.ethan.ai.infrastructure.adapter.repository;

import cn.ethan.ai.domain.agent.adapter.repository.IRagIngestionRepository;
import cn.ethan.ai.domain.agent.model.valobj.RagIngestionChunkVO;
import cn.ethan.ai.domain.agent.model.valobj.RagIngestionDocumentVO;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

/**
 * RAG 文档与分块元数据仓储实现。
 */
@Repository
public class RagIngestionRepository implements IRagIngestionRepository {

    @Resource
    @Qualifier("mysqlJdbcTemplate")
    private JdbcTemplate mysqlJdbcTemplate;

    @Override
    public void replaceDocument(RagIngestionDocumentVO document, List<RagIngestionChunkVO> chunks) {
        mysqlJdbcTemplate.update("DELETE FROM ai_rag_chunk WHERE rag_id = ? AND doc_id = ?",
                document.getRagId(), document.getDocId());
        mysqlJdbcTemplate.update("DELETE FROM ai_rag_document WHERE rag_id = ? AND doc_id = ?",
                document.getRagId(), document.getDocId());
        mysqlJdbcTemplate.update("""
                        INSERT INTO ai_rag_document (
                            rag_id, doc_id, title, source, summary, metadata_json, status, create_time, update_time
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                        """,
                document.getRagId(), document.getDocId(), document.getTitle(), document.getSource(),
                document.getSummary(), document.getMetadataJson(), document.getStatus());
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        mysqlJdbcTemplate.batchUpdate("""
                        INSERT INTO ai_rag_chunk (
                            rag_id, doc_id, chunk_id, parent_chunk_id, chunk_level, chunk_type,
                            chunk_text, metadata_json, status, create_time, update_time
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                        """,
                chunks,
                chunks.size(),
                (PreparedStatement ps, RagIngestionChunkVO chunk) -> {
                    ps.setString(1, chunk.getRagId());
                    ps.setString(2, chunk.getDocId());
                    ps.setString(3, chunk.getChunkId());
                    ps.setString(4, chunk.getParentChunkId());
                    ps.setInt(5, chunk.getChunkLevel());
                    ps.setString(6, chunk.getChunkType());
                    ps.setString(7, chunk.getChunkText());
                    ps.setString(8, chunk.getMetadataJson());
                    ps.setInt(9, chunk.getStatus());
                });
    }
}
