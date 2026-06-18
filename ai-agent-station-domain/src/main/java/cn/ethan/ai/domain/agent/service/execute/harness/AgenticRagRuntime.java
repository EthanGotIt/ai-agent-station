package cn.ethan.ai.domain.agent.service.execute.harness;

import cn.ethan.ai.domain.agent.adapter.port.IAgentModelPort;
import cn.ethan.ai.domain.agent.adapter.port.IRagRetrievalPort;
import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.AgentModelCallResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.ai.domain.agent.model.valobj.AgenticRagTraceVO;
import cn.ethan.ai.domain.agent.model.valobj.RagEvidenceVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.ethan.ai.domain.agent.service.armory.factory.element.RagRetrievalSupport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agentic RAG 3.0 主入口：规划检索、证据评估、有限二次检索和 grounded answer。
 */
@Slf4j
@Service
public class AgenticRagRuntime {

    public static final String METADATA_TRACE = "qa_agentic_rag_trace";

    private static final int DEFAULT_TOP_K = 4;

    private static final int MAX_REWRITE_QUERIES = 3;

    private static final int MAX_FINAL_EVIDENCE = 4;

    private static final int MIN_EVIDENCE_CHARS = 160;

    private final RagRetrievalSupport ragRetrievalSupport = new RagRetrievalSupport();

    @Resource
    private IRagRetrievalPort ragRetrievalPort;

    @Resource
    private IAgentModelPort agentModelPort;

    @Resource
    private AgentActionPolicy actionPolicy;

    public AgentModelCallResultEntity execute(AgentRunAggregate run,
                                              ExecuteCommandEntity command,
                                              AgentExecutionContextVO executionContext,
                                              String query,
                                              ToolRoutingDecisionVO toolRoutingDecision,
                                              int streamStep) {
        String question = StringUtils.defaultIfBlank(query, command.getMessage());
        AgenticRagTraceVO trace = AgenticRagTraceVO.builder()
                .originalQuestion(question)
                .intent(classifyIntent(question))
                .plannedQueries(ragRetrievalSupport.rewriteQueries(question, MAX_REWRITE_QUERIES))
                .build();

        List<Document> collectedDocuments = new ArrayList<>();
        retrieveLocal(trace, trace.getPlannedQueries().isEmpty() ? question : trace.getPlannedQueries().get(0), 1, collectedDocuments);

        boolean evidenceSufficient = evaluateEvidence(collectedDocuments);
        if (!evidenceSufficient && shouldUseMcpEvidence(trace, toolRoutingDecision)) {
            readMcpEvidence(trace, run, command, executionContext, question, toolRoutingDecision, streamStep, collectedDocuments);
            evidenceSufficient = evaluateEvidence(collectedDocuments);
        }

        if (!evidenceSufficient && trace.getPlannedQueries().size() > 1) {
            trace.setSecondRetrievalTriggered(true);
            retrieveLocal(trace, trace.getPlannedQueries().get(1), 2, collectedDocuments);
            evidenceSufficient = evaluateEvidence(collectedDocuments);
        }

        List<Document> finalDocuments = ragRetrievalSupport.mergeAndDeduplicate(collectedDocuments, MAX_FINAL_EVIDENCE, 180);
        trace.setFinalEvidences(toEvidence(finalDocuments));
        trace.setEvidenceSufficient(evidenceSufficient && !finalDocuments.isEmpty());
        if (finalDocuments.isEmpty()) {
            trace.setNoEvidenceReason("本地知识库和已授权只读资料均未召回可确认依据。");
        }

        String answer = buildGroundedAnswer(run, command, executionContext, question, finalDocuments, trace, streamStep);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(METADATA_TRACE, trace);
        metadata.put("qa_retrieved_documents", finalDocuments);
        metadata.put("qa_retrieval_queries", trace.getPlannedQueries());
        metadata.put("qa_retrieval_no_evidence", finalDocuments.isEmpty());
        return AgentModelCallResultEntity.builder()
                .content(answer)
                .metadata(metadata)
                .build();
    }

    private void retrieveLocal(AgenticRagTraceVO trace, String query, int round, List<Document> collectedDocuments) {
        if (StringUtils.isBlank(query)) {
            return;
        }
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(DEFAULT_TOP_K)
                    .build();
            List<Document> documents = ragRetrievalPort.retrieve(searchRequest, Map.of(
                    "rag_runtime", "agentic_rag",
                    "qa_retrieval_query", query,
                    "qa_retrieval_round", round
            ));
            List<Document> safeDocuments = documents == null ? List.of() : documents;
            collectedDocuments.addAll(safeDocuments);
            trace.getRetrievalRounds().add(AgenticRagTraceVO.RetrievalRoundVO.builder()
                    .round(round)
                    .query(query)
                    .channel("local_pgvector_bm25")
                    .hitCount(safeDocuments.size())
                    .rrfApplied(safeDocuments.stream().anyMatch(this::hasRrfScore))
                    .smallToBigApplied(safeDocuments.stream().anyMatch(this::hasParentExpansion))
                    .reason(round == 1 ? "首轮本地知识检索" : "证据不足后的有限二次检索")
                    .build());
        } catch (Exception e) {
            log.warn("Agentic RAG 本地检索失败，query：{}，原因：{}", query, e.getMessage());
            trace.getRetrievalRounds().add(AgenticRagTraceVO.RetrievalRoundVO.builder()
                    .round(round)
                    .query(query)
                    .channel("local_pgvector_bm25")
                    .hitCount(0)
                    .reason("本地检索失败：" + e.getMessage())
                    .build());
        }
    }

    private void readMcpEvidence(AgenticRagTraceVO trace,
                                 AgentRunAggregate run,
                                 ExecuteCommandEntity command,
                                 AgentExecutionContextVO executionContext,
                                 String question,
                                 ToolRoutingDecisionVO toolRoutingDecision,
                                 int streamStep,
                                 List<Document> collectedDocuments) {
        ToolRoutingDecisionVO readOnlyDecision = actionPolicy.readOnlyEvidenceDecision(toolRoutingDecision);
        if (!readOnlyDecision.isEnabled()) {
            trace.getRetrievalRounds().add(AgenticRagTraceVO.RetrievalRoundVO.builder()
                    .round(trace.getRetrievalRounds().size() + 1)
                    .query(question)
                    .channel("mcp_read_only")
                    .hitCount(0)
                    .reason(readOnlyDecision.getSummary())
                    .build());
            return;
        }

        String prompt = """
                请使用已授权的只读 MCP 工具补充 evidence，只允许读取公开文档、搜索结果或资料页面。
                禁止写入、通知、记忆、执行命令或修改外部系统。
                如果工具不可用或没有证据，请明确说明，不要编造。

                需要核验的问题：
                %s
                """.formatted(question);
        AgentModelCallResultEntity result = agentModelPort.callModelResult(
                executionContext.getAiAgentClientHarnessConfigVOMap(),
                command,
                run.getContextWindowGuard(),
                run.getTrace(),
                prompt,
                "agentic_rag_mcp_read",
                "rag_mcp_read",
                streamStep,
                readOnlyDecision,
                AiClientTypeEnumVO.EXECUTOR_CLIENT,
                AiClientTypeEnumVO.RESPONSE_ASSISTANT,
                AiClientTypeEnumVO.DEFAULT
        );
        String content = StringUtils.trimToEmpty(result.getContent());
        boolean hasEvidence = StringUtils.isNotBlank(content)
                && !content.contains("未找到")
                && !content.contains("不可用")
                && !content.contains("不能确认");
        if (hasEvidence) {
            collectedDocuments.add(Document.builder()
                    .id("mcp-read-" + (collectedDocuments.size() + 1))
                    .text(content)
                    .metadata(Map.of(
                            "qa_retrieval_source", "mcp_read_only",
                            "source", "MCP 只读资料",
                            "qa_retrieval_query", question
                    ))
                    .score(0.6D)
                    .build());
        }
        trace.getRetrievalRounds().add(AgenticRagTraceVO.RetrievalRoundVO.builder()
                .round(trace.getRetrievalRounds().size() + 1)
                .query(question)
                .channel("mcp_read_only")
                .hitCount(hasEvidence ? 1 : 0)
                .reason(hasEvidence ? "已融合 MCP 只读 evidence" : "MCP 只读工具未返回可确认 evidence")
                .build());
    }

    private String buildGroundedAnswer(AgentRunAggregate run,
                                       ExecuteCommandEntity command,
                                       AgentExecutionContextVO executionContext,
                                       String question,
                                       List<Document> documents,
                                       AgenticRagTraceVO trace,
                                       int streamStep) {
        if (documents == null || documents.isEmpty()) {
            return "未能从当前知识库或已授权只读资料中找到可确认依据，无法给出基于证据的回答。";
        }
        String evidenceContext = ragRetrievalSupport.formatEvidenceContext(documents);
        String prompt = """
                请只基于下列证据回答用户问题。
                要求：
                - 结论必须能从证据推出。
                - 如果证据不足，明确说明不足点。
                - 不要编造证据外的事实。
                - 回答末尾简要列出证据来源。

                用户问题：
                %s

                证据：
                %s
                """.formatted(question, evidenceContext);
        return agentModelPort.callModel(
                executionContext.getAiAgentClientHarnessConfigVOMap(),
                command,
                run.getContextWindowGuard(),
                run.getTrace(),
                prompt,
                "agentic_rag_answer",
                "rag_grounded_answer",
                streamStep,
                ToolRoutingDecisionVO.disabled("Agentic RAG 最终回答阶段不注入外部 MCP 工具。"),
                AiClientTypeEnumVO.RESPONSE_ASSISTANT,
                AiClientTypeEnumVO.EXECUTOR_CLIENT,
                AiClientTypeEnumVO.DEFAULT
        );
    }

    private boolean shouldUseMcpEvidence(AgenticRagTraceVO trace, ToolRoutingDecisionVO toolRoutingDecision) {
        return toolRoutingDecision != null && toolRoutingDecision.isEnabled()
                && ("external_research".equals(trace.getIntent()) || "technical_docs".equals(trace.getIntent()));
    }

    private String classifyIntent(String question) {
        String normalized = StringUtils.defaultString(question).toLowerCase();
        if (containsAny(normalized, List.of("最新", "官网", "联网", "搜索", "资料", "调研"))) {
            return "external_research";
        }
        if (containsAny(normalized, List.of("spring ai", "mcp", "sdk", "接口", "配置", "源码", "文档"))) {
            return "technical_docs";
        }
        return "knowledge_qa";
    }

    private boolean evaluateEvidence(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return false;
        }
        int totalChars = documents.stream()
                .map(Document::getText)
                .filter(Objects::nonNull)
                .mapToInt(String::length)
                .sum();
        boolean hasConfidentScore = documents.stream()
                .map(Document::getScore)
                .filter(Objects::nonNull)
                .anyMatch(score -> score >= 0.65D);
        return documents.size() >= 2 || totalChars >= MIN_EVIDENCE_CHARS || hasConfidentScore;
    }

    private List<RagEvidenceVO> toEvidence(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<RagEvidenceVO> evidences = new ArrayList<>();
        int rank = 1;
        for (Document document : documents) {
            evidences.add(RagEvidenceVO.builder()
                    .evidenceId(StringUtils.defaultIfBlank(document.getId(), "evidence-" + rank))
                    .documentId(metadata(document, "doc_id", "document_id", "documentId"))
                    .chunkId(metadata(document, "chunk_id", "chunkId"))
                    .hitChunkId(metadata(document, "qa_hit_chunk_id"))
                    .parentChunkId(metadata(document, "qa_parent_chunk_id", "parent_chunk_id"))
                    .parentKey(metadata(document, "qa_parent_key"))
                    .parentExpanded(hasParentExpansion(document))
                    .sourceName(StringUtils.defaultIfBlank(metadata(document, "source", "file_name", "title"), "未知来源"))
                    .sourceType(StringUtils.defaultIfBlank(metadata(document, "qa_retrieval_source"), "local"))
                    .sectionTitle(metadata(document, "section_title", "sectionTitle"))
                    .retrievalQuery(metadata(document, "qa_retrieval_query"))
                    .rank(rank)
                    .fusionRank(rank)
                    .score(document.getScore())
                    .contentPreview(limit(document.getText(), 240))
                    .build());
            rank++;
        }
        return evidences;
    }

    private boolean hasRrfScore(Document document) {
        return document != null && document.getScore() != null && document.getScore() > 0D && document.getScore() < 0.1D;
    }

    private boolean hasParentExpansion(Document document) {
        return StringUtils.isNotBlank(metadata(document, "qa_parent_chunk_id", "parent_chunk_id", "qa_hit_chunk_id"));
    }

    private String metadata(Document document, String... keys) {
        if (document == null || document.getMetadata() == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            Object value = document.getMetadata().get(key);
            if (value != null && StringUtils.isNotBlank(value.toString())) {
                return value.toString();
            }
        }
        return "";
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String limit(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        return content.length() <= maxLength ? content : content.substring(0, maxLength) + "...";
    }
}
