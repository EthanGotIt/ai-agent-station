package cn.ethan.ai.test.dao;

import cn.ethan.ai.domain.agent.adapter.repository.IRagIngestionRepository;
import cn.ethan.ai.domain.agent.model.valobj.RagIngestionChunkVO;
import cn.ethan.ai.domain.agent.model.valobj.RagIngestionDocumentVO;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@SpringBootTest
@Transactional
@Rollback
public class RagIngestionRepositoryIT {

    @Resource
    private IRagIngestionRepository ragIngestionRepository;

    @Resource
    @Qualifier("mysqlJdbcTemplate")
    private JdbcTemplate mysqlJdbcTemplate;

    @Test
    public void replaceDocumentShouldPersistFlatChunks() {
        String docId = "doc_test_ingestion";
        RagIngestionDocumentVO document = RagIngestionDocumentVO.builder()
                .ragId("rag-agent-station")
                .docId(docId)
                .title("测试 Markdown")
                .source("test-doc.md")
                .summary("测试摘要")
                .metadataJson("{\"doc_type\":\"markdown\"}")
                .status(1)
                .build();
        List<RagIngestionChunkVO> chunks = List.of(
                RagIngestionChunkVO.builder()
                        .ragId("rag-agent-station")
                        .docId(docId)
                        .chunkId("c_0001")
                        .parentChunkId(null)
                        .chunkLevel(1)
                        .chunkType("markdown_chunk")
                        .chunkText("分块一")
                        .metadataJson("{\"section_title\":\"章节一\",\"chunk_order\":1}")
                        .status(1)
                        .build(),
                RagIngestionChunkVO.builder()
                        .ragId("rag-agent-station")
                        .docId(docId)
                        .chunkId("c_0002")
                        .parentChunkId(null)
                        .chunkLevel(1)
                        .chunkType("markdown_chunk")
                        .chunkText("分块二")
                        .metadataJson("{\"section_title\":\"章节二\",\"chunk_order\":2,\"chunk_level\":1}")
                        .status(1)
                        .build()
        );

        ragIngestionRepository.replaceDocument(document, chunks);

        Integer docCount = mysqlJdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM ai_rag_document WHERE rag_id = ? AND doc_id = ?",
                Integer.class,
                "rag-agent-station", docId
        );
        Integer chunkCount = mysqlJdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM ai_rag_chunk WHERE rag_id = ? AND doc_id = ?",
                Integer.class,
                "rag-agent-station", docId
        );
        Integer flatChunkCount = mysqlJdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM ai_rag_chunk WHERE rag_id = ? AND doc_id = ? AND chunk_level = 1 AND parent_chunk_id IS NULL",
                Integer.class,
                "rag-agent-station", docId
        );

        Assertions.assertEquals(Integer.valueOf(1), docCount);
        Assertions.assertEquals(Integer.valueOf(2), chunkCount);
        Assertions.assertEquals(Integer.valueOf(2), flatChunkCount);
    }

}
