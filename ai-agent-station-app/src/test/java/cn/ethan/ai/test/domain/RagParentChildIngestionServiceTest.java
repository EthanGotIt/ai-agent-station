package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.adapter.port.IRagChildChunkIndexPort;
import cn.ethan.ai.domain.agent.adapter.repository.IRagParentChildIngestionRepository;
import cn.ethan.ai.domain.agent.model.valobj.RagIngestionChunkVO;
import cn.ethan.ai.domain.agent.model.valobj.RagIngestionDocumentVO;
import cn.ethan.ai.domain.agent.model.valobj.RagParentChildIngestionResultVO;
import cn.ethan.ai.domain.agent.service.rag.RagParentChildIngestionService;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.ArrayList;
import java.util.List;

public class RagParentChildIngestionServiceTest {

    @Test
    public void ingestMarkdownShouldBuildParentChildAndIndexChildrenOnly() {
        RecordingRepository repository = new RecordingRepository();
        RecordingIndexPort indexPort = new RecordingIndexPort();
        RagParentChildIngestionService service = new RagParentChildIngestionService(
                TokenTextSplitter.builder().build(),
                repository,
                indexPort
        );

        RagParentChildIngestionResultVO result = service.ingestMarkdown(
                "7001",
                "Spring AI MCP 指南",
                "spring-ai-mcp-guide.md",
                buildMarkdown()
        );

        Assert.assertNotNull(result.getDocId());
        Assert.assertTrue(result.getParentChunkCount() >= 3);
        Assert.assertTrue(result.getChildChunkCount() > result.getParentChunkCount());
        Assert.assertNotNull(repository.document);
        Assert.assertEquals(result.getDocId(), repository.document.getDocId());
        Assert.assertTrue(repository.chunks.stream().anyMatch(item -> item.getChunkLevel() == 1));
        Assert.assertTrue(repository.chunks.stream().anyMatch(item -> item.getChunkLevel() == 2));
        Assert.assertEquals(result.getChildChunkCount().intValue(), indexPort.indexedChildren.size());
        Assert.assertTrue(indexPort.indexedChildren.stream().allMatch(item ->
                Integer.valueOf(2).equals(item.getMetadata().get("chunk_level")) &&
                        item.getMetadata().get("parent_chunk_id") != null &&
                        item.getMetadata().get("section_title") != null
        ));
    }

    private String buildMarkdown() {
        String repeatedParagraph = "Controlled Agent Harness 会约束模型输出受控 action，并在执行阶段结合工具路由、证据上下文和证据评估结果逐步完成任务。";
        StringBuilder builder = new StringBuilder();
        builder.append("# Spring AI MCP Client 使用指南\n\n");
        builder.append("## 接入方式\n\n");
        appendRepeatedParagraph(builder, repeatedParagraph, 18);
        builder.append("\n## 动态工具路由\n\n");
        appendRepeatedParagraph(builder, repeatedParagraph, 18);
        builder.append("\n## Parent-Child 检索\n\n");
        appendRepeatedParagraph(builder, repeatedParagraph, 18);
        return builder.toString();
    }

    private void appendRepeatedParagraph(StringBuilder builder, String paragraph, int count) {
        for (int i = 0; i < count; i++) {
            builder.append(paragraph).append(" 这部分说明用于构造较长父块内容，便于观察子块切分效果。").append("\n\n");
        }
    }

    private static class RecordingRepository implements IRagParentChildIngestionRepository {

        private RagIngestionDocumentVO document;
        private List<RagIngestionChunkVO> chunks = List.of();

        @Override
        public void replaceDocument(RagIngestionDocumentVO document, List<RagIngestionChunkVO> chunks) {
            this.document = document;
            this.chunks = new ArrayList<>(chunks);
        }
    }

    private static class RecordingIndexPort implements IRagChildChunkIndexPort {

        private List<Document> indexedChildren = List.of();

        @Override
        public void replaceChildChunks(RagIngestionDocumentVO document, List<Document> childDocuments) {
            this.indexedChildren = new ArrayList<>(childDocuments);
        }
    }

}
