package cn.ethan.ai.infrastructure.adapter.port;

import cn.ethan.ai.domain.agent.adapter.port.IAgentModelPort;
import cn.ethan.ai.domain.agent.model.entity.AgentRunEventEntity;
import cn.ethan.ai.domain.agent.model.entity.AgentRunTraceEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextWindowGuardVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;

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
                            AiClientTypeEnumVO... clientTypes) {
        long start = System.currentTimeMillis();
        if (contextWindowGuard.shouldStopNewLlmCall()) {
            String message = "上下文较长，已跳过新的模型调用。";
            AgentRunEventEntity event = trace.record(eventType, stepId, step, start, message, null);
            log.info("Agent 模型调用跳过追踪事件：{}", event);
            return message;
        }

        try {
            ChatClient chatClient = resolve(flowConfigMap, clientTypes);
            if (chatClient == null) {
                String message = "未找到可用的模型客户端：" + eventType;
                AgentRunEventEntity event = trace.record(eventType, stepId, step, start, message, null);
                log.warn("Agent 模型客户端缺失追踪事件：{}", event);
                return message;
            }

            contextWindowGuard.record(prompt);
            String content = chatClient.prompt(prompt)
                    .system(s -> s.param("current_date", LocalDate.now().toString()))
                    .advisors(a -> a
                            .param(CHAT_MEMORY_CONVERSATION_ID_KEY, command.getSessionId())
                            .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 50))
                    .call()
                    .content();
            contextWindowGuard.record(content);
            AgentRunEventEntity event = trace.record(eventType, stepId, step, start, limit(content, 300), null);
            log.info("Agent 模型调用追踪事件：{}", event);
            return content == null ? "" : content;
        } catch (Exception e) {
            AgentRunEventEntity event = trace.record(eventType, stepId, step, start, null, e.getMessage());
            log.warn("Agent 模型调用异常追踪事件：{}", event);
            throw new IllegalStateException(eventType + " 调用失败：" + e.getMessage(), e);
        }
    }

    private ChatClient resolve(Map<String, AiAgentClientFlowConfigVO> flowConfigMap, AiClientTypeEnumVO... clientTypes) {
        AiAgentClientFlowConfigVO config = firstAvailableConfig(flowConfigMap, clientTypes);
        if (config == null) {
            return null;
        }
        return getChatClient(config.getClientId());
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

    private String limit(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        return content.length() <= maxLength ? content : content.substring(0, maxLength) + "...";
    }
}
