package cn.ethan.ai.test.spring.ai;

import cn.ethan.ai.domain.agent.model.valobj.RagIngestionResultVO;
import cn.ethan.ai.domain.agent.service.rag.RagIngestionService;
import cn.ethan.ai.test.support.ManualTestGate;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Markdown RAG 手工导入测试。
 * 仅用于本地 dev 环境下的导入 smoke，不参与默认回归。
 */
@Slf4j
@SpringBootTest
public class MarkdownRagImportSmokeIT {

    @BeforeAll
    public static void beforeClass() {
        ManualTestGate.requireRealAi("MarkdownRagImportSmokeTest");
        ManualTestGate.requireDbMutation("MarkdownRagImportSmokeTest");
    }

    @Value("classpath:data/spring-ai-mcp-client.md")
    private Resource springAiMcpClientMarkdown;

    @Value("classpath:data/rag-evidence-retrieval.md")
    private Resource ragEvidenceMarkdown;

    @jakarta.annotation.Resource
    private RagIngestionService ragIngestionService;

    @jakarta.annotation.Resource(name = "mysqlJdbcTemplate")
    private JdbcTemplate mysqlJdbcTemplate;

    @Test
    public void importMarkdownParentChild() throws IOException {
        RagIngestionResultVO mcpGuideResult = ragIngestionService.ingestMarkdown(
                "rag-agent-station",
                "Spring AI MCP Client 使用指南",
                "spring-ai-mcp-client.md",
                readUtf8Resource(springAiMcpClientMarkdown)
        );

        RagIngestionResultVO ragGuideResult = ragIngestionService.ingestMarkdown(
                "rag-agent-station",
                "Evidence Retrieval 说明",
                "rag-evidence-retrieval.md",
                readUtf8Resource(ragEvidenceMarkdown)
        );

        int documentCount = queryCount("SELECT COUNT(1) FROM ai_rag_document WHERE rag_id = 'rag-agent-station'");
        int chunkCount = queryCount("SELECT COUNT(1) FROM ai_rag_chunk WHERE rag_id = 'rag-agent-station' AND chunk_level = 1");
        int hierarchicalChunkCount = queryCount("SELECT COUNT(1) FROM ai_rag_chunk WHERE rag_id = 'rag-agent-station' AND parent_chunk_id IS NOT NULL");
        int expectedChunkCount = mcpGuideResult.getChunkCount() + ragGuideResult.getChunkCount();

        Assertions.assertTrue(documentCount >= 2, "文档记录写入失败");
        Assertions.assertEquals(expectedChunkCount, chunkCount, "RAG 分块数量异常");
        Assertions.assertEquals(0, hierarchicalChunkCount, "不应继续写入父子回溯数据");

        log.info("Markdown RAG 导入 smoke 完成，result1:{}，result2:{}，documentCount:{}，chunkCount:{}",
                mcpGuideResult, ragGuideResult, documentCount, chunkCount);
    }

    private int queryCount(String sql) {
        Integer count = mysqlJdbcTemplate.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    private String readUtf8Resource(Resource resource) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            return FileCopyUtils.copyToString(reader);
        }
    }

}
