package cn.ethan.ai.domain.agent.service.execute.flow;

import cn.ethan.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Flow 工具能力服务
 */
@Service
public class FlowToolCapabilityService {

    @Resource
    private IAgentRepository repository;

    public Set<String> loadAllowedTools(Map<String, AiAgentClientFlowConfigVO> flowConfigMap) {
        Set<String> tools = new LinkedHashSet<>();
        if (flowConfigMap == null || flowConfigMap.isEmpty()) {
            return normalize(tools);
        }

        List<String> clientIds = flowConfigMap.values().stream()
                .filter(Objects::nonNull)
                .map(AiAgentClientFlowConfigVO::getClientId)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.toList());
        if (clientIds.isEmpty()) {
            return normalize(tools);
        }

        List<AiClientToolMcpVO> mcpTools;
        try {
            mcpTools = repository.queryAiClientToolMcpVOByClientIds(clientIds);
        } catch (Exception e) {
            throw new IllegalStateException("加载 MCP 工具白名单失败", e);
        }

        if (mcpTools != null) {
            for (AiClientToolMcpVO tool : mcpTools) {
                if (StringUtils.isNotBlank(tool.getMcpId())) {
                    tools.add(tool.getMcpId());
                }
                if (StringUtils.isNotBlank(tool.getMcpName())) {
                    tools.add(tool.getMcpName());
                }
                if (tool.getToolNames() != null) {
                    tool.getToolNames().stream()
                            .filter(StringUtils::isNotBlank)
                            .forEach(tools::add);
                }
            }
        }
        return normalize(tools);
    }

    public String buildToolCapabilitySummary(Set<String> allowedTools) {
        if (allowedTools == null || allowedTools.isEmpty()) {
            return "未配置明确的 MCP 工具白名单，优先使用 LLM 步骤。";
        }
        return "可用工具白名单：" + String.join(", ", allowedTools);
    }

    private Set<String> normalize(Set<String> tools) {
        return tools.stream()
                .filter(StringUtils::isNotBlank)
                .map(item -> item.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
