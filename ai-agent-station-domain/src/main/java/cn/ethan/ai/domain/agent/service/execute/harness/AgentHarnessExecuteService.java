package cn.ethan.ai.domain.agent.service.execute.harness;

import cn.ethan.ai.domain.agent.adapter.port.IAgentModelPort;
import cn.ethan.ai.domain.agent.adapter.port.IAgentStreamPort;
import cn.ethan.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.ethan.ai.domain.agent.adapter.repository.IAgentRunRepository;
import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.AgentModelCallResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentActionVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentStepRunRecordVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextGuardResultVO;
import cn.ethan.ai.domain.agent.model.valobj.HarnessObservationVO;
import cn.ethan.ai.domain.agent.model.valobj.SessionContextSnapshotVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentActionTypeEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentStepRunStatusEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.StreamTransportTypeEnumVO;
import cn.ethan.ai.domain.agent.service.execute.runtime.AgentContextBoundaryService;
import cn.ethan.ai.domain.agent.service.execute.runtime.AgentContextPolicyService;
import cn.ethan.ai.domain.agent.service.execute.runtime.AgentContextWindowService;
import cn.ethan.ai.domain.agent.service.execute.runtime.AgentConversationMemoryService;
import cn.ethan.ai.domain.agent.service.execute.runtime.AgentExecutionException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Controlled Agent Harness 主执行入口。
 */
@Slf4j
@Service
public class AgentHarnessExecuteService {

    private static final int DEFAULT_MAX_STEP = 4;

    private static final String RAG_EVIDENCE_SUB_TYPE = "rag_evidence";

    @Resource
    private IAgentRepository repository;

    @Resource
    private IAgentRunRepository agentRunRepository;

    @Resource
    private IAgentModelPort agentModelPort;

    @Resource
    private AgentContextPolicyService agentContextPolicyService;

    @Resource
    private AgentContextBoundaryService agentContextBoundaryService;

    @Resource
    private AgentContextWindowService agentContextWindowService;

    @Resource
    private AgentConversationMemoryService agentConversationMemoryService;

    @Resource
    private RuntimeToolCapabilityService toolCapabilityService;

    @Resource
    private AgentActionPromptFactory promptFactory;

    @Resource
    private AgentActionParser actionParser;

    @Resource
    private AgentActionPolicy actionPolicy;

    @Resource
    private AgenticRagRuntime agenticRagRuntime;

    public void execute(ExecuteCommandEntity command, IAgentStreamPort streamPort) throws Exception {
        AgentExecutionContextVO executionContext = initializeContext(command, streamPort);
        AgentRunAggregate run = executionContext.getAgentRunAggregate();

        try {
            initializeRun(command, executionContext, run);
            routeTools(command, executionContext, run);
            runHarnessLoop(command, executionContext, run);
        } catch (Exception e) {
            if (!run.isCancelled()) {
                run.markFailed(e.getMessage());
                agentRunRepository.updateRun(run.toRecord());
            }
            throw new AgentExecutionException(run.runId(), e.getMessage(), e);
        }
    }

    private AgentExecutionContextVO initializeContext(ExecuteCommandEntity command, IAgentStreamPort streamPort) {
        AgentExecutionContextVO executionContext = new AgentExecutionContextVO();
        executionContext.setMaxStep(command.getMaxStep() != null ? command.getMaxStep() : DEFAULT_MAX_STEP);
        executionContext.setStreamProtocol(StreamTransportTypeEnumVO.fromCode(command.getStreamProtocol()));
        executionContext.setStreamPort(streamPort);
        executionContext.bindSessionId(command.getSessionId());

        SessionContextSnapshotVO sessionContextSnapshot = agentConversationMemoryService.loadSessionContext(command.getSessionId());
        executionContext.setContextBoundary(agentContextBoundaryService.buildBoundary(command, sessionContextSnapshot.getContextSummary()));

        AgentRunAggregate run = AgentRunAggregate.create(command, agentContextPolicyService.buildPolicy());
        run.bindSessionContextSummary(sessionContextSnapshot.getContextSummary());
        executionContext.setAgentRunAggregate(run);
        return executionContext;
    }

    private void initializeRun(ExecuteCommandEntity command, AgentExecutionContextVO executionContext, AgentRunAggregate run) {
        agentRunRepository.createRun(run.toRecord());
        agentConversationMemoryService.recordUserMessage(command.getSessionId(), run.runId(), command.getMessage());

        long startTime = markStepRunning(run, "harness_root", "Harness 初始化", 0, "SYSTEM");
        executionContext.setMaxStep(run.maxStepOrDefault(executionContext.getMaxStep()));
        executionContext.setAiAgentClientHarnessConfigVOMap(repository.queryAiAgentClientHarnessConfig(command.getAiAgentId()));
        run.markRunning();
        agentRunRepository.updateRun(run.toRecord());

        sendStreamResult(executionContext, AgentExecuteResultEntity.createAnalysisSubResult(
                0,
                "context_boundary",
                "上下文边界已绑定，Harness 将按 session 隔离短期记忆、用户偏好和运行态摘要。",
                AgentContextBoundaryService.buildPayload(executionContext.getContextBoundary()),
                command.getSessionId(),
                run.runId()
        ));

        if (executionContext.getAiAgentClientHarnessConfigVOMap() == null || executionContext.getAiAgentClientHarnessConfigVOMap().isEmpty()) {
            String message = "智能体未配置 Harness 客户端，无法执行";
            run.markFailed(message);
            agentRunRepository.updateRun(run.toRecord());
            markStepFailed(run, "harness_root", message, startTime);
            sendStreamResult(executionContext, AgentExecuteResultEntity.createErrorResult(message, command.getSessionId(), run.runId()));
            throw new IllegalStateException(message);
        }
        markStepSuccess(run, "harness_root", "Harness 初始化完成", startTime);
    }

    private void routeTools(ExecuteCommandEntity command, AgentExecutionContextVO executionContext, AgentRunAggregate run) {
        long startTime = markStepRunning(run, "harness_tool_routing", "运行时工具路由", 1, "SYSTEM");
        ToolRoutingDecisionVO toolRoutingDecision = toolCapabilityService.routeTools(
                executionContext.getAiAgentClientHarnessConfigVOMap(),
                command.getMessage()
        );
        executionContext.setToolRoutingDecision(toolRoutingDecision);
        executionContext.setAllowedTools(toolRoutingDecision.getAllowedToolNames());
        executionContext.setToolCapabilitySummary(toolRoutingDecision.getSummary());

        sendStreamResult(executionContext, AgentExecuteResultEntity.createAnalysisSubResult(
                executionContext.nextStreamStepCursor(),
                "tool_routing",
                toolRoutingDecision.isEnabled() ? "本轮已完成 MCP 工具筛选。" : "本轮未启用 MCP 工具。",
                buildRoutingPayload(toolRoutingDecision),
                command.getSessionId(),
                run.runId()
        ));
        markStepSuccess(run, "harness_tool_routing", toolRoutingDecision.getSummary(), startTime);
    }

    private void runHarnessLoop(ExecuteCommandEntity command, AgentExecutionContextVO executionContext, AgentRunAggregate run) {
        List<HarnessObservationVO> observations = new ArrayList<>();
        int ragRetrievalRounds = 0;
        int maxRounds = Math.min(AgentActionPolicy.DEFAULT_MAX_ACTION_ROUNDS, Math.max(1, executionContext.getMaxStep()));
        String finalAnswer = "";

        for (int round = 1; round <= maxRounds; round++) {
            if (stopIfCancelled(command, executionContext, run)) {
                return;
            }
            ContextGuardResultVO guardedOutputs = agentContextWindowService.prepareStepOutputs(run);
            String stepId = "harness_action_" + round;
            long startTime = markStepRunning(run, stepId, "Harness Action " + round, round + 1, "ACTION");
            try {
                String prompt = promptFactory.buildActionPrompt(
                        command,
                        executionContext.getContextBoundary(),
                        executionContext.getToolRoutingDecision(),
                        observations,
                        round,
                        maxRounds
                ) + System.lineSeparator() + "当前压缩后的运行上下文：" + guardedOutputs.getStepOutputs();

                AgentModelCallResultEntity decision = agentModelPort.callModelResult(
                        executionContext.getAiAgentClientHarnessConfigVOMap(),
                        command,
                        run.getContextWindowGuard(),
                        run.getTrace(),
                        prompt,
                        "harness_action_decision",
                        stepId,
                        executionContext.nextStreamStepCursor(),
                        ToolRoutingDecisionVO.disabled("Harness action 决策阶段不注入 MCP 工具。"),
                        AiClientTypeEnumVO.TASK_ANALYZER_CLIENT,
                        AiClientTypeEnumVO.PLANNING_CLIENT,
                        AiClientTypeEnumVO.DEFAULT
                );
                AgentActionVO action = actionParser.parse(decision.getContent(), command.getMessage());
                AgentActionPolicy.PolicyCheckResult policyResult = actionPolicy.validate(
                        action,
                        round,
                        ragRetrievalRounds,
                        run.getContextWindowGuard()
                );
                if (!policyResult.accepted()) {
                    HarnessObservationVO observation = HarnessObservationVO.failure(action, policyResult.reason(), true);
                    observations.add(observation);
                    finalAnswer = policyResult.reason();
                    markStepFailed(run, stepId, policyResult.reason(), startTime);
                    sendObservation(command, executionContext, run, observation);
                    break;
                }

                HarnessObservationVO observation = executeAction(command, executionContext, run, action, observations, round);
                if (action.getType() == AgentActionTypeEnumVO.RAG_RETRIEVE) {
                    ragRetrievalRounds++;
                }
                observations.add(observation);
                run.recordStepOutput(stepId, observation.getMessage());
                markStepSuccess(run, stepId, observation.getMessage(), startTime);
                sendObservation(command, executionContext, run, observation);
                if (observation.isTerminal()) {
                    finalAnswer = observation.getMessage();
                    break;
                }
            } catch (Exception e) {
                markStepFailed(run, stepId, e.getMessage(), startTime);
                throw e;
            }
        }

        if (StringUtils.isBlank(finalAnswer)) {
            finalAnswer = buildFallbackFinal(command, executionContext, run, observations);
        }
        run.markSuccess(finalAnswer);
        agentRunRepository.updateRun(run.toRecord());
        agentConversationMemoryService.recordAssistantMessage(command.getSessionId(), run.runId(), finalAnswer);
        sendStreamResult(executionContext, AgentExecuteResultEntity.createSummaryResult(finalAnswer, command.getSessionId(), run.runId()));
        sendStreamResult(executionContext, AgentExecuteResultEntity.createCompleteResult(command.getSessionId(), run.runId()));
    }

    private HarnessObservationVO executeAction(ExecuteCommandEntity command,
                                               AgentExecutionContextVO executionContext,
                                               AgentRunAggregate run,
                                               AgentActionVO action,
                                               List<HarnessObservationVO> observations,
                                               int round) {
        if (action.hasParseError()) {
            String answer = callDirectResponder(command, executionContext, run, observations, round);
            return HarnessObservationVO.success(action, answer, Map.of("parseError", action.getParseError()), true);
        }
        return switch (action.getType()) {
            case RAG_PLAN -> HarnessObservationVO.success(
                    action,
                    "已完成 RAG 检索规划，下一步应进入 RAG_RETRIEVE 或 FINAL。",
                    Map.of("query", action.getQuery(), "reason", StringUtils.defaultString(action.getReason())),
                    false
            );
            case RAG_RETRIEVE -> executeRagAction(command, executionContext, run, action, round);
            case MCP_READ -> executeMcpReadAction(command, executionContext, run, action, round);
            case EVALUATE_EVIDENCE -> HarnessObservationVO.success(
                    action,
                    "证据评估由 AgenticRagRuntime 内部完成，Harness 不再单独执行固定评估节点。",
                    Map.of("reason", StringUtils.defaultString(action.getReason())),
                    false
            );
            case LLM_RESPOND -> HarnessObservationVO.success(
                    action,
                    callDirectResponder(command, executionContext, run, observations, round),
                    Map.of("reason", StringUtils.defaultString(action.getReason())),
                    true
            );
            case ASK_CLARIFY -> HarnessObservationVO.success(
                    action,
                    StringUtils.defaultIfBlank(action.getAnswer(), "请补充更具体的问题范围或资料来源。"),
                    Map.of("reason", StringUtils.defaultString(action.getReason())),
                    true
            );
            case FINAL -> HarnessObservationVO.success(
                    action,
                    StringUtils.defaultIfBlank(action.getAnswer(), callDirectResponder(command, executionContext, run, observations, round)),
                    Map.of("reason", StringUtils.defaultString(action.getReason())),
                    true
            );
        };
    }

    private HarnessObservationVO executeRagAction(ExecuteCommandEntity command,
                                                  AgentExecutionContextVO executionContext,
                                                  AgentRunAggregate run,
                                                  AgentActionVO action,
                                                  int round) {
        AgentModelCallResultEntity result = agenticRagRuntime.execute(
                run,
                command,
                executionContext,
                action.getQuery(),
                executionContext.getToolRoutingDecision(),
                executionContext.nextStreamStepCursor()
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        Object trace = result.getMetadata().get(AgenticRagRuntime.METADATA_TRACE);
        payload.put(RAG_EVIDENCE_SUB_TYPE, trace);
        payload.put("retrievalQueries", result.getMetadata().get("qa_retrieval_queries"));
        payload.put("noEvidence", result.getMetadata().get("qa_retrieval_no_evidence"));
        return HarnessObservationVO.success(action, result.getContent(), payload, true);
    }

    private HarnessObservationVO executeMcpReadAction(ExecuteCommandEntity command,
                                                      AgentExecutionContextVO executionContext,
                                                      AgentRunAggregate run,
                                                      AgentActionVO action,
                                                      int round) {
        ToolRoutingDecisionVO readOnlyDecision = actionPolicy.readOnlyEvidenceDecision(executionContext.getToolRoutingDecision());
        String prompt = """
                请基于已授权只读 MCP 工具读取资料并回答问题。
                禁止写入、通知、记忆和执行命令。如果工具不可用，请直接说明。

                问题：
                %s
                """.formatted(StringUtils.defaultIfBlank(action.getQuery(), command.getMessage()));
        AgentModelCallResultEntity result = agentModelPort.callModelResult(
                executionContext.getAiAgentClientHarnessConfigVOMap(),
                command,
                run.getContextWindowGuard(),
                run.getTrace(),
                prompt,
                "harness_mcp_read",
                "harness_mcp_read_" + round,
                executionContext.nextStreamStepCursor(),
                readOnlyDecision,
                AiClientTypeEnumVO.EXECUTOR_CLIENT,
                AiClientTypeEnumVO.RESPONSE_ASSISTANT,
                AiClientTypeEnumVO.DEFAULT
        );
        return HarnessObservationVO.success(action, result.getContent(), Map.of("toolRouting", readOnlyDecision), false);
    }

    private String buildFallbackFinal(ExecuteCommandEntity command,
                                      AgentExecutionContextVO executionContext,
                                      AgentRunAggregate run,
                                      List<HarnessObservationVO> observations) {
        return callDirectResponder(command, executionContext, run, observations, executionContext.nextStreamStepCursor());
    }

    private String callDirectResponder(ExecuteCommandEntity command,
                                       AgentExecutionContextVO executionContext,
                                       AgentRunAggregate run,
                                       List<HarnessObservationVO> observations,
                                       int step) {
        return agentModelPort.callModel(
                executionContext.getAiAgentClientHarnessConfigVOMap(),
                command,
                run.getContextWindowGuard(),
                run.getTrace(),
                promptFactory.buildDirectResponsePrompt(command, observations),
                "harness_llm_respond",
                "harness_llm_respond",
                step,
                ToolRoutingDecisionVO.disabled("直接回答阶段不注入 MCP 工具。"),
                AiClientTypeEnumVO.RESPONSE_ASSISTANT,
                AiClientTypeEnumVO.EXECUTOR_CLIENT,
                AiClientTypeEnumVO.DEFAULT
        );
    }

    private boolean stopIfCancelled(ExecuteCommandEntity command, AgentExecutionContextVO executionContext, AgentRunAggregate run) {
        if (!agentRunRepository.isCancelled(run.runId())) {
            return false;
        }
        executionContext.setCancelled(true);
        String message = "任务已取消，Harness 停止继续执行。";
        run.markCancelled(message);
        agentRunRepository.updateRun(run.toRecord());
        sendStreamResult(executionContext, AgentExecuteResultEntity.createSummarySubResult(
                "cancelled",
                message,
                command.getSessionId(),
                run.runId()
        ));
        sendStreamResult(executionContext, AgentExecuteResultEntity.createCompleteResult(message, command.getSessionId(), run.runId()));
        return true;
    }

    private long markStepRunning(AgentRunAggregate run, String stepId, String stepName, Integer stepOrder, String stepType) {
        long start = System.currentTimeMillis();
        LocalDateTime now = LocalDateTime.now();
        agentRunRepository.createStep(AgentStepRunRecordVO.builder()
                .runId(run.runId())
                .stepId(stepId)
                .stepName(stepName)
                .stepOrder(stepOrder)
                .stepType(stepType)
                .status(AgentStepRunStatusEnumVO.RUNNING)
                .startTime(now)
                .createTime(now)
                .updateTime(now)
                .build());
        return start;
    }

    private void markStepSuccess(AgentRunAggregate run, String stepId, String summary, long startTime) {
        long end = System.currentTimeMillis();
        agentRunRepository.updateStep(AgentStepRunRecordVO.builder()
                .runId(run.runId())
                .stepId(stepId)
                .status(AgentStepRunStatusEnumVO.SUCCESS)
                .outputSummary(limit(summary, 500))
                .costMillis(end - startTime)
                .endTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build());
    }

    private void markStepFailed(AgentRunAggregate run, String stepId, String errorMessage, long startTime) {
        long end = System.currentTimeMillis();
        agentRunRepository.updateStep(AgentStepRunRecordVO.builder()
                .runId(run.runId())
                .stepId(stepId)
                .status(AgentStepRunStatusEnumVO.FAILED)
                .errorMessage(limit(errorMessage, 500))
                .costMillis(end - startTime)
                .endTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build());
    }

    private void sendObservation(ExecuteCommandEntity command,
                                 AgentExecutionContextVO executionContext,
                                 AgentRunAggregate run,
                                 HarnessObservationVO observation) {
        HarnessObservationVO streamObservation = observation;
        Object ragEvidence = observation.getPayload() == null ? null : observation.getPayload().get(RAG_EVIDENCE_SUB_TYPE);
        if (ragEvidence != null) {
            sendStreamResult(executionContext, AgentExecuteResultEntity.createExecutionSubResult(
                    executionContext.nextStreamStepCursor(),
                    RAG_EVIDENCE_SUB_TYPE,
                    "Agentic RAG 证据轨迹已生成。",
                    ragEvidence,
                    command.getSessionId(),
                    run.runId()
            ));

            Map<String, Object> compactPayload = new LinkedHashMap<>(observation.getPayload());
            compactPayload.remove(RAG_EVIDENCE_SUB_TYPE);
            streamObservation = HarnessObservationVO.builder()
                    .actionId(observation.getActionId())
                    .actionType(observation.getActionType())
                    .success(observation.isSuccess())
                    .terminal(observation.isTerminal())
                    .message(observation.getMessage())
                    .payload(compactPayload)
                    .build();
        }
        sendStreamResult(executionContext, AgentExecuteResultEntity.createExecutionSubResult(
                executionContext.nextStreamStepCursor(),
                "harness_observation",
                streamObservation.getMessage(),
                streamObservation,
                command.getSessionId(),
                run.runId()
        ));
    }

    private void sendStreamResult(AgentExecutionContextVO executionContext, AgentExecuteResultEntity result) {
        try {
            IAgentStreamPort streamPort = executionContext.getStreamPort();
            if (streamPort != null) {
                streamPort.send(result);
            }
        } catch (Exception e) {
            log.warn("发送 Harness 流式结果失败：{}", e.getMessage(), e);
        }
    }

    private Map<String, Object> buildRoutingPayload(ToolRoutingDecisionVO toolRoutingDecision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enabled", toolRoutingDecision.isEnabled());
        payload.put("summary", toolRoutingDecision.getSummary());
        payload.put("selectedCount", toolRoutingDecision.getSelectedTools() == null ? 0 : toolRoutingDecision.getSelectedTools().size());
        payload.put("allowedToolNames", toolRoutingDecision.getAllowedToolNames());
        payload.put("selectedMcpIds", toolRoutingDecision.getSelectedMcpIds());
        payload.put("selectedTools", toolRoutingDecision.getSelectedTools());
        payload.put("blockedToolNames", toolRoutingDecision.getBlockedToolNames());
        payload.put("blockedToolReasons", toolRoutingDecision.getBlockedToolReasons());
        return payload;
    }

    private String limit(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        return content.length() <= maxLength ? content : content.substring(0, maxLength) + "...";
    }
}
