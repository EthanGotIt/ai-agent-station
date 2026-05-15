package cn.ethan.ai.test.dao;

import cn.ethan.ai.domain.agent.adapter.repository.IRagParentChildIngestionRepository;
import cn.ethan.ai.domain.agent.model.valobj.RagIngestionChunkVO;
import cn.ethan.ai.domain.agent.model.valobj.RagIngestionDocumentVO;
import jakarta.annotation.Resource;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RunWith(SpringRunner.class)
@SpringBootTest
@Transactional
@Rollback
public class RagParentChildIngestionRepositoryTest {

    @Resource
    private IRagParentChildIngestionRepository ragParentChildIngestionRepository;

    @Resource
    @Qualifier("mysqlJdbcTemplate")
    private JdbcTemplate mysqlJdbcTemplate;

    @Test
    public void replaceDocumentShouldPersistParentAndChildChunks() {
        String docId = "doc_test_parent_child";
        RagIngestionDocumentVO document = RagIngestionDocumentVO.builder()
                .ragId("7001")
                .docId(docId)
                .title("测试 Markdown")
                .source("test-doc.md")
                .summary("测试摘要")
                .metadataJson("{\"doc_type\":\"markdown\"}")
                .status(1)
                .build();
        List<RagIngestionChunkVO> chunks = List.of(
                RagIngestionChunkVO.builder()
                        .ragId("7001")
                        .docId(docId)
                        .chunkId("p_001")
                        .parentChunkId(null)
                        .chunkLevel(1)
                        .chunkType("markdown_parent")
                        .chunkText("父块内容")
                        .metadataJson("{\"section_title\":\"章节一\",\"chunk_order\":1}")
                        .status(1)
                        .build(),
                RagIngestionChunkVO.builder()
                        .ragId("7001")
                        .docId(docId)
                        .chunkId("p_001_c_001")
                        .parentChunkId("p_001")
                        .chunkLevel(2)
                        .chunkType("markdown_child")
                        .chunkText("子块内容")
                        .metadataJson("{\"section_title\":\"章节一\",\"chunk_order\":2,\"chunk_level\":2}")
                        .status(1)
                        .build()
        );

        ragParentChildIngestionRepository.replaceDocument(document, chunks);

        Integer docCount = mysqlJdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM ai_rag_document WHERE rag_id = ? AND doc_id = ?",
                Integer.class,
                "7001", docId
        );
        Integer chunkCount = mysqlJdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM ai_rag_chunk WHERE rag_id = ? AND doc_id = ?",
                Integer.class,
                "7001", docId
        );
        Integer childCount = mysqlJdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM ai_rag_chunk WHERE rag_id = ? AND doc_id = ? AND chunk_level = 2 AND parent_chunk_id = ?",
                Integer.class,
                "7001", docId, "p_001"
        );

        Assert.assertEquals(Integer.valueOf(1), docCount);
        Assert.assertEquals(Integer.valueOf(2), chunkCount);
        Assert.assertEquals(Integer.valueOf(1), childCount);
    }

}
