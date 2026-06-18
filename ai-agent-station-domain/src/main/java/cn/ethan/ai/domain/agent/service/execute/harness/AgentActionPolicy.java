package cn.ethan.ai.domain.agent.service.execute.harness;

import cn.ethan.ai.domain.agent.model.valobj.AgentActionVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextWindowGuardVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingItemVO;
import cn.ethan.ai.domain.agent.service.execute.runtime.ToolGuardPolicy;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Harness 动作权限、轮次和上下文预算策略。
 */
@Service
public class AgentActionPolicy {

    public static final int DEFAULT_MAX_ACTION_ROUNDS = 4;

    public static final int DEFAULT_MAX_RAG_RETRIEVAL_ROUNDS = 2;

    public PolicyCheckResult validate(AgentActionVO action,
                                      int round,
                                      int ragRetrievalRounds,
                                      ContextWindowGuardVO contextWindowGuard) {
        if (round > DEFAULT_MAX_ACTION_ROUNDS) {
            return PolicyCheckResult.reject("已达到最大 Action Loop 轮次，停止继续执行。");
        }
        if (action == null || action.getType() == null) {
            return PolicyCheckResult.reject("Action 类型为空，拒绝执行。");
        }
        if (contextWindowGuard != null && contextWindowGuard.shouldStopNewLlmCall()) {
            return PolicyCheckResult.reject("上下文预算已接近上限，拒绝继续发起新动作。");
        }
        if (action.getType().name().startsWith("RAG") && ragRetrievalRounds >= DEFAULT_MAX_RAG_RETRIEVAL_ROUNDS) {
            return PolicyCheckResult.reject("RAG 检索轮次已达到上限。");
        }
        return PolicyCheckResult.accept();
    }

    public ToolRoutingDecisionVO readOnlyEvidenceDecision(ToolRoutingDecisionVO original) {
        if (original == null || !original.isEnabled()) {
            return ToolRoutingDecisionVO.disabled("RAG evidence 子链路未启用 MCP 只读工具。");
        }

        Set<String> allowedNames = original.getAllowedToolNames() == null ? Set.of() : original.getAllowedToolNames().stream()
                .filter(StringUtils::isNotBlank)
                .map(ToolGuardPolicy::normalize)
                .filter(ToolGuardPolicy::isReadOnlyEvidenceTool)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (allowedNames.isEmpty()) {
            return ToolRoutingDecisionVO.disabled("本轮没有符合 RAG evidence 只读规则的 MCP 工具。",
                    collectBlockedNames(original),
                    collectBlockedReasons(original));
        }

        List<ToolRoutingItemVO> selectedItems = original.getSelectedTools() == null ? List.of() : original.getSelectedTools().stream()
                .map(item -> filterItem(item, allowedNames))
                .filter(item -> item.getToolNames() != null && !item.getToolNames().isEmpty())
                .toList();
        Set<String> selectedMcpIds = selectedItems.stream()
                .map(ToolRoutingItemVO::getMcpId)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return ToolRoutingDecisionVO.builder()
                .enabled(!selectedItems.isEmpty())
                .summary(selectedItems.isEmpty()
                        ? "本轮没有符合 RAG evidence 只读规则的 MCP 工具。"
                        : "RAG evidence 子链路仅注入只读 MCP 工具。")
                .allowedToolNames(allowedNames)
                .selectedMcpIds(selectedMcpIds)
                .selectedTools(selectedItems)
                .blockedToolNames(collectBlockedNames(original))
                .blockedToolReasons(collectBlockedReasons(original))
                .build();
    }

    private ToolRoutingItemVO filterItem(ToolRoutingItemVO item, Set<String> allowedNames) {
        List<String> safeToolNames = item.getToolNames() == null ? List.of() : item.getToolNames().stream()
                .filter(StringUtils::isNotBlank)
                .map(ToolGuardPolicy::normalize)
                .filter(allowedNames::contains)
                .toList();
        return ToolRoutingItemVO.builder()
                .mcpId(item.getMcpId())
                .mcpName(item.getMcpName())
                .transportType(item.getTransportType())
                .toolNames(safeToolNames)
                .routeTags(item.getRouteTags())
                .riskLevel(item.getRiskLevel())
                .blockedToolNames(item.getBlockedToolNames())
                .guardReason(item.getGuardReason())
                .selectedReason("RAG evidence 只读工具")
                .build();
    }

    private Set<String> collectBlockedNames(ToolRoutingDecisionVO original) {
        Set<String> blocked = new LinkedHashSet<>();
        if (original.getBlockedToolNames() != null) {
            blocked.addAll(original.getBlockedToolNames());
        }
        if (original.getAllowedToolNames() != null) {
            original.getAllowedToolNames().stream()
                    .filter(StringUtils::isNotBlank)
                    .map(ToolGuardPolicy::normalize)
                    .filter(ToolGuardPolicy::isWriteOrDangerousTool)
                    .forEach(blocked::add);
        }
        return blocked;
    }

    private Map<String, String> collectBlockedReasons(ToolRoutingDecisionVO original) {
        Map<String, String> reasons = new LinkedHashMap<>();
        if (original.getBlockedToolReasons() != null) {
            reasons.putAll(original.getBlockedToolReasons());
        }
        if (original.getAllowedToolNames() != null) {
            original.getAllowedToolNames().stream()
                    .filter(StringUtils::isNotBlank)
                    .map(ToolGuardPolicy::normalize)
                    .filter(ToolGuardPolicy::isWriteOrDangerousTool)
                    .forEach(toolName -> reasons.putIfAbsent(toolName, "RAG evidence 子链路禁止写入、通知、记忆或危险工具。"));
        }
        return reasons;
    }

    public record PolicyCheckResult(boolean accepted, String reason) {

        public static PolicyCheckResult accept() {
            return new PolicyCheckResult(true, "");
        }

        public static PolicyCheckResult reject(String reason) {
            return new PolicyCheckResult(false, reason);
        }
    }
}
