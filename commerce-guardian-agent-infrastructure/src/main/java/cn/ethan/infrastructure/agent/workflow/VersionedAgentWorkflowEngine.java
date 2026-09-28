package cn.ethan.infrastructure.agent.workflow;

import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.workflow.AgentWorkflowEngine;
import cn.ethan.core.agent.workflow.AgentWorkflowOrchestrationVersionEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowRunModel;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.agent.workflow.AgentWorkflowTypeEnum;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.LinkedHashMap;
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

    private final AgentWorkflowEngine retired;
    private final AgentWorkflowEngine javaExpedite;
    private final AgentWorkflowEngine javaRefundDelete;
    private final AgentWorkflowRunStore runs;
    private final String mode;
    private final String refundMode;
    private final String deleteMode;

    @Autowired
    public VersionedAgentWorkflowEngine(
            @Qualifier("retiredAgentWorkflowEngine") AgentWorkflowEngine retired,
            @Qualifier("javaExpediteWorkflowEngine") AgentWorkflowEngine javaExpedite,
            @Qualifier("javaRefundDeleteWorkflowEngine") AgentWorkflowEngine javaRefundDelete,
            AgentWorkflowRunStore runs,
            @Value("${ai-agent.workflow.expedite-mode:JAVA}") String configuredMode,
            @Value("${ai-agent.workflow.expedite-graph-enabled:false}") boolean legacyEnabled,
            @Value("${ai-agent.workflow.refund-mode:JAVA}") String refundMode,
            @Value("${ai-agent.workflow.delete-mode:JAVA}") String deleteMode
    ) {
        this.retired = retired;
        this.javaExpedite = javaExpedite;
        this.javaRefundDelete = javaRefundDelete;
        this.runs = runs;
        this.mode = normalize(configuredMode, legacyEnabled);
        this.refundMode = writeMode(refundMode);
        this.deleteMode = writeMode(deleteMode);
    }

    /** 保留旧路由测试构造边界；生产装配使用三类 Java 引擎。 */
    public VersionedAgentWorkflowEngine(AgentWorkflowEngine retired, AgentWorkflowEngine javaExpedite,
                                        AgentWorkflowRunStore runs, String configuredMode, boolean legacyEnabled) {
        this(retired, javaExpedite, retired, runs, configuredMode, legacyEnabled, "OFF", "OFF");
    }

    @Override
    public StartResult start(AgentThreadModel thread, AgentTurnModel turn,
                             String operation, Map<String, String> arguments) {
        String intent = arguments == null ? "" : arguments.getOrDefault("intent", "");
        String normalizedIntent = normalizeIntent(intent);
        Map<String, String> javaArguments = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        javaArguments.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
        if (!normalizedIntent.isBlank()) javaArguments.put("intent", normalizedIntent);
        Optional<AgentWorkflowRunModel> prior = runs.findBySource(
                thread.userId(), turn.turnId(), AgentWorkflowTypeEnum.ORDER_SERVICE);
        if (prior.isPresent()) {
            AgentWorkflowEngine selected = engine(prior.get().orchestrationVersion());
            return selected.start(thread, turn, operation,
                    selected == retired ? arguments : Map.copyOf(javaArguments));
        }
        if ("JAVA".equals(mode) && "EXPEDITE".equals(normalizedIntent)) {
            return javaExpedite.start(thread, turn, operation, Map.copyOf(javaArguments));
        }
        if (("REFUND".equals(normalizedIntent) && "JAVA".equals(refundMode))
                || ("DELETE_ORDER".equals(normalizedIntent) && "JAVA".equals(deleteMode))) {
            return javaRefundDelete.start(thread, turn, operation, Map.copyOf(javaArguments));
        }
        return retired.start(thread, turn, operation, arguments);
    }

    @Override
    public ResumeResult resume(AgentThreadModel thread, AgentTurnModel turn, Map<String, String> answers) {
        String runId = turn.questionAnswerInput() != null ? turn.questionAnswerInput().runId()
                : turn.workflowDecisionInput() != null ? turn.workflowDecisionInput().runId() : null;
        if (runId == null || runId.isBlank()) {
            return retired.resume(thread, turn, answers);
        }
        Optional<AgentWorkflowRunModel> run = runs.find(thread.userId(), runId);
        return run.map(value -> engine(value.orchestrationVersion()).resume(thread, turn, answers))
                .orElseGet(() -> retired.resume(thread, turn, answers));
    }

    private AgentWorkflowEngine engine(AgentWorkflowOrchestrationVersionEnum version) {
        return switch (version) {
            case EXPEDITE_JAVA_V1 -> javaExpedite;
            case REFUND_JAVA_V1, DELETE_JAVA_V1 -> javaRefundDelete;
            default -> retired;
        };
    }

    private String normalize(String configured, boolean legacyEnabled) {
        if (configured == null || configured.isBlank()) return "JAVA";
        String normalized = configured.strip().toUpperCase(Locale.ROOT);
        if (!normalized.equals("OFF") && !normalized.equals("JAVA")) {
            throw new IllegalArgumentException("AI_AGENT_EXPEDITE_MODE 仅支持 OFF 或 JAVA；旧图模式已退出");
        }
        return normalized;
    }

    private String writeMode(String configured) {
        String normalized = configured == null ? "JAVA" : configured.strip().toUpperCase(Locale.ROOT);
        if (normalized.equals("LEGACY")) normalized = "OFF";
        if (!normalized.equals("JAVA") && !normalized.equals("OFF")) {
            throw new IllegalArgumentException("退款/删除 Workflow 模式仅支持 JAVA 或 OFF");
        }
        return normalized;
    }

    private String normalizeIntent(String raw) {
        String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "退款" -> "REFUND";
            case "催发货" -> "EXPEDITE";
            case "DELETE", "删除", "删除订单", "删除订单记录" -> "DELETE_ORDER";
            default -> value;
        };
    }
}
