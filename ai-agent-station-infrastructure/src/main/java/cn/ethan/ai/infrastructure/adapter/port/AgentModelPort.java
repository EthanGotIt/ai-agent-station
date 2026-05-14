package cn.ethan.ai.infrastructure.adapter.port;

import cn.ethan.ai.domain.agent.adapter.port.IAgentModelPort;
import cn.ethan.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.ethan.ai.domain.agent.model.entity.AgentModelCallResultEntity;
import cn.ethan.ai.domain.agent.model.entity.AgentRunEventEntity;
import cn.ethan.ai.domain.agent.model.entity.AgentRunTraceEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AiClientVO;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextWindowGuardVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Agent 模型调用端口实现
 */
@Service
@Slf4j
public class AgentModelPort implements IAgentModelPort {

    public static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";
    public static final String CHAT_MEMORY_RETRIEVE_SIZE_KEY = "chat_memory_response_size";

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private IAgentRepository repository;

    @Override
    public boolean hasAvailableModelClient(Map<String, AiAgentClientFlowConfigVO> flowConfigMap, AiClientTypeEnumVO... clientTypes) {
        return firstAvailableConfig(flowConfigMap, clientTypes) != null;
    }

    @Override
    public String callModel(Map<String, AiAgentClientFlowConfigVO> flowConfigMap,
                            ExecuteCommandEntity command,
                            ContextWindowGuardVO contextWindowGuard,
                            AgentRunTraceEntity trace,
                            String prompt,
                            String eventType,
                            String stepId,
                            Integer step,
                            ToolRoutingDecisionVO toolRoutingDecision,
                            AiClientTypeEnumVO... clientTypes) {
        return callModelResult(
                flowConfigMap,
                command,
                contextWindowGuard,
                trace,
                prompt,
                eventType,
                stepId,
                step,
                toolRoutingDecision,
                clientTypes
        ).getContent();
    }

    @Override
    public AgentModelCallResultEntity callModelResult(Map<String, AiAgentClientFlowConfigVO> flowConfigMap,
                                                      ExecuteCommandEntity command,
                                                      ContextWindowGuardVO contextWindowGuard,
                                                      AgentRunTraceEntity trace,
                                                      String prompt,
                                                      String eventType,
                                                      String stepId,
                                                      Integer step,
                                                      ToolRoutingDecisionVO toolRoutingDecision,
                            AiClientTypeEnumVO... clientTypes) {
        long start = System.currentTimeMillis();
        if (contextWindowGuard.shouldStopNewLlmCall()) {
            String message = "上下文较长，已跳过新的模型调用。";
            AgentRunEventEntity event = trace.record(eventType, stepId, step, start, message, null);
            log.info("Agent 模型调用跳过追踪事件：{}", event);
            return AgentModelCallResultEntity.builder()
                    .content(message)
                    .build();
        }

        try {
            AiAgentClientFlowConfigVO selectedConfig = firstAvailableConfig(flowConfigMap, clientTypes);
            ChatClient baseChatClient = resolve(selectedConfig);
            if (baseChatClient == null) {
                String message = "未找到可用的模型客户端：" + eventType;
                AgentRunEventEntity event = trace.record(eventType, stepId, step, start, message, null);
                log.warn("Agent 模型客户端缺失追踪事件：{}", event);
                return AgentModelCallResultEntity.builder()
                        .content(message)
                        .build();
            }

            List<Advisor> advisors = resolveAdvisors(selectedConfig);
            List<ToolCallback> callbacks = resolveToolCallbacks(toolRoutingDecision);
            ChatClient runtimeChatClient = buildRuntimeChatClient(baseChatClient, selectedConfig, advisors, callbacks, eventType);

            contextWindowGuard.record(prompt);
            ChatClient.ChatClientRequestSpec requestSpec = runtimeChatClient.prompt(prompt)
                    .system(s -> s.param("current_date", LocalDate.now().toString()));
            requestSpec = applyAdvisorRuntimeParams(requestSpec, command, advisors);
            logCallAdvisorChain(requestSpec, eventType, selectedConfig.getClientId());
            ChatClient.CallResponseSpec responseSpec = requestSpec.call();
            ChatResponse chatResponse = responseSpec.chatResponse();
            String content = extractContent(chatResponse);
            Map<String, Object> metadata = extractMetadata(chatResponse);
            contextWindowGuard.record(content);
            AgentRunEventEntity event = trace.record(eventType, stepId, step, start, limit(content, 300), null);
            log.info("Agent 模型调用追踪事件：{}", event);
            return AgentModelCallResultEntity.builder()
                    .content(content == null ? "" : content)
                    .metadata(metadata)
                    .build();
        } catch (Exception e) {
            AgentRunEventEntity event = trace.record(eventType, stepId, step, start, null, e.getMessage());
            log.warn("Agent 模型调用异常追踪事件：{}", event);
            throw new IllegalStateException(eventType + " 调用失败：" + e.getMessage(), e);
        }
    }

    private ChatClient resolve(AiAgentClientFlowConfigVO config) {
        if (config == null) {
            return null;
        }
        return getChatClient(config.getClientId());
    }

    private ChatClient buildRuntimeChatClient(ChatClient baseChatClient,
                                              AiAgentClientFlowConfigVO config,
                                              List<Advisor> advisors,
                                              List<ToolCallback> callbacks,
                                              String eventType) {
        ChatClient.Builder builder = baseChatClient.mutate();
        if (advisors.isEmpty()) {
            log.info("运行时未装配顾问，继续直接调用模型。eventType：{}，clientId：{}", eventType,
                    config == null ? "" : config.getClientId());
        } else {
            log.info("运行时装配顾问完成，eventType：{}，clientId：{}，advisorCount：{}", eventType,
                    config == null ? "" : config.getClientId(), advisors.size());
            builder.defaultAdvisors(advisors);
        }

        if (!callbacks.isEmpty()) {
            builder.defaultToolCallbacks(callbacks);
        }

        return builder.build();
    }

    private ChatClient.ChatClientRequestSpec applyAdvisorRuntimeParams(ChatClient.ChatClientRequestSpec requestSpec,
                                                                       ExecuteCommandEntity command,
                                                                       List<Advisor> advisors) {
        if (requestSpec == null || command == null || advisors == null || advisors.isEmpty()) {
            return requestSpec;
        }

        if (StringUtils.isBlank(command.getSessionId())) {
            return requestSpec;
        }

        return requestSpec.advisors(advisorSpec -> advisorSpec
                .param(CHAT_MEMORY_CONVERSATION_ID_KEY, command.getSessionId())
                .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 100));
    }

    private AiAgentClientFlowConfigVO firstAvailableConfig(Map<String, AiAgentClientFlowConfigVO> flowConfigMap,
                                                          AiClientTypeEnumVO... clientTypes) {
        if (flowConfigMap == null || flowConfigMap.isEmpty()) {
            return null;
        }
        for (AiClientTypeEnumVO clientType : clientTypes) {
            AiAgentClientFlowConfigVO config = flowConfigMap.get(clientType.getCode());
            if (config != null && StringUtils.isNotBlank(config.getClientId())) {
                return config;
            }
        }
        return flowConfigMap.values().stream()
                .filter(item -> item != null && StringUtils.isNotBlank(item.getClientId()))
                .findFirst()
                .orElse(null);
    }

    private ChatClient getChatClient(String clientId) {
        if (StringUtils.isBlank(clientId)) {
            return null;
        }
        String beanName = AiAgentEnumVO.AI_CLIENT.getBeanName(clientId);
        try {
            return applicationContext.getBean(beanName, ChatClient.class);
        } catch (BeansException e) {
            throw new IllegalStateException("ChatClient Bean 不存在，clientId=" + clientId + "，beanName=" + beanName, e);
        }
    }

    private List<Advisor> resolveAdvisors(AiAgentClientFlowConfigVO config) {
        if (config == null || StringUtils.isBlank(config.getClientId())) {
            return Collections.emptyList();
        }

        List<AiClientVO> clients = repository.queryAiClientVOByClientIds(List.of(config.getClientId()));
        if (clients == null || clients.isEmpty()) {
            log.info("运行时未查询到客户端顾问配置，clientId：{}", config.getClientId());
            return Collections.emptyList();
        }

        AiClientVO client = clients.get(0);
        if (client.getAdvisorIdList() == null || client.getAdvisorIdList().isEmpty()) {
            log.info("运行时客户端未绑定顾问，clientId：{}", config.getClientId());
            return Collections.emptyList();
        }

        List<Advisor> advisors = new ArrayList<>();
        for (String advisorId : client.getAdvisorIdList()) {
            if (StringUtils.isBlank(advisorId)) {
                continue;
            }
            String beanName = AiAgentEnumVO.AI_CLIENT_ADVISOR.getBeanName(advisorId);
            try {
                advisors.add(applicationContext.getBean(beanName, Advisor.class));
            } catch (BeansException e) {
                log.debug("运行时未找到顾问 Bean，advisorId：{}，beanName：{}", advisorId, beanName);
            }
        }
        return advisors;
    }

    private String limit(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        return content.length() <= maxLength ? content : content.substring(0, maxLength) + "...";
    }

    private List<ToolCallback> resolveToolCallbacks(ToolRoutingDecisionVO toolRoutingDecision) {
        if (toolRoutingDecision == null || !toolRoutingDecision.isEnabled()
                || toolRoutingDecision.getSelectedMcpIds() == null
                || toolRoutingDecision.getSelectedMcpIds().isEmpty()) {
            return Collections.emptyList();
        }

        List<McpSyncClient> clients = new ArrayList<>();
        for (String mcpId : toolRoutingDecision.getSelectedMcpIds()) {
            if (StringUtils.isBlank(mcpId)) {
                continue;
            }
            String beanName = AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(mcpId);
            try {
                clients.add(applicationContext.getBean(beanName, McpSyncClient.class));
            } catch (BeansException e) {
                log.debug("运行时工具路由未找到 MCP 客户端 Bean，mcpId：{}，beanName：{}", mcpId, beanName);
            }
        }
        if (clients.isEmpty()) {
            return Collections.emptyList();
        }

        ToolCallback[] rawCallbacks = SyncMcpToolCallbackProvider.builder()
                .mcpClients(clients)
                .build()
                .getToolCallbacks();
        if (rawCallbacks == null || rawCallbacks.length == 0) {
            return Collections.emptyList();
        }

        Set<String> allowedNames = new LinkedHashSet<>();
        if (toolRoutingDecision.getAllowedToolNames() != null) {
            for (String name : toolRoutingDecision.getAllowedToolNames()) {
                if (StringUtils.isNotBlank(name)) {
                    allowedNames.add(name.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (allowedNames.isEmpty()) {
            return List.of(rawCallbacks);
        }

        List<ToolCallback> filtered = new ArrayList<>();
        for (ToolCallback callback : rawCallbacks) {
            if (callback == null || callback.getToolDefinition() == null || StringUtils.isBlank(callback.getToolDefinition().name())) {
                continue;
            }
            String toolName = callback.getToolDefinition().name().trim().toLowerCase(Locale.ROOT);
            if (allowedNames.contains(toolName)) {
                filtered.add(callback);
            }
        }
        return filtered;
    }

    private Map<String, Object> extractMetadata(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getMetadata() == null || chatResponse.getMetadata().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        chatResponse.getMetadata().entrySet().forEach(entry -> metadata.put(entry.getKey(), entry.getValue()));
        return metadata;
    }

    private String extractContent(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null
                || chatResponse.getResult().getOutput() == null
                || chatResponse.getResult().getOutput().getText() == null) {
            return "";
        }
        return chatResponse.getResult().getOutput().getText();
    }

    /**
     * 输出 Spring AI 真实构建出的调用顾问链，便于定位请求期顾问顺序问题。
     */
    private void logCallAdvisorChain(ChatClient.ChatClientRequestSpec requestSpec, String eventType, String clientId) {
        if (!log.isDebugEnabled() || requestSpec == null) {
            return;
        }
        try {
            Method buildAdvisorChainMethod = requestSpec.getClass().getDeclaredMethod("buildAdvisorChain");
            buildAdvisorChainMethod.setAccessible(true);
            Object advisorChain = buildAdvisorChainMethod.invoke(requestSpec);
            Method getCallAdvisorsMethod = advisorChain.getClass().getMethod("getCallAdvisors");
            @SuppressWarnings("unchecked")
            List<CallAdvisor> callAdvisors = (List<CallAdvisor>) getCallAdvisorsMethod.invoke(advisorChain);
            if (callAdvisors == null || callAdvisors.isEmpty()) {
                log.debug("运行时调用顾问链为空，eventType：{}，clientId：{}", eventType, clientId);
                return;
            }

            List<String> advisorSummary = callAdvisors.stream()
                    .map(advisor -> advisor.getName() + "(" + advisor.getOrder() + ")")
                    .toList();
            log.debug("运行时调用顾问链，eventType：{}，clientId：{}，callAdvisors：{}",
                    eventType, clientId, advisorSummary);
        } catch (Exception e) {
            log.debug("读取运行时调用顾问链失败，eventType：{}，clientId：{}，原因：{}",
                    eventType, clientId, e.getMessage());
        }
    }
}
