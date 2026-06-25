package cn.ethan.ai.domain.agent.service.execute.springai.advisor;

import cn.ethan.ai.domain.agent.model.valobj.AgenticRagTraceVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolInvocationCollector;
import cn.ethan.ai.domain.agent.model.valobj.ToolInvocationRecordVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.EvidenceSourceTypeEnumVO;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 Spring AI 响应上下文转换为项目 evidence trace。
 */
public class ObservationTraceAdvisor implements BaseAdvisor {

    private static final String RETRIEVED_DOCUMENTS = "qa_retrieved_documents";

    private final McpEvidenceNormalizer mcpEvidenceNormalizer;

    public ObservationTraceAdvisor() {
        this(new McpEvidenceNormalizer());
    }

    public ObservationTraceAdvisor(McpEvidenceNormalizer mcpEvidenceNormalizer) {
        this.mcpEvidenceNormalizer = mcpEvidenceNormalizer;
    }

    @Override
    public @NonNull ChatClientRequest before(ChatClientRequest request, @NonNull AdvisorChain advisorChain) {
        if (request.context().containsKey(SpringAiAdvisorContextKeys.EVIDENCE_ACCUMULATOR)) {
            return request;
        }
        return request.mutate()
                .context(SpringAiAdvisorContextKeys.EVIDENCE_ACCUMULATOR, new EvidenceAccumulator())
                .build();
    }

    @Override
    public @NonNull ChatClientResponse after(ChatClientResponse response, @NonNull AdvisorChain advisorChain) {
        Map<String, Object> context = new LinkedHashMap<>(response.context());
        EvidenceAccumulator accumulator = resolveAccumulator(context);
        accumulator.addDocuments(extractDocuments(context.get(RETRIEVED_DOCUMENTS)));
        List<ToolInvocationRecordVO> invocations = extractToolInvocations(
                context.get(ToolInvocationCollector.METADATA_KEY));
        if (!invocations.isEmpty()) {
            accumulator.addDocuments(mcpEvidenceNormalizer.normalize(
                    invocations, EvidenceSourceTypeEnumVO.WEB_RESEARCH, ""));
        }
        AgenticRagTraceVO trace = AgenticRagTraceVO.builder()
                .intent("spring_ai_advisor_chain")
                .finalEvidences(accumulator.snapshot())
                .evidenceSufficient(!accumulator.isEmpty())
                .policyResult(accumulator.isEmpty() ? "NO_EVIDENCE" : "EVIDENCE_COLLECTED")
                .noEvidenceReason(accumulator.isEmpty() ? "Advisor Chain 未产生可归因 evidence。" : "")
                .build();
        context.put(SpringAiAdvisorContextKeys.EVIDENCE_ACCUMULATOR, accumulator);
        context.put(SpringAiAdvisorContextKeys.RAG_EVIDENCE_TRACE, trace);
        return response.mutate().context(context).build();
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE;
    }

    private EvidenceAccumulator resolveAccumulator(Map<String, Object> context) {
        Object value = context.get(SpringAiAdvisorContextKeys.EVIDENCE_ACCUMULATOR);
        return value instanceof EvidenceAccumulator accumulator ? accumulator : new EvidenceAccumulator();
    }

    private List<Document> extractDocuments(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Document> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Document document) {
                result.add(document);
            }
        }
        return result;
    }

    private List<ToolInvocationRecordVO> extractToolInvocations(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<ToolInvocationRecordVO> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof ToolInvocationRecordVO record) {
                result.add(record);
            }
        }
        return result;
    }
}
