package cn.ethan.ai.domain.agent.service.execute.flow;

import cn.ethan.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingItemVO;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Flow 执行期工具能力与动态路由服务。
 */
@Service
public class FlowToolCapabilityService {

    private static final List<String> SIMPLE_TASK_KEYWORDS = List.of(
            "润色", "改写", "翻译", "总结", "解释", "写一段", "写一篇", "生成文案", "优化表达"
    );

    private static final List<String> SEARCH_TASK_KEYWORDS = List.of(
            "搜索", "检索", "查找", "查一下", "资料", "文档", "官网", "最新", "联网", "教程", "示例", "案例", "对比"
    );

    private static final List<String> COMPLEX_REASONING_KEYWORDS = List.of(
            "规划", "拆解", "步骤", "执行计划", "分步", "路线图", "排期", "编排"
    );

    private static final List<String> NOTIFY_KEYWORDS = List.of(
            "通知", "提醒", "完成后告诉我", "结束后告诉我", "发个提醒"
    );

    private static final Map<String, List<String>> TAG_KEYWORDS = Map.of(
            "search", List.of("search", "fetch", "doc", "docs", "context7", "exa", "resolve-library-id", "get-library-docs"),
            "reasoning", List.of("sequential", "thinking", "plan"),
            "memory", List.of("memory", "graph", "node", "relation", "observation"),
            "notify", List.of("notify", "notification", "reminder")
    );

    @Resource
    private IAgentRepository repository;

    public ToolRoutingDecisionVO buildToolRoutingDecision(Map<String, AiAgentClientFlowConfigVO> flowConfigMap, String userMessage) {
        List<AiClientToolMcpVO> mcpTools = loadMcpTools(flowConfigMap);
        if (mcpTools.isEmpty()) {
            return ToolRoutingDecisionVO.disabled("当前智能体未配置可用 MCP 工具，本轮仅使用模型能力。");
        }

        if (isSimpleTask(userMessage)) {
            return ToolRoutingDecisionVO.disabled("当前任务偏内容生成或解释，本轮不启用 MCP 工具。");
        }

        List<ToolRoutingItemVO> selectedTools = selectTools(mcpTools, userMessage);
        if (selectedTools.isEmpty()) {
            return ToolRoutingDecisionVO.disabled("当前问题未命中合适的工具标签，本轮直接使用模型推理。");
        }

        Set<String> allowedToolNames = new LinkedHashSet<>();
        Set<String> selectedMcpIds = new LinkedHashSet<>();
        for (ToolRoutingItemVO item : selectedTools) {
            selectedMcpIds.add(item.getMcpId());
            if (item.getToolNames() != null) {
                for (String toolName : item.getToolNames()) {
                    if (StringUtils.isNotBlank(toolName)) {
                        allowedToolNames.add(toolName.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }

        return ToolRoutingDecisionVO.builder()
                .enabled(true)
                .summary(buildToolCapabilitySummary(selectedTools))
                .allowedToolNames(allowedToolNames)
                .selectedMcpIds(selectedMcpIds)
                .selectedTools(selectedTools)
                .build();
    }

    public String buildToolCapabilitySummary(List<ToolRoutingItemVO> selectedTools) {
        if (selectedTools == null || selectedTools.isEmpty()) {
            return "本轮未选择任何 MCP 工具，优先使用 LLM 直接完成任务。";
        }

        StringBuilder builder = new StringBuilder("本轮可用工具：");
        for (int i = 0; i < selectedTools.size(); i++) {
            ToolRoutingItemVO item = selectedTools.get(i);
            if (i > 0) {
                builder.append("；");
            }
            builder.append(item.getMcpName()).append(" -> ");
            builder.append(String.join(", ", item.getToolNames()));
            if (StringUtils.isNotBlank(item.getSelectedReason())) {
                builder.append("（").append(item.getSelectedReason()).append("）");
            }
        }
        return builder.toString();
    }

    private List<AiClientToolMcpVO> loadMcpTools(Map<String, AiAgentClientFlowConfigVO> flowConfigMap) {
        if (flowConfigMap == null || flowConfigMap.isEmpty()) {
            return List.of();
        }

        List<String> clientIds = flowConfigMap.values().stream()
                .filter(Objects::nonNull)
                .map(AiAgentClientFlowConfigVO::getClientId)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.toList());
        if (clientIds.isEmpty()) {
            return List.of();
        }

        List<AiClientToolMcpVO> mcpTools = repository.queryAiClientToolMcpVOByClientIds(clientIds);
        return mcpTools == null ? List.of() : mcpTools;
    }

    private boolean isSimpleTask(String userMessage) {
        String normalized = normalize(userMessage);
        if (StringUtils.isBlank(normalized)) {
            return true;
        }
        boolean containsSearchIntent = containsAny(normalized, SEARCH_TASK_KEYWORDS);
        boolean containsSimpleIntent = containsAny(normalized, SIMPLE_TASK_KEYWORDS);
        return containsSimpleIntent && !containsSearchIntent;
    }

    private List<ToolRoutingItemVO> selectTools(List<AiClientToolMcpVO> mcpTools, String userMessage) {
        String normalized = normalize(userMessage);
        List<ToolRoutingItemVO> selected = new ArrayList<>();

        boolean needSearch = containsAny(normalized, SEARCH_TASK_KEYWORDS);
        boolean needReasoning = containsAny(normalized, COMPLEX_REASONING_KEYWORDS);
        boolean needNotify = containsAny(normalized, NOTIFY_KEYWORDS);

        for (AiClientToolMcpVO mcpTool : mcpTools) {
            Set<String> tags = inferTags(mcpTool);
            int score = score(tags, normalized, needSearch, needReasoning, needNotify);
            if (score <= 0) {
                continue;
            }
            selected.add(ToolRoutingItemVO.builder()
                    .mcpId(mcpTool.getMcpId())
                    .mcpName(defaultName(mcpTool))
                    .transportType(mcpTool.getTransportType())
                    .toolNames(normalizeToolNames(mcpTool))
                    .routeTags(new ArrayList<>(tags))
                    .selectedReason(buildReason(tags, needSearch, needReasoning, needNotify))
                    .build());
        }

        if (selected.isEmpty() && needSearch) {
            for (AiClientToolMcpVO mcpTool : mcpTools) {
                Set<String> tags = inferTags(mcpTool);
                if (tags.contains("search")) {
                    selected.add(ToolRoutingItemVO.builder()
                            .mcpId(mcpTool.getMcpId())
                            .mcpName(defaultName(mcpTool))
                            .transportType(mcpTool.getTransportType())
                            .toolNames(normalizeToolNames(mcpTool))
                            .routeTags(new ArrayList<>(tags))
                            .selectedReason("兜底启用通用检索工具")
                            .build());
                    break;
                }
            }
        }
        return selected;
    }

    private int score(Set<String> tags, String normalized, boolean needSearch, boolean needReasoning, boolean needNotify) {
        int score = 0;
        if (needSearch && tags.contains("search")) {
            score += 3;
        }
        if (needReasoning && tags.contains("reasoning")) {
            score += 2;
        }
        if (needNotify && tags.contains("notify")) {
            score += 2;
        }
        if (normalized.contains("记住") && tags.contains("memory")) {
            score += 2;
        }
        if (!needSearch && !needReasoning && !needNotify && tags.contains("search")) {
            score += 1;
        }
        return score;
    }

    private String buildReason(Set<String> tags, boolean needSearch, boolean needReasoning, boolean needNotify) {
        List<String> reasons = new ArrayList<>();
        if (needSearch && tags.contains("search")) {
            reasons.add("匹配资料检索");
        }
        if (needReasoning && tags.contains("reasoning")) {
            reasons.add("匹配分步推理");
        }
        if (needNotify && tags.contains("notify")) {
            reasons.add("匹配完成提醒");
        }
        if (reasons.isEmpty() && tags.contains("search")) {
            reasons.add("通用检索兜底");
        }
        return String.join("、", reasons);
    }

    private Set<String> inferTags(AiClientToolMcpVO mcpTool) {
        String searchText = normalize(defaultName(mcpTool) + " " + String.join(" ", normalizeToolNames(mcpTool)));
        Set<String> tags = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : TAG_KEYWORDS.entrySet()) {
            if (containsAny(searchText, entry.getValue())) {
                tags.add(entry.getKey());
            }
        }
        if (tags.isEmpty()) {
            tags.add("general");
        }
        return tags;
    }

    private List<String> normalizeToolNames(AiClientToolMcpVO mcpTool) {
        if (mcpTool.getToolNames() == null || mcpTool.getToolNames().isEmpty()) {
            return List.of(defaultName(mcpTool));
        }
        return mcpTool.getToolNames().stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());
    }

    private String defaultName(AiClientToolMcpVO mcpTool) {
        return StringUtils.defaultIfBlank(mcpTool.getMcpName(), mcpTool.getMcpId());
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (StringUtils.isBlank(text) || keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.isNotBlank(keyword) && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        return StringUtils.defaultString(text).trim().toLowerCase(Locale.ROOT);
    }
}

