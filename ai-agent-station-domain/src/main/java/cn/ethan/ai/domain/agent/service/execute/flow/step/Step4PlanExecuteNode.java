package cn.ethan.ai.domain.agent.service.execute.flow.step;

import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.AgentModelCallResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentPlanStepVO;
import cn.ethan.ai.domain.agent.model.valobj.RagEvidenceVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.PlanStepTypeEnumVO;
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
    private Step5QualitySupervisorNode step5QualitySupervisorNode;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) throws Exception {
        log.info("步骤4：按计划执行任务步骤");

        AgentRunAggregate run = currentRun(executionContext);
        int stepIndex = 1;
        for (AgentPlanStepVO step : run.getPlan().getSteps()) {
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

            AgentModelCallResultEntity modelCallResult = executeSingleStep(requestParameter, executionContext, run, step, stepIndex);
            String output = modelCallResult.getContent();
            run.recordStepOutput(step.getStepId(), output);
            sendStreamResult(executionContext, AgentExecuteResultEntity.createExecutionResult(
                    stepIndex,
                    output,
                    requestParameter.getSessionId(),
                    run.runId()
            ));

            emitRagEvidence(executionContext, requestParameter, run, step, stepIndex, modelCallResult);

            if (run.getContextWindowGuard().shouldCompactHistory()) {
                run.getContextWindowGuard().markHistoryCompacted();
                sendStreamResult(executionContext, AgentExecuteResultEntity.createAnalysisSubResult(
                        stepIndex,
                        "analysis_progress",
                        "上下文较长，后续步骤将使用压缩后的历史摘要。",
                        requestParameter.getSessionId(),
                        run.runId()
                ));
            }
            stepIndex++;
        }

        return router(requestParameter, executionContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, AgentExecutionContextVO, String> get(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) {
        return step5QualitySupervisorNode;
    }

    private AgentModelCallResultEntity executeSingleStep(ExecuteCommandEntity requestParameter,
                                                         AgentExecutionContextVO executionContext,
                                                         AgentRunAggregate run,
                                                         AgentPlanStepVO step,
                                                         Integer stepIndex) {
        if (PlanStepTypeEnumVO.SUMMARY.name().equalsIgnoreCase(step.getType())) {
            return AgentModelCallResultEntity.builder()
                    .content(promptFactory.buildLocalSummary(requestParameter, run.getPlan(), run.stepOutputs(), ""))
                    .build();
        }

        Map<String, String> promptStepOutputs = run.getContextWindowGuard().isHistoryCompacted()
                ? promptFactory.compactStepOutputsAsMap(run.stepOutputs())
                : run.stepOutputs();
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
                    .sourceName(readMetadata(metadata, "source", "title", "file_name", "filename"))
                    .sectionTitle(readMetadata(metadata, "section_title", "sectionTitle", "qa_parent_chunk_id"))
                    .retrievalQuery(readMetadata(metadata, "qa_retrieval_query"))
                    .rank(resolveInteger(metadata.get("qa_retrieval_rank"), index))
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

