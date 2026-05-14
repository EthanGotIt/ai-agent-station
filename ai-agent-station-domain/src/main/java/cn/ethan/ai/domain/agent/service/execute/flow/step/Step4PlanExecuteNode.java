package cn.ethan.ai.domain.agent.service.execute.flow.step;

import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.AgentModelCallResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentPlanStepVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextGuardResultVO;
import cn.ethan.ai.domain.agent.model.valobj.RagEvidenceVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.PlanStepTypeEnumVO;
import cn.ethan.ai.domain.agent.service.execute.flow.AgentContextWindowService;
import cn.ethan.ai.domain.agent.service.execute.flow.plan.AgentPlanPromptFactory;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 步骤4：按计划顺序执行任务。
 */
@Slf4j
@Service
public class Step4PlanExecuteNode extends AbstractExecuteSupport {

    @Resource
    private AgentPlanPromptFactory promptFactory;

    @Resource
    private AgentContextWindowService agentContextWindowService;

    @Resource
    private Step5QualitySupervisorNode step5QualitySupervisorNode;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) throws Exception {
        log.info("步骤4：按计划执行任务步骤");
        if (stopIfCancelled(executionContext, "任务已取消，停止执行计划步骤。")) {
            return "任务已取消";
        }

        AgentRunAggregate run = currentRun(executionContext);
        int stepIndex = 1;
        for (AgentPlanStepVO step : run.getPlan().getSteps()) {
            if (stopIfCancelled(executionContext, "任务已取消，停止执行计划步骤。")) {
                break;
            }
            if (run.getContextWindowGuard().shouldStopNewLlmCall()) {
                sendStreamResult(executionContext, AgentExecuteResultEntity.createExecutionSubResult(
                        stepIndex,
                        "execution_quality",
                        "上下文较长，停止新的模型调用，并基于已有结果生成总结。",
                        requestParameter.getSessionId(),
                        run.runId()
                ));
                break;
            }

            long startTime = markStepRunning(
                    executionContext,
                    step.getStepId(),
                    step.getName(),
                    10 + stepIndex,
                    step.getType(),
                    step.getToolName()
            );
            sendStreamResult(executionContext, AgentExecuteResultEntity.createExecutionSubResult(
                    stepIndex,
                    "execution_target",
                    step.getName(),
                    requestParameter.getSessionId(),
                    run.runId()
            ));

            sendStreamResult(executionContext, AgentExecuteResultEntity.createExecutionSubResult(
                    stepIndex,
                    "tool_routing",
                    buildStepRoutingMessage(step, executionContext.getToolRoutingDecision()),
                    buildStepRoutingPayload(step, stepIndex, executionContext.getToolRoutingDecision()),
                    requestParameter.getSessionId(),
                    run.runId()
            ));

            try {
                ContextGuardResultVO contextGuardResult = agentContextWindowService.prepareStepOutputs(run);
                if (contextGuardResult.isCompressed()) {
                    sendStreamResult(executionContext, AgentExecuteResultEntity.createAnalysisSubResult(
                            stepIndex,
                            "context_guard",
                            "上下文已超过阈值，后续步骤将优先使用压缩后的历史摘要。",
                            buildContextGuardPayload(contextGuardResult),
                            requestParameter.getSessionId(),
                            run.runId()
                    ));
                    syncRunState(executionContext);
                }

                AgentModelCallResultEntity modelCallResult = executeSingleStep(
                        requestParameter,
                        executionContext,
                        run,
                        step,
                        stepIndex,
                        contextGuardResult
                );
                String output = modelCallResult.getContent();
                run.recordStepOutput(step.getStepId(), output);
                sendStreamResult(executionContext, AgentExecuteResultEntity.createExecutionResult(
                        stepIndex,
                        output,
                        requestParameter.getSessionId(),
                        run.runId()
                ));

                emitRagEvidence(executionContext, requestParameter, run, step, stepIndex, modelCallResult);
                markStepSuccess(executionContext, step.getStepId(), output, startTime);
            } catch (Exception e) {
                markStepFailed(executionContext, step.getStepId(), e.getMessage(), startTime);
                throw e;
            }
            stepIndex++;
        }

        return router(requestParameter, executionContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, AgentExecutionContextVO, String> get(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) {
        if (executionContext.isCancelled()) {
            return defaultStrategyHandler;
        }
        return step5QualitySupervisorNode;
    }

    private AgentModelCallResultEntity executeSingleStep(ExecuteCommandEntity requestParameter,
                                                         AgentExecutionContextVO executionContext,
                                                         AgentRunAggregate run,
                                                         AgentPlanStepVO step,
                                                         Integer stepIndex,
                                                         ContextGuardResultVO contextGuardResult) {
        if (PlanStepTypeEnumVO.SUMMARY.name().equalsIgnoreCase(step.getType())) {
            return AgentModelCallResultEntity.builder()
                    .content(promptFactory.buildLocalSummary(requestParameter, run.getPlan(), run.stepOutputs(), ""))
                    .build();
        }

        Map<String, String> promptStepOutputs = contextGuardResult == null
                ? run.stepOutputs()
                : contextGuardResult.getStepOutputs();
        String prompt = promptFactory.buildStepExecutionPrompt(requestParameter, run.getPlan(), step, promptStepOutputs);
        return agentModelPort.callModelResult(
                executionContext.getAiAgentClientFlowConfigVOMap(),
                requestParameter,
                run.getContextWindowGuard(),
                run.getTrace(),
                prompt,
                "LLM_CALL_STEP",
                step.getStepId(),
                stepIndex,
                shouldEnableTools(step) ? executionContext.getToolRoutingDecision() : null,
                AiClientTypeEnumVO.EXECUTOR_CLIENT,
                AiClientTypeEnumVO.PRECISION_EXECUTOR_CLIENT,
                AiClientTypeEnumVO.DEFAULT
        );
    }

    private boolean shouldEnableTools(AgentPlanStepVO step) {
        return PlanStepTypeEnumVO.requiresTool(step.getType()) || StringUtils.isNotBlank(step.getToolName());
    }

    private String buildStepRoutingMessage(AgentPlanStepVO step, ToolRoutingDecisionVO routingDecision) {
        if (PlanStepTypeEnumVO.requiresTool(step.getType())) {
            return "当前步骤使用工具：" + StringUtils.defaultIfBlank(step.getToolName(), "未指定");
        }
        if (routingDecision == null || !routingDecision.isEnabled()) {
            return "当前步骤直接使用模型执行。";
        }
        return "当前步骤为 LLM 执行，保留本轮工具白名单以便需要时调用。";
    }

    private Map<String, Object> buildStepRoutingPayload(AgentPlanStepVO step, Integer stepIndex, ToolRoutingDecisionVO routingDecision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("step", stepIndex);
        payload.put("stepId", step.getStepId());
        payload.put("stepName", step.getName());
        payload.put("stepType", step.getType());
        payload.put("toolName", step.getToolName());
        payload.put("dependsOn", step.getDependsOn() == null ? Collections.emptyList() : step.getDependsOn());
        payload.put("allowedToolNames", routingDecision == null ? Collections.emptyList() : routingDecision.getAllowedToolNames());
        payload.put("routeReason", routingDecision == null ? "当前步骤直接执行" : routingDecision.getSummary());
        payload.put("selectedCount", routingDecision == null || routingDecision.getSelectedTools() == null ? 0 : routingDecision.getSelectedTools().size());
        payload.put("disabledReason", routingDecision == null || routingDecision.isEnabled() ? "" : routingDecision.getSummary());
        return payload;
    }

    private Map<String, Object> buildContextGuardPayload(ContextGuardResultVO contextGuardResult) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("compressed", contextGuardResult.isCompressed());
        payload.put("originalChars", contextGuardResult.getOriginalChars());
        payload.put("compressedChars", contextGuardResult.getCompressedChars());
        payload.put("historySummary", contextGuardResult.getHistorySummary());
        return payload;
    }

    private void emitRagEvidence(AgentExecutionContextVO executionContext,
                                 ExecuteCommandEntity requestParameter,
                                 AgentRunAggregate run,
                                 AgentPlanStepVO step,
                                 Integer stepIndex,
                                 AgentModelCallResultEntity modelCallResult) {
        if (modelCallResult == null || modelCallResult.getMetadata() == null || modelCallResult.getMetadata().isEmpty()) {
            return;
        }

        List<RagEvidenceVO> evidences = simplifyEvidences(modelCallResult.getMetadata().get("qa_retrieved_documents"));
        List<String> retrievalQueries = simplifyQueries(modelCallResult.getMetadata().get("qa_retrieval_queries"));
        if (evidences.isEmpty() && retrievalQueries.isEmpty()) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("step", stepIndex);
        payload.put("stepId", step.getStepId());
        payload.put("stepName", step.getName());
        payload.put("queries", retrievalQueries);
        payload.put("evidences", evidences);

        sendStreamResult(executionContext, AgentExecuteResultEntity.createExecutionSubResult(
                stepIndex,
                "rag_evidence",
                "本步骤关联 RAG 证据 " + evidences.size() + " 条。",
                payload,
                requestParameter.getSessionId(),
                run.runId()
        ));
    }

    private List<RagEvidenceVO> simplifyEvidences(Object rawDocuments) {
        if (!(rawDocuments instanceof List<?> documents) || documents.isEmpty()) {
            return List.of();
        }

        List<RagEvidenceVO> result = new ArrayList<>();
        int index = 1;
        for (Object item : documents) {
            if (!(item instanceof Document document)) {
                continue;
            }
            Map<String, Object> metadata = document.getMetadata() == null ? Collections.emptyMap() : document.getMetadata();
            result.add(RagEvidenceVO.builder()
                    .evidenceId("evidence_" + index)
                    .documentId(readMetadata(metadata, "doc_id", "document_id", "documentId"))
                    .chunkId(readMetadata(metadata, "chunk_id", "chunkId"))
                    .parentChunkId(readMetadata(metadata, "parent_chunk_id", "parentChunkId"))
                    .sourceName(readMetadata(metadata, "source", "title", "file_name", "filename"))
                    .sectionTitle(readMetadata(metadata, "section_title", "sectionTitle", "qa_parent_chunk_id"))
                    .retrievalQuery(readMetadata(metadata, "qa_retrieval_query"))
                    .rank(resolveInteger(metadata.get("qa_retrieval_rank"), index))
                    .fusionRank(resolveInteger(metadata.get("qa_retrieval_rank"), index))
                    .sourceType(readMetadata(metadata, "qa_retrieval_source"))
                    .score(document.getScore())
                    .contentPreview(clip(document.getText(), 260))
                    .build());
            index++;
        }
        return result;
    }

    private List<String> simplifyQueries(Object rawQueries) {
        if (!(rawQueries instanceof List<?> queryList) || queryList.isEmpty()) {
            return List.of();
        }

        List<String> queries = new ArrayList<>();
        for (Object item : queryList) {
            if (item == null) {
                continue;
            }
            String query = item.toString().trim();
            if (!query.isEmpty()) {
                queries.add(query);
            }
        }
        return queries;
    }

    private String readMetadata(Map<String, Object> metadata, String... keys) {
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value != null && StringUtils.isNotBlank(value.toString())) {
                return value.toString().trim();
            }
        }
        return "";
    }

    private Integer resolveInteger(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception ignore) {
            return defaultValue;
        }
    }

    private String clip(String text, int maxLength) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        String normalized = text.trim().replaceAll("\\s+", " ");
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }
}

