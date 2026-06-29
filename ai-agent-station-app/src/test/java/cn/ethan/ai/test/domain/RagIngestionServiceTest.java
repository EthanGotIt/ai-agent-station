package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.adapter.port.IRagChunkIndexPort;
import cn.ethan.ai.domain.agent.adapter.repository.IRagIngestionRepository;
import cn.ethan.ai.domain.agent.model.valobj.RagIngestionChunkVO;
import cn.ethan.ai.domain.agent.model.valobj.RagIngestionDocumentVO;
import cn.ethan.ai.domain.agent.model.valobj.RagIngestionResultVO;
import cn.ethan.ai.domain.agent.service.rag.RagIngestionService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.ArrayList;
import java.util.List;

public class RagIngestionServiceTest {

    @Test
    void shouldPersistAndIndexTheSameFlatMarkdownChunks() {
        RecordingRepository repository = new RecordingRepository();
        RecordingIndexPort indexPort = new RecordingIndexPort();
        RagIngestionService service = new RagIngestionService(
                TokenTextSplitter.builder().build(), repository, indexPort);

        RagIngestionResultVO result = service.ingestMarkdown(
                "rag-agent-station", "Spring AI MCP 指南", "spring-ai-mcp-guide.md", buildMarkdown());

        Assertions.assertNotNull(result.getDocId());
        Assertions.assertTrue(result.getChunkCount() >= 3);
        Assertions.assertEquals(result.getChunkCount().intValue(), repository.chunks.size());
        Assertions.assertEquals(result.getChunkCount().intValue(), indexPort.indexedChunks.size());
        Assertions.assertTrue(repository.chunks.stream().allMatch(item ->
                item.getChunkLevel() == 1
                        && item.getParentChunkId() == null
                        && "markdown_chunk".equals(item.getChunkType())));
        Assertions.assertTrue(indexPort.indexedChunks.stream().allMatch(item ->
                item.getMetadata().get("chunk_id") != null
                        && item.getMetadata().get("section_title") != null));
    }

    private String buildMarkdown() {
        return """
                # Spring AI MCP Client 使用指南

                ## 接入方式

                Context7 通过 Stdio 接入。

                ## 工具治理

                Spring AI Advisor Chain 通过只读工具注册边界和 Guard 包装控制 MCP 调用。

                ## 证据治理

                工具返回结果统一归一化为 evidence。
                """;
    }

    private static class RecordingRepository implements IRagIngestionRepository {

        private List<RagIngestionChunkVO> chunks = List.of();

        @Override
        public void replaceDocument(RagIngestionDocumentVO document, List<RagIngestionChunkVO> chunks) {
            this.chunks = new ArrayList<>(chunks);
        }
    }

    private static class RecordingIndexPort implements IRagChunkIndexPort {

        private List<Document> indexedChunks = List.of();

        @Override
        public void replaceChunks(RagIngestionDocumentVO document, List<Document> chunks) {
            this.indexedChunks = new ArrayList<>(chunks);
        }
    }
}
