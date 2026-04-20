package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.service.armory.factory.element.RagRetrievalSupport;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

public class RagRetrievalSupportTest {

    private final RagRetrievalSupport ragRetrievalSupport = new RagRetrievalSupport();

    @Test
    public void rewriteQueryShouldGenerateStableMultipleRoutes() {
        List<String> queries = ragRetrievalSupport.rewriteQueries("请帮我总结一下 Spring AI MCP 工具接入流程？", 3);

        Assert.assertEquals(3, queries.size());
        Assert.assertEquals("请帮我总结一下 Spring AI MCP 工具接入流程？", queries.get(0));
        Assert.assertTrue(queries.get(1).contains("Spring AI MCP 工具接入流程"));
        Assert.assertFalse(queries.get(1).contains("请帮我"));
    }

    @Test
    public void deduplicateByDocumentAndChunkMetadata() {
        Document first = Document.builder()
                .id("doc-1")
                .text("第一段 MCP 工具配置说明")
                .metadata(Map.of("doc_id", "agent", "chunk_id", "1"))
                .score(0.70)
                .build();
        Document duplicatedWithHigherScore = Document.builder()
                .id("doc-1")
                .text("第一段 MCP 工具配置说明")
                .metadata(Map.of("doc_id", "agent", "chunk_id", "1"))
                .score(0.95)
                .build();
        Document second = Document.builder()
                .id("doc-2")
                .text("第二段 Flow Plan 执行说明")
                .metadata(Map.of("doc_id", "agent", "chunk_id", "2"))
                .score(0.80)
                .build();

        List<Document> documents = ragRetrievalSupport.mergeAndDeduplicate(
                List.of(first, duplicatedWithHigherScore, second), 4, 180);

        Assert.assertEquals(2, documents.size());
        Assert.assertEquals("doc-1", documents.get(0).getId());
        Assert.assertEquals(Double.valueOf(0.95), documents.get(0).getScore());
    }

    @Test
    public void deduplicateByContentFingerprintWhenMetadataMissing() {
        Document first = Document.builder()
                .text("RAG 多路召回会把原始问题和改写问题分别检索，然后合并结果。")
                .score(0.60)
                .build();
        Document duplicated = Document.builder()
                .text("RAG 多路召回，会把原始问题和改写问题分别检索，然后合并结果！后续还有更多内容。")
                .score(0.90)
                .build();

        List<Document> documents = ragRetrievalSupport.mergeAndDeduplicate(List.of(first, duplicated), 4, 28);

        Assert.assertEquals(1, documents.size());
        Assert.assertEquals(Double.valueOf(0.90), documents.get(0).getScore());
    }

    @Test
    public void formatEvidenceContextShouldIncludeCitationNumberAndSource() {
        Document document = Document.builder()
                .text("Flow Plan 负责任务编排。")
                .metadata(Map.of("source", "agent-doc.md"))
                .build();

        String context = ragRetrievalSupport.formatEvidenceContext(List.of(document));

        Assert.assertTrue(context.contains("[证据1]"));
        Assert.assertTrue(context.contains("agent-doc.md"));
        Assert.assertTrue(context.contains("Flow Plan"));
    }

}
