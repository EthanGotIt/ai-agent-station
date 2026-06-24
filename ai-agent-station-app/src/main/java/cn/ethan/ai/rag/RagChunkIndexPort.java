package cn.ethan.ai.rag;

import cn.ethan.ai.domain.agent.adapter.port.IRagChunkIndexPort;
import cn.ethan.ai.domain.agent.model.valobj.RagIngestionDocumentVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 将 RAG 分块批量写入 PGVector。
 */
@Slf4j
@Service
public class RagChunkIndexPort implements IRagChunkIndexPort {

    private static final int EMBEDDING_BATCH_SIZE = 10;

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ObjectProvider<JdbcTemplate> pgVectorJdbcTemplateProvider;
    public RagChunkIndexPort(ObjectProvider<VectorStore> vectorStoreProvider,
                             @Qualifier("pgVectorJdbcTemplate") ObjectProvider<JdbcTemplate> pgVectorJdbcTemplateProvider) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.pgVectorJdbcTemplateProvider = pgVectorJdbcTemplateProvider;
    }

    @Override
    public void replaceChunks(RagIngestionDocumentVO document, List<Document> chunks) {
        if (document == null) {
            throw new IllegalArgumentException("document 不能为空");
        }
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks 不能为空");
        }

        replacePgVectorChunks(document, chunks);
        log.info("RAG 分块索引写入完成，ragId:{}，docId:{}，chunks:{}",
                document.getRagId(), document.getDocId(), chunks.size());
    }

    private void replacePgVectorChunks(RagIngestionDocumentVO document, List<Document> chunks) {
        JdbcTemplate pgVectorJdbcTemplate = pgVectorJdbcTemplateProvider.getIfAvailable();
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (pgVectorJdbcTemplate == null || vectorStore == null) {
            throw new IllegalStateException("PGVector 未启用，无法写入 RAG 分块");
        }

        pgVectorJdbcTemplate.update("""
                        DELETE FROM vector_store_openai
                         WHERE metadata ->> 'rag_id' = ?
                           AND metadata ->> 'doc_id' = ?
                        """,
                document.getRagId(), document.getDocId());
        for (int start = 0; start < chunks.size(); start += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + EMBEDDING_BATCH_SIZE, chunks.size());
            vectorStore.accept(List.copyOf(chunks.subList(start, end)));
        }
    }
}
