package cn.ethan.infrastructure.agent.workflow.transaction;

import cn.ethan.core.agent.workflow.AgentWorkflowOrchestrationVersionEnum;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 类型职责：为订单 Workflow 生成唯一的固定节点快照，供 WorkflowRun 和执行回执共用。
 *
 * <p>这是受控订单流程的状态投影，不是通用 DAG 或编排 DSL。节点顺序与外部动作边界在代码中保持稳定，
 * 具体的分支原因由 WORKFLOW_STEP Item 记录。</p>
 *
 * @author ethan
 * @date 2026-08-26
 */
public final class OrderWorkflowStepProjection {

    private static final List<String> NODES = List.of(
            "RESOLVE_ORDER", "VERIFY_FACTS", "SWITCH_REQUIREMENTS", "AUTHORIZE",
            "EXECUTE_ACTION", "VERIFY_OUTCOME", "HANDOFF_AGENT");
    private static final List<String> EXPEDITE_V2_NODES = List.of(
            "RESOLVE_ORDER", "VERIFY_FACTS", "PREPARE_CONFIRMATION", "AUTHORIZE",
            "REVERIFY_FACTS", "BUILD_ACTION_COMMAND", "HANDOFF_WORKER");
    private static final List<String> EXPEDITE_JAVA_V1_NODES = List.of(
            "RESOLVE_ORDER", "VERIFY_FACTS", "PREPARE_CONFIRMATION", "AUTHORIZE",
            "REVERIFY_FACTS", "BUILD_ACTION_COMMAND", "HANDOFF_WORKER", "VERIFY_OUTCOME");

    private OrderWorkflowStepProjection() {
    }

    public static String snapshot(ObjectMapper objectMapper, String activeNode, String activeStatus) {
        return snapshot(objectMapper, activeNode, activeStatus,
                AgentWorkflowOrchestrationVersionEnum.LEGACY_V1);
    }

    /**
     * 生成指定编排版本的固定节点快照；V2 保持图节点名称，避免 Worker 结算时退回旧流程投影。
     */
    public static String snapshot(
            ObjectMapper objectMapper,
            String activeNode,
            String activeStatus,
            AgentWorkflowOrchestrationVersionEnum orchestrationVersion
    ) {
        List<String> nodes = nodes(orchestrationVersion);
        String node = normalizeNode(activeNode, nodes);
        String status = activeStatus == null || activeStatus.isBlank() ? "PENDING" : activeStatus;
        int activeIndex = nodes.indexOf(node);
        List<Map<String, String>> steps = nodes.stream().map(candidate -> {
            int index = nodes.indexOf(candidate);
            String value = index < activeIndex ? "COMPLETED"
                    : index == activeIndex ? status : "PENDING";
            Map<String, String> item = new LinkedHashMap<>();
            item.put("node", candidate);
            item.put("status", value);
            return item;
        }).toList();
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (Exception failure) {
            throw new IllegalStateException("无法编码订单 Workflow 节点快照", failure);
        }
    }

    public static List<String> nodes() {
        return NODES;
    }

    private static List<String> nodes(AgentWorkflowOrchestrationVersionEnum orchestrationVersion) {
        if (orchestrationVersion == AgentWorkflowOrchestrationVersionEnum.EXPEDITE_GRAPH_V2) {
            return EXPEDITE_V2_NODES;
        }
        if (orchestrationVersion == AgentWorkflowOrchestrationVersionEnum.EXPEDITE_JAVA_V1) {
            return EXPEDITE_JAVA_V1_NODES;
        }
        return NODES;
    }

    private static String normalizeNode(String value, List<String> nodes) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return nodes.contains(normalized) ? normalized : nodes.get(0);
    }

    public static String nodeForLegacyStep(String activeStep) {
        String normalized = activeStep == null ? "" : activeStep.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "INTENT", "HISTORY_ACTION", "ORDER_SELECT", "PARSE_CONDITIONS", "CANDIDATE_ORDERS" -> "RESOLVE_ORDER";
            case "REASON", "ORDER_LOGISTICS_VERIFICATION", "VERIFY_FACTS" -> "VERIFY_FACTS";
            case "AUTHORIZE", "CONFIRM", "FINAL_AUTHORIZATION", "USER_INPUT" -> "AUTHORIZE";
            case "EXTERNAL_ACTION", "EXECUTE_ACTION" -> "EXECUTE_ACTION";
            case "VERIFY_OUTCOME" -> "VERIFY_OUTCOME";
            case "HANDOFF_AGENT", "TERMINAL" -> "HANDOFF_AGENT";
            default -> "RESOLVE_ORDER";
        };
    }
}
