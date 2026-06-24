package cn.ethan.ai.domain.agent.service.execute.harness;

import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentActionVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.ai.domain.agent.model.valobj.AgenticRagTraceVO;
import cn.ethan.ai.domain.agent.model.valobj.EvidenceBoardVO;
import cn.ethan.ai.domain.agent.model.valobj.EvidenceRetrievalResultVO;
import cn.ethan.ai.domain.agent.model.valobj.HarnessObservationVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.EvidenceSourceTypeEnumVO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行 Harness 的受控高层动作。模型只负责选择动作，副作用由这里统一治理。
 */
@Service
public class HarnessActionExecutor {

    public static final String RAG_EVIDENCE_SUB_TYPE = "rag_evidence";

    private final EvidenceRetrievalService retrievalService;

    private final GroundedAnswerService groundedAnswerService;

    private final EvidencePolicy evidencePolicy;

    private final EvidenceTraceAssembler traceAssembler;

    public HarnessActionExecutor(EvidenceRetrievalService retrievalService,
                                 GroundedAnswerService groundedAnswerService,
                                 EvidencePolicy evidencePolicy,
                                 EvidenceTraceAssembler traceAssembler) {
        this.retrievalService = retrievalService;
        this.groundedAnswerService = groundedAnswerService;
        this.evidencePolicy = evidencePolicy;
        this.traceAssembler = traceAssembler;
    }

    public HarnessObservationVO execute(AgentRunAggregate run,
                                        ExecuteCommandEntity command,
                                        AgentExecutionContextVO context,
                                        AgentActionVO action,
                                        int round) {
        return switch (action.getType()) {
            case RETRIEVE -> retrieve(run, command, context, action, round);
            case ASK_CLARIFY -> HarnessObservationVO.success(action,
                    StringUtils.defaultIfBlank(action.getClarifyingQuestion(), "请补充问题范围或期望核验的资料来源。"),
                    Map.of("reason", StringUtils.defaultString(action.getReason())), true);
            case FINALIZE -> finalizeAnswer(run, command, context, action, round);
        };
    }

    public HarnessObservationVO forceFinalize(AgentRunAggregate run,
                                              ExecuteCommandEntity command,
                                              AgentExecutionContextVO context,
                                              String reason,
                                              int streamStep) {
        AgentActionVO action = AgentActionVO.builder()
                .actionId("policy_finalize")
                .type(cn.ethan.ai.domain.agent.model.valobj.enums.AgentActionTypeEnumVO.FINALIZE)
                .reason(reason)
                .build();
        return finalizeAnswer(run, command, context, action, streamStep);
    }

    private HarnessObservationVO retrieve(AgentRunAggregate run,
                                          ExecuteCommandEntity command,
                                          AgentExecutionContextVO context,
                                          AgentActionVO action,
                                          int round) {
        EvidenceBoardVO board = context.getEvidenceBoard();
        board.updateAssessment(action.getAssessment());
        List<String> freshQueries = action.getQueries().stream()
                .filter(query -> board.registerRetrieval(action.getSourceType(), query))
                .toList();
        if (freshQueries.isEmpty()) {
            return HarnessObservationVO.failure(action, "相同来源和 query 已执行过，拒绝重复检索。", false);
        }
        action.setQueries(freshQueries);
        action.setQuery(freshQueries.get(0));
        EvidenceRetrievalResultVO result = retrievalService.retrieve(run, command, context, action, round);
        int added = board.addEvidence(result.getDocuments());
        if (action.getSourceType().isExternal()) {
            board.markExternalRetrieval();
        }

        AgenticRagTraceVO.RetrievalRoundVO traceRound = AgenticRagTraceVO.RetrievalRoundVO.builder()
                .round(round)
                .query(String.join(" | ", result.getQueries()))
                .channel(result.getChannel())
                .sourceType(result.getSourceType().name())
                .hitCount(result.getDocuments().size())
                .reason(result.getReason())
                .policyResult("allowed")
                .costMillis(result.getCostMillis())
                .build();
        board.recordRound(traceRound);

        EvidencePolicy.Decision currentPolicy = evidencePolicy.evaluateFinalization(
                command.getMessage(), board, context.getContextBoundary());
        AgenticRagTraceVO trace = traceAssembler.assemble(command.getMessage(), board, currentPolicy);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(RAG_EVIDENCE_SUB_TYPE, trace);
        payload.put("sourceType", result.getSourceType());
        payload.put("knowledgeScope", resolveKnowledgeScope(context));
        payload.put("channel", result.getChannel());
        payload.put("addedEvidence", added);
        payload.put("policyResult", currentPolicy);
        payload.put("costMillis", result.getCostMillis());
        String message = (added > 0
                ? "检索完成，Evidence Board 新增 " + added + " 条可归因证据。"
                : "检索完成，但未新增可归因证据。")
                + " Evidence Policy：" + currentPolicy.reason();
        return HarnessObservationVO.success(action, message, payload, false);
    }

    private HarnessObservationVO finalizeAnswer(AgentRunAggregate run,
                                                 ExecuteCommandEntity command,
                                                 AgentExecutionContextVO context,
                                                 AgentActionVO action,
                                                 int streamStep) {
        context.getEvidenceBoard().updateAssessment(action.getAssessment());
        GroundedAnswerService.Result result = groundedAnswerService.finalizeAnswer(run, command, context, streamStep);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(RAG_EVIDENCE_SUB_TYPE, result.trace());
        payload.put("policyResult", result.policy());
        return HarnessObservationVO.success(action, result.answer(), payload, true);
    }

    private Object resolveKnowledgeScope(AgentExecutionContextVO context) {
        return context.getAiAgentClientHarnessConfigVOMap().values().stream()
                .flatMap(config -> config.getRagIds().stream())
                .distinct()
                .toList();
    }
}
