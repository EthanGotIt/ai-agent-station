package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.adapter.port.IRagRetrievalPort;
import cn.ethan.ai.domain.agent.service.execute.graph.GraphRagSearchToolCallback;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

import java.util.List;
import java.util.Map;

public class GraphRagSearchToolCallbackTest {

    @Test
    public void shouldExposeHybridEvidencePayload() {
        GraphRagSearchToolCallback callback = new GraphRagSearchToolCallback(new StubRetrievalPort());

        JSONObject result = JSON.parseObject(callback.call("{\"query\":\"Spring AI MCP 接入方式\"}"));

        Assert.assertTrue(result.getBooleanValue("success"));
        Assert.assertTrue(result.getIntValue("evidenceCount") > 0);
        Assert.assertTrue(result.getString("evidenceContext").contains("证据"));
        Assert.assertEquals(Boolean.TRUE, callback.lastEvidencePayload().get("agenticRag"));
        Assert.assertTrue(((List<?>) callback.lastEvidencePayload().get("pipeline")).contains("rrf_fusion"));
        Assert.assertFalse(((List<?>) callback.lastEvidencePayload().get("evidences")).isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectBlankQuery() {
        new GraphRagSearchToolCallback(new StubRetrievalPort()).call("{\"query\":\"\"}");
    }

    private static class StubRetrievalPort implements IRagRetrievalPort {

        @Override
        public List<Document> retrieve(SearchRequest request, Map<String, Object> context) {
            return List.of(Document.builder()
                    .id("doc-1")
                    .text("Spring AI MCP Client 支持 stdio 和 streamable HTTP 接入。")
                    .metadata(Map.of(
                            "doc_id", "doc-1",
                            "chunk_id", "p_001",
                            "parent_chunk_id", "p_001",
                            "source", "spring-ai-mcp-guide.md",
                            "section_title", "接入方式"
                    ))
                    .score(0.9)
                    .build());
        }
    }

}
