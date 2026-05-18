package cn.ethan.ai.test.spring.ai;

import cn.ethan.ai.domain.agent.model.valobj.RagParentChildIngestionResultVO;
import cn.ethan.ai.domain.agent.service.rag.RagParentChildIngestionService;
import cn.ethan.ai.test.support.ManualTestGate;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Markdown Parent-Child 手工导入测试。
 * 仅用于本地 dev 环境下的导入 smoke，不参与默认回归。
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class MarkdownRagImportSmokeTest {

    @BeforeClass
    public static void beforeClass() {
        ManualTestGate.requireRealAi("MarkdownRagImportSmokeTest");
        ManualTestGate.requireDbMutation("MarkdownRagImportSmokeTest");
    }

    @Value("classpath:data/spring-ai-mcp-client.md")
    private Resource springAiMcpClientMarkdown;

    @Value("classpath:data/rag-parent-child-upgrade.md")
    private Resource ragParentChildMarkdown;

    @jakarta.annotation.Resource
    private RagParentChildIngestionService ragParentChildIngestionService;

    @jakarta.annotation.Resource(name = "mysqlJdbcTemplate")
    private JdbcTemplate mysqlJdbcTemplate;

    @Test
    public void importMarkdownParentChild() throws IOException {
        RagParentChildIngestionResultVO mcpGuideResult = ragParentChildIngestionService.ingestMarkdown(
                "7001",
                "Spring AI MCP Client 使用指南",
                "spring-ai-mcp-client.md",
                readUtf8Resource(springAiMcpClientMarkdown)
        );

        RagParentChildIngestionResultVO ragGuideResult = ragParentChildIngestionService.ingestMarkdown(
                "7001",
                "Parent-Child RAG 升级说明",
                "rag-parent-child-upgrade.md",
                readUtf8Resource(ragParentChildMarkdown)
        );

        int documentCount = queryCount("SELECT COUNT(1) FROM ai_rag_document WHERE rag_id = '7001'");
        int parentChunkCount = queryCount("SELECT COUNT(1) FROM ai_rag_chunk WHERE rag_id = '7001' AND chunk_level = 1");
        int childChunkCount = queryCount("SELECT COUNT(1) FROM ai_rag_chunk WHERE rag_id = '7001' AND chunk_level = 2");
        int expectedParentChunkCount = mcpGuideResult.getParentChunkCount() + ragGuideResult.getParentChunkCount();
        int expectedChildChunkCount = mcpGuideResult.getChildChunkCount() + ragGuideResult.getChildChunkCount();

        Assert.assertTrue("文档记录写入失败", documentCount >= 2);
        Assert.assertEquals("父块数量异常", expectedParentChunkCount, parentChunkCount);
        Assert.assertEquals("子块数量异常", expectedChildChunkCount, childChunkCount);
        Assert.assertTrue("子块数量至少应与父块数量持平", childChunkCount >= parentChunkCount);

        log.info("Markdown Parent-Child 导入 smoke 完成，result1:{}，result2:{}，documentCount:{}，parentChunkCount:{}，childChunkCount:{}",
                mcpGuideResult, ragGuideResult, documentCount, parentChunkCount, childChunkCount);
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
