package cn.ethan.ai.infrastructure.adapter.port;

import cn.ethan.ai.domain.agent.adapter.port.ISpringAiChatClientPort;
import cn.ethan.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.ethan.ai.domain.agent.model.entity.AgentModelCallResultEntity;
import cn.ethan.ai.domain.agent.model.entity.AgentRunEventEntity;
import cn.ethan.ai.domain.agent.model.entity.AgentRunTraceEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentClientConfigVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolInvocationCollector;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring AI ChatClient 调用网关。工具发现与安全过滤由 {@link #buildToolCallingAdvisor} 统一处理。
 */
@Slf4j
@Service
public class SpringAiChatClientPort implements ISpringAiChatClientPort {

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private IAgentRepository repository;

    @Override
    public AgentModelCallResultEntity call(Map<String, AiAgentClientConfigVO> clientConfigMap,
                                           ExecuteCommandEntity command,
                                           AgentRunTraceEntity trace,
                                           String prompt,
                                           String eventType,
                                           String stepId,
                                           Integer step,
                                           List<Advisor> advisors,
                                           Map<String, Object> advisorParams,
                                           AiClientTypeEnumVO... clientTypes) {
        long start = System.currentTimeMillis();
        ToolInvocationCollector collector = new ToolInvocationCollector();
        try {
            AiAgentClientConfigVO selectedConfig = firstAvailableConfig(clientConfigMap, clientTypes);
            ChatClient chatClient = resolve(selectedConfig);
            if (chatClient == null) {
                String message = "未找到可用的 Spring AI ChatClient：" + eventType;
                trace.record(eventType, stepId, step, start, message, null);
                return AgentModelCallResultEntity.builder().content(message).build();
            }

            ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt(prompt)
                    .system(system -> system.param("current_date", LocalDate.now().toString()))
                    .toolContext(Map.of(ToolInvocationCollector.TOOL_CONTEXT_KEY, collector))
                    .options(OpenAiChatOptions.builder()
                            .toolChoice("auto")
                            .parallelToolCalls(false)
                            .extraBody(Map.of("enable_thinking", false)));
            if (advisors != null && !advisors.isEmpty()) {
                requestSpec.advisors(spec -> {
                    if (advisorParams != null && !advisorParams.isEmpty()) {
                        spec.params(advisorParams);
                    }
                    spec.advisors(advisors);
                });
            }

            ChatClient.CallResponseSpec responseSpec = requestSpec.call();
            ChatClientResponse chatClientResponse = responseSpec.chatClientResponse();
            ChatResponse chatResponse = chatClientResponse.chatResponse();
            String content = extractContent(chatResponse);
            Map<String, Object> metadata = extractMetadata(chatClientResponse, chatResponse);
            if (!collector.snapshot().isEmpty()) {
                metadata = new LinkedHashMap<>(metadata);
                metadata.put(ToolInvocationCollector.METADATA_KEY, collector.snapshot());
            }
            AgentRunEventEntity event = trace.record(eventType, stepId, step, start, limit(content, 300), null);
            log.info("Spring AI ChatClient 调用追踪事件：{}", event);
            return AgentModelCallResultEntity.builder()
                    .content(StringUtils.defaultString(content))
                    .metadata(metadata)
                    .build();
        } catch (Exception e) {
            if (!collector.snapshot().isEmpty()) {
                trace.record(eventType, stepId, step, start, "工具调用已完成，模型后处理失败，保留原始工具证据。", null);
                return AgentModelCallResultEntity.builder()
                        .content("")
                        .metadata(Map.of(ToolInvocationCollector.METADATA_KEY, collector.snapshot()))
                        .build();
            }
            trace.record(eventType, stepId, step, start, null, e.getMessage());
            throw new IllegalStateException(eventType + " 调用失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Advisor buildToolCallingAdvisor(Map<String, AiAgentClientConfigVO> clientConfigMap) {
        List<List<ToolCallback>> mcpGroups = new ArrayList<>();
        List<AiClientToolMcpVO> mcpTools = repository.queryAiClientToolMcpVOByClientIds(
                clientIdsFromConfig(clientConfigMap));
        for (AiClientToolMcpVO mcpTool : mcpTools) {
            if (mcpTool == null || StringUtils.isBlank(mcpTool.getMcpId())) {
                continue;
            }
            McpSyncClient client;
            try {
                client = applicationContext.getBean(
                        AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(mcpTool.getMcpId()), McpSyncClient.class);
            } catch (BeansException e) {
                log.debug("Spring AI 主链路工具集合跳过未装配 MCP，mcpId：{}", mcpTool.getMcpId());
                continue;
            }
            ToolCallback[] callbacks = SyncMcpToolCallbackProvider.builder()
                    .mcpClients(client).build().getToolCallbacks();
            if (callbacks.length > 0) {
                mcpGroups.add(List.of(callbacks));
            }
        }
        if (mcpGroups.isEmpty()) {
            return null;
        }
        DefaultToolCallingManager manager = DefaultToolCallingManager.builder()
                .toolCallbackResolver(new GuardedToolCallbackResolver(mcpGroups))
                .build();
        return ToolCallingAdvisor.builder().toolCallingManager(manager).build();
    }

    private List<String> clientIdsFromConfig(Map<String, AiAgentClientConfigVO> clientConfigMap) {
        if (clientConfigMap == null || clientConfigMap.isEmpty()) {
            return List.of();
        }
        return clientConfigMap.values().stream()
                .filter(java.util.Objects::nonNull)
                .map(AiAgentClientConfigVO::getClientId)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
    }

    private AiAgentClientConfigVO firstAvailableConfig(Map<String, AiAgentClientConfigVO> clientConfigMap,
                                                              AiClientTypeEnumVO... clientTypes) {
        if (clientConfigMap == null || clientConfigMap.isEmpty()) {
            return null;
        }
        for (AiClientTypeEnumVO clientType : clientTypes) {
            AiAgentClientConfigVO config = clientConfigMap.get(clientType.getCode());
            if (config != null && StringUtils.isNotBlank(config.getClientId())) {
                return config;
            }
        }
        return clientConfigMap.values().stream()
                .filter(item -> item != null && StringUtils.isNotBlank(item.getClientId()))
                .findFirst()
                .orElse(null);
    }

    private ChatClient resolve(AiAgentClientConfigVO config) {
        if (config == null || StringUtils.isBlank(config.getClientId())) {
            return null;
        }
        return applicationContext.getBean(AiAgentEnumVO.AI_CLIENT.getBeanName(config.getClientId()), ChatClient.class);
    }

    private Map<String, Object> extractMetadata(ChatClientResponse chatClientResponse, ChatResponse chatResponse) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (chatResponse != null && !chatResponse.getMetadata().isEmpty()) {
            chatResponse.getMetadata().entrySet().forEach(entry -> metadata.put(entry.getKey(), entry.getValue()));
        }
        if (chatClientResponse != null && !chatClientResponse.context().isEmpty()) {
            metadata.putAll(chatClientResponse.context());
        }
        return metadata.isEmpty() ? Map.of() : metadata;
    }

    private String extractContent(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null || chatResponse.getResult().getOutput().getText() == null) {
            return "";
        }
        return chatResponse.getResult().getOutput().getText();
    }

    private String limit(String content, int maxLength) {
        String value = StringUtils.defaultString(content);
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
