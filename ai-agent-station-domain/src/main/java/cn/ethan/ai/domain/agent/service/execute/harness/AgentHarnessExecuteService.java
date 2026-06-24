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
import cn.ethan.ai.domain.agent.model.valobj.HarnessObservationVO;
import cn.ethan.ai.domain.agent.model.valobj.SessionContextSnapshotVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentStepRunStatusEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.StreamTransportTypeEnumVO;
import cn.ethan.ai.domain.agent.service.execute.runtime.AgentContextBoundaryService;
import cn.ethan.ai.domain.agent.service.execute.runtime.AgentContextPolicyService;
import cn.ethan.ai.domain.agent.service.execute.runtime.AgentConversationMemoryService;
import cn.ethan.ai.domain.agent.service.execute.runtime.AgentExecutionException;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Controlled Agent Harness 主入口。只编排决策轮次，动作副作用由 HarnessActionExecutor 执行。
 */
@Service
public class AgentHarnessExecuteService {

    private static final int MIN_ACTION_ROUNDS = 2;

    private static final int DEFAULT_MAX_ACTION_ROUNDS = 4;

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
    private AgentConversationMemoryService agentConversationMemoryService;

    @Resource
    private AgentActionPromptFactory promptFactory;

    @Resource
    private AgentActionParser actionParser;

    @Resource
    private AgentActionPolicy actionPolicy;

    @Resource
    private EvidencePolicy evidencePolicy;

    @Resource
    private HarnessActionExecutor actionExecutor;

    @Resource
    private HarnessEventPublisher eventPublisher;

    public void execute(ExecuteCommandEntity command, IAgentStreamPort streamPort) throws Exception {
        AgentExecutionContextVO context = initializeContext(command, streamPort);
        AgentRunAggregate run = context.getAgentRunAggregate();
        try {
            initializeRun(command, context, run);
            runHarnessLoop(command, context, run);
        } catch (Exception e) {
            if (!run.isCancelled()) {
                run.markFailed(e.getMessage());
                agentRunRepository.updateRun(run.toRecord());
            }
            throw new AgentExecutionException(run.runId(), e.getMessage(), e);
        }
    }

    private AgentExecutionContextVO initializeContext(ExecuteCommandEntity command, IAgentStreamPort streamPort) {
        AgentExecutionContextVO context = new AgentExecutionContextVO();
        context.setMaxStep(clampRounds(command.getMaxStep()));
        context.setStreamProtocol(StreamTransportTypeEnumVO.fromCode(command.getStreamProtocol()));
        context.setStreamPort(streamPort);
        context.bindSessionId(command.getSessionId());

        SessionContextSnapshotVO snapshot = agentConversationMemoryService.loadSessionContext(command.getSessionId());
        context.setContextBoundary(agentContextBoundaryService.buildBoundary(command, snapshot.getContextSummary()));
        AgentRunAggregate run = AgentRunAggregate.create(command, agentContextPolicyService.buildPolicy());
        run.bindSessionContextSummary(snapshot.getContextSummary());
        context.setAgentRunAggregate(run);
        return context;
    }

    private void initializeRun(ExecuteCommandEntity command, AgentExecutionContextVO context, AgentRunAggregate run) {
        agentRunRepository.createRun(run.toRecord());
        agentConversationMemoryService.recordUserMessage(command.getSessionId(), run.runId(), command.getMessage());
        long startTime = markStepRunning(run, "harness_root", "Harness 初始化", 0, "SYSTEM");
        context.setAiAgentClientHarnessConfigVOMap(repository.queryAiAgentClientHarnessConfig(command.getAiAgentId()));
        run.markRunning();
        agentRunRepository.updateRun(run.toRecord());

        eventPublisher.send(context, AgentExecuteResultEntity.createAnalysisSubResult(
                0, "context_boundary",
                "上下文边界已绑定，Harness 将按 session 隔离短期记忆与单次 Run 证据。",
                AgentContextBoundaryService.buildPayload(context.getContextBoundary()),
                command.getSessionId(), run.runId()));

        if (context.getAiAgentClientHarnessConfigVOMap() == null
                || context.getAiAgentClientHarnessConfigVOMap().isEmpty()) {
            String message = "智能体未配置 Harness 客户端，无法执行";
            run.markFailed(message);
            agentRunRepository.updateRun(run.toRecord());
            markStepFailed(run, "harness_root", message, startTime);
            eventPublisher.send(context, AgentExecuteResultEntity.createErrorResult(message, command.getSessionId(), run.runId()));
            throw new IllegalStateException(message);
        }
        markStepSuccess(run, "harness_root", "Harness 初始化完成", startTime);
    }

    private void runHarnessLoop(ExecuteCommandEntity command, AgentExecutionContextVO context, AgentRunAggregate run) {
        List<HarnessObservationVO> observations = new ArrayList<>();
        int retrievalRounds = 0;
        String finalAnswer = "";

        for (int round = 1; round <= context.getMaxStep(); round++) {
            if (stopIfCancelled(command, context, run)) {
                return;
            }
            String stepId = "harness_action_" + round;
            long startTime = markStepRunning(run, stepId, "Harness Action " + round, round, "ACTION");
            try {
                String prompt = promptFactory.buildActionPrompt(
                        command, context.getContextBoundary(), observations, context.getEvidenceBoard(),
                        round, context.getMaxStep());
                AgentModelCallResultEntity decision = agentModelPort.callModelResult(
                        context.getAiAgentClientHarnessConfigVOMap(), command, run.getContextWindowGuard(), run.getTrace(),
                        prompt, "harness_action_decision", stepId, context.nextStreamStepCursor(),
                        ToolRoutingDecisionVO.disabled("Harness 决策阶段不注入 MCP 工具。"),
                        AiClientTypeEnumVO.TASK_ANALYZER_CLIENT,
                        AiClientTypeEnumVO.PLANNING_CLIENT,
                        AiClientTypeEnumVO.DEFAULT);
                AgentActionVO action = actionParser.parse(
                        decision.getContent(), command.getMessage(), context.getEvidenceBoard().hasEvidence());
                AgentActionPolicy.PolicyCheckResult policy = actionPolicy.validate(
                        action, round, retrievalRounds, context.getEvidenceBoard());
                EvidencePolicy.Decision finalizationDecision = action.getType()
                        == cn.ethan.ai.domain.agent.model.valobj.enums.AgentActionTypeEnumVO.FINALIZE
                        ? evidencePolicy.evaluateFinalization(command.getMessage(),
                        context.getEvidenceBoard(), context.getContextBoundary())
                        : null;

                HarnessObservationVO observation;
                if (!policy.accepted()) {
                    observation = actionExecutor.forceFinalize(
                            run, command, context, policy.reason(), context.nextStreamStepCursor());
                } else if (finalizationDecision != null
                        && !finalizationDecision.allowed()
                        && actionPolicy.canContinueAfterRejectedFinalization(
                        round, context.getMaxStep(), retrievalRounds)) {
                    observation = HarnessObservationVO.failure(action,
                            "FINALIZE 被 Evidence Policy 否决："
                                    + finalizationDecision.reason()
                                    + " 请根据缺口选择新的 RETRIEVE 来源。",
                            false);
                } else {
                    observation = actionExecutor.execute(run, command, context, action, round);
                    if (action.getType() == cn.ethan.ai.domain.agent.model.valobj.enums.AgentActionTypeEnumVO.RETRIEVE) {
                        retrievalRounds++;
                    }
                }
                observations.add(observation);
                run.recordStepOutput(stepId, observation.getMessage());
                markStepSuccess(run, stepId, observation.getMessage(), startTime);
                eventPublisher.observation(command, context, run, observation);
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
            HarnessObservationVO finalObservation = actionExecutor.forceFinalize(
                    run, command, context, "达到 Harness 轮次上限，基于现有 Evidence Board 收口。",
                    context.nextStreamStepCursor());
            observations.add(finalObservation);
            finalAnswer = finalObservation.getMessage();
            eventPublisher.observation(command, context, run, finalObservation);
        }
        run.markSuccess(finalAnswer);
        agentRunRepository.updateRun(run.toRecord());
        agentConversationMemoryService.recordAssistantMessage(command.getSessionId(), run.runId(), finalAnswer);
        eventPublisher.send(context, AgentExecuteResultEntity.createSummaryResult(finalAnswer, command.getSessionId(), run.runId()));
        eventPublisher.send(context, AgentExecuteResultEntity.createCompleteResult(command.getSessionId(), run.runId()));
    }

    private int clampRounds(Integer requested) {
        int value = requested == null ? DEFAULT_MAX_ACTION_ROUNDS : requested;
        return Math.max(MIN_ACTION_ROUNDS, Math.min(DEFAULT_MAX_ACTION_ROUNDS, value));
    }

    private boolean stopIfCancelled(ExecuteCommandEntity command,
                                    AgentExecutionContextVO context,
                                    AgentRunAggregate run) {
        if (!agentRunRepository.isCancelled(run.runId())) {
            return false;
        }
        context.setCancelled(true);
        String message = "任务已取消，Harness 停止继续执行。";
        run.markCancelled(message);
        agentRunRepository.updateRun(run.toRecord());
        eventPublisher.send(context, AgentExecuteResultEntity.createSummarySubResult(
                "cancelled", message, command.getSessionId(), run.runId()));
        eventPublisher.send(context, AgentExecuteResultEntity.createCompleteResult(
                message, command.getSessionId(), run.runId()));
        return true;
    }

    private long markStepRunning(AgentRunAggregate run,
                                 String stepId,
                                 String stepName,
                                 Integer stepOrder,
                                 String stepType) {
        long start = System.currentTimeMillis();
        LocalDateTime now = LocalDateTime.now();
        agentRunRepository.createStep(AgentStepRunRecordVO.builder()
                .runId(run.runId()).stepId(stepId).stepName(stepName).stepOrder(stepOrder).stepType(stepType)
                .status(AgentStepRunStatusEnumVO.RUNNING).startTime(now).createTime(now).updateTime(now).build());
        return start;
    }

    private void markStepSuccess(AgentRunAggregate run, String stepId, String summary, long startTime) {
        long end = System.currentTimeMillis();
        agentRunRepository.updateStep(AgentStepRunRecordVO.builder()
                .runId(run.runId()).stepId(stepId).status(AgentStepRunStatusEnumVO.SUCCESS)
                .outputSummary(limit(summary, 500)).costMillis(end - startTime)
                .endTime(LocalDateTime.now()).updateTime(LocalDateTime.now()).build());
    }

    private void markStepFailed(AgentRunAggregate run, String stepId, String errorMessage, long startTime) {
        long end = System.currentTimeMillis();
        agentRunRepository.updateStep(AgentStepRunRecordVO.builder()
                .runId(run.runId()).stepId(stepId).status(AgentStepRunStatusEnumVO.FAILED)
                .errorMessage(limit(errorMessage, 500)).costMillis(end - startTime)
                .endTime(LocalDateTime.now()).updateTime(LocalDateTime.now()).build());
    }

    private String limit(String content, int maxLength) {
        String value = StringUtils.defaultString(content);
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
