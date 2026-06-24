package cn.ethan.ai.infrastructure.adapter.port;

import cn.ethan.ai.domain.agent.adapter.port.IAgentModelPort;
import cn.ethan.ai.domain.agent.model.entity.AgentModelCallResultEntity;
import cn.ethan.ai.domain.agent.model.entity.AgentRunEventEntity;
import cn.ethan.ai.domain.agent.model.entity.AgentRunTraceEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentClientHarnessConfigVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextWindowGuardVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolInvocationCollector;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.ethan.ai.domain.agent.service.execute.runtime.ToolGuardPolicy;
import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

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

    private static final List<String> RAG_CONTEXT_METADATA_KEYS = List.of(
            "qa_agentic_rag_trace",
            "qa_retrieved_documents",
            "qa_retrieval_queries",
            "question_answer_context",
            "qa_retrieval_no_evidence",
            "qa_retrieval_skipped_reason"
    );

    @Resource
    private ApplicationContext applicationContext;

    @Override
    public boolean hasAvailableModelClient(Map<String, AiAgentClientHarnessConfigVO> harnessConfigMap, AiClientTypeEnumVO... clientTypes) {
        return firstAvailableConfig(harnessConfigMap, clientTypes) != null;
    }

    @Override
    public String callModel(Map<String, AiAgentClientHarnessConfigVO> harnessConfigMap,
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
                harnessConfigMap,
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
    public AgentModelCallResultEntity callModelResult(Map<String, AiAgentClientHarnessConfigVO> harnessConfigMap,
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
        ToolInvocationCollector toolInvocationCollector = new ToolInvocationCollector();
        if (contextWindowGuard.shouldStopNewLlmCall(prompt)) {
            String message = "当前单次 Prompt 超过近似上下文预算，已跳过模型调用。";
            AgentRunEventEntity event = trace.record(eventType, stepId, step, start, message, null);
            log.info("Agent 模型调用跳过追踪事件：{}", event);
            return AgentModelCallResultEntity.builder()
                    .content(message)
                    .build();
        }

        try {
            AiAgentClientHarnessConfigVO selectedConfig = firstAvailableConfig(harnessConfigMap, clientTypes);
            ChatClient baseChatClient = resolve(selectedConfig);
            if (baseChatClient == null) {
                String message = "未找到可用的模型客户端：" + eventType;
                AgentRunEventEntity event = trace.record(eventType, stepId, step, start, message, null);
                log.warn("Agent 模型客户端缺失追踪事件：{}", event);
                return AgentModelCallResultEntity.builder()
                        .content(message)
                        .build();
            }

            List<ToolCallback> callbacks = resolveToolCallbacks(toolRoutingDecision, requiresToolCall(eventType));
            ChatClient.ChatClientRequestSpec requestSpec = baseChatClient.prompt(prompt)
                    .system(s -> s.param("current_date", LocalDate.now().toString()));
            if (!callbacks.isEmpty()) {
                log.info("运行时注入工具回调完成，eventType：{}，clientId：{}，toolCount：{}", eventType,
                        selectedConfig.getClientId(), callbacks.size());
                requestSpec.tools(callbacks);
            requestSpec.toolContext(Map.of(ToolInvocationCollector.TOOL_CONTEXT_KEY, toolInvocationCollector));
            if (requiresToolCall(eventType)) {
                requestSpec.options(externalEvidenceOptions());
            }
            }
            ChatClient.CallResponseSpec responseSpec = requestSpec.call();
            ChatClientResponse chatClientResponse = responseSpec.chatClientResponse();
            ChatResponse chatResponse = chatClientResponse == null ? responseSpec.chatResponse() : chatClientResponse.chatResponse();
            String content = extractContent(chatResponse);
            Map<String, Object> metadata = extractMetadata(chatClientResponse, chatResponse);
            if (!toolInvocationCollector.snapshot().isEmpty()) {
                metadata = new LinkedHashMap<>(metadata);
                metadata.put(ToolInvocationCollector.METADATA_KEY, toolInvocationCollector.snapshot());
            }
            AgentRunEventEntity event = trace.record(eventType, stepId, step, start, limit(content, 300), null);
            log.info("Agent 模型调用追踪事件：{}", event);
            return AgentModelCallResultEntity.builder()
                    .content(content == null ? "" : content)
                    .metadata(metadata)
                    .build();
        } catch (Exception e) {
            AgentModelCallResultEntity toolFallback = fallbackFromToolInvocations(
                    eventType, toolInvocationCollector.snapshot());
            if (toolFallback != null) {
                AgentRunEventEntity event = trace.record(eventType, stepId, step, start,
                        "工具调用已完成，模型后处理失败，保留原始工具证据。", null);
                log.warn("Agent 模型后处理失败但工具证据已保留，event：{}，error：{}", event, e.getMessage());
                return toolFallback;
            }
            AgentRunEventEntity event = trace.record(eventType, stepId, step, start, null, e.getMessage());
            log.warn("Agent 模型调用异常追踪事件：{}", event);
            throw new IllegalStateException(eventType + " 调用失败：" + e.getMessage(), e);
        }
    }

    private ChatClient resolve(AiAgentClientHarnessConfigVO config) {
        if (config == null) {
            return null;
        }
        return getChatClient(config.getClientId());
    }

    static boolean requiresToolCall(String eventType) {
        return "harness_external_evidence".equals(eventType);
    }

    static OpenAiChatOptions.Builder externalEvidenceOptions() {
        return OpenAiChatOptions.builder()
                .toolChoice("auto")
                .parallelToolCalls(false)
                .extraBody(Map.of("enable_thinking", false));
    }

    static AgentModelCallResultEntity fallbackFromToolInvocations(
            String eventType, List<cn.ethan.ai.domain.agent.model.valobj.ToolInvocationRecordVO> invocations) {
        if (!requiresToolCall(eventType) || invocations == null || invocations.isEmpty()) {
            return null;
        }
        return AgentModelCallResultEntity.builder()
                .content("")
                .metadata(Map.of(ToolInvocationCollector.METADATA_KEY, List.copyOf(invocations)))
                .build();
    }

    private AiAgentClientHarnessConfigVO firstAvailableConfig(Map<String, AiAgentClientHarnessConfigVO> harnessConfigMap,
                                                          AiClientTypeEnumVO... clientTypes) {
        if (harnessConfigMap == null || harnessConfigMap.isEmpty()) {
            return null;
        }
        for (AiClientTypeEnumVO clientType : clientTypes) {
            AiAgentClientHarnessConfigVO config = harnessConfigMap.get(clientType.getCode());
            if (config != null && StringUtils.isNotBlank(config.getClientId())) {
                return config;
            }
        }
        return harnessConfigMap.values().stream()
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

    private String limit(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        return content.length() <= maxLength ? content : content.substring(0, maxLength) + "...";
    }

    private List<ToolCallback> resolveToolCallbacks(ToolRoutingDecisionVO toolRoutingDecision,
                                                    boolean readOnlyEvidenceOnly) {
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
            log.warn("已选择 MCP Server，但未发现可注入的 ToolCallback，mcpIds：{}",
                    toolRoutingDecision.getSelectedMcpIds());
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
            log.warn("运行时工具路由已启用但授权工具集合为空，本轮不注入任何工具。");
            return Collections.emptyList();
        }

        List<ToolCallback> permitted = new ArrayList<>();
        Set<String> effectiveAllowedNames = new LinkedHashSet<>();
        Set<String> discoveredNames = new LinkedHashSet<>();
        for (ToolCallback callback : rawCallbacks) {
            if (callback == null || callback.getToolDefinition() == null || StringUtils.isBlank(callback.getToolDefinition().name())) {
                continue;
            }
            String toolName = callback.getToolDefinition().name().trim().toLowerCase(Locale.ROOT);
            discoveredNames.add(toolName);
            if (!isToolAuthorized(toolName, allowedNames, readOnlyEvidenceOnly)) {
                continue;
            }
            if (ToolGuardPolicy.isBlocked(toolName)) {
                log.warn("Tool Guard 拦截危险工具注入，toolName：{}", toolName);
                continue;
            }
            permitted.add(callback);
            effectiveAllowedNames.add(toolName);
        }
        if (permitted.isEmpty()) {
            log.warn("MCP ToolCallback 均未通过本轮授权，configuredNames：{}，discoveredNames：{}，readOnlyEvidenceOnly：{}",
                    allowedNames, discoveredNames, readOnlyEvidenceOnly);
            return Collections.emptyList();
        }
        return permitted.stream()
                .map(callback -> (ToolCallback) new GuardedToolCallback(callback, effectiveAllowedNames))
                .toList();
    }

    static boolean isToolAuthorized(String toolName,
                                    Set<String> configuredAllowedNames,
                                    boolean readOnlyEvidenceOnly) {
        String normalized = ToolGuardPolicy.normalize(toolName);
        if (StringUtils.isBlank(normalized) || ToolGuardPolicy.isBlocked(normalized)) {
            return false;
        }
        if (configuredAllowedNames != null && configuredAllowedNames.contains(normalized)) {
            return !readOnlyEvidenceOnly || ToolGuardPolicy.isReadOnlyEvidenceTool(normalized);
        }
        // MCP Server upgrades can rename read-only tools. For an evidence-only route,
        // the selected server boundary plus the risk policy is the source of truth.
        return readOnlyEvidenceOnly && ToolGuardPolicy.isReadOnlyEvidenceTool(normalized);
    }

    private Map<String, Object> extractMetadata(ChatClientResponse chatClientResponse, ChatResponse chatResponse) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (chatResponse != null && chatResponse.getMetadata() != null && !chatResponse.getMetadata().isEmpty()) {
            chatResponse.getMetadata().entrySet().forEach(entry -> metadata.put(entry.getKey(), entry.getValue()));
        }

        if (chatClientResponse != null && chatClientResponse.context() != null && !chatClientResponse.context().isEmpty()) {
            for (String key : RAG_CONTEXT_METADATA_KEYS) {
                Object value = chatClientResponse.context().get(key);
                if (value != null) {
                    metadata.putIfAbsent(key, value);
                }
            }
        }

        if (metadata.isEmpty()) {
            return Collections.emptyMap();
        }
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

}
