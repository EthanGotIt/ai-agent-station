package cn.ethan.ai.domain.agent.service.execute.springai;

import cn.ethan.ai.domain.agent.adapter.port.IAgentStreamPort;
import cn.ethan.ai.domain.agent.adapter.port.ILocalEvidenceRetrievalPort;
import cn.ethan.ai.domain.agent.adapter.port.ISpringAiChatClientPort;
import cn.ethan.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.ethan.ai.domain.agent.adapter.repository.IAgentRunRepository;
import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.AgentModelCallResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentRunRecordVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentStepRunRecordVO;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentClientHarnessConfigVO;
import cn.ethan.ai.domain.agent.model.valobj.SessionContextSnapshotVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentStepRunStatusEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.ethan.ai.domain.agent.service.execute.springai.advisor.McpEvidenceNormalizer;
import cn.ethan.ai.domain.agent.service.execute.runtime.AgentConversationMemoryService;
import cn.ethan.ai.types.exception.AgentExecutionException;
import cn.ethan.ai.domain.agent.service.execute.springai.advisor.ContextBudgetAdvisor;
import cn.ethan.ai.domain.agent.service.execute.springai.advisor.EvidenceRetrievalAdvisor;
import cn.ethan.ai.domain.agent.service.execute.springai.advisor.ObservationTraceAdvisor;
import cn.ethan.ai.domain.agent.service.execute.springai.advisor.SpringAiAdvisorContextKeys;
import cn.ethan.ai.domain.agent.model.valobj.HeuristicContextUnitEstimator;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spring AI 2 主执行链路。
 */
@Service
public class SpringAiAgentRuntime {

    @Resource
    private IAgentRepository repository;

    @Resource
    private IAgentRunRepository agentRunRepository;

    @Resource
    private ISpringAiChatClientPort springAiChatClientPort;

    @Resource
    private ILocalEvidenceRetrievalPort localEvidenceRetrievalPort;

    @Resource
    private McpEvidenceNormalizer mcpEvidenceNormalizer;

    @Resource
    private AgentConversationMemoryService agentConversationMemoryService;

    @Value("${ai-agent.context.max-context-units:12000}")
    private int maxContextUnits;

    @Value("${ai-agent.context.stop-threshold:0.95}")
    private double stopThreshold;

    @Autowired(required = false)
    private SessionMemoryAdvisor sessionMemoryAdvisor;

    public void execute(ExecuteCommandEntity command, IAgentStreamPort streamPort) {
        AgentRunAggregate run = AgentRunAggregate.create(command);
        try {
            SessionContextSnapshotVO sessionSnapshot = agentConversationMemoryService.loadSessionContext(command.getSessionId());
            run.bindSessionContextSummary(sessionSnapshot.getContextSummary());
            createAndStartRun(run);
            agentConversationMemoryService.recordUserMessage(command.getSessionId(), run.runId(), command.getMessage());

            Map<String, AiAgentClientHarnessConfigVO> configMap =
                    repository.queryAiAgentClientHarnessConfig(command.getAiAgentId());
            if (configMap == null || configMap.isEmpty()) {
                throw new IllegalStateException("智能体未配置 Spring AI ChatClient，无法执行");
            }
            long startTime = markStepRunning(run, "spring_ai_chat", "Spring AI Advisor Chain", 1, "SPRING_AI");
            String prompt = buildPrompt(command);
            AgentModelCallResultEntity result = springAiChatClientPort.call(
                    configMap, command, run.getTrace(),
                    prompt, "spring_ai_advisor_chain", "spring_ai_chat", 1,
                    buildAdvisors(configMap),
                    buildAdvisorParams(command),
                    AiClientTypeEnumVO.EXECUTOR_CLIENT,
                    AiClientTypeEnumVO.DEFAULT);
            String finalAnswer = StringUtils.defaultIfBlank(result.getContent(), "证据不足，无法生成可靠回答。");
            markStepSuccess(run, "spring_ai_chat", finalAnswer, startTime);
            publishTraceEvents(command, streamPort, run, result);
            publishObservation(command, streamPort, run, "Spring AI Advisor Chain 执行完成。");

            run.markSuccess(finalAnswer);
            agentRunRepository.updateRun(run.toRecord());
            agentConversationMemoryService.recordAssistantMessage(command.getSessionId(), run.runId(), finalAnswer);
            streamPort.send(AgentExecuteResultEntity.createSummaryResult(finalAnswer, command.getSessionId(), run.runId()));
            streamPort.send(AgentExecuteResultEntity.createCompleteResult(command.getSessionId(), run.runId()));
        } catch (Exception e) {
            run.markFailed(e.getMessage());
            agentRunRepository.updateRun(run.toRecord());
            throw new AgentExecutionException(run.runId(), e.getMessage(), e);
        }
    }

    private void createAndStartRun(AgentRunAggregate run) {
        agentRunRepository.createRun(run.toRecord());
        run.markRunning();
        agentRunRepository.updateRun(run.toRecord());
    }

    private String buildPrompt(ExecuteCommandEntity command) {
        // Session 上下文由 Advisor Chain 中的 SessionMemoryAdvisor 注入，此处仅组装系统提示与用户问题。
        return "你是企业 Java 项目知识与技术资料助手。"
                + "回答必须优先基于项目知识和可归因 evidence；证据不足时说明无法确认，不要编造。\n\n"
                + "用户问题：\n"
                + command.getMessage();
    }

    private List<Advisor> buildAdvisors(Map<String, AiAgentClientHarnessConfigVO> configMap) {
        List<Advisor> advisors = new ArrayList<>();
        advisors.add(new ContextBudgetAdvisor(maxContextUnits, stopThreshold,
                HeuristicContextUnitEstimator.INSTANCE));
        if (sessionMemoryAdvisor != null) {
            advisors.add(sessionMemoryAdvisor);
        }
        Advisor toolAdvisor = springAiChatClientPort.buildToolCallingAdvisor(configMap);
        if (toolAdvisor != null) {
            advisors.add(toolAdvisor);
        }
        advisors.add(new EvidenceRetrievalAdvisor(localEvidenceRetrievalPort, resolveRagIds(configMap)));
        advisors.add(new ObservationTraceAdvisor(mcpEvidenceNormalizer));
        return advisors;
    }

    private Map<String, Object> buildAdvisorParams(ExecuteCommandEntity command) {
        if (StringUtils.isBlank(command.getSessionId())) {
            return Map.of();
        }
        return Map.of(
                SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, command.getSessionId(),
                SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, "ai-agent-station"
        );
    }

    private Set<String> resolveRagIds(Map<String, AiAgentClientHarnessConfigVO> configMap) {
        Set<String> ragIds = new LinkedHashSet<>();
        configMap.values().stream()
                .filter(java.util.Objects::nonNull)
                .map(AiAgentClientHarnessConfigVO::getRagIds)
                .filter(java.util.Objects::nonNull)
                .flatMap(Set::stream)
                .filter(StringUtils::isNotBlank)
                .forEach(ragIds::add);
        return ragIds;
    }

    private void publishTraceEvents(ExecuteCommandEntity command,
                                    IAgentStreamPort streamPort,
                                    AgentRunAggregate run,
                                    AgentModelCallResultEntity result) {
        Object trace = result.getMetadata().get(SpringAiAdvisorContextKeys.RAG_EVIDENCE_TRACE);
        if (trace != null) {
            streamPort.send(AgentExecuteResultEntity.createAnalysisSubResult(
                    1, "rag_evidence", "Spring AI Advisor Chain 已生成 evidence trace。",
                    trace, command.getSessionId(), run.runId()));
        }
    }

    private void publishObservation(ExecuteCommandEntity command,
                                    IAgentStreamPort streamPort,
                                    AgentRunAggregate run,
                                    String message) {
        streamPort.send(AgentExecuteResultEntity.createExecutionSubResult(
                1, "agent_observation", message, command.getSessionId(), run.runId()));
        // 兼容旧客户端，保留 harness_observation 事件，待前端全部迁移后可移除。
        streamPort.send(AgentExecuteResultEntity.createExecutionSubResult(
                1, "harness_observation", message, command.getSessionId(), run.runId()));
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

    private String limit(String content, int maxLength) {
        String value = StringUtils.defaultString(content);
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
