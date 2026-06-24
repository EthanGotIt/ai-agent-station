package cn.ethan.ai.domain.agent.service.execute.harness;

import cn.ethan.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentClientHarnessConfigVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingItemVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.ToolRiskLevelEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.EvidenceSourceTypeEnumVO;
import cn.ethan.ai.domain.agent.service.execute.runtime.ToolGuardPolicy;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Harness 运行期工具能力服务，负责按企业知识助手场景动态筛选 MCP 工具。
 */
@Service
public class RuntimeToolCapabilityService {

    private static final int MAX_SELECTED_MCP_SERVERS = 3;

    private static final String TAG_DOCS = "docs";

    private static final String TAG_SEARCH = "search";

    private static final List<String> SIMPLE_TASK_HINTS = List.of(
            "润色", "改写", "翻译", "总结", "解释", "写一段", "写一篇", "生成文案", "优化这段"
    );

    private static final List<String> EXTERNAL_TOOL_HINTS = List.of(
            "搜索", "检索", "查找", "查询", "联网", "官网", "文档", "资料", "最新", "帮我找", "调研"
    );

    @Resource
    private IAgentRepository repository;

    private ToolRoutingDecisionVO routeCandidates(Map<String, AiAgentClientHarnessConfigVO> harnessConfigMap, String userMessage) {
        List<AiClientToolMcpVO> mcpTools = loadMcpTools(harnessConfigMap);
        if (mcpTools.isEmpty()) {
            return ToolRoutingDecisionVO.disabled("当前智能体未配置可用 MCP 工具，本轮仅使用模型能力。");
        }

        if (shouldSkipExternalTools(userMessage)) {
            return ToolRoutingDecisionVO.disabled("当前任务偏生成或解释，本轮无需调用外部 MCP 工具。");
        }

        List<ToolRoutingItemVO> routeItems = mcpTools.stream()
                .map(this::toRouteItem)
                .toList();
        Set<String> blockedToolNames = collectBlockedToolNames(routeItems);
        Map<String, String> blockedToolReasons = collectBlockedToolReasons(routeItems);

        List<ToolRoutingItemVO> candidates = routeItems.stream()
                .filter(item -> item.getToolNames() != null && !item.getToolNames().isEmpty())
                .filter(this::isScenarioTool)
                .toList();
        if (candidates.isEmpty()) {
            return ToolRoutingDecisionVO.disabled(
                    blockedToolNames.isEmpty()
                            ? "MCP 工具缺少 docs/search 场景下可路由的只读工具名，本轮仅使用模型能力。"
                            : "MCP 工具均被 Tool Guard 拦截或不符合 docs/search 场景，本轮仅使用模型能力。",
                    blockedToolNames,
                    blockedToolReasons
            );
        }

        String normalizedMessage = normalize(userMessage);
        boolean needExternalSearch = containsAny(normalizedMessage, EXTERNAL_TOOL_HINTS) || normalizedMessage.contains("mcp");

        List<ScoredRouteItem> scoredItems = new ArrayList<>();
        for (ToolRoutingItemVO item : candidates) {
            int score = scoreRouteItem(item, normalizedMessage, needExternalSearch);
            if (score > 0) {
                scoredItems.add(new ScoredRouteItem(item, score));
            }
        }

        if (scoredItems.isEmpty() && needExternalSearch) {
            for (ToolRoutingItemVO item : candidates) {
                if (item.getRouteTags().contains(TAG_DOCS) || item.getRouteTags().contains(TAG_SEARCH)) {
                    scoredItems.add(new ScoredRouteItem(item, 1));
                }
            }
        }

        if (scoredItems.isEmpty()) {
            return ToolRoutingDecisionVO.disabled("未匹配到合适的外部工具，本轮由模型直接完成。", blockedToolNames, blockedToolReasons);
        }

        List<ToolRoutingItemVO> selectedItems = scoredItems.stream()
                .sorted(Comparator.comparingInt(ScoredRouteItem::score).reversed()
                        .thenComparing(item -> item.item().getMcpId(), Comparator.nullsLast(String::compareTo)))
                .map(ScoredRouteItem::item)
                .distinct()
                .limit(MAX_SELECTED_MCP_SERVERS)
                .toList();

        Set<String> allowedToolNames = selectedItems.stream()
                .flatMap(item -> item.getToolNames().stream())
                .filter(StringUtils::isNotBlank)
                .map(this::normalize)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> selectedMcpIds = selectedItems.stream()
                .map(ToolRoutingItemVO::getMcpId)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return ToolRoutingDecisionVO.builder()
                .enabled(true)
                .summary(buildToolCapabilitySummary(selectedItems))
                .allowedToolNames(allowedToolNames)
                .selectedMcpIds(selectedMcpIds)
                .selectedTools(selectedItems)
                .blockedToolNames(blockedToolNames)
                .blockedToolReasons(blockedToolReasons)
                .build();
    }

    private String buildToolCapabilitySummary(List<ToolRoutingItemVO> selectedItems) {
        if (selectedItems == null || selectedItems.isEmpty()) {
            return "本轮没有选择任何 MCP 工具。";
        }

        StringBuilder builder = new StringBuilder("本轮已选择以下 MCP 工具：");
        for (ToolRoutingItemVO item : selectedItems) {
            builder.append(System.lineSeparator())
                    .append("- ")
                    .append(item.getMcpName())
                    .append("：")
                    .append(String.join(", ", item.getToolNames()));
            if (StringUtils.isNotBlank(item.getRiskLevel())) {
                builder.append("，风险等级=").append(item.getRiskLevel());
            }
            if (item.getBlockedToolNames() != null && !item.getBlockedToolNames().isEmpty()) {
                builder.append("，已拦截=").append(String.join(", ", item.getBlockedToolNames()));
            }
            if (StringUtils.isNotBlank(item.getSelectedReason())) {
                builder.append("（").append(item.getSelectedReason()).append("）");
            }
        }
        return builder.toString();
    }

    private List<AiClientToolMcpVO> loadMcpTools(Map<String, AiAgentClientHarnessConfigVO> harnessConfigMap) {
        if (harnessConfigMap == null || harnessConfigMap.isEmpty()) {
            return List.of();
        }

        List<String> clientIds = harnessConfigMap.values().stream()
                .filter(Objects::nonNull)
                .map(AiAgentClientHarnessConfigVO::getClientId)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        if (clientIds.isEmpty()) {
            return List.of();
        }

        try {
            return repository.queryAiClientToolMcpVOByClientIds(clientIds);
        } catch (Exception e) {
            throw new IllegalStateException("加载 MCP 工具配置失败", e);
        }
    }

    private boolean shouldSkipExternalTools(String userMessage) {
        String normalizedMessage = normalize(userMessage);
        return containsAny(normalizedMessage, SIMPLE_TASK_HINTS) && !containsAny(normalizedMessage, EXTERNAL_TOOL_HINTS);
    }

    private ToolRoutingItemVO toRouteItem(AiClientToolMcpVO mcpTool) {
        List<String> rawToolNames = normalizeToolNames(mcpTool.getToolNames());
        List<String> allowedToolNames = new ArrayList<>();
        List<String> blockedToolNames = new ArrayList<>();
        ToolRiskLevelEnumVO maxRiskLevel = ToolRiskLevelEnumVO.LOW;
        for (String toolName : rawToolNames) {
            ToolRiskLevelEnumVO riskLevel = ToolGuardPolicy.assessRisk(toolName);
            maxRiskLevel = ToolGuardPolicy.max(maxRiskLevel, riskLevel);
            if (ToolGuardPolicy.isBlocked(toolName)) {
                blockedToolNames.add(toolName);
            } else {
                allowedToolNames.add(toolName);
            }
        }
        List<String> routeTags = inferRouteTags(mcpTool.getMcpName(), rawToolNames);
        return ToolRoutingItemVO.builder()
                .mcpId(mcpTool.getMcpId())
                .mcpName(StringUtils.defaultIfBlank(mcpTool.getMcpName(), mcpTool.getMcpId()))
                .transportType(mcpTool.getTransportType())
                .toolNames(allowedToolNames)
                .routeTags(routeTags)
                .riskLevel(maxRiskLevel.name())
                .blockedToolNames(blockedToolNames)
                .guardReason(blockedToolNames.isEmpty()
                        ? "Tool Guard 检查通过"
                        : "Tool Guard 已拦截危险工具：" + String.join(", ", blockedToolNames))
                .selectedReason(resolveDefaultReason(routeTags))
                .build();
    }

    private Set<String> collectBlockedToolNames(List<ToolRoutingItemVO> routeItems) {
        if (routeItems == null || routeItems.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return routeItems.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getBlockedToolNames() != null)
                .flatMap(item -> item.getBlockedToolNames().stream())
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<String, String> collectBlockedToolReasons(List<ToolRoutingItemVO> routeItems) {
        Map<String, String> reasons = new LinkedHashMap<>();
        if (routeItems == null || routeItems.isEmpty()) {
            return reasons;
        }
        for (ToolRoutingItemVO item : routeItems) {
            if (item == null || item.getBlockedToolNames() == null) {
                continue;
            }
            for (String blockedToolName : item.getBlockedToolNames()) {
                if (StringUtils.isNotBlank(blockedToolName)) {
                    reasons.putIfAbsent(blockedToolName, ToolGuardPolicy.describe(blockedToolName));
                }
            }
        }
        return reasons;
    }

    private int scoreRouteItem(ToolRoutingItemVO item,
                               String message,
                               boolean needExternalSearch) {
        int score = 0;
        List<String> tags = item.getRouteTags();
        if (tags.contains(TAG_DOCS) && (message.contains("spring ai") || message.contains("sdk") || message.contains("文档"))) {
            score += 5;
        }
        if (tags.contains(TAG_SEARCH) && needExternalSearch) {
            score += 4;
        }
        if (tags.contains(TAG_SEARCH) && message.contains("最新")) {
            score += 2;
        }
        if (tags.contains(TAG_DOCS) && message.contains("mcp")) {
            score += 2;
        }
        return score;
    }

    public ToolRoutingDecisionVO routeForEvidenceSource(Map<String, AiAgentClientHarnessConfigVO> harnessConfigMap,
                                                        EvidenceSourceTypeEnumVO sourceType) {
        if (sourceType == null || !sourceType.isExternal()) {
            return ToolRoutingDecisionVO.disabled("项目知识检索不注入外部 MCP 工具。");
        }
        String routingHint = sourceType == EvidenceSourceTypeEnumVO.OFFICIAL_DOCS
                ? "查询官网官方文档 SDK MCP"
                : "联网搜索最新技术资料";
        ToolRoutingDecisionVO original = routeCandidates(harnessConfigMap, routingHint);
        if (!original.isEnabled() || original.getSelectedTools() == null) {
            return original;
        }
        String requiredTag = sourceType == EvidenceSourceTypeEnumVO.OFFICIAL_DOCS ? TAG_DOCS : TAG_SEARCH;
        List<ToolRoutingItemVO> selected = original.getSelectedTools().stream()
                .filter(item -> item.getRouteTags() != null && item.getRouteTags().contains(requiredTag))
                .toList();
        if (selected.isEmpty()) {
            return ToolRoutingDecisionVO.disabled("当前没有匹配 " + sourceType + " 的只读 MCP 工具。",
                    original.getBlockedToolNames(), original.getBlockedToolReasons());
        }
        Set<String> toolNames = selected.stream()
                .flatMap(item -> item.getToolNames().stream())
                .map(ToolGuardPolicy::normalize)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> mcpIds = selected.stream()
                .map(ToolRoutingItemVO::getMcpId)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return ToolRoutingDecisionVO.builder()
                .enabled(true)
                .summary("已按 evidence source=" + sourceType + " 选择只读 MCP 工具。")
                .allowedToolNames(toolNames)
                .selectedMcpIds(mcpIds)
                .selectedTools(selected)
                .blockedToolNames(original.getBlockedToolNames())
                .blockedToolReasons(original.getBlockedToolReasons())
                .build();
    }

    private List<String> inferRouteTags(String mcpName, List<String> toolNames) {
        String merged = normalize((StringUtils.defaultString(mcpName) + " " + String.join(" ", toolNames)));
        List<String> tags = new ArrayList<>();
        if (containsAny(merged, List.of("context7", "resolve-library", "get-library-docs", "docs"))) {
            tags.add(TAG_DOCS);
        }
        if (containsAny(merged, List.of("search", "fetch", "exa", "web_"))) {
            tags.add(TAG_SEARCH);
        }
        if (tags.isEmpty()) {
            tags.add("general");
        }
        return tags;
    }

    private String resolveDefaultReason(List<String> routeTags) {
        if (routeTags.contains(TAG_DOCS)) {
            return "适合文档检索与官方资料查询";
        }
        if (routeTags.contains(TAG_SEARCH)) {
            return "适合联网搜索与信息补充";
        }
        return "适合通用外部能力补充";
    }

    private boolean isScenarioTool(ToolRoutingItemVO item) {
        if (item == null || item.getRouteTags() == null) {
            return false;
        }
        return item.getRouteTags().contains(TAG_DOCS) || item.getRouteTags().contains(TAG_SEARCH);
    }

    private List<String> normalizeToolNames(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return List.of();
        }
        return toolNames.stream()
                .filter(StringUtils::isNotBlank)
                .map(this::normalize)
                .distinct()
                .toList();
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (StringUtils.isBlank(text) || keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.isNotBlank(keyword) && text.contains(normalize(keyword))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        return StringUtils.defaultString(text).trim().toLowerCase(Locale.ROOT);
    }

    private record ScoredRouteItem(ToolRoutingItemVO item, int score) {
    }
}
