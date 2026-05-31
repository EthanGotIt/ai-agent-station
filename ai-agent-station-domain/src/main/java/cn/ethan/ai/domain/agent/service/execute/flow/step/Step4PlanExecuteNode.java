package cn.ethan.ai.domain.agent.service.execute.flow.step;

import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.AgentModelCallResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentPlanStepVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextGuardResultVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentStepRunStatusEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.PlanStepTypeEnumVO;
import cn.ethan.ai.domain.agent.service.execute.flow.AgentContextBoundaryService;
import cn.ethan.ai.domain.agent.service.execute.flow.AgentContextWindowService;
import cn.ethan.ai.domain.agent.service.execute.flow.AgentStepToolInjectionPolicy;
import cn.ethan.ai.domain.agent.service.execute.flow.RagEvidenceAssembler;
import cn.ethan.ai.domain.agent.service.execute.flow.plan.AgentPlanPromptFactory;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    private final RagEvidenceAssembler ragEvidenceAssembler = new RagEvidenceAssembler();

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) throws Exception {
        log.info("步骤4：按计划执行任务步骤");
        if (stopIfCancelled(executionContext, "任务已取消，停止执行计划步骤。")) {
            return "任务已取消";
        }

        AgentRunAggregate run = currentRun(executionContext);
        int stepIndex = 1;
        List<AgentPlanStepVO> planSteps = run.getPlan().getSteps();
        for (int planIndex = 0; planIndex < planSteps.size(); planIndex++) {
            AgentPlanStepVO step = planSteps.get(planIndex);
            if (stopIfCancelled(executionContext, "任务已取消，停止执行计划步骤。")) {
                markRemainingPlanSteps(
                        executionContext,
                        planSteps,
                        planIndex,
                        AgentStepRunStatusEnumVO.CANCELLED,
                        "任务已取消，未执行该计划步骤。"
                );
                return "任务已取消";
            }
            if (run.getContextWindowGuard().shouldStopNewLlmCall()) {
                markRemainingPlanSteps(
                        executionContext,
                        planSteps,
                        planIndex,
                        AgentStepRunStatusEnumVO.SKIPPED,
                        "上下文预算达到终止阈值，跳过未执行计划步骤。"
                );
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
                    step.getType()
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
                AgentContextBoundaryService.attachRunSummary(
                        executionContext.getContextBoundary(),
                        contextGuardResult.getHistorySummary()
                );
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
        String prompt = promptFactory.buildStepExecutionPrompt(
                requestParameter,
                run.getPlan(),
                step,
                promptStepOutputs,
                executionContext.getContextBoundary()
        );
        return agentModelPort.callModelResult(
                executionContext.getAiAgentClientFlowConfigVOMap(),
                requestParameter,
                run.getContextWindowGuard(),
                run.getTrace(),
                prompt,
                "LLM_CALL_STEP",
                step.getStepId(),
                stepIndex,
                AgentStepToolInjectionPolicy.shouldInjectExternalMcpTools(step, executionContext.getToolRoutingDecision())
                        ? executionContext.getToolRoutingDecision()
                        : null,
                AiClientTypeEnumVO.EXECUTOR_CLIENT,
                AiClientTypeEnumVO.PRECISION_EXECUTOR_CLIENT,
                AiClientTypeEnumVO.DEFAULT
        );
    }

    private void markRemainingPlanSteps(AgentExecutionContextVO executionContext,
                                        List<AgentPlanStepVO> planSteps,
                                        int fromIndex,
                                        AgentStepRunStatusEnumVO status,
                                        String reason) {
        if (planSteps == null || planSteps.isEmpty()) {
            return;
        }
        for (int index = fromIndex; index < planSteps.size(); index++) {
            markPlannedStepTerminal(
                    executionContext,
                    planSteps.get(index),
                    10 + index + 1,
                    status,
                    reason
            );
        }
        syncRunState(executionContext);
    }

    private String buildStepRoutingMessage(AgentPlanStepVO step, ToolRoutingDecisionVO routingDecision) {
        if (PlanStepTypeEnumVO.RAG.name().equalsIgnoreCase(step.getType())) {
            return "当前步骤使用 Agentic RAG：知识库检索、证据融合和可追踪回答，不注入外部 MCP 工具。";
        }
        if (PlanStepTypeEnumVO.SUPERVISION.name().equalsIgnoreCase(step.getType())
                || PlanStepTypeEnumVO.SUMMARY.name().equalsIgnoreCase(step.getType())) {
            return "当前步骤为内部监督或总结步骤，不注入外部 MCP 工具。";
        }
        if (routingDecision == null || !routingDecision.isEnabled()) {
            return "当前步骤直接使用模型执行。";
        }
        if (PlanStepTypeEnumVO.TOOL.name().equalsIgnoreCase(step.getType())) {
            return "当前步骤可使用本轮已筛选 MCP 工具，模型将在执行时自主决定是否调用。";
        }
        return "当前步骤为 LLM 执行，已注入本轮筛选后的 MCP 工具供模型按需调用。";
    }

    private Map<String, Object> buildStepRoutingPayload(AgentPlanStepVO step, Integer stepIndex, ToolRoutingDecisionVO routingDecision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("step", stepIndex);
        payload.put("stepId", step.getStepId());
        payload.put("stepName", step.getName());
        payload.put("stepType", step.getType());
        payload.put("agenticRag", PlanStepTypeEnumVO.RAG.name().equalsIgnoreCase(step.getType()));
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

        Map<String, Object> payload = ragEvidenceAssembler.buildPayload(step, stepIndex, modelCallResult.getMetadata());
        if (payload.isEmpty()) {
            return;
        }

        sendStreamResult(executionContext, AgentExecuteResultEntity.createExecutionSubResult(
                stepIndex,
                "rag_evidence",
                ragEvidenceAssembler.buildMessage(payload),
                payload,
                requestParameter.getSessionId(),
                run.runId()
        ));
    }
}

