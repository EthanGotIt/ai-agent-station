package cn.ethan.infrastructure.agent.workflow.transaction;

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

    private OrderWorkflowStepProjection() {
    }

    public static String snapshot(ObjectMapper objectMapper, String activeNode, String activeStatus) {
        String node = normalizeNode(activeNode);
        String status = activeStatus == null || activeStatus.isBlank() ? "PENDING" : activeStatus;
        int activeIndex = NODES.indexOf(node);
        List<Map<String, String>> steps = NODES.stream().map(candidate -> {
            int index = NODES.indexOf(candidate);
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

    private static String normalizeNode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return NODES.contains(normalized) ? normalized : NODES.get(0);
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
