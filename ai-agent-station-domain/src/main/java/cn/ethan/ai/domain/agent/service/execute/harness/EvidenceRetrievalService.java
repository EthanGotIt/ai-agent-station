package cn.ethan.ai.domain.agent.service.execute.harness;

import cn.ethan.ai.domain.agent.adapter.port.IAgentModelPort;
import cn.ethan.ai.domain.agent.adapter.port.ILocalEvidenceRetrievalPort;
import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.AgentModelCallResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentActionVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentClientHarnessConfigVO;
import cn.ethan.ai.domain.agent.model.valobj.EvidenceRetrievalRequestVO;
import cn.ethan.ai.domain.agent.model.valobj.EvidenceRetrievalResultVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolInvocationCollector;
import cn.ethan.ai.domain.agent.model.valobj.ToolInvocationRecordVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.EvidenceSourceTypeEnumVO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Harness 唯一 evidence 检索入口。
 */
@Service
public class EvidenceRetrievalService {

    private static final int DEFAULT_TOP_K = 4;

    private final ILocalEvidenceRetrievalPort localEvidenceRetrievalPort;

    private final IAgentModelPort agentModelPort;

    private final RuntimeToolCapabilityService toolCapabilityService;

    private final AgentActionPolicy actionPolicy;

    private final McpEvidenceNormalizer mcpEvidenceNormalizer;

    public EvidenceRetrievalService(ILocalEvidenceRetrievalPort localEvidenceRetrievalPort,
                                    IAgentModelPort agentModelPort,
                                    RuntimeToolCapabilityService toolCapabilityService,
                                    AgentActionPolicy actionPolicy,
                                    McpEvidenceNormalizer mcpEvidenceNormalizer) {
        this.localEvidenceRetrievalPort = localEvidenceRetrievalPort;
        this.agentModelPort = agentModelPort;
        this.toolCapabilityService = toolCapabilityService;
        this.actionPolicy = actionPolicy;
        this.mcpEvidenceNormalizer = mcpEvidenceNormalizer;
    }

    public EvidenceRetrievalResultVO retrieve(AgentRunAggregate run,
                                              ExecuteCommandEntity command,
                                              AgentExecutionContextVO context,
                                              AgentActionVO action,
                                              int streamStep) {
        long start = System.currentTimeMillis();
        EvidenceSourceTypeEnumVO sourceType = action.getSourceType();
        List<String> queries = normalizeQueries(action, command.getMessage());
        List<Document> documents = sourceType == EvidenceSourceTypeEnumVO.PROJECT_KNOWLEDGE
                ? retrieveProjectKnowledge(context, queries)
                : retrieveExternal(run, command, context, sourceType, queries, streamStep);
        return EvidenceRetrievalResultVO.builder()
                .sourceType(sourceType)
                .queries(queries)
                .documents(documents)
                .channel(resolveChannel(documents, sourceType))
                .reason(documents.isEmpty() ? "本轮未获得可归因 evidence。" : "本轮新增 evidence=" + documents.size())
                .costMillis(System.currentTimeMillis() - start)
                .build();
    }

    private List<Document> retrieveProjectKnowledge(AgentExecutionContextVO context, List<String> queries) {
        Set<String> ragIds = resolveRagIds(context.getAiAgentClientHarnessConfigVOMap());
        if (ragIds.isEmpty()) {
            return List.of();
        }
        List<Document> documents = new ArrayList<>();
        for (String query : queries) {
            List<Document> retrieved = localEvidenceRetrievalPort.retrieve(EvidenceRetrievalRequestVO.builder()
                    .query(query)
                    .sourceType(EvidenceSourceTypeEnumVO.PROJECT_KNOWLEDGE)
                    .ragIds(ragIds)
                    .topK(DEFAULT_TOP_K)
                    .retrievalRound(context.getEvidenceBoard().getRetrievalRounds().size() + 1)
                    .build());
            if (retrieved != null) {
                retrieved.stream().map(document -> markSource(document, EvidenceSourceTypeEnumVO.PROJECT_KNOWLEDGE, true))
                        .forEach(documents::add);
            }
        }
        return mergeTopProjectEvidence(documents);
    }

    private List<Document> retrieveExternal(AgentRunAggregate run,
                                            ExecuteCommandEntity command,
                                            AgentExecutionContextVO context,
                                            EvidenceSourceTypeEnumVO sourceType,
                                            List<String> queries,
                                            int streamStep) {
        ToolRoutingDecisionVO routed = toolCapabilityService.routeForEvidenceSource(
                context.getAiAgentClientHarnessConfigVOMap(), sourceType);
        ToolRoutingDecisionVO readOnly = actionPolicy.readOnlyEvidenceDecision(routed);
        if (!readOnly.isEnabled()) {
            return List.of();
        }
        String prompt = """
                必须实际调用至少一个已授权的只读 MCP 工具获取原始资料。
                不要输出模拟的 tool_call、调用说明或等待提示，不要依靠模型已有知识补写事实。
                只需要完成资料读取，工具结果会由系统直接采集并归一化为 evidence。
                查询：%s
                """.formatted(queries);
        AgentModelCallResultEntity result = agentModelPort.callModelResult(
                context.getAiAgentClientHarnessConfigVOMap(),
                command,
                run.getContextWindowGuard(),
                run.getTrace(),
                prompt,
                "harness_external_evidence",
                "external_evidence_" + (context.getEvidenceBoard().getExternalRetrievalCount() + 1),
                streamStep,
                readOnly,
                AiClientTypeEnumVO.EXECUTOR_CLIENT,
                AiClientTypeEnumVO.DEFAULT
        );
        Object rawInvocations = result.getMetadata().get(ToolInvocationCollector.METADATA_KEY);
        List<ToolInvocationRecordVO> invocations = rawInvocations instanceof List<?> list
                ? list.stream().filter(ToolInvocationRecordVO.class::isInstance)
                .map(ToolInvocationRecordVO.class::cast).toList()
                : List.of();
        return mcpEvidenceNormalizer.normalize(invocations, sourceType, String.join(" | ", queries));
    }

    private Set<String> resolveRagIds(Map<String, AiAgentClientHarnessConfigVO> configs) {
        Set<String> result = new LinkedHashSet<>();
        if (configs == null) {
            return result;
        }
        configs.values().stream()
                .filter(java.util.Objects::nonNull)
                .map(AiAgentClientHarnessConfigVO::getRagIds)
                .filter(java.util.Objects::nonNull)
                .flatMap(Collection::stream)
                .filter(StringUtils::isNotBlank)
                .forEach(result::add);
        return result;
    }

    private List<String> normalizeQueries(AgentActionVO action, String fallback) {
        List<String> queries = action.getQueries() == null ? new ArrayList<>() : action.getQueries().stream()
                .filter(StringUtils::isNotBlank).map(String::trim).distinct().limit(2).toList();
        if (queries.isEmpty()) {
            return List.of(StringUtils.defaultIfBlank(action.getQuery(), fallback));
        }
        return queries;
    }

    private Document markSource(Document document, EvidenceSourceTypeEnumVO sourceType, boolean attributable) {
        Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
        metadata.put("qa_evidence_source_type", sourceType.name());
        metadata.put("qa_evidence_attributable", attributable);
        return document.mutate().metadata(metadata).build();
    }

    private String resolveChannel(List<Document> documents, EvidenceSourceTypeEnumVO sourceType) {
        if (sourceType != EvidenceSourceTypeEnumVO.PROJECT_KNOWLEDGE) {
            return "mcp:" + sourceType.name().toLowerCase();
        }
        Set<String> channels = new LinkedHashSet<>();
        for (Document document : documents) {
            Object value = document.getMetadata().get("qa_retrieval_source");
            if (value != null) {
                channels.add(value.toString());
            }
        }
        return channels.isEmpty() ? "project_knowledge" : String.join("+", channels);
    }

    private List<Document> mergeTopProjectEvidence(List<Document> documents) {
        Set<String> seen = new LinkedHashSet<>();
        return documents.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(
                        document -> document.getScore() == null ? 0D : document.getScore(),
                        Comparator.reverseOrder()))
                .filter(document -> seen.add(evidenceKey(document)))
                .limit(DEFAULT_TOP_K)
                .toList();
    }

    private String evidenceKey(Document document) {
        for (String key : List.of("uri", "url", "source_url")) {
            Object value = document.getMetadata().get(key);
            if (value != null && StringUtils.isNotBlank(value.toString())) {
                return key + ":" + value;
            }
        }
        return StringUtils.defaultIfBlank(document.getId(), document.getText());
    }
}
