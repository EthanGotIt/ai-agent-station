package cn.ethan.infrastructure.agent.workflow;

import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.workflow.AgentWorkflowEngine;
import cn.ethan.core.agent.workflow.AgentWorkflowOrchestrationVersionEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowRunModel;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.agent.workflow.AgentWorkflowTypeEnum;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 类型职责：只为新 Run 选择催发货编排，并按持久化版本恢复已有 Workflow。
 *
 * @author ethan
 * @date 2026-09-24
 */
@Primary
@Component
public final class VersionedAgentWorkflowEngine implements AgentWorkflowEngine {

    private final AgentWorkflowEngine legacy;
    private final AgentWorkflowEngine javaExpedite;
    private final AgentWorkflowRunStore runs;
    private final String mode;

    public VersionedAgentWorkflowEngine(
            @Qualifier("langGraphAgentWorkflowEngine") AgentWorkflowEngine legacy,
            @Qualifier("javaExpediteWorkflowEngine") AgentWorkflowEngine javaExpedite,
            AgentWorkflowRunStore runs,
            @Value("${ai-agent.workflow.expedite-mode:}") String configuredMode,
            @Value("${ai-agent.workflow.expedite-graph-enabled:false}") boolean legacyEnabled
    ) {
        this.legacy = legacy;
        this.javaExpedite = javaExpedite;
        this.runs = runs;
        this.mode = normalize(configuredMode, legacyEnabled);
    }

    @Override
    public StartResult start(AgentThreadModel thread, AgentTurnModel turn,
                             String operation, Map<String, String> arguments) {
        Optional<AgentWorkflowRunModel> prior = runs.findBySource(
                thread.userId(), turn.turnId(), AgentWorkflowTypeEnum.ORDER_SERVICE);
        if (prior.isPresent()) {
            return engine(prior.get().orchestrationVersion()).start(thread, turn, operation, arguments);
        }
        String intent = arguments == null ? "" : arguments.getOrDefault("intent", "");
        if ("JAVA".equals(mode) && "EXPEDITE".equalsIgnoreCase(intent == null ? "" : intent.strip())) {
            return javaExpedite.start(thread, turn, operation, arguments);
        }
        return legacy.start(thread, turn, operation, arguments);
    }

    @Override
    public ResumeResult resume(AgentThreadModel thread, AgentTurnModel turn, Map<String, String> answers) {
        String runId = turn.questionAnswerInput() != null ? turn.questionAnswerInput().runId()
                : turn.workflowDecisionInput() != null ? turn.workflowDecisionInput().runId() : null;
        if (runId == null || runId.isBlank()) {
            return legacy.resume(thread, turn, answers);
        }
        Optional<AgentWorkflowRunModel> run = runs.find(thread.userId(), runId);
        return run.map(value -> engine(value.orchestrationVersion()).resume(thread, turn, answers))
                .orElseGet(() -> legacy.resume(thread, turn, answers));
    }

    private AgentWorkflowEngine engine(AgentWorkflowOrchestrationVersionEnum version) {
        return version == AgentWorkflowOrchestrationVersionEnum.EXPEDITE_JAVA_V1 ? javaExpedite : legacy;
    }

    private String normalize(String configured, boolean legacyEnabled) {
        if (configured == null || configured.isBlank()) return legacyEnabled ? "V1" : "OFF";
        String normalized = configured.strip().toUpperCase(Locale.ROOT);
        if (!normalized.equals("OFF") && !normalized.equals("V1")
                && !normalized.equals("V2") && !normalized.equals("JAVA")) {
            throw new IllegalArgumentException("AI_AGENT_EXPEDITE_MODE 必须为 OFF、V1、V2 或 JAVA");
        }
        return normalized;
    }
}
