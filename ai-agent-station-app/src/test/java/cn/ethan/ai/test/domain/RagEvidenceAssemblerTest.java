package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.valobj.AgentPlanStepVO;
import cn.ethan.ai.domain.agent.model.valobj.RagEvidenceVO;
import cn.ethan.ai.domain.agent.service.execute.flow.RagEvidenceAssembler;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

public class RagEvidenceAssemblerTest {

    private final RagEvidenceAssembler assembler = new RagEvidenceAssembler();

    @Test
    @SuppressWarnings("unchecked")
    public void shouldBuildAgenticRagEvidencePayloadWithParentExpansionAndDeduplication() {
        AgentPlanStepVO step = AgentPlanStepVO.builder()
                .stepId("step_rag")
                .name("知识库检索")
                .type("RAG")
                .build();
        Document first = Document.builder()
                .id("doc-1")
                .text("父块内容：Spring AI MCP Client 接入流程")
                .metadata(Map.of(
                        "doc_id", "spring-ai",
                        "chunk_id", "child-1",
                        "qa_hit_chunk_id", "child-1",
                        "qa_parent_chunk_id", "parent-1",
                        "qa_parent_key", "parent:spring-ai:parent-1",
                        "qa_retrieval_query", "Spring AI MCP Client 接入",
                        "qa_retrieval_source", "pgvector",
                        "qa_retrieval_rank", 1
                ))
                .score(0.9D)
                .build();
        Document duplicateParent = first.mutate()
                .id("doc-2")
                .score(0.8D)
                .build();

        Map<String, Object> payload = assembler.buildPayload(step, 1, Map.of(
                "qa_retrieved_documents", List.of(first, duplicateParent),
                "qa_retrieval_queries", List.of("Spring AI MCP Client 接入", "Spring AI MCP Client 接入")
        ));

        Assert.assertEquals(Boolean.TRUE, payload.get("agenticRag"));
        Assert.assertEquals(1, payload.get("evidenceCount"));
        Assert.assertTrue(payload.get("pipeline").toString().contains("rrf_fusion"));
        List<RagEvidenceVO> evidences = (List<RagEvidenceVO>) payload.get("evidences");
        Assert.assertEquals(1, evidences.size());
        Assert.assertEquals("child-1", evidences.get(0).getHitChunkId());
        Assert.assertEquals("parent-1", evidences.get(0).getParentChunkId());
        Assert.assertEquals(Boolean.TRUE, evidences.get(0).getParentExpanded());
    }

    @Test
    public void shouldExposeNoEvidencePayloadWhenQueriesExistWithoutDocuments() {
        AgentPlanStepVO step = AgentPlanStepVO.builder()
                .stepId("step_rag")
                .name("知识库检索")
                .type("RAG")
                .build();

        Map<String, Object> payload = assembler.buildPayload(step, 1, Map.of(
                "qa_retrieved_documents", List.of(),
                "qa_retrieval_queries", List.of("不存在的知识")
        ));

        Assert.assertEquals(0, payload.get("evidenceCount"));
        Assert.assertEquals(Boolean.TRUE, payload.get("noEvidence"));
        Assert.assertTrue(assembler.buildMessage(payload).contains("未召回"));
    }

    @Test
    public void shouldNotEmitPayloadForNonRagSkippedRequest() {
        AgentPlanStepVO step = AgentPlanStepVO.builder()
                .stepId("step_llm")
                .name("普通生成")
                .type("LLM")
                .build();

        Map<String, Object> payload = assembler.buildPayload(step, 1, Map.of(
                "qa_retrieval_skipped_reason", "非 RAG 请求，跳过知识检索。"
        ));

        Assert.assertTrue(payload.isEmpty());
    }
}
