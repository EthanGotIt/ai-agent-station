package cn.ethan.ai.infrastructure.adapter.port;

import cn.ethan.ai.domain.agent.adapter.port.IMcpClientLifecyclePort;
import cn.ethan.ai.domain.agent.adapter.port.IAgentRuntimeAssemblyPort;
import cn.ethan.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.ethan.ai.domain.agent.model.valobj.AiClientVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.ethan.ai.domain.agent.service.execute.graph.ToolGuardPolicy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从动态装配容器中解析 Graph Runtime 需要的模型和 MCP 工具。
 */
@Slf4j
@Service
public class AgentRuntimeAssemblyPort implements IAgentRuntimeAssemblyPort {

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private IAgentRepository repository;

    @Resource
    private IMcpClientLifecyclePort mcpClientLifecyclePort;

    @Override
    public ChatClient resolveChatClient(String clientId) {
        if (StringUtils.isBlank(clientId)) {
            throw new IllegalArgumentException("Graph Runtime clientId 不能为空");
        }
        return getBean(AiAgentEnumVO.AI_CLIENT.getBeanName(clientId), ChatClient.class);
    }

    @Override
    public ChatModel resolveChatModel(String clientId) {
        List<AiClientVO> clients = repository.queryAiClientVOByClientIds(List.of(clientId));
        if (clients == null || clients.isEmpty() || StringUtils.isBlank(clients.get(0).getModelId())) {
            throw new IllegalStateException("Graph Runtime 未找到客户端关联模型，clientId=" + clientId);
        }
        String modelId = clients.get(0).getModelId();
        return getBean(AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(modelId), ChatModel.class);
    }

    @Override
    public List<ToolCallback> resolveMcpToolCallbacks(ToolRoutingDecisionVO routingDecision) {
        if (routingDecision == null || !routingDecision.isEnabled()
                || routingDecision.getSelectedMcpIds() == null
                || routingDecision.getSelectedMcpIds().isEmpty()) {
            return List.of();
        }

        List<ToolCallback> rawCallbacks = mcpClientLifecyclePort.resolveToolCallbacks(routingDecision);
        if (rawCallbacks.isEmpty()) {
            return List.of();
        }

        Set<String> allowedNames = normalizeAllowedNames(routingDecision.getAllowedToolNames());
        if (allowedNames.isEmpty()) {
            log.warn("Graph Runtime 工具路由已启用但授权集合为空，本轮不注入 MCP 工具。");
            return List.of();
        }

        List<ToolCallback> callbacks = new ArrayList<>();
        for (ToolCallback callback : rawCallbacks) {
            if (callback == null || callback.getToolDefinition() == null
                    || StringUtils.isBlank(callback.getToolDefinition().name())) {
                continue;
            }
            String toolName = ToolGuardPolicy.normalize(callback.getToolDefinition().name());
            if (!allowedNames.contains(toolName) || ToolGuardPolicy.isBlocked(toolName)) {
                continue;
            }
            callbacks.add(new GuardedToolCallback(callback, allowedNames));
        }
        return callbacks;
    }

    private Set<String> normalizeAllowedNames(Set<String> toolNames) {
        Set<String> allowedNames = new LinkedHashSet<>();
        if (toolNames == null) {
            return allowedNames;
        }
        for (String toolName : toolNames) {
            if (StringUtils.isNotBlank(toolName)) {
                allowedNames.add(ToolGuardPolicy.normalize(toolName));
            }
        }
        return allowedNames;
    }

    private <T> T getBean(String beanName, Class<T> type) {
        try {
            return applicationContext.getBean(beanName, type);
        } catch (BeansException e) {
            throw new IllegalStateException("动态装配 Bean 不存在，beanName=" + beanName, e);
        }
    }

}
